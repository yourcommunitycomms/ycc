package org.communitycomms.app.net;

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
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
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
 * <p>
 * Plain Java (no Android classes): the same code runs in the app and in the headless internet
 * relay (relay/server, ycc-relay.jar). The app plugs in its network through {@link #local}.
 * <p>
 * Internet relay (VPS): a headless Relay with no local network of its own. A gateway phone on the
 * MANET connects to it like a remote (a Client with gateway = true) and bridges the channels marked
 * "share to internet" between the mesh and the relay; phones off the MANET connect to it directly.
 *
 * <pre>
 * Tunnel frame: 'U' 'T' type(1) flags(1) session(8) seq(4) [key ID(4)] | AES-CTR body | HMAC (10)
 *   flags 0: no key ID (direct tunnel, older phones); flags 1: the 4-byte key ID follows the
 *   header, in the clear but covered by the HMAC. Key ID = the tunnel key's tag (as a channel's
 *   tag), so the internet relay finds the user's key at once and many users share one port.
 *   type 1 DATA      body = one radio packet as sent on the network
 *   type 2 HELLO     body = time ms(8) nonce(8) count(1) channel tags(4 each)   remote to relay, every 5 s
 *   type 3 HELLO_ACK body = nonce(8)                                           relay to remote
 *   type 4 SITES     body = count(1), per site: kind(1) state(1) secondsSinceHeard(4) nameLen(1) name
 *                    internet relay to everyone connected, every 10 s and on any change.
 *                    kind 1 = MANET site (gateway), 2 = single remote; state 0 never seen, 1 up, 2 down
 *   type 5 BEACON    body = time ms(8) candidate since ms(8) nonce(8) linkUp(1) nameLen(1) callsign
 *                    gateway to the mesh (local broadcast), every 5 s: the earliest candidate stays
 *                    gateway, later ones stand by
 *   type 6 BYE       body = reason text                                        internet relay to a user
 *                    its admin just removed: the phone stops and shows why
 * Key: ChannelCrypto("tunnel:" + DRYAD row or relay-issued key). TCP carries the same frames, each
 * after a 2-byte length. Relay admin packets ('U' 'A', RelayAdmin) share the internet relay's port.
 * </pre>
 */
public final class Tunnel {
    static final int DATA = 1, HELLO = 2, HELLO_ACK = 3, SITES = 4, BEACON = 5, BYE = 6;
    private static final int HDR = 16, HDR_ID = 20;
    private static final SecureRandom RND = new SecureRandom();
    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** No HELLO_ACK (or anything) from the relay for this long = link down (HELLO goes every 5 s). */
    public static final long DOWN_MS = 30000;

    /** Logging, plugged in by the app (android.util.Log) or the headless relay (stdout). */
    public interface Logger {
        void log(String msg, Throwable t);
    }

    public static volatile Logger logger = new Logger() {
        @Override
        public void log(String msg, Throwable t) {
            System.out.println("Tunnel: " + msg + (t == null ? "" : " (" + t + ")"));
        }
    };

    static void log(String msg, Throwable t) {
        Logger l = logger;
        if (l != null) l.log(msg, t);
    }

    /** This phone's own network, while on air (null in the headless relay). */
    public interface Local {
        /** Hands a packet to this phone's own receiver, the same way as one heard on the network. */
        void deliverLocal(byte[] data, int len);

        /** Puts a packet on the local network only (not back into any tunnel). */
        void sendLanOnly(byte[] data);
    }

    public static volatile Local local;

    /** Internet link events (the VPS client only), for notifications and the site list. */
    public interface Listener {
        /** up = the link came back after outageMs (0 = first connection), or went down. */
        void onLink(Client c, boolean up, long outageMs);

        /** The relay's site list changed (wentDown / cameUp: names whose state changed). */
        void onSites(Client c, List<String> wentDown, List<String> cameUp);

        /** Gateway election: this phone became standby (or active again). */
        void onStandby(Client c, boolean standby);
    }

    public static volatile Listener listener;

