package org.communitycomms.app.net;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

/**
 * One UDP socket that receives broadcast, multicast and unicast on the radio port, and sends each
 * packet to every configured destination (subnet broadcast or multicast group, plus any extra
 * peers such as a host across a routed mesh or another emulator).
 */
public final class UdpLink implements Tunnel.Local {
    public interface Receiver {
        void onDatagram(byte[] buf, int len, InetAddress from);
    }

    private static final String TAG = "UdpLink";
    public static final int DEFAULT_PORT = 47474;
    public static final String DEFAULT_GROUP = "239.47.47.47";

    private final MulticastSocket socket;
    private volatile List<InetSocketAddress> destinations;
    /** Destinations that are not network broadcasts (extra peers, multicast): kept as they are. */
    private final List<InetSocketAddress> fixed = new ArrayList<InetSocketAddress>();
    /** Follow WiFi changes: the subnet broadcast address(es) are worked out again every few seconds. */
    private final boolean followNetwork;
    private long lastNetCheck;

    /**
     * A sending socket tied to the WiFi network. On Android 5.1+ a WiFi without internet (the "!" icon, e.g. an
     * OpenMANET mesh node) is not the default network while mobile data is on, so broadcasts from an unbound socket
     * went out mobile data and were lost: the phone heard everyone, nobody heard it. Set by RadioService.
     */
    public interface NetBinder {
        /** A new socket bound to the WiFi network (broadcast allowed), or null when there is no WiFi. */
        MulticastSocket wifiSocket() throws IOException;
    }

    public static volatile NetBinder binder;
    private volatile MulticastSocket wifiTx;
    private final Thread rxThread;
    private volatile boolean running = true;

