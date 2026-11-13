package org.communitycomms.app.camera;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Reolink's own LAN protocol ("Baichuan", TCP port 9000), for cameras that have no HTTP or RTSP
 * (e.g. the E1). Our own implementation, written from the protocol as described by the MIT-licensed
 * reolink_aio project (Copyright (c) 2023 starkillerOG; see NOTICE). Plain Java so it can be tested on
 * a PC. LAN only: a direct connection to the camera, never Reolink's cloud.
 *
 * Only what the app needs: sign in with the user's own camera password, device info, still pictures,
 * and motion / AI events. Nothing here switches lights, sirens or audio.
 *
 * Messages: a little-endian header (magic f0 de bc 0a, command, body length, channel byte, 24-bit
 * message number, status / encryption marker, class, and for class 14 64 a payload offset), then an
 * XML part (XOR-scrambled for the login, AES-128-CFB after it) and an optional binary payload.
 */
public final class BcClient implements Closeable {
    public interface Events {
        /** A pushed alarm message (decrypted XML, e.g. AlarmEventList), on the reader thread. */
        void onEvent(String xml);

        /** The connection ended (camera gone, unplugged, or closed). */
        void onClosed(String why);
    }

    public static final class Reply {
        public int cmd, status;
        public String xml = "";
        public byte[] payload = new byte[0];
    }

    public static final class LoginFailed extends IOException {
        LoginFailed(String m) {
            super(m);
        }
    }

    private static final int CH_HOST = 250, CH_EVENTS = 251;
    private static final byte[] MAGIC = {(byte) 0xf0, (byte) 0xde, (byte) 0xbc, 0x0a};
    private static final int[] XML_KEY = {0x1F, 0x2D, 0x3C, 0x4B, 0x5A, 0x69, 0x78, 0xFF};
    private static final byte[] AES_IV = ascii("0123456789abcdef");
    private static final long END = -1;

    private final Socket socket = new Socket();
    private DataInputStream in;
    private OutputStream out;
    private Thread reader;
    private volatile boolean closed;
    private volatile Events events;
    private byte[] aesKey;
    private int messId;
    private final Map<Long, LinkedBlockingQueue<Reply>> waiting = new HashMap<Long, LinkedBlockingQueue<Reply>>();
    /** From the login reply: channel count and device type. */
    public int channels = 1;
    public String deviceType = "";
    private final Object sendLock = new Object();

    public void connect(String host, int port, int timeoutMs) throws IOException {
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setSoTimeout(0);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "bc-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public void setEvents(Events e) {
        events = e;
    }

    public boolean isOpen() {
        return !closed && socket.isConnected() && !socket.isClosed();
    }

    // ---- sign in ----