    /** Remote side, while connected (UdpLink sends through it instead of the local network). */
    public static volatile Client client;
    /** Internet relay (VPS) client: a remote off the MANET, or the gateway on it. */
    public static volatile Client vps;
    /** Relay side, while on. */
    public static volatile Relay relay;
    /** Channel tags this phone is on (on air + listened to), for the HELLO. */
    public static volatile int[] tags = new int[0];
    /** Only some channels go through the tunnel (the per-channel tunnel icons): send only packets of tags. */
    public static volatile boolean onlyTags;

    private Tunnel() {
    }

    public static ChannelCrypto key(String dryadRow) throws GeneralSecurityException {
        return new ChannelCrypto("tunnel:" + dryadRow.trim());
    }

    // ---- frames ----

    static byte[] seal(ChannelCrypto k, int type, long session, int seq, byte[] body, int len) throws GeneralSecurityException {
        return seal(k, type, session, seq, body, len, false);
    }

    /** withId = put the key ID in the header (internet relay clients). */
    static byte[] seal(ChannelCrypto k, int type, long session, int seq, byte[] body, int len, boolean withId) throws GeneralSecurityException {
        int h = withId ? HDR_ID : HDR;
        byte[] out = new byte[h + len + ChannelCrypto.MAC_LEN];
        out[0] = 'U';
        out[1] = 'T';
        out[2] = (byte) type;
        out[3] = (byte) (withId ? 1 : 0);
        Packet.putLong(out, 4, session);
        Packet.putInt(out, 12, seq);
        if (withId) Packet.putInt(out, 16, k.channelTag);
        System.arraycopy(body, 0, out, h, len);
        k.ctr(iv(session, seq), out, h, len);
        System.arraycopy(k.mac(out, 0, h + len), 0, out, h + len, ChannelCrypto.MAC_LEN);
        return out;
    }

    /** The key ID of a tunnel key (what the internet relay looks users up by). */
    public static int keyId(String keyText) throws GeneralSecurityException {
        return key(keyText).channelTag;
    }

    /** A frame carrying a key ID (flags 1). */
    static boolean hasId(byte[] b, int len) {
        return len >= HDR_ID + ChannelCrypto.MAC_LEN && b[0] == 'U' && b[1] == 'T' && b[3] == 1;
    }

    static final class Frame {
        int type;
        long session;
        int seq;
        byte[] body;
    }

    static Frame open(ChannelCrypto k, byte[] b, int len) {
        if (len < HDR + ChannelCrypto.MAC_LEN || b[0] != 'U' || b[1] != 'T' || (b[3] != 0 && b[3] != 1)) return null;
        int h = b[3] == 1 ? HDR_ID : HDR;
        int n = len - h - ChannelCrypto.MAC_LEN;
        if (n < 0) return null;
        try {
            if (!k.verify(b, 0, h + n, b, h + n)) return null;
            Frame f = new Frame();
            f.type = b[2] & 0xff;
            f.session = Packet.getLong(b, 4);
            f.seq = Packet.getInt(b, 12);
            f.body = new byte[n];
            System.arraycopy(b, h, f.body, 0, n);
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

    /** Recently seen packet keys (bounded). */
    static final class Recent {
        private final Map<String, Boolean> m;

        Recent(final int max) {
            m = new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > max;
                }
            };
        }

        /** True the first time a key is added. */
        synchronized boolean add(String k) {
            return k == null || m.put(k, Boolean.TRUE) == null;
        }

        synchronized boolean has(String k) {
            return k != null && m.containsKey(k);
        }
    }

    /**
     * Packets already handed to this phone by a tunnel: with Relay mode "Both" the same packet comes
     * over the direct tunnel and the internet relay, and the second copy is dropped here.
     */
    private static final Recent delivered = new Recent(4000);

    /** Second copies dropped by {@link #deliver} (Relay mode Both). */
    public static volatile int repeatsDropped;

    static void deliver(byte[] p, int len) {
        if (!delivered.add(key(p, len))) {
            if (++repeatsDropped % 25 == 1) log("repeat from the other tunnel dropped (" + repeatsDropped + " so far)", null);
            return;
        }
        Local l = local;
        if (l != null) l.deliverLocal(p, len);
    }

