package org.communitycomms.app.net;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Internet relay admin: commands from the admin's phone to the headless relay (ycc-relay.jar),
 * on the relay's own port (UDP, then TCP if UDP gets no answer). Plain Java: the same code runs in
 * the app and in the relay.
 *
 * <pre>
 * Packet: 'U' 'A' version 1 | dir(1) | time ms(8) | nonce(16) | AES-256-CTR body | HMAC-SHA256 (16)
 *   dir 0 = request (admin to relay), 1 = answer (relay to admin)
 *   IV = the nonce; the HMAC covers everything before it
 *   keys: SHA-512("ycc-admin/v1|" + admin key) -> AES key (first 32 bytes), HMAC key (last 32)
 *   request body = UTF-8 lines: command, then its arguments
 *   answer body  = the request's nonce(16) + UTF-8 lines: "ok" or "err" or "clock", then the result
 * Replay protection: the relay takes a request only if its time is within 5 minutes of the relay's
 * clock and not before the relay started, and each nonce only once (a repeat gets the first answer
 * again and is not run again). An answer must carry the request's nonce. Packets that fail the
 * HMAC get no answer at all, so a wrong admin key looks like a relay that is not there.
 * </pre>
 * Admin key: 40 letters A-Z (188 bits). Setup QR text: "YCCADMIN1|host:port|KEY".
 */
public final class RelayAdmin {
    public static final String QR_PREFIX = "YCCADMIN1|";
    public static final long WINDOW_MS = 5 * 60000L;
    private static final int HDR = 4 + 8 + 16, MAC = 16;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final SecureRandom RND = new SecureRandom();

    private final SecretKeySpec enc, mac;

    public RelayAdmin(String key) throws GeneralSecurityException {
        byte[] okm = MessageDigest.getInstance("SHA-512").digest(("ycc-admin/v1|" + normalize(key)).getBytes(UTF8));
        enc = new SecretKeySpec(Arrays.copyOfRange(okm, 0, 32), "AES");
        mac = new SecretKeySpec(Arrays.copyOfRange(okm, 32, 64), "HmacSHA256");
    }

    /** Letters only, upper case (spaces and dashes in a typed key do not matter). */
    public static String normalize(String key) {
        StringBuilder sb = new StringBuilder();
        for (char c : (key == null ? "" : key).toUpperCase(java.util.Locale.US).toCharArray()) {
            if (c >= 'A' && c <= 'Z') sb.append(c);
        }
        return sb.toString();
    }

    /** A random key of n letters A-Z (4.7 bits each). */
    public static String newKey(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append((char) ('A' + RND.nextInt(26)));
        return sb.toString();
    }

    public static String newAdminKey() {
        return newKey(40);
    }

