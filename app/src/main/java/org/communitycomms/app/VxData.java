package org.communitycomms.app;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TAK Vx "data channel" (Settings > Network, off by default; NOT encrypted): Vx phones send a small beacon every
 * 3 s to 239.255.1.2:5000 saying who they are and which voice channel they are on. Worked out from captures of
 * Vx on OpenMANET (no published spec): a 6-byte header (2-byte version 1, 4-byte length, big-endian) then a
 * protobuf message: 1 device ID, 2 callsign, 3 channel address, 4 channel port, 5 IP address, 6 and 8 flags,
 * 7 time (ms), 9 protocol (2 = RTP), 10 link status ("NOT_LINKING"). An empty address means "no channel".
 *
 * While on the OpenMANET / TAK talk group with this on, YCC lists Vx users by callsign (Contacts, talker names)
 * and sends its own beacon the same way, so Vx shows this phone too.
 */
public final class VxData {
    private static final String TAG = "VxData";
    public static final String ADDRESS = "239.255.1.2";
    public static final int PORT = 5000;

    public static final class User {
        public String id = "", callsign = "", channel = "", ip = "", link = "";
        public int port;
        public long lastMs;
    }

    private static volatile VxData running;
    private static final Map<String, User> users = new HashMap<String, User>(); // by device ID

    public static boolean enabled(android.content.Context c) {
        return c.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getBoolean("vxDataOn", true); // callsign: on by default
    }

    public static synchronized void start(android.content.Context c, String groupAndPort, NetworkInterface iface) {
        stop();
        if (!enabled(c)) return;
        try {
            running = new VxData(c, groupAndPort, iface);
        } catch (IOException e) {
            Log.w(TAG, "data channel not available: " + e.getMessage());
        }
    }

    public static synchronized void stop() {
        final VxData v = running;
        running = null;
        if (v != null) {
            v.on = false;
            new Thread(new Runnable() { // the goodbye beacon is network: never on the UI thread
                @Override
                public void run() {
                    v.close();
                }
            }, "vx-data-close").start();
        }
    }

    /** Vx users heard in the last 30 s. */
    public static List<User> users() {
        long now = System.currentTimeMillis();
        List<User> out = new ArrayList<User>();
        synchronized (users) {
            for (User u : users.values()) if (now - u.lastMs < 30000) out.add(u);
        }
        return out;
    }

    /** The callsign of the Vx user at this address (their voice comes from the same address), or null. */
    public static String callsignAt(String ip) {
        if (ip == null) return null;
        synchronized (users) {
            for (User u : users.values()) if (ip.equals(u.ip) && u.callsign.length() > 0) return u.callsign;
        }
        return null;
    }

    public static User byCallsign(String cs) {
        synchronized (users) {
            for (User u : users.values()) if (cs.equals(u.callsign)) return u;
        }
        return null;
    }

    // ---- instance ----

    private final MulticastSocket socket;
    private final InetAddress group;
    private final String callsign, chAddr, chPort, myId;
    private final NetworkInterface iface;
    volatile boolean on = true;

