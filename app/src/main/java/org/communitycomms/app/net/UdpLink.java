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
public final class UdpLink {
    public interface Receiver {
        void onDatagram(byte[] buf, int len, InetAddress from);
    }

    private static final String TAG = "UdpLink";
    public static final int DEFAULT_PORT = 47474;
    public static final String DEFAULT_GROUP = "239.47.47.47";

    private final MulticastSocket socket;
    private final List<InetSocketAddress> destinations;
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
                        Tunnel.Relay r = Tunnel.relay;
                        if (r != null && !r.onLanReceive(buf, dp.getLength())) continue; // already had it
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
    }

    /** The link on air (tunnel comms sends and delivers through it). */
    public static volatile UdpLink current;
    private final int port;

    /** Hands a packet to this phone's own receiver, the same way as one heard on the network. */
    void deliverLocal(byte[] data, int len) {
        try {
            socket.send(new DatagramPacket(data, len, InetAddress.getByName("127.0.0.1"), port));
        } catch (IOException e) {
            Log.d(TAG, "local delivery failed: " + e.getMessage());
        }
    }

    /** Relay: a remote's packet onto the local network (not back into the tunnel). */
    void sendLanOnly(byte[] data) {
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

    private void sendNow(byte[] data) {
        // Tunnel comms: a remote phone also sends everything through the relay. It keeps sending on the
        // network here too, so a phone back on the group's WiFi still works while the tunnel is on (or
        // the relay cannot be reached from inside); copies that arrive both ways are dropped as repeats.
        Tunnel.Client c = Tunnel.client;
        if (c != null) c.send(data);
        Tunnel.Relay r = Tunnel.relay;
        if (r != null) r.onOwnSend(data, data.length);
        for (InetSocketAddress d : destinations) {
            try {
                socket.send(new DatagramPacket(data, data.length, d));
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
        running = false;
        txThread.interrupt();
        socket.close();
        try {
            rxThread.join(500);
        } catch (InterruptedException ignored) {
        }
    }
}