    /** Frames on the local network that are tunnel frames ('U' 'T'), e.g. another gateway's beacon. */
    public static void onLanTunnelFrame(byte[] b, int len) {
        Client g = vps;
        if (g != null && g.gateway) g.onBeacon(b, len);
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
        /** Send the key ID in each frame (internet relay client; never on beacons, which stay on the mesh). */
        volatile boolean withId;
        /** The UDP socket belongs to a Mux (many users on one port): never closed by this end. */
        boolean sharedSocket;

        End(ChannelCrypto k) {
            this.k = k;
        }

        synchronized byte[] frame(int type, byte[] body, int len) throws GeneralSecurityException {
            return seal(k, type, session, seq++, body, len, withId && type != BEACON);
        }

        void queue(int type, byte[] body, int len) {
            try {
                // TCP cannot drop a late packet by itself: keep at most ~1.5 s waiting, oldest dropped.
                while (out.size() > 80) out.poll();
                out.offer(frame(type, body, len));
            } catch (GeneralSecurityException e) {
                log("seal", e);
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
            log("send: " + e.getMessage(), null);
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
            if (u != null && !sharedSocket) u.close();
        }
    }

    // ---- remote phone (direct relay or internet relay) ----

    /** A site as the internet relay last reported it (SITES frame). */
    public static final class Site {
        public String name;
        /** 1 = MANET site (gateway), 2 = single remote. */
        public int kind;
        /** 0 = never seen, 1 = up, 2 = down. */
        public int state;
        /** When this phone last heard of it being up (local clock). */
        public long lastHeardMs;
    }

    public static final class Client extends End {
        private final String host;
        private final int port;
        private final long nonce = RND.nextLong();
        private volatile Socket tcpSocket;
        private volatile long startedMs = System.currentTimeMillis();
        public volatile String state = "connecting";
        /** Own channel list (internet relay client); null = the shared tags / onlyTags. */
        private volatile int[] ownTags;
        private volatile boolean ownOnly;

        // ---- internet relay extras ----
        /** Gateway on the MANET: bridges the shared channels between the mesh and the relay. */
        public final boolean gateway;
        /** Gateway election: another gateway for this site is active on the mesh, so this one waits. */
        public volatile boolean standby;
        /** Callsign of the active gateway heard on the mesh (while standby). */
        public volatile String otherGateway = "";
        private volatile long otherBeaconMs;
        private final long candidateSince = System.currentTimeMillis();
        private volatile String callsign = "";
        private final Map<Long, Window> beaconWindows = new LinkedHashMap<Long, Window>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Window> e) {
                return size() > 16;
            }
        };
        /** Packets this gateway put on the mesh from the relay: never sent back up. */
        private final Recent fromRelay = new Recent(4000);
        /** Packets this gateway sent up (or heard on the mesh): a copy back from the relay is not put on the mesh again. */
        private final Recent sentUp = new Recent(4000);
        public volatile List<Site> sites = Collections.emptyList();
        public volatile long sitesMs;
        private volatile boolean linkUp;
        private volatile boolean everUp;
        /** When the link was last heard before going down (0 = not down). */
        public volatile long downSinceMs;
        /** The relay's admin removed this key (BYE): nothing more is sent until the settings change. */
        public volatile boolean revoked;

        Client(String host, int port, ChannelCrypto k, boolean gateway) {
            super(k);
            this.host = host;
            this.port = port;
            this.gateway = gateway;
            // A new gateway first listens for another one already active on the mesh.
            this.standby = gateway;
        }

        /** Starts the remote side (replaces any running one). */
        public static void start(String hostPort, String dryadRow) {
            stopClient();
            Client cl = create(hostPort, dryadRow, false);
            if (cl == null) return;
            client = cl;
            cl.begin();
        }

        /**
         * Starts the internet relay client (replaces any running one). gateway = this phone is on
         * the MANET and bridges channelTags (the shared channels); otherwise channelTags are the
         * channels this remote phone is on.
         */
        public static void startVps(String hostPort, String dryadRow, boolean gateway, int[] channelTags, String callsign) {
            stopVps();
            Client cl = create(hostPort, dryadRow, gateway);
            if (cl == null) return;
            cl.ownTags = channelTags;
            cl.ownOnly = gateway;
            cl.callsign = callsign == null ? "" : callsign;
            cl.withId = true; // the internet relay serves many users on one port and finds each by key ID
            vps = cl;
            cl.begin();
        }