    private VxData(android.content.Context c, String groupAndPort, NetworkInterface iface) throws IOException {
        this.iface = iface;
        int colon = groupAndPort.lastIndexOf(':');
        chAddr = colon > 0 ? groupAndPort.substring(0, colon).trim() : groupAndPort;
        chPort = colon > 0 ? groupAndPort.substring(colon + 1).trim() : "";
        Prefs p = new Prefs(c);
        callsign = p.callsign();
        android.content.SharedPreferences sp = c.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE);
        String id = sp.getString("vxDeviceId", "");
        if (id.length() == 0) {
            byte[] b = new byte[8];
            new java.security.SecureRandom().nextBytes(b);
            StringBuilder s = new StringBuilder("YCC-");
            for (byte x : b) s.append(String.format("%02x", x & 0xff));
            id = s.toString();
            sp.edit().putString("vxDeviceId", id).commit();
        }
        myId = id;
        group = InetAddress.getByName(ADDRESS);
        socket = new MulticastSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(PORT));
        socket.setTimeToLive(32);
        if (iface != null) {
            socket.setNetworkInterface(iface);
            socket.joinGroup(new InetSocketAddress(group, PORT), iface);
        } else {
            socket.joinGroup(group);
        }
        Thread rx = new Thread(new Runnable() {
            @Override
            public void run() {
                receive();
            }
        }, "vx-data-rx");
        rx.setDaemon(true);
        rx.start();
        Thread tx = new Thread(new Runnable() {
            @Override
            public void run() {
                while (on) {
                    send(false);
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "vx-data-tx");
        tx.setDaemon(true);
        tx.start();
    }

    private void close() {
        on = false;
        sendNow(true); // "no channel": Vx drops us at once
        try {
            socket.leaveGroup(group);
        } catch (IOException ignored) {
        }
        socket.close();
    }

    private String myIp() {
        try {
            Enumeration<InetAddress> as = iface != null ? iface.getInetAddresses() : null;
            if (as == null) {
                for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements(); ) {
                    NetworkInterface n = e.nextElement();
                    if (!n.isUp() || n.isLoopback()) continue;
                    for (Enumeration<InetAddress> a = n.getInetAddresses(); a.hasMoreElements(); ) {
                        InetAddress x = a.nextElement();
                        if (x instanceof Inet4Address && x.getHostAddress().startsWith("10.")) return x.getHostAddress();
                    }
                }
                return "";
            }
            while (as.hasMoreElements()) {
                InetAddress x = as.nextElement();
                if (x instanceof Inet4Address) return x.getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private void send(boolean leaving) {
        if (on) sendNow(leaving);
    }

    private void sendNow(boolean leaving) {
        try {
            ByteArrayOutputStream m = new ByteArrayOutputStream();
            str(m, 1, myId + ":1");
            str(m, 2, callsign + ":1");
            str(m, 3, leaving ? "" : chAddr);
            str(m, 4, leaving ? "" : chPort);
            str(m, 5, myIp());
            num(m, 6, 0);
            num(m, 7, System.currentTimeMillis());
            num(m, 8, 0);
            num(m, 9, 2);
            str(m, 10, "NOT_LINKING");
            byte[] body = m.toByteArray();
            byte[] pkt = new byte[6 + body.length];
            pkt[1] = 1;
            pkt[2] = (byte) (body.length >>> 24);
            pkt[3] = (byte) (body.length >>> 16);
            pkt[4] = (byte) (body.length >>> 8);
            pkt[5] = (byte) body.length;
            System.arraycopy(body, 0, pkt, 6, body.length);
            if (!RadioEngine.radioSilent) socket.send(new DatagramPacket(pkt, pkt.length, group, PORT));
        } catch (IOException e) {
            if (on) Log.d(TAG, "send: " + e.getMessage());
        }
    }

    private static void varint(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7fL) != 0) {
            o.write((int) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }
        o.write((int) v);
    }

    private static void str(ByteArrayOutputStream o, int field, String s) throws IOException {
        byte[] b = s.getBytes("UTF-8");
        varint(o, (field << 3) | 2);
        varint(o, b.length);
        o.write(b);
    }

    private static void num(ByteArrayOutputStream o, int field, long v) {
        varint(o, field << 3);
        varint(o, v);
    }

    private void receive() {
        byte[] buf = new byte[2048];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        while (on) {
            try {
                dp.setLength(buf.length);
                socket.receive(dp);
            } catch (IOException e) {
                return;
            }
            try {
                parse(buf, dp.getLength());
            } catch (RuntimeException e) {
                Log.d(TAG, "bad beacon: " + e.getMessage());
            }
        }
    }

    private void parse(byte[] b, int len) {
        if (len < 8) return;
        int n = ((b[2] & 0xff) << 24) | ((b[3] & 0xff) << 16) | ((b[4] & 0xff) << 8) | (b[5] & 0xff);
        int end = Math.min(len, 6 + Math.max(0, n));
        int[] pos = {6};
        User u = new User();
        while (pos[0] < end) {
            long key = readVarint(b, pos, end);
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (wire == 0) {
                long v = readVarint(b, pos, end);
                if (field == 4) u.port = (int) v;
            } else if (wire == 2) {
                int l = (int) readVarint(b, pos, end);
                if (l < 0 || pos[0] + l > end) return;
                String s;
                try {
                    s = new String(b, pos[0], l, "UTF-8");
                } catch (java.io.UnsupportedEncodingException e) {
                    return;
                }
                pos[0] += l;
                switch (field) {
                    case 1: u.id = s; break;
                    case 2: u.callsign = s; break;
                    case 3: u.channel = s; break;
                    case 4:
                        try {
                            u.port = s.length() == 0 ? 0 : Integer.parseInt(s.trim());
                        } catch (NumberFormatException ignored) {
                        }
                        break;
                    case 5: u.ip = s; break;
                    case 10: u.link = s; break;
                    default: break;
                }
            } else if (wire == 1) {
                pos[0] += 8;
            } else if (wire == 5) {
                pos[0] += 4;
            } else {
                return;
            }
        }
        // Vx adds ":1" to the ID and callsign in its regular beacon; the person is the same either way.
        u.id = strip(u.id);
        u.callsign = strip(u.callsign);
        if (u.id.length() == 0 || u.id.equals(myId)) return;
        u.lastMs = System.currentTimeMillis();
        synchronized (users) {
            User old = users.get(u.id);
            if (old != null && u.channel.length() == 0 && u.ip.length() > 0 && old.channel.length() > 0) {
                old.channel = "";
                old.port = 0;
                old.lastMs = u.lastMs;
                return;
            }
            users.put(u.id, u);
        }
    }

    private static String strip(String s) {
        int c = s.lastIndexOf(':');
        return c > 0 && s.substring(c + 1).matches("\\d{1,2}") ? s.substring(0, c) : s;
    }

    private static long readVarint(byte[] b, int[] pos, int end) {
        long r = 0;
        int shift = 0;
        while (pos[0] < end) {
            int c = b[pos[0]++] & 0xff;
            r |= (long) (c & 0x7f) << shift;
            if (c < 0x80) return r;
            shift += 7;
            if (shift > 63) break;
        }
        return r;
    }

    /** The details sheet for an TAK / OpenMANET contact (the "!" next to the name). */
    public static String details(Contacts.Contact c) {
        StringBuilder s = new StringBuilder();
        User u = byCallsign(c.name);
        boolean vx = u != null || "TAK Vx".equals(c.heardOn);
        s.append("Source: ").append(vx ? "TAK Vx (voice plugin)" : "OpenMANET (Comms web page or node)").append('\n');
        s.append("Not encrypted, half duplex (one talker at a time), voice only: no chat, calls, acks or DRYAD.\n\n");
        if (u != null) {
            s.append("Callsign: ").append(u.callsign).append('\n');
            s.append("Device ID: ").append(u.id).append('\n');
            s.append("Channel: ").append(u.channel.length() > 0 ? CompatVoice.channelLabel(u.channel + ":" + u.port) : "none").append('\n');
            s.append("Address: ").append(u.ip).append('\n');
            if (u.link.length() > 0) s.append("Link status: ").append(u.link).append('\n');
        } else if (c.address != null && c.address.length() > 0) {
            s.append("Address: ").append(c.address).append('\n');
        }
        if (c.lastSeenMs > 0) s.append("Last heard: ").append(org.communitycomms.app.geo.Geo.age(System.currentTimeMillis() - c.lastSeenMs));
        return s.toString();
    }

    public static boolean outside(Contacts.Contact c) {
        return "TAK Vx".equals(c.heardOn) || "OpenMANET".equals(c.heardOn) || "OM/TAK".equals(c.heardOn);
    }
}