    /** Signs in with the camera's own user name and password (sent only as salted MD5 hashes). */
    public void login(String user, String pass, int timeoutMs) throws IOException {
        // 1. Ask for a nonce (legacy 20-byte header, empty body).
        Reply n = request(1, CH_HOST, null, null, true, false, timeoutMs);
        String nonce = tag(n.xml, "nonce");
        if (nonce == null) {
            if ("v3".equals(tag(n.xml, "sigVer"))) throw new LoginFailed("this camera only allows sign-in through a Reolink account");
            throw new IOException("the camera sent no sign-in challenge");
        }
        String userHash = md5Hex31(user + nonce), passHash = md5Hex31(pass + nonce);
        // 2. The login itself, XOR-scrambled.
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<LoginUser version=\"1.1\">\n<userName>" + userHash
                + "</userName>\n<password>" + passHash + "</password>\n<userVer>1</userVer>\n</LoginUser>\n"
                + "<LoginNet version=\"1.1\">\n<type>LAN</type>\n<udpPort>0</udpPort>\n</LoginNet>\n</body>\n";
        Reply r;
        try {
            r = request(1, CH_HOST, null, body, false, false, timeoutMs);
        } catch (StatusException e) {
            if (e.status == 401) throw new LoginFailed("the camera refused the user name or password");
            if (e.status == 406) throw new LoginFailed("this camera only allows sign-in through a Reolink account");
            throw e;
        }
        aesKey = ascii(md5Hex31(nonce + "-" + pass).substring(0, 16));
        String cn = tag(r.xml, "channelNum");
        if (cn != null) {
            try {
                channels = Math.max(1, Integer.parseInt(cn.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        String ty = tag(r.xml, "type");
        if (ty != null) deviceType = ty;
    }

    /** Device info (cmd 80): XML with type, firmwareVersion, name... */
    public String deviceInfo(int timeoutMs) throws IOException {
        return request(80, CH_HOST, null, null, false, true, timeoutMs).xml;
    }

    public void logout() {
        if (aesKey == null) return;
        try {
            request(2, CH_HOST, null, "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<LoginUser version=\"1.1\">\n"
                    + "<userVer>1</userVer>\n</LoginUser>\n</body>\n", false, true, 3000);
        } catch (Exception ignored) {
        }
        aesKey = null;
    }

    // ---- pictures ----

    /** One JPEG (cmd 109). stream "sub" (small) or "main". */
    public byte[] snapshot(int channel, String stream, int timeoutMs) throws IOException {
        String ext = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<Extension version=\"1.1\">\n<channelId>" + channel
                + "</channelId>\n</Extension>\n";
        String body = "\n<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<Snap version=\"1.1\">\n<channelId>" + channel
                + "</channelId>\n<logicChannel>0</logicChannel>\n<time>0</time>\n<fullFrame>0</fullFrame>\n<streamType>" + stream
                + "</streamType>\n</Snap>\n</body>\n";
        long key;
        LinkedBlockingQueue<Reply> q = new LinkedBlockingQueue<Reply>();
        synchronized (sendLock) {
            int id = nextId();
            key = key(109, channel + 1, id);
            synchronized (waiting) {
                waiting.put(key, q);
            }
            send(109, channel + 1, id, ext, body, false, true);
        }
        try {
            long deadline = System.currentTimeMillis() + timeoutMs;
            Reply first = take(q, deadline);
            checkStatus(first);
            String ps = tag(first.xml, "pictureSize");
            int size = ps == null ? -1 : Integer.parseInt(ps.trim());
            java.io.ByteArrayOutputStream jpg = new java.io.ByteArrayOutputStream(size > 0 ? size : 65536);
            if (first.payload.length > 0) jpg.write(first.payload, 0, first.payload.length);
            while (size < 0 || jpg.size() < size) {
                Reply r = take(q, deadline);
                if (r.payload.length == 0) break; // an empty payload ends the picture
                jpg.write(r.payload, 0, r.payload.length);
                if (jpg.size() > 12 * 1048576) throw new IOException("picture too big");
            }
            byte[] b = jpg.toByteArray();
            if (size > 0 && b.length != size) throw new IOException("picture cut short (" + b.length + " of " + size + " bytes)");
            return b;
        } catch (NumberFormatException e) {
            throw new IOException("the camera sent an unreadable picture size");
        } finally {
            synchronized (waiting) {
                waiting.remove(key);
            }
        }
    }

    // ---- events ----

    /** Ask the camera to push alarm events (cmd 31) on this connection. */
    public void subscribe(int timeoutMs) throws IOException {
        request(31, CH_EVENTS, null, null, false, true, timeoutMs);
    }

    /** Keepalive while subscribed (cmd 93); send about every 30 s. */
    public void keepalive(int timeoutMs) throws IOException {
        request(93, CH_HOST, null, null, false, true, timeoutMs);
    }

    // ---- requests ----

    private static final class StatusException extends IOException {
        final int status;

        StatusException(int s) {
            super(s == 401 ? "not signed in" : "the camera answered " + s);
            status = s;
        }
    }

    private int nextId() {
        messId = (messId + 1) % 16777216;
        return messId;
    }

    private static long key(int cmd, int ch, int id) {
        return ((long) cmd << 32) | ((long) (ch & 0xff)) | ((long) id << 8);
    }

    private Reply request(int cmd, int ch, String ext, String body, boolean legacy, boolean aes, int timeoutMs) throws IOException {
        long key;
        LinkedBlockingQueue<Reply> q = new LinkedBlockingQueue<Reply>();
        synchronized (sendLock) {
            int id = nextId();
            key = key(cmd, ch, id);
            synchronized (waiting) {
                waiting.put(key, q);
            }
            send(cmd, ch, id, ext, body, legacy, aes);
        }
        try {
            Reply r = take(q, System.currentTimeMillis() + timeoutMs);
            checkStatus(r);
            return r;
        } finally {
            synchronized (waiting) {
                waiting.remove(key);
            }
        }
    }

    private static void checkStatus(Reply r) throws IOException {
        if (r.status != 0 && r.status != 200 && r.status != 201 && r.status != 300) throw new StatusException(r.status);
    }

    private Reply take(LinkedBlockingQueue<Reply> q, long deadline) throws IOException {
        try {
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) throw new IOException("the camera did not answer in time");
                Reply r = q.poll(Math.min(left, 500), TimeUnit.MILLISECONDS);
                if (r != null) {
                    if (r.cmd == END) throw new IOException("the connection to the camera ended");
                    return r;
                }
                if (closed) throw new IOException("the connection to the camera ended");
            }
        } catch (InterruptedException e) {
            throw new IOException("interrupted");
        }
    }

    private void send(int cmd, int ch, int id, String ext, String body, boolean legacy, boolean aes) throws IOException {
        byte[] e = ext == null ? new byte[0] : utf8(ext), b = body == null ? new byte[0] : utf8(body);
        if (aes) {
            if (aesKey == null) throw new IOException("not signed in");
            e = aes(e, Cipher.ENCRYPT_MODE);
            b = aes(b, Cipher.ENCRYPT_MODE);
        } else {
            e = xor(e, ch);
            b = xor(b, ch);
        }
        int hdr = legacy ? 20 : 24;
        byte[] m = new byte[hdr + e.length + b.length];
        System.arraycopy(MAGIC, 0, m, 0, 4);
        le32(m, 4, cmd);
        le32(m, 8, e.length + b.length);
        m[12] = (byte) ch;
        m[13] = (byte) id;
        m[14] = (byte) (id >> 8);
        m[15] = (byte) (id >> 16);
        if (legacy) {
            m[16] = 0x12;
            m[17] = (byte) 0xdc;
            m[18] = 0x14;
            m[19] = 0x65;
        } else {
            m[18] = 0x14;
            m[19] = 0x64;
            le32(m, 20, e.length);
        }
        System.arraycopy(e, 0, m, hdr, e.length);
        System.arraycopy(b, 0, m, hdr + e.length, b.length);
        out.write(m);
        out.flush();
    }

    // ---- reading ----

    private void readLoop() {
        String why = "closed";
        try {
            byte[] h = new byte[24];
            while (!closed) {
                in.readFully(h, 0, 20);
                if (h[0] != MAGIC[0] || h[1] != MAGIC[1] || h[2] != MAGIC[2] || h[3] != MAGIC[3]) {
                    throw new IOException("lost track of the camera's messages");
                }
                int cmd = le32(h, 4), len = le32(h, 8);
                int b16 = h[16] & 0xff, b17 = h[17] & 0xff, c18 = h[18] & 0xff, c19 = h[19] & 0xff;
                boolean short20 = c18 == 0x14 && c19 == 0x66;
                int hdr = short20 ? 20 : 24;
                int payloadOffset = 0;
                if (!short20) {
                    in.readFully(h, 20, 4);
                    payloadOffset = le32(h, 20);
                }
                if (len < 0 || len > 16 * 1048576) throw new IOException("message too big");
                byte[] body = new byte[len];
                in.readFully(body);
                if (payloadOffset == 0 || payloadOffset > len) payloadOffset = len;
                int ch = h[12] & 0xff;
                int id = (h[13] & 0xff) | ((h[14] & 0xff) << 8) | ((h[15] & 0xff) << 16);
                Reply r = new Reply();
                r.cmd = cmd;
                r.status = short20 ? 0 : (b16 | (b17 << 8));
                byte[] xmlPart = new byte[payloadOffset];
                System.arraycopy(body, 0, xmlPart, 0, payloadOffset);
                r.payload = new byte[len - payloadOffset];
                System.arraycopy(body, payloadOffset, r.payload, 0, r.payload.length);
                r.xml = decryptXml(xmlPart, ch, short20 ? (b16 << 8 | b17) : -1);
                if (r.payload.length > 0 && aesKey != null) {
                    String el = tag(r.xml, "encryptLen");
                    if (el != null) {
                        try {
                            int n = Math.min(r.payload.length, Integer.parseInt(el.trim()));
                            byte[] head = new byte[n];
                            System.arraycopy(r.payload, 0, head, 0, n);
                            head = aes(head, Cipher.DECRYPT_MODE);
                            System.arraycopy(head, 0, r.payload, 0, n);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                if (cmd == 234 && len == 0) { // heartbeat from the camera: answer it
                    byte[] m = new byte[24];
                    System.arraycopy(MAGIC, 0, m, 0, 4);
                    le32(m, 4, 234);
                    System.arraycopy(h, 12, m, 12, 4);
                    m[18] = 0x14;
                    m[19] = 0x64;
                    synchronized (sendLock) {
                        out.write(m);
                        out.flush();
                    }
                    continue;
                }
                LinkedBlockingQueue<Reply> q;
                synchronized (waiting) {
                    q = waiting.get(key(cmd, ch, id));
                }
                if (q != null) q.add(r);
                else if (cmd == 33) {
                    Events ev = events;
                    if (ev != null) ev.onEvent(r.xml);
                }
            }
        } catch (IOException e) {
            why = e.getMessage() == null ? e.toString() : e.getMessage();
        } finally {
            boolean was = closed;
            closed = true;
            synchronized (waiting) {
                for (LinkedBlockingQueue<Reply> q : waiting.values()) {
                    Reply end = new Reply();
                    end.cmd = (int) END;
                    q.add(end);
                }
            }
            Events ev = events;
            if (ev != null && !was) ev.onClosed(why);
        }
    }

    /** marker: for 20-byte headers the encryption marker (0x01dd/0x12dd XOR, 0x02dd/0x03dd AES, 0x00dd plain). */
    private String decryptXml(byte[] x, int ch, int marker) {
        if (x.length == 0) return "";
        if (marker == 0x00dd) return new String(x, java.nio.charset.Charset.forName("UTF-8"));
        if (marker == 0x01dd || marker == 0x12dd) return str(xor(x, ch));
        if ((marker == 0x02dd || marker == 0x03dd) && aesKey != null) return str(aes(x, Cipher.DECRYPT_MODE));
        // 24-byte header: AES once signed in, else XOR; fall back to whichever gives XML.
        if (aesKey != null) {
            String a = str(aes(x, Cipher.DECRYPT_MODE));
            if (a.trim().startsWith("<?xml")) return a;
        }
        String o = str(xor(x, ch));
        if (o.trim().startsWith("<?xml")) return o;
        String p = str(x);
        return p.trim().startsWith("<?xml") ? p : o;
    }

    // ---- crypto and helpers ----

    static byte[] xor(byte[] in, int offset) {
        offset &= 0xff;
        byte[] k = new byte[8];
        for (int j = 0; j < 8; j++) k[j] = (byte) (XML_KEY[(offset + j) % 8] ^ offset);
        byte[] o = new byte[in.length];
        for (int i = 0; i < in.length; i++) o[i] = (byte) (in[i] ^ k[i % 8]);
        return o;
    }

    private byte[] aes(byte[] in, int mode) {
        if (in.length == 0) return in;
        try {
            Cipher c = Cipher.getInstance("AES/CFB/NoPadding"); // CFB with a 128-bit segment
            c.init(mode, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(AES_IV));
            return c.doFinal(in);
        } catch (Exception e) {
            throw new RuntimeException("AES not available: " + e);
        }
    }

    /** MD5 as upper-case hex, cut to 31 characters (what the camera expects). */
    static String md5Hex31(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(utf8(s));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x & 0xff));
            return b.substring(0, 31).toUpperCase(java.util.Locale.US);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final Map<String, Pattern> tagPatterns = new HashMap<String, Pattern>();

    /** Text of the first &lt;name&gt; element, or null. */
    public static String tag(String xml, String name) {
        Pattern p;
        synchronized (tagPatterns) {
            p = tagPatterns.get(name);
            if (p == null) {
                p = Pattern.compile("<" + name + "(?:\\s[^>]*)?>([^<]*)</" + name + ">");
                tagPatterns.put(name, p);
            }
        }
        Matcher m = p.matcher(xml == null ? "" : xml);
        return m.find() ? m.group(1) : null;
    }

    private static void le32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(java.nio.charset.Charset.forName("UTF-8"));
    }

    private static byte[] ascii(String s) {
        return s.getBytes(java.nio.charset.Charset.forName("US-ASCII"));
    }

    private static String str(byte[] b) {
        return new String(b, java.nio.charset.Charset.forName("UTF-8"));
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