        private static Client create(String hostPort, String dryadRow, boolean gateway) {
            int c = hostPort.lastIndexOf(':');
            if (c <= 0 || dryadRow.trim().length() == 0) return null;
            try {
                return new Client(hostPort.substring(0, c).trim(), Integer.parseInt(hostPort.substring(c + 1).trim()), key(dryadRow), gateway);
            } catch (Exception e) {
                log("client", e);
                return null;
            }
        }

        private void begin() {
            sender.start();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, gateway ? "tunnel-gateway" : "tunnel-client").start();
        }

        public static void stopClient() {
            Client c = client;
            client = null;
            if (c != null) c.shutdown();
        }

        public static void stopVps() {
            Client c = vps;
            vps = null;
            if (c != null) c.shutdown();
        }

        private void shutdown() {
            stop();
            closeTcp();
        }

        /** Channel list changed (no reconnect: it goes in the next HELLO). */
        public void setTags(int[] channelTags) {
            ownTags = channelTags;
        }

        public int[] tagsNow() {
            int[] t = ownTags;
            return t != null ? t : tags;
        }

        private boolean only() {
            return ownTags != null ? ownOnly : onlyTags;
        }

        public boolean connected() {
            return System.currentTimeMillis() - lastRxMs < 20000;
        }

        /** Internet link state with the 30 s rule (3 missed 10 s heartbeats). */
        public boolean linkUp() {
            return linkUp;
        }

        public boolean everUp() {
            return everUp;
        }

        private boolean carries(byte[] p, int len) {
            if (!only()) return true;
            int t = channelTag(p, len);
            for (int x : tagsNow()) if (x == t) return true;
            return false; // a channel kept off the tunnel
        }

        /** From UdpLink: a radio packet this phone sends. */
        public void send(byte[] p) {
            if (standby || !carries(p, p.length)) return;
            if (gateway) sentUp.add(key(p, p.length));
            queue(DATA, p, p.length);
        }

        /** Gateway: a packet heard on the mesh goes up if its channel is shared (and it did not come from the relay). */
        public void fromLan(byte[] p, int len) {
            if (!gateway || standby || len < 20 || p[0] != 'U' || p[1] != 'B') return;
            String k = key(p, len);
            if (fromRelay.has(k) || !carries(p, len)) return;
            if (!sentUp.add(k)) return;
            queue(DATA, p, len);
        }

        private long lastUdpTry;

        private void udpHello(DatagramSocket u, InetSocketAddress addr) {
            try {
                byte[] b = helloBody();
                byte[] f = frame(HELLO, b, b.length);
                u.send(new DatagramPacket(f, f.length, addr));
            } catch (Exception e) {
                log("udp retry: " + e.getMessage(), null);
            }
        }

        private void hello() {
            byte[] b = helloBody();
            queue(HELLO, b, b.length);
        }

        private byte[] helloBody() {
            int[] t = tagsNow();
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
                long lastHello = 0, lastBeacon = 0;
                while (running) {
                    long now = System.currentTimeMillis();
                    if (revoked) {
                        checkLink(now);
                        Thread.sleep(1000);
                        continue;
                    }
                    if (gateway) {
                        if (now - lastBeacon >= 5000) {
                            lastBeacon = now;
                            electAndBeacon(now);
                        }
                        if (standby) {
                            state = otherGateway.length() > 0 ? "standby: " + otherGateway + " is the gateway" : "checking for another gateway";
                            startedMs = now;
                            Thread.sleep(500);
                            continue;
                        }
                    }
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
                    checkLink(now);
                    Thread.sleep(500);
                }
            } catch (Exception e) {
                state = "failed: " + e.getMessage();
                log("client loop", e);
            }
        }

        /** Link up/down with the 30 s rule; drops shorter than that never show as down. */
        private void checkLink(long now) {
            if (this != vps) return;
            boolean up = lastRxMs > 0 && now - lastRxMs < DOWN_MS;
            if (up == linkUp) return;
            linkUp = up;
            Listener l = listener;
            if (up) {
                long outage = everUp && downSinceMs > 0 ? now - downSinceMs : 0;
                everUp = true;
                downSinceMs = 0;
                if (l != null) l.onLink(this, true, outage);
            } else {
                downSinceMs = lastRxMs;
                if (l != null) l.onLink(this, false, 0);
            }
        }

        // ---- gateway election on the mesh ----

        private void electAndBeacon(long now) {
            if (!standby || now - candidateSince > 7000) {
                // Standby ends when the active gateway has not been heard for 20 s (or none was heard at start).
                if (standby && now - otherBeaconMs > 20000) setStandby(false);
            }
            if (standby) return;
            byte[] cs = callsign.getBytes(UTF8);
            int n = Math.min(cs.length, 40);
            byte[] b = new byte[26 + n];
            Packet.putLong(b, 0, now);
            Packet.putLong(b, 8, candidateSince);
            Packet.putLong(b, 16, nonce);
            b[24] = (byte) (linkUp ? 1 : 0);
            b[25] = (byte) n;
            System.arraycopy(cs, 0, b, 26, n);
            Local l = local;
            if (l == null) return;
            try {
                l.sendLanOnly(frame(BEACON, b, b.length));
            } catch (GeneralSecurityException e) {
                log("beacon", e);
            }
        }

        void onBeacon(byte[] buf, int len) {
            Frame f = open(k, buf, len);
            if (f == null || f.type != BEACON || f.body.length < 26) return;
            if (Packet.getLong(f.body, 16) == nonce) return; // our own, looped back
            long t = Packet.getLong(f.body, 0);
            if (Math.abs(System.currentTimeMillis() - t) > 15 * 60000L) return; // a recording from long ago
            synchronized (beaconWindows) {
                Window w = beaconWindows.get(f.session);
                if (w == null) {
                    w = new Window();
                    w.reset(f.session);
                    beaconWindows.put(f.session, w);
                }
                if (!w.accept(f.session, f.seq)) return;
            }
            long since = Packet.getLong(f.body, 8);
            long otherNonce = Packet.getLong(f.body, 16);
            int n = Math.min(f.body[25] & 0xff, f.body.length - 26);
            String who = new String(f.body, 26, n, UTF8);
            // The earlier candidate wins (ties: the lower nonce), so exactly one stays gateway.
            boolean theyWin = since < candidateSince || (since == candidateSince && otherNonce < nonce);
            if (!theyWin) return;
            otherGateway = who;
            otherBeaconMs = System.currentTimeMillis();
            if (!standby) setStandby(true);
        }

        private void setStandby(boolean s) {
            if (standby == s) return;
            standby = s;
            if (s) {
                closeTcp();
                linkUp = false;
            }
            Listener l = listener;
            if (l != null) l.onStandby(this, s);
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
                            log("tcp closed: " + e.getMessage(), null);
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
            if (standby) return;
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
            if (f.type == SITES) {
                if (!in.accept(f.session, f.seq)) return;
                lastRxMs = System.currentTimeMillis();
                onSites(f.body);
                return;
            }
            if (f.type == BYE) {
                if (!in.accept(f.session, f.seq)) return;
                String why = new String(f.body, UTF8).trim();
                revoked = true;
                state = "removed from the relay by its admin" + (why.length() > 0 ? " (" + why + ")" : "")
                        + ": ask for a new setup QR code";
                lastRxMs = 0;
                closeTcp();
                log("internet relay: " + state, null);
                return;
            }
            if (f.type != DATA || !in.accept(f.session, f.seq)) return;
            lastRxMs = System.currentTimeMillis();
            byte[] p = f.body;
            if (gateway) {
                String k = key(p, p.length);
                // Already on the mesh (it was heard here and sent up): do not put a second copy on it.
                if (sentUp.has(k)) return;
                fromRelay.add(k);
                Local l = local;
                if (l != null) l.sendLanOnly(p);
                Relay r = relay;
                if (r != null) r.fromOther(p, p.length); // this phone is also a direct relay: its remotes get it too
            }
            deliver(p, p.length);
        }

        private void onSites(byte[] b) {
            if (b.length < 1) return;
            int n = b[0] & 0xff, o = 1;
            long now = System.currentTimeMillis();
            List<Site> list = new ArrayList<Site>();
            for (int i = 0; i < n && o + 7 <= b.length; i++) {
                Site s = new Site();
                s.kind = b[o] & 0xff;
                s.state = b[o + 1] & 0xff;
                long ago = Packet.getInt(b, o + 2) & 0xffffffffL;
                int nl = b[o + 6] & 0xff;
                if (o + 7 + nl > b.length) break;
                s.name = new String(b, o + 7, nl, UTF8);
                s.lastHeardMs = s.state == 0 ? 0 : now - ago * 1000L;
                o += 7 + nl;
                list.add(s);
            }
            List<Site> old = sites;
            List<String> down = new ArrayList<String>(), up = new ArrayList<String>();
            for (Site s : list) {
                for (Site was : old) {
                    if (!was.name.equals(s.name) || was.state == s.state) continue;
                    if (s.state == 2) down.add(s.name);
                    else if (s.state == 1) up.add(s.name);
                }
            }
            sites = Collections.unmodifiableList(list);
            sitesMs = now;
            Listener l = listener;
            if (l != null && (!down.isEmpty() || !up.isEmpty() || old.isEmpty())) l.onSites(this, down, up);
        }

        public String describe() {
            String what = gateway ? "Internet relay gateway" : this == vps ? "Internet relay" : "Tunnel";
            if (standby || revoked) return what + ": " + state;
            return connected() ? what + ": connected to " + host + ":" + port + " over " + transport : what + ": " + state;
        }
    }

    // ---- relay (commander tablet, or the headless internet relay with no local network) ----

    public static final class Relay {
        public final List<Remote> remotes = new java.util.concurrent.CopyOnWriteArrayList<Remote>();
        private int nextId = 1000000;
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
                    log("relay port " + f[0], e);
                }
            }
            relay = r;
        }

        public static void stopRelay() {
            Relay r = relay;
            relay = null;
            if (r != null) for (Remote m : r.remotes) m.close();
        }

        /** Headless internet relay: an empty relay; users are added with {@link #addShared} and reached through a {@link Mux}. */
        public static Relay startShared() {
            stopRelay();
            Relay r = new Relay();
            relay = r;
            return r;
        }

        /** A user on a shared port (Mux), found by its key ID. */
        public synchronized Remote addShared(String label, ChannelCrypto k) {
            Remote m = new Remote(this, 0, label, k, nextId++);
            m.sharedSocket = true;
            m.sender.start();
            remotes.add(m);
            return m;
        }

        /** Cuts a user off now: a BYE with the reason, then its session and TCP link are dropped. */
        public void removeShared(final Remote m, String reason) {
            remotes.remove(m);
            m.kick(reason);
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

        /** From the internet relay (this phone is also its gateway): to the remotes only, it is already on the mesh. */
        void fromOther(byte[] p, int len) {
            String k = key(p, len);
            if (k == null) return;
            synchronized (seen) {
                if (seen.containsKey(k)) return;
                seen.put(k, -2);
            }
            forward(p, len, -1);
        }

        /** From remote `fromId` (its port, or its user number on a shared port): onto the local network (and this phone), and to the other remotes. */
        void fromRemote(byte[] p, int fromId) {
            String k = key(p, p.length);
            if (k == null) return;
            synchronized (seen) {
                if (seen.containsKey(k)) return; // duplicate (e.g. it came over UDP and TCP)
                seen.put(k, fromId);
            }
            Local l = local;
            if (l != null) {
                l.sendLanOnly(p);
                l.deliverLocal(p, p.length);
            }
            Client g = vps;
            if (g != null && g.gateway) g.fromLan(p, p.length); // also up to the internet relay
            forward(p, p.length, fromId);
        }

        private void forward(byte[] p, int len, int exceptId) {
            int tag = channelTag(p, len);
            if (tag == 0) return;
            for (Remote m : remotes) {
                if (m.id != exceptId && m.listensTo(tag)) m.queue(DATA, p, len);
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
        /** Frames that passed the key check (for the headless relay's status). */
        public volatile long framesIn;
        /** Who it is when forwarding: its port, or its user number on a shared port. */
        final int id;
        /** Shared port: the socket the user's last UDP frame came in on (replies go out the same way). */
        private volatile DatagramSocket lastSock;

        Remote(Relay r, int port, String label, ChannelCrypto k) {
            this(r, port, label, k, port);
        }

        Remote(Relay r, int port, String label, ChannelCrypto k, int id) {
            super(k);
            this.relay = r;
            this.port = port;
            this.label = label;
            this.id = id;
        }

        /** Shared port (Mux): one UDP frame for this user. */
        synchronized void fromUdp(byte[] b, int len, DatagramSocket sock, InetSocketAddress from) {
            if (!running) return;
            lastFrom = from;
            lastSock = sock;
            handle(b, len, "UDP");
        }

        /** Shared port (Mux): one frame for this user from TCP connection s. */
        void fromTcp(byte[] b, int len, Socket s) {
            if (!running) return;
            tcpCandidate.set(s);
            handle(b, len, "TCP");
        }

        /** Shared port: TCP connection s closed. */
        void tcpClosed(Socket s) {
            if (tcpSocket == s) {
                tcpSocket = null;
                tcp = null;
                useTcp = false;
            }
        }

        /** Removed by the relay's admin: a BYE, then the session and any TCP link are dropped. */
        void kick(String reason) {
            byte[] b = (reason == null ? "" : reason).getBytes(UTF8);
            queue(BYE, b, b.length);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(700); // time for the BYE to leave
                    } catch (InterruptedException ignored) {
                    }
                    close();
                }
            }, "relay-kick").start();
        }

        boolean listensTo(int tag) {
            if (System.currentTimeMillis() - lastRxMs > 30000) return false;
            for (int t : listen) if (t == tag) return true;
            return false;
        }

        /** Last authenticated frame from this remote (0 = never). */
        public long lastRx() {
            return lastRxMs;
        }

        public String transport() {
            return transport;
        }

        public int channels() {
            return listen.length;
        }

        public String problem() {
            return problem;
        }

        public String peer() {
            InetSocketAddress a = udpPeer;
            return useTcp ? "TCP" : a == null ? "?" : a.getAddress().getHostAddress() + ":" + a.getPort();
        }

        /** Headless relay: the site list to this remote (if it has been heard lately). */
        public void sendSites(byte[] body) {
            if (System.currentTimeMillis() - lastRxMs < DOWN_MS) queue(SITES, body, body.length);
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
                framesIn++;
                byte[] ack = new byte[8];
                System.arraycopy(f.body, 8, ack, 0, 8);
                queue(HELLO_ACK, ack, 8);
                return;
            }
            if (f.type != DATA || !in.accept(f.session, f.seq)) return;
            arrived(via);
            framesIn++;
            relay.fromRemote(f.body, id);
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
                DatagramSocket ls = lastSock;
                if (sharedSocket && ls != null) udp = ls;
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

    // ---- many users on one port (the headless internet relay) ----

    /**
     * Listens on one or more ports (UDP and TCP) for every user of a Relay made with
     * {@link Relay#startShared}. Frames with a key ID go straight to that user; frames without one
     * (older phones) are tried against each user's key. Relay admin packets ('U' 'A') go to
     * {@link #admin}, which answers on the same socket. Anything else is dropped silently.
     */
    public static final class Mux {
        /** Relay admin packets: returns the answer to send back, or null to stay silent. */
        public interface Admin {
            byte[] onAdmin(byte[] b, int len, String from);
        }

        private final Relay relay;
        private final Map<Integer, Remote> byId = new java.util.concurrent.ConcurrentHashMap<Integer, Remote>();
        public volatile Admin admin;
        /** Ports that could not be opened, for the status page. */
        public final List<String> problems = new java.util.concurrent.CopyOnWriteArrayList<String>();

        public Mux(Relay r) {
            relay = r;
        }

        public void put(int keyId, Remote m) {
            byId.put(keyId, m);
        }

        public void remove(int keyId) {
            byId.remove(keyId);
        }

        private Remote find(byte[] b, int len) {
            if (hasId(b, len)) return byId.get(Packet.getInt(b, 16));
            if (len < HDR + ChannelCrypto.MAC_LEN || b[0] != 'U' || b[1] != 'T' || b[3] != 0) return null;
            for (Remote m : relay.remotes) {
                if (m.sharedSocket && m.running && open(m.k, b, len) != null) return m;
            }
            return null;
        }

        private static boolean isAdmin(byte[] b, int len) {
            return len >= 4 && b[0] == 'U' && b[1] == 'A';
        }

        /** Opens UDP and TCP on port; false (and a line in problems) if either is busy. */
        public boolean listen(final int port) {
            boolean ok = true;
            try {
                final DatagramSocket u = new DatagramSocket(null);
                u.setReuseAddress(true);
                u.bind(new InetSocketAddress(port));
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        byte[] buf = new byte[65536];
                        DatagramPacket dp = new DatagramPacket(buf, buf.length);
                        while (true) {
                            try {
                                dp.setLength(buf.length);
                                u.receive(dp);
                                int n = dp.getLength();
                                InetSocketAddress from = (InetSocketAddress) dp.getSocketAddress();
                                if (isAdmin(buf, n)) {
                                    Admin a = admin;
                                    byte[] reply = a == null ? null : a.onAdmin(java.util.Arrays.copyOf(buf, n), n,
                                            from.getAddress().getHostAddress());
                                    if (reply != null) u.send(new DatagramPacket(reply, reply.length, from));
                                    continue;
                                }
                                Remote m = find(buf, n);
                                if (m != null) m.fromUdp(buf, n, u, from);
                            } catch (IOException e) {
                                if (u.isClosed()) return;
                            } catch (RuntimeException e) {
                                log("mux udp", e);
                            }
                        }
                    }
                }, "mux-udp-" + port);
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                problems.add("UDP port " + port + " busy: " + e.getMessage());
                ok = false;
            }
            try {
                final ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(port));
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while (true) {
                            try {
                                final Socket s = ss.accept();
                                s.setTcpNoDelay(true);
                                s.setSoTimeout(90000); // a phone sends a HELLO every 5 s; silent connections are dropped
                                Thread c = new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        tcpConnection(s);
                                    }
                                }, "mux-tcp-rx-" + port);
                                c.setDaemon(true);
                                c.start();
                            } catch (IOException e) {
                                if (ss.isClosed()) return;
                            }
                        }
                    }
                }, "mux-tcp-" + port);
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                problems.add("TCP port " + port + " busy: " + e.getMessage());
                ok = false;
            }
            return ok;
        }

        private void tcpConnection(Socket s) {
            Remote last = null;
            try {
                DataInputStream in = new DataInputStream(s.getInputStream());
                OutputStream out = s.getOutputStream();
                byte[] buf = new byte[65536];
                while (true) {
                    int n = in.readUnsignedShort();
                    in.readFully(buf, 0, n);
                    if (isAdmin(buf, n)) {
                        Admin a = admin;
                        byte[] reply = a == null ? null : a.onAdmin(java.util.Arrays.copyOf(buf, n), n,
                                s.getInetAddress().getHostAddress());
                        if (reply != null && reply.length <= 0xffff) {
                            synchronized (out) {
                                out.write(new byte[]{(byte) (reply.length >> 8), (byte) reply.length});
                                out.write(reply);
                                out.flush();
                            }
                        }
                        continue;
                    }
                    Remote m = find(buf, n);
                    if (m == null) continue;
                    if (last != null && last != m) last.tcpClosed(s);
                    last = m;
                    m.fromTcp(buf, n, s);
                }
            } catch (IOException ignored) {
            } catch (RuntimeException e) {
                log("mux tcp", e);
            } finally {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
                if (last != null) last.tcpClosed(s);
            }
        }
    }

    public static String describe() {
        Client c = client;
        Client v = vps;
        Relay r = relay;
        StringBuilder sb = new StringBuilder();
        if (c != null) sb.append(c.describe());
        else if (r != null) sb.append(r.describe());
        if (v != null) sb.append(sb.length() > 0 ? "\n" : "").append(v.describe());
        return sb.toString();
    }
}
