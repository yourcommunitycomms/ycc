package org.communitycomms.app.net;

import android.util.Log;

import org.communitycomms.app.crypto.ChannelCrypto;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Tunnel comms: a phone away from the group's network reaches it over the internet through a
 * relay (best the commander tablet) that is on the group's network with ports forwarded to it.
 * <p>
 * Each remote has its own port on the relay and its own key, made from a DRYAD row. Everything the
 * remote would broadcast goes to the relay instead; the relay puts it on the local network, and
 * sends back whatever is heard locally on the channels the remote said it listens to (by channel
 * tag, so the relay needs no channel names). Radio packets stay end-to-end encrypted inside; the
 * tunnel adds its own encryption, authentication and replay protection. UDP first; if nothing
 * comes back, TCP on the same port (with old voice dropped so it does not lag further and further).
 *
 * <pre>
 * Tunnel frame: 'U' 'T' type(1) 0(1) session(8) seq(4) | AES-CTR body | HMAC (10)
 *   type 1 DATA      body = one radio packet as sent on the network
 *   type 2 HELLO     body = time ms(8) nonce(8) count(1) channel tags(4 each)   remote to relay, every 5 s
 *   type 3 HELLO_ACK body = nonce(8)                                           relay to remote
 * Key: ChannelCrypto("tunnel:" + DRYAD row). TCP carries the same frames, each after a 2-byte length.
 * </pre>
 */
public final class Tunnel {
    private static final String TAG = "Tunnel";
    static final int DATA = 1, HELLO = 2, HELLO_ACK = 3;
    private static final int HDR = 16;
    private static final SecureRandom RND = new SecureRandom();

    /** Remote side, while connected (UdpLink sends through it instead of the local network). */
    public static volatile Client client;
    /** Relay side, while on. */
    public static volatile Relay relay;
    /** Channel tags this phone is on (on air + listened to), for the HELLO. */
    public static volatile int[] tags = new int[0];

    private Tunnel() {
    }

    public static ChannelCrypto key(String dryadRow) throws GeneralSecurityException {
        return new ChannelCrypto("tunnel:" + dryadRow.trim());
    }

    // ---- frames ----

    static byte[] seal(ChannelCrypto k, int type, long session, int seq, byte[] body, int len) throws GeneralSecurityException {
        byte[] out = new byte[HDR + len + ChannelCrypto.MAC_LEN];
        out[0] = 'U';
        out[1] = 'T';
        out[2] = (byte) type;
        Packet.putLong(out, 4, session);
        Packet.putInt(out, 12, seq);
        System.arraycopy(body, 0, out, HDR, len);
        k.ctr(iv(session, seq), out, HDR, len);
        System.arraycopy(k.mac(out, 0, HDR + len), 0, out, HDR + len, ChannelCrypto.MAC_LEN);
        return out;
    }

    static final class Frame {
        int type;
        long session;
        int seq;
        byte[] body;
    }