    /** "YCCADMIN1|host:port|KEY" -> {host:port, key}, or null. A bare key gives {"", key}. */
    public static String[] parse(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.startsWith(QR_PREFIX)) {
            String[] f = t.substring(QR_PREFIX.length()).split("\\|", 2);
            if (f.length != 2 || normalize(f[1]).length() < 32) return null;
            return new String[]{f[0].trim(), normalize(f[1])};
        }
        return normalize(t).length() >= 32 ? new String[]{"", normalize(t)} : null;
    }

    // ---- packets ----

    byte[] seal(int dir, long time, byte[] nonce, byte[] body) throws GeneralSecurityException {
        byte[] out = new byte[HDR + body.length + MAC];
        out[0] = 'U';
        out[1] = 'A';
        out[2] = 1;
        out[3] = (byte) dir;
        for (int i = 0; i < 8; i++) out[4 + i] = (byte) (time >>> (56 - 8 * i));
        System.arraycopy(nonce, 0, out, 12, 16);
        Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, enc, new IvParameterSpec(nonce));
        byte[] ct = c.doFinal(body);
        System.arraycopy(ct, 0, out, HDR, ct.length);
        System.arraycopy(hmac(out, HDR + body.length), 0, out, HDR + body.length, MAC);
        return out;
    }

    static final class Msg {
        long time;
        byte[] nonce;
        byte[] body;
    }

    Msg open(byte[] b, int len, int dir) {
        if (len < HDR + MAC || b[0] != 'U' || b[1] != 'A' || b[2] != 1 || b[3] != dir) return null;
        try {
            byte[] want = hmac(b, len - MAC);
            if (!MessageDigest.isEqual(want, Arrays.copyOfRange(b, len - MAC, len))) return null;
            Msg m = new Msg();
            for (int i = 0; i < 8; i++) m.time = (m.time << 8) | (b[4 + i] & 0xff);
            m.nonce = Arrays.copyOfRange(b, 12, 28);
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.DECRYPT_MODE, enc, new IvParameterSpec(m.nonce));
            m.body = c.doFinal(b, HDR, len - MAC - HDR);
            return m;
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    private byte[] hmac(byte[] b, int len) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(mac);
        m.update(b, 0, len);
        return Arrays.copyOf(m.doFinal(), MAC);
    }

    // ---- admin phone side ----

    /** Relay clock minus this phone's clock, learnt from a "clock" answer. */
    private static volatile long clockOffset;

    /**
     * Sends one command (lines joined by \n) and returns the answer lines ("ok" / "err" first).
     * Throws IOException when nothing answers (relay down, or the admin key is wrong).
     */
    public String call(String hostPort, String command) throws IOException {
        int c = hostPort.lastIndexOf(':');
        if (c <= 0) throw new IOException("relay address must be host:port");
        String host = hostPort.substring(0, c).trim();
        int port;
        try {
            port = Integer.parseInt(hostPort.substring(c + 1).trim());
        } catch (NumberFormatException e) {
            throw new IOException("bad port in " + hostPort);
        }
        InetSocketAddress addr = new InetSocketAddress(InetAddress.getByName(host), port);
        for (int attempt = 0; attempt < 2; attempt++) {
            String a = once(addr, command);
            if (a.startsWith("clock\n") && attempt == 0) {
                try {
                    clockOffset = Long.parseLong(a.substring(6).trim()) - System.currentTimeMillis();
                } catch (NumberFormatException e) {
                    return "err\nbad clock answer";
                }
                continue; // again with the relay's time
            }
            if (a.startsWith("clock\n")) return "err\nthis phone's clock is too far from the relay's";
            return a;
        }
        return "err\nno answer";
    }

    private String once(InetSocketAddress addr, String command) throws IOException {
        byte[] nonce = new byte[16];
        RND.nextBytes(nonce);
        byte[] req;
        try {
            req = seal(0, System.currentTimeMillis() + clockOffset, nonce, command.getBytes(UTF8));
        } catch (GeneralSecurityException e) {
            throw new IOException("crypto: " + e.getMessage());
        }
        // UDP: the same packet up to 3 times (a repeat is answered, never run twice), then TCP.
        DatagramSocket u = new DatagramSocket();
        try {
            u.setSoTimeout(2000);
            byte[] buf = new byte[65536];
            for (int i = 0; i < 3; i++) {
                u.send(new DatagramPacket(req, req.length, addr));
                long until = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < until) {
                    DatagramPacket dp = new DatagramPacket(buf, buf.length);
                    try {
                        u.receive(dp);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                    String a = answer(buf, dp.getLength(), nonce);
                    if (a != null) return a;
                }
            }
        } finally {
            u.close();
        }
        Socket s = new Socket();
        try {
            s.connect(addr, 6000);
            s.setSoTimeout(6000);
            OutputStream o = s.getOutputStream();
            o.write(new byte[]{(byte) (req.length >> 8), (byte) req.length});
            o.write(req);
            o.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            int n = in.readUnsignedShort();
            byte[] b = new byte[n];
            in.readFully(b);
            String a = answer(b, n, nonce);
            if (a != null) return a;
        } catch (IOException e) {
            // fall through to the message below
        } finally {
            s.close();
        }
        throw new IOException("no answer from the relay (is the address right, the relay running, and the admin key current?)");
    }

    private String answer(byte[] b, int len, byte[] nonce) {
        Msg m = open(b, len, 1);
        if (m == null || m.body.length < 16 || !Arrays.equals(Arrays.copyOf(m.body, 16), nonce)) return null;
        return new String(m.body, 16, m.body.length - 16, UTF8);
    }

    // ---- relay side ----

    /** Runs one checked command; viaNewKey = it came with a key from "rotate" (which now replaces the old one). */
    public interface Handler {
        String run(String[] lines, String from, boolean viaNewKey);
    }

    public static final class Server {
        private volatile RelayAdmin current, pending;
        private final long startedMs = System.currentTimeMillis();
        /** nonce (hex) -> [time, answer] of requests already run, for the replay window. */
        private final Map<String, Object[]> done = new LinkedHashMap<String, Object[]>();
        private final Handler handler;

        public Server(Handler h) {
            handler = h;
        }

        /** Sets the keys (pending = from "rotate", not used yet; may be null). */
        public synchronized void setKeys(String currentKey, String pendingKey) throws GeneralSecurityException {
            current = currentKey == null || currentKey.length() == 0 ? null : new RelayAdmin(currentKey);
            pending = pendingKey == null || pendingKey.length() == 0 ? null : new RelayAdmin(pendingKey);
        }

        /** An admin packet from address `from`: the sealed answer, or null (bad key: stay silent). */
        public synchronized byte[] onPacket(byte[] b, int len, String from) {
            RelayAdmin k = null;
            Msg m = null;
            boolean viaNew = false;
            if (current != null) {
                m = current.open(b, len, 0);
                k = current;
            }
            if (m == null && pending != null) {
                m = pending.open(b, len, 0);
                k = pending;
                viaNew = true;
            }
            if (m == null) return null;
            long now = System.currentTimeMillis();
            String id = hex(m.nonce);
            Iterator<Map.Entry<String, Object[]>> it = done.entrySet().iterator();
            while (it.hasNext()) {
                if (now - (Long) it.next().getValue()[0] > 2 * WINDOW_MS || done.size() > 5000) it.remove();
                else break;
            }
            String text;
            Object[] before = done.get(id);
            if (before != null) {
                text = (String) before[1]; // a repeat (lost answer, or a replay): answer again, never run again
                handler.run(new String[]{"#repeat"}, from, viaNew);
            } else if (Math.abs(now - m.time) > WINDOW_MS || m.time < startedMs) {
                text = "clock\n" + now; // outside the window: not run; the admin phone retries with the relay's time
            } else {
                String[] lines = new String(m.body, UTF8).split("\n", -1);
                try {
                    text = handler.run(lines, from, viaNew);
                } catch (RuntimeException e) {
                    text = "err\n" + e.getMessage();
                }
                done.put(id, new Object[]{now, text});
            }
            byte[] body = text.getBytes(UTF8);
            byte[] withNonce = new byte[16 + body.length];
            System.arraycopy(m.nonce, 0, withNonce, 0, 16);
            System.arraycopy(body, 0, withNonce, 16, body.length);
            byte[] n2 = new byte[16];
            RND.nextBytes(n2);
            try {
                return k.seal(1, now, n2, withNonce);
            } catch (GeneralSecurityException e) {
                return null;
            }
        }

        private static String hex(byte[] b) {
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        }
    }
}