    /**
     * @param iface the network to use for multicast (WiFi, VPN tunnel, USB Ethernet...), or null
     *              to let the system choose. Broadcast destinations should already be limited to
     *              that interface by the caller.
     */
    public UdpLink(int port, List<InetSocketAddress> destinations, InetAddress joinGroup,
                   java.net.NetworkInterface iface, final Receiver receiver) throws IOException {
        this.destinations = new ArrayList<InetSocketAddress>(destinations);
        // Broadcast destinations (no network chosen, not multicast): the WiFi can change while on air (driving from
        // home WiFi onto a mesh node, mobile data to WiFi). Without this the old network's broadcast address kept
        // being used: this phone still HEARD everyone but nobody heard it (seen 2026-11-25 on the OpenMANET mesh).
        java.util.Set<InetAddress> bc = broadcasts();
        boolean anyBroadcast = false;
        for (InetSocketAddress d : destinations) {
            InetAddress a = d.getAddress();
            if (a != null && (bc.contains(a) || "255.255.255.255".equals(a.getHostAddress()))) anyBroadcast = true;
            else fixed.add(d);
        }
        followNetwork = iface == null && anyBroadcast;
        Log.i(TAG, "sending to " + destinations + (followNetwork ? " (follows WiFi changes)" : ""));
        socket = new MulticastSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port));
        socket.setBroadcast(true);
        socket.setTimeToLive(8);
        socket.setLoopbackMode(true); // true disables loopback of our own multicast
        if (iface != null) {
            try {
                socket.setNetworkInterface(iface);
            } catch (IOException e) {
                Log.w(TAG, "cannot send multicast on " + iface.getName(), e);
            }
        }
        if (joinGroup != null) {
            try {
                if (iface != null) socket.joinGroup(new InetSocketAddress(joinGroup, port), iface);
                else socket.joinGroup(joinGroup);
            } catch (IOException e) {
                Log.w(TAG, "joinGroup failed, multicast receive unavailable", e);
            }
        }
        rxThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[2048];
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                while (running) {
                    try {
                        dp.setLength(buf.length);
                        socket.receive(dp);
                        int n = dp.getLength();
                        if (n >= 2 && buf[0] == 'U' && buf[1] == 'T') { // a tunnel frame on the mesh (gateway beacon)
                            Tunnel.onLanTunnelFrame(buf, n);
                            continue;
                        }
                        Tunnel.Relay r = Tunnel.relay;
                        if (r != null && !r.onLanReceive(buf, n)) continue; // already had it
                        Tunnel.Client g = Tunnel.vps;
                        if (g != null && g.gateway && !dp.getAddress().isLoopbackAddress()) g.fromLan(buf, n); // shared channels up to the internet relay
                        receiver.onDatagram(buf, dp.getLength(), dp.getAddress());
                    } catch (SocketException e) {
                        if (running) Log.w(TAG, "socket error", e);
                        return;
                    } catch (IOException e) {
                        Log.w(TAG, "receive", e);
                    } catch (RuntimeException e) {
                        Log.e(TAG, "receiver crashed on a packet", e);
                    }
                }
            }
        }, "udp-rx");
        rxThread.setPriority(Thread.MAX_PRIORITY - 1);
        rxThread.start();
        txThread.setPriority(Thread.MAX_PRIORITY - 1);
        txThread.start();
        this.port = port;
        current = this;
        Tunnel.logger = LOG;
        Tunnel.local = this;
    }

    private static final Tunnel.Logger LOG = new Tunnel.Logger() {
        @Override
        public void log(String msg, Throwable t) {
            if (t == null) Log.d("Tunnel", msg);
            else Log.w("Tunnel", msg, t);
        }
    };

    /** The link on air (tunnel comms sends and delivers through it). */
    public static volatile UdpLink current;
    private final int port;

    /** Hands a packet to this phone's own receiver, the same way as one heard on the network. */
    @Override
    public void deliverLocal(byte[] data, int len) {
        try {
            socket.send(new DatagramPacket(data, len, InetAddress.getByName("127.0.0.1"), port));
        } catch (IOException e) {
            Log.d(TAG, "local delivery failed: " + e.getMessage());
        }
    }

    /** Relay: a remote's packet onto the local network (not back into the tunnel). */
    @Override
    public void sendLanOnly(byte[] data) {
        for (InetSocketAddress d : destinations) {
            try {
                socket.send(new DatagramPacket(data, data.length, d));
            } catch (IOException e) {
                Log.d(TAG, "send to " + d + " failed: " + e.getMessage());
            }
        }
    }

    /**
     * Queues a packet. Sending happens on one background thread so callers on the UI thread
     * (PTT release, buttons) never touch the network themselves, which Android forbids.
     */
    public void send(byte[] data) {
        if (!running) return;
        if (!txQueue.offer(data)) Log.d(TAG, "send queue full, dropped a packet");
    }

    private final java.util.concurrent.LinkedBlockingQueue<byte[]> txQueue =
            new java.util.concurrent.LinkedBlockingQueue<byte[]>(2000);
    private final Thread txThread = new Thread(new Runnable() {
        @Override
        public void run() {
            while (running) {
                try {
                    sendNow(txQueue.take());
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }, "udp-tx");

    private static java.util.Set<InetAddress> broadcasts() {
        java.util.Set<InetAddress> out = new java.util.LinkedHashSet<InetAddress>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> e = java.net.NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                java.net.NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof java.net.Inet4Address && ia.getBroadcast() != null) out.add(ia.getBroadcast());
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Every 5 s while sending: the broadcast address(es) of the networks up now (plus the fixed ones). */
    private void followNetworkNow() {
        long now = System.currentTimeMillis();
        if (!followNetwork || now - lastNetCheck < 5000) return;
        lastNetCheck = now;
        NetBinder nb = binder;
        if (nb != null) {
            MulticastSocket old = wifiTx;
            try {
                wifiTx = nb.wifiSocket();
            } catch (IOException e) {
                wifiTx = null;
            }
            if (old != null) old.close();
        }
        java.util.Set<InetAddress> bc = broadcasts();
        List<InetSocketAddress> next = new ArrayList<InetSocketAddress>();
        if (bc.isEmpty()) bc.add(InetAddressAll.ALL);
        for (InetAddress a : bc) next.add(new InetSocketAddress(a, port));
        for (InetSocketAddress d : fixed) if (!next.contains(d)) next.add(d);
        if (!next.equals(destinations)) {
            Log.i(TAG, "network changed: sending to " + next + " (was " + destinations + ")");
            destinations = next;
        }
    }

    private static final class InetAddressAll {
        static final InetAddress ALL;

        static {
            InetAddress a = null;
            try {
                a = InetAddress.getByName("255.255.255.255");
            } catch (Exception ignored) {
            }
            ALL = a;
        }
    }

    private void sendNow(byte[] data) {
        followNetworkNow();
        // Tunnel comms: a remote phone also sends everything through the relay. It keeps sending on the
        // network here too, so a phone back on the group's WiFi still works while the tunnel is on (or
        // the relay cannot be reached from inside); copies that arrive both ways are dropped as repeats.
        Tunnel.Client c = Tunnel.client;
        if (c != null) c.send(data);
        Tunnel.Client v = Tunnel.vps; // internet relay: everything (off the MANET) or the shared channels (gateway)
        if (v != null) v.send(data);
        Tunnel.Client cg = Tunnel.cloud; // cloud group: only its own channel goes to its relay
        if (cg != null) cg.send(data);
        Tunnel.Relay r = Tunnel.relay;
        if (r != null) r.onOwnSend(data, data.length);
        MulticastSocket w = wifiTx;
        for (InetSocketAddress d : destinations) {
            try {
                if (w != null && !fixed.contains(d)) w.send(new DatagramPacket(data, data.length, d)); // the mesh / WiFi broadcast
                else socket.send(new DatagramPacket(data, data.length, d));
            } catch (IOException e) {
                // Unreachable networks are normal while WiFi is coming and going.
                Log.d(TAG, "send to " + d + " failed: " + e.getMessage());
            }
        }
    }

    public List<InetSocketAddress> destinations() {
        return destinations;
    }

    public void close() {
        if (current == this) current = null;
        if (Tunnel.local == this) Tunnel.local = null;
        running = false;
        txThread.interrupt();
        socket.close();
        MulticastSocket w = wifiTx;
        if (w != null) w.close();
        try {
            rxThread.join(500);
        } catch (InterruptedException ignored) {
        }
    }
}