    static Frame open(ChannelCrypto k, byte[] b, int len) {
        if (len < HDR + ChannelCrypto.MAC_LEN || b[0] != 'U' || b[1] != 'T') return null;
        int n = len - HDR - ChannelCrypto.MAC_LEN;
        try {
            if (!k.verify(b, 0, HDR + n, b, HDR + n)) return null;
            Frame f = new Frame();
            f.type = b[2] & 0xff;
            f.session = Packet.getLong(b, 4);
            f.seq = Packet.getInt(b, 12);
            f.body = new byte[n];
            System.arraycopy(b, HDR, f.body, 0, n);
            k.ctr(iv(f.session, f.seq), f.body, 0, n);
            return f;
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    private static byte[] iv(long session, int seq) {
        byte[] iv = new byte[16];
        Packet.putLong(iv, 0, session);
        Packet.putInt(iv, 8, seq);
        iv[12] = 'T';
        return iv;
    }

    /** Replay window: the last 64 sequence numbers of the peer's current session. */
    static final class Window {
        long session;
        int top = -1;
        long bits;
        boolean ready;

        void reset(long s) {
            session = s;
            top = -1;
            bits = 0;
            ready = true;
        }

        boolean accept(long s, int seq) {
            if (!ready || s != session || seq < 0) return false;
            if (seq > top) {
                int shift = seq - top;
                bits = shift >= 64 ? 1 : (bits << shift) | 1;
                top = seq;
                return true;
            }
            int back = top - seq;
            if (back >= 64 || (bits & (1L << back)) != 0) return false;
            bits |= 1L << back;
            return true;
        }
    }

    /** A packet's identity: its sender id and sequence number (bytes 8..19 of a radio packet). */
    static String key(byte[] p, int len) {
        if (len < 20) return null;
        StringBuilder sb = new StringBuilder(24);
        for (int i = 8; i < 20; i++) sb.append((char) (p[i] & 0xff));
        return sb.toString();
    }

    static int channelTag(byte[] p, int len) {
        return len < 8 || p[0] != 'U' || p[1] != 'B' ? 0 : Packet.getInt(p, 4);
    }

    // ---- one end of a tunnel: sends over UDP or TCP, whichever last worked ----

    abstract static class End {
        final ChannelCrypto k;
        final long session = RND.nextLong();
        int seq;
        final Window in = new Window();
        final LinkedBlockingQueue<byte[]> out = new LinkedBlockingQueue<byte[]>();
        volatile DatagramSocket udp;
        volatile InetSocketAddress udpPeer;
        volatile OutputStream tcp;
        volatile boolean useTcp;
        volatile long lastRxMs;
        volatile boolean running = true;
        volatile String transport = "";

        End(ChannelCrypto k) {
            this.k = k;
        }

        synchronized byte[] frame(int type, byte[] body, int len) throws GeneralSecurityException {
            return seal(k, type, session, seq++, body, len);
        }

        void queue(int type, byte[] body, int len) {
            try {
                // TCP cannot drop a late packet by itself: keep at most ~1.5 s waiting, oldest dropped.
                while (out.size() > 80) out.poll();
                out.offer(frame(type, body, len));
            } catch (GeneralSecurityException e) {
                Log.w(TAG, "seal", e);
            }
        }

        final Thread sender = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    byte[] f;
                    try {
                        f = out.take();
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        OutputStream t = tcp;
                        if (useTcp && t != null) {
                            synchronized (t) {
                                t.write(new byte[]{(byte) (f.length >> 8), (byte) f.length});
                                t.write(f);
                                t.flush();
                            }
                        } else {
                            DatagramSocket u = udp;
                            InetSocketAddress a = udpPeer;
                            if (u != null && a != null) u.send(new DatagramPacket(f, f.length, a));
                        }
                    } catch (IOException e) {
                        onSendFailed(e);
                    }
                }
            }
        }, "tunnel-tx");

        void onSendFailed(IOException e) {
            Log.d(TAG, "send: " + e.getMessage());
        }

        /** A frame that passed authentication; via = "UDP" or "TCP". */
        abstract void onFrame(Frame f, String via);

        void handle(byte[] b, int len, String via) {
            Frame f = open(k, b, len);
            if (f == null) return;
            onFrame(f, via);
        }

        static void readTcp(Socket s, End e) throws IOException {
            DataInputStream in = new DataInputStream(s.getInputStream());
            byte[] buf = new byte[4096];
            while (e.running) {
                int n = in.readUnsignedShort();
                if (n > buf.length) throw new IOException("frame too big");
                in.readFully(buf, 0, n);
                e.handle(buf, n, "TCP");
            }
        }

        void stop() {
            running = false;
            sender.interrupt();
            DatagramSocket u = udp;
            if (u != null) u.close();
        }
    }

    // ---- remote phone ----

    public static final class Client extends End {
        private final String host;
        private final int port;
        private final long nonce = RND.nextLong();
        private volatile Socket tcpSocket;
        private volatile long startedMs = System.currentTimeMillis();
        public volatile String state = "connecting";

        Client(String host, int port, ChannelCrypto k) {
            super(k);
            this.host = host;
            this.port = port;
        }

        /** Starts the remote side (replaces any running one). */
        public static void start(String hostPort, String dryadRow) {
            stopClient();
            int c = hostPort.lastIndexOf(':');
            if (c <= 0 || dryadRow.trim().length() == 0) return;
            try {
                final Client cl = new Client(hostPort.substring(0, c).trim(), Integer.parseInt(hostPort.substring(c + 1).trim()), key(dryadRow));
                client = cl;
                cl.sender.start();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        cl.loop();
                    }
                }, "tunnel-client").start();
            } catch (Exception e) {
                Log.w(TAG, "client", e);
            }
        }

        public static void stopClient() {
            Client c = client;
            client = null;
            if (c != null) {
                c.stop();
                c.closeTcp();
            }
        }

        public boolean connected() {
            return System.currentTimeMillis() - lastRxMs < 20000;
        }

        /** From UdpLink: a radio packet this phone sends. */
        public void send(byte[] p) {
            queue(DATA, p, p.length);
        }

        private long lastUdpTry;

        private void udpHello(DatagramSocket u, InetSocketAddress addr) {
            try {
                byte[] b = helloBody();
                byte[] f = frame(HELLO, b, b.length);
                u.send(new DatagramPacket(f, f.length, addr));
            } catch (Exception e) {
                Log.d(TAG, "udp retry: " + e.getMessage());
            }
        }

        private void hello() {
            byte[] b = helloBody();
            queue(HELLO, b, b.length);
        }

        private byte[] helloBody() {
            int[] t = tags;
            byte[] b = new byte[17 + 4 * Math.min(t.length, 60)];
            Packet.putLong(b, 0, System.currentTimeMillis());
            Packet.putLong(b, 8, nonce);
            b[16] = (byte) Math.min(t.length, 60);
            for (int i = 0; i < (b[16] & 0xff); i++) Packet.putInt(b, 17 + 4 * i, t[i]);
            return b;
        }

        private void loop() {
            try {
                InetSocketAddress addr = new InetSocketAddress(InetAddress.getByName(host), port);
                udpPeer = addr;
                final DatagramSocket u = new DatagramSocket();
                udp = u;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        byte[] buf = new byte[4096];
                        DatagramPacket dp = new DatagramPacket(buf, buf.length);
                        while (running) {
                            try {
                                dp.setLength(buf.length);
                                u.receive(dp);
                                handle(buf, dp.getLength(), "UDP");
                            } catch (IOException e) {
                                if (!running) return;
                            }
                        }
                    }
                }, "tunnel-udp-rx").start();
                long lastHello = 0;
                while (running) {
                    long now = System.currentTimeMillis();
                    if (now - lastHello >= 5000) {
                        lastHello = now;
                        hello();
                    }
                    // On TCP: every 30 s also try UDP, which is better for voice; its answer switches back.
                    if (useTcp && now - lastUdpTry >= 30000) {
                        lastUdpTry = now;
                        udpHello(u, addr);
                    }
                    // No answer over UDP for 12 s (or it stopped answering): try TCP on the same port.
                    if (!useTcp && now - Math.max(lastRxMs, startedMs) > 12000) {
                        state = "no answer over UDP, trying TCP";
                        connectTcp(addr);
                        startedMs = System.currentTimeMillis();
                    } else if (!connected()) {
                        state = "connecting to " + host + ":" + port;
                    }
                    Thread.sleep(500);
                }
            } catch (Exception e) {
                state = "failed: " + e.getMessage();
                Log.w(TAG, "client loop", e);
            }
        }

        private void connectTcp(InetSocketAddress addr) {
            try {
                final Socket s = new Socket();
                s.connect(addr, 8000);
                s.setTcpNoDelay(true);
                tcpSocket = s;
                tcp = s.getOutputStream();
                useTcp = true;
                hello();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            readTcp(s, Client.this);
                        } catch (IOException e) {
                            Log.d(TAG, "tcp closed: " + e.getMessage());
                        }
                        closeTcp(); // back to UDP; TCP is tried again if UDP still gets nothing
                    }
                }, "tunnel-tcp-rx").start();
            } catch (IOException e) {
                state = "TCP failed too: " + e.getMessage();
                useTcp = false;
            }
        }

        private void closeTcp() {
            useTcp = false;
            tcp = null;
            Socket s = tcpSocket;
            tcpSocket = null;
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }

        @Override
        void onSendFailed(IOException e) {
            if (useTcp) closeTcp();
        }

        @Override
        void onFrame(Frame f, String via) {
            if (f.type == HELLO_ACK) {
                if (f.body.length < 8 || Packet.getLong(f.body, 0) != nonce) return;
                if (f.session != in.session || !in.ready) in.reset(f.session);
                if (!in.accept(f.session, f.seq)) return;
                lastRxMs = System.currentTimeMillis();
                if ("UDP".equals(via) && useTcp) closeTcp(); // UDP works again: leave TCP
                transport = via;
                state = "connected over " + via;
                return;
            }
            if (f.type != DATA || !in.accept(f.session, f.seq)) return;
            lastRxMs = System.currentTimeMillis();
            UdpLink l = UdpLink.current;
            if (l != null) l.deliverLocal(f.body, f.body.length);
        }

        public String describe() {
            return connected() ? "Tunnel: connected to " + host + ":" + port + " over " + transport : "Tunnel: " + state;
        }
    }

    // ---- relay (commander tablet) ----

    public static final class Relay {
        public final List<Remote> remotes = new ArrayList<Remote>();
        /** Packets put on the local network for a remote (or sent by this phone): key -> origin port (0 = own). */
        private final Map<String, Integer> seen = new LinkedHashMap<String, Integer>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Integer> e) {
                return size() > 4000;
            }
        };
        private final Set<String> deliveredHere = new HashSet<String>();

        /** remotes: one line each "port|label|DRYAD row". */
        public static void start(String remotesText) {
            stopRelay();
            Relay r = new Relay();
            for (String line : remotesText.split("\n")) {
                String[] f = line.split("\\|", 3);
                if (f.length < 3) continue;
                try {
                    Remote m = new Remote(r, Integer.parseInt(f[0].trim()), f[1].trim(), key(f[2]));
                    r.remotes.add(m);
                    m.open();
                } catch (Exception e) {
                    Log.w(TAG, "relay port " + f[0], e);
                }
            }
            relay = r;
        }

        public static void stopRelay() {
            Relay r = relay;
            relay = null;
            if (r != null) for (Remote m : r.remotes) m.close();
        }

        /** UdpLink receive: false = a second copy of a packet already delivered here, drop it. */
        public boolean onLanReceive(byte[] p, int len) {
            String k = key(p, len);
            if (k == null) return true;
            synchronized (seen) {
                Integer origin = seen.get(k);
                if (origin != null) {
                    // From a remote (or our own send looping back): give it to this phone once, never forward again.
                    if (origin == 0 || deliveredHere.contains(k)) return false;
                    deliveredHere.add(k);
                    if (deliveredHere.size() > 4000) deliveredHere.clear();
                    return true;
                }
                seen.put(k, -1); // heard on the local network
            }
            forward(p, len, -1);
            return true;
        }

        /** UdpLink send: this phone's own packet also goes to the remotes listening to its channel. */
        public void onOwnSend(byte[] p, int len) {
            String k = key(p, len);
            if (k != null) {
                synchronized (seen) {
                    seen.put(k, 0);
                }
            }
            forward(p, len, -1);
        }

        /** From remote `fromPort`: onto the local network (and this phone), and to the other remotes. */
        void fromRemote(byte[] p, int fromPort) {
            String k = key(p, p.length);
            if (k == null) return;
            synchronized (seen) {
                if (seen.containsKey(k)) return; // duplicate (e.g. it came over UDP and TCP)
                seen.put(k, fromPort);
            }
            UdpLink l = UdpLink.current;
            if (l != null) {
                l.sendLanOnly(p);
                l.deliverLocal(p, p.length);
            }
            forward(p, p.length, fromPort);
        }

        private void forward(byte[] p, int len, int exceptPort) {
            int tag = channelTag(p, len);
            if (tag == 0) return;
            for (Remote m : remotes) {
                if (m.port != exceptPort && m.listensTo(tag)) m.queue(DATA, p, len);
            }
        }

        public String describe() {
            StringBuilder sb = new StringBuilder("Relay on: ");
            if (remotes.isEmpty()) sb.append("no remotes set");
            for (Remote m : remotes) sb.append("\n  ").append(m.describe());
            return sb.toString();
        }
    }

    public static final class Remote extends End {
        final Relay relay;
        public final int port;
        public final String label;
        private volatile int[] listen = new int[0];
        private volatile long lastHelloTime;
        private volatile String problem = "";
        private ServerSocket server;
        private volatile Socket tcpSocket;

        Remote(Relay r, int port, String label, ChannelCrypto k) {
            super(k);
            this.relay = r;
            this.port = port;
            this.label = label;
        }

        boolean listensTo(int tag) {
            if (System.currentTimeMillis() - lastRxMs > 30000) return false;
            for (int t : listen) if (t == tag) return true;
            return false;
        }

        void open() {
            sender.start();
            try {
                final DatagramSocket u = new DatagramSocket(null);
                u.setReuseAddress(true);
                u.bind(new InetSocketAddress(port));
                udp = u;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        byte[] buf = new byte[4096];
                        DatagramPacket dp = new DatagramPacket(buf, buf.length);
                        while (running) {
                            try {
                                dp.setLength(buf.length);
                                u.receive(dp);
                                lastFrom = (InetSocketAddress) dp.getSocketAddress();
                                handle(buf, dp.getLength(), "UDP");
                            } catch (IOException e) {
                                if (!running) return;
                            }
                        }
                    }
                }, "relay-udp-" + port).start();
            } catch (IOException e) {
                problem = "UDP port " + port + " busy: " + e.getMessage();
            }
            try {
                final ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(port));
                server = ss;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while (running) {
                            try {
                                final Socket s = ss.accept();
                                s.setTcpNoDelay(true);
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        tcpCandidate.set(s);
                                        try {
                                            readTcp(s, Remote.this);
                                        } catch (IOException ignored) {
                                        }
                                        try {
                                            s.close();
                                        } catch (IOException ignored) {
                                        }
                                        if (tcpSocket == s) {
                                            tcpSocket = null;
                                            tcp = null;
                                            useTcp = false;
                                        }
                                    }
                                }, "relay-tcp-rx-" + port).start();
                            } catch (IOException e) {
                                if (!running) return;
                            }
                        }
                    }
                }, "relay-tcp-" + port).start();
            } catch (IOException e) {
                problem = (problem.length() > 0 ? problem + "; " : "") + "TCP port " + port + " busy";
            }
        }

        private volatile InetSocketAddress lastFrom;
        /** The TCP connection a frame is being read from (set by that connection's own thread). */
        private final ThreadLocal<Socket> tcpCandidate = new ThreadLocal<Socket>();

        @Override
        void onFrame(Frame f, String via) {
            if (f.type == HELLO) {
                if (f.body.length < 17) return;
                long t = Packet.getLong(f.body, 0);
                if (f.session != in.session || !in.ready) {
                    // A new session (the remote restarted) only with a HELLO newer than any before:
                    // a recorded old HELLO cannot reopen an old session.
                    if (t <= lastHelloTime) return;
                    in.reset(f.session);
                }
                if (!in.accept(f.session, f.seq)) return;
                lastHelloTime = Math.max(lastHelloTime, t);
                int n = Math.min(f.body[16] & 0xff, (f.body.length - 17) / 4);
                int[] l = new int[n];
                for (int i = 0; i < n; i++) l[i] = Packet.getInt(f.body, 17 + 4 * i);
                listen = l;
                arrived(via);
                byte[] ack = new byte[8];
                System.arraycopy(f.body, 8, ack, 0, 8);
                queue(HELLO_ACK, ack, 8);
                return;
            }
            if (f.type != DATA || !in.accept(f.session, f.seq)) return;
            arrived(via);
            relay.fromRemote(f.body, port);
        }

        /** Replies go back the way the remote last reached us (its NAT address may change). */
        private void arrived(String via) {
            lastRxMs = System.currentTimeMillis();
            transport = via;
            if ("TCP".equals(via)) {
                Socket s = tcpCandidate.get();
                if (s != null && s != tcpSocket) {
                    try {
                        tcp = s.getOutputStream();
                        tcpSocket = s;
                    } catch (IOException ignored) {
                    }
                }
                useTcp = tcp != null;
            } else {
                udpPeer = lastFrom;
                useTcp = false;
            }
        }

        void close() {
            stop();
            try {
                if (server != null) server.close();
                Socket s = tcpSocket;
                if (s != null) s.close();
            } catch (IOException ignored) {
            }
        }

        String describe() {
            long ago = (System.currentTimeMillis() - lastRxMs) / 1000;
            String s = label + " (port " + port + "): ";
            if (problem.length() > 0) return s + problem;
            if (lastRxMs == 0) return s + "waiting";
            return s + (ago < 20 ? "connected over " + transport + ", " + listen.length + " channel(s)" : "last heard " + ago + " s ago");
        }
    }

    public static String describe() {
        Client c = client;
        Relay r = relay;
        if (c != null) return c.describe();
        if (r != null) return r.describe();
        return "";
    }
}
