package org.communitycomms.app.power;

import android.content.Context;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * EcoFlow power stations (River 3, Delta 3 family, which send protobuf telemetry) over Bluetooth LE.
 * UNTESTED on real hardware. Our own Java port of the read-only part of ha-ef-ble (Apache-2.0,
 * rabits and contributors; see NOTICE), with its session key table as the asset
 * ecoflow_keydata.bin: secp160r1 ECDH, AES-128-CBC frames, then the account check, which needs the
 * owner's EcoFlow user ID (typed in once; the app never signs in to EcoFlow) and the unit's serial.
 * Read-only: nothing is sent after the account check. The unit's clock-sync request is ignored on
 * purpose (answering it would set the unit's clock), so some estimates may not come.
 * Delta 2 and older models use a different format and are not read.
 */
public final class EcoFlowReader extends BatteryReader {
    public static final int MANUFACTURER = 0xB5B5;
    private static final String[] NOTIFY = {"00000003-0000-1000-8000-00805f9b34fb", "6e400003-b5a3-f393-e0a9-e50e24dcca9e"};
    private static final String[] WRITE = {"00000002-0000-1000-8000-00805f9b34fb", "6e400002-b5a3-f393-e0a9-e50e24dcca9e"};
    private static byte[] keyTable;

    private final Context ctx;
    private BleLink link;
    private byte[] iv, sessionKey;
    private boolean authed;
    private int seq = 1;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    /** Telemetry comes as changes: keep the latest of each field. */
    private final Map<Integer, Number> fields = new HashMap<Integer, Number>();
    private long lastTelemetryWall;

    EcoFlowReader(Context c, BatteryUnit u, Log log) {
        super(u, log);
        ctx = c.getApplicationContext();
    }

    private synchronized byte[] table() {
        if (keyTable != null) return keyTable;
        try {
            InputStream in = ctx.getAssets().open("ecoflow_keydata.bin");
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            in.close();
            keyTable = o.toByteArray();
        } catch (Exception e) {
            keyTable = null;
        }
        return keyTable;
    }

    @Override
    public BatteryStatus read() {
        if (unit.userId.trim().length() == 0 || unit.serial.trim().length() == 0) {
            lastError = "needs your EcoFlow user ID and the unit's serial number (tap the unit > Settings)";
            return null;
        }
        if (table() == null || table().length < 65280) {
            lastError = "this build has no EcoFlow key table, so it can't connect yet";
            return null;
        }
        if (link == null || !link.isConnected() || !authed) {
            close();
            link = new BleLink(ctx, unit.addr, log);
            link.rawLog = rawLog;
            if (!link.connect(NOTIFY, WRITE, "an EcoFlow power station")) {
                lastError = link.lastError;
                link = null;
                return null;
            }
            try {
                if (!handshake()) {
                    close();
                    return null;
                }
            } catch (Exception e) {
                lastError = "handshake error: " + e.getMessage();
                close();
                return null;
            }
        }
        link.rawLog = rawLog;
        long end = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < end) {
            byte[][] f = nextFrame(end - SystemClock.elapsedRealtime());
            if (f == null) break;
            if (f[0][0] == 1) handlePacket(f[1]);
        }
        if (fields.isEmpty() || System.currentTimeMillis() - lastTelemetryWall > 120000) {
            lastError = link.isConnected() ? "connected, but no readings came" : "link lost";
            if (!link.isConnected()) close();
            return null;
        }
        return status();
    }

    private boolean handshake() throws Exception {
        authed = false;
        sessionKey = null;
        Ec c = Ec.SECP160R1;
        BigInteger d = c.randomKey();
        byte[] pub = c.encode(c.mul(c.g(), d));
        byte[] p1 = new byte[2 + pub.length];
        p1[0] = 1;
        System.arraycopy(pub, 0, p1, 2, pub.length);
        link.write(outer(0, p1));
        byte[] r = waitPlain(1, 43, 15000);
        if (r == null) {
            lastError = "no key exchange answer";
            return false;
        }
        if (r.length < 3 + 40) {
            lastError = "short key answer";
            return false;
        }
        byte[] secret = c.ecdh(d, c.point(r, 3));
        if (secret == null) {
            lastError = "the unit uses a key type this app does not know (" + (r[2] & 0xFF) + ")";
            return false;
        }
        iv = MessageDigest.getInstance("MD5").digest(secret);
        byte[] k1 = java.util.Arrays.copyOf(secret, 16);
        link.write(outer(0, new byte[]{2}));
        r = waitPlain(2, 33, 15000);
        if (r == null) {
            lastError = "no session answer";
            return false;
        }
        byte[] enc = java.util.Arrays.copyOfRange(r, 1, 1 + ((r.length - 1) / 16) * 16);
        byte[] dd = cbc(Cipher.DECRYPT_MODE, k1, enc, false);
        byte[] srand = java.util.Arrays.copyOfRange(dd, 0, 16);
        int pos = (dd[16] & 0xFF) * 0x10 + (((dd[17] & 0xFF) - 1) & 0xFF) * 0x100;
        byte[] t = table();
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        m.write(t, pos, 16);
        m.write(srand, 0, 16);
        sessionKey = MessageDigest.getInstance("MD5").digest(m.toByteArray());
        // Account check: the unit wants md5(user ID + serial) as upper-case hex.
        send(0x21, 0x35, 0x35, 0x89, new byte[0]);
        byte[] md = MessageDigest.getInstance("MD5").digest((unit.userId.trim() + unit.serial.trim()).getBytes("US-ASCII"));
        StringBuilder h = new StringBuilder();
        for (byte b : md) h.append(String.format("%02X", b & 0xFF));
        send(0x21, 0x35, 0x35, 0x86, h.toString().getBytes("US-ASCII"));
        long end = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < end) {
            byte[][] f = nextFrame(end - SystemClock.elapsedRealtime());
            if (f == null) break;
            if (f[0][0] != 1) continue;
            Packet p = Packet.parse(f[1], false);
            if (p == null) continue;
            if (p.cmdSet == 0x35 && p.cmdId == 0x86) {
                int code = p.payload.length > 0 ? p.payload[0] & 0xFF : 0;
                if (code != 0) {
                    lastError = "the unit refused the account check (code " + code + "): check the user ID and serial";
                    return false;
                }
                authed = true;
                log.log("EcoFlow: signed in to the unit");
                return true;
            }
            handlePacket(f[1]);
        }
        lastError = "no answer to the account check";
        return false;
    }

    private byte[] waitPlain(int first, int minLen, long ms) {
        long end = SystemClock.elapsedRealtime() + ms;
        while (SystemClock.elapsedRealtime() < end) {
            byte[][] f = nextFrame(end - SystemClock.elapsedRealtime());
            if (f == null) return null;
            if (f[0][0] == 0 && f[1].length >= minLen && (f[1][0] & 0xFF) == first) return f[1];
        }
        return null;
    }

    private void send(int src, int dst, int cmdSet, int cmdId, byte[] payload) throws Exception {
        byte[] inner = Packet.build(seq++, src, dst, cmdSet, cmdId, payload);
        link.write(outer(1, cbc(Cipher.ENCRYPT_MODE, sessionKey, inner, true)));
    }

    private byte[] cbc(int mode, byte[] k, byte[] data, boolean pad) throws Exception {
        Cipher c = Cipher.getInstance(pad ? "AES/CBC/PKCS5Padding" : "AES/CBC/NoPadding");
        c.init(mode, new SecretKeySpec(k, "AES"), new IvParameterSpec(iv));
        return c.doFinal(data);
    }

    /** 5A 5A, type<<4, 01, length (payload + CRC), payload, CRC-16/ARC little-endian. */
    static byte[] outer(int type, byte[] payload) {
        int n = payload.length + 2;
        byte[] o = new byte[6 + n];
        o[0] = 0x5a;
        o[1] = 0x5a;
        o[2] = (byte) (type << 4);
        o[3] = 1;
        o[4] = (byte) n;
        o[5] = (byte) (n >> 8);
        System.arraycopy(payload, 0, o, 6, payload.length);
        int crc = crc16(o, 0, 6 + payload.length);
        o[o.length - 2] = (byte) crc;
        o[o.length - 1] = (byte) (crc >> 8);
        return o;
    }

    /** {type}, payload (decrypted for type 1), or null after ms. */
    private byte[][] nextFrame(long ms) {
        long end = SystemClock.elapsedRealtime() + ms;
        while (true) {
            byte[] b = buf.toByteArray();
            int start = -1;
            for (int i = 0; i + 1 < b.length; i++) if (b[i] == 0x5a && b[i + 1] == 0x5a) {
                start = i;
                break;
            }
            if (start > 0 || (start < 0 && b.length > 1)) {
                buf.reset();
                if (start > 0) buf.write(b, start, b.length - start);
                else if (b[b.length - 1] == 0x5a) buf.write(0x5a);
                continue;
            }
            if (start == 0 && b.length >= 6) {
                int n = (b[4] & 0xFF) | ((b[5] & 0xFF) << 8);
                if (n < 2 || n > 4096) {
                    buf.reset();
                    buf.write(b, 1, b.length - 1);
                    continue;
                }
                if (b.length >= 6 + n) {
                    buf.reset();
                    buf.write(b, 6 + n, b.length - 6 - n);
                    int crc = crc16(b, 0, 4 + n);
                    if ((b[4 + n] & 0xFF) != (crc & 0xFF) || (b[5 + n] & 0xFF) != ((crc >> 8) & 0xFF)) {
                        log.log("EcoFlow: bad frame CRC, dropped");
                        continue;
                    }
                    int type = (b[2] & 0xFF) >> 4;
                    byte[] payload = java.util.Arrays.copyOfRange(b, 6, 4 + n);
                    if (type != 0) {
                        if (sessionKey == null) continue;
                        try {
                            payload = cbc(Cipher.DECRYPT_MODE, sessionKey, java.util.Arrays.copyOf(payload, payload.length / 16 * 16), false);
                            int padN = payload.length > 0 ? payload[payload.length - 1] & 0xFF : 0;
                            if (padN >= 1 && padN <= 16 && padN <= payload.length) payload = java.util.Arrays.copyOf(payload, payload.length - padN);
                        } catch (Exception e) {
                            continue;
                        }
                    }
                    if (rawLog) log.log("frame " + type + ": " + hex(payload));
                    return new byte[][]{{(byte) type}, payload};
                }
            }
            long left = end - SystemClock.elapsedRealtime();
            if (left <= 0 || link == null) return null;
            byte[] x = link.next(left);
            if (x == null) return null;
            buf.write(x, 0, x.length);
        }
    }

    private void handlePacket(byte[] data) {
        Packet p = Packet.parse(data, true);
        if (p == null) return;
        if (p.src == 0x02 && p.cmdSet == 0xFE && p.cmdId == 0x15) {
            Proto.read(p.payload, fields);
            lastTelemetryWall = System.currentTimeMillis();
        }
        // cmd 0x01/0x52 (clock sync request) is ignored: answering it would set the unit's clock.
    }

    private float f(int field) {
        Number n = fields.get(field);
        return n == null ? Float.NaN : n.floatValue();
    }

    private int w(int field) {
        float v = f(field);
        return Float.isNaN(v) ? 0 : Math.max(0, Math.round(v));
    }

    BatteryStatus status() {
        BatteryStatus s = new BatteryStatus();
        s.time = System.currentTimeMillis();
        s.model = "EcoFlow " + unit.model;
        float soc = f(262);
        if (Float.isNaN(soc)) soc = f(242);
        s.soc = Float.isNaN(soc) ? -1 : Math.max(0, Math.min(100, Math.round(soc)));
        s.acIn = w(54);
        int inSum = w(3), outSum = w(4);
        s.dcIn = Math.max(w(361), inSum - s.acIn);
        s.acOut = w(368);
        s.dcOut = Math.max(0, outSum - s.acOut);
        Number acPort = fields.get(367);
        if (acPort != null) s.acOn = acPort.intValue() != 0 ? 1 : 0;
        Number plugged = fields.get(202);
        if (plugged != null) s.mains = plugged.intValue() != 0 ? 1 : 0;
        Number dsg = fields.get(268);
        if (dsg != null && dsg.intValue() > 0 && dsg.intValue() < 100000) s.minutesLeft = dsg.intValue();
        return s;
    }

    @Override
    public void close() {
        if (link != null) {
            link.close();
            link = null;
        }
        authed = false;
        sessionKey = null;
        buf.reset();
    }

    // ---- inner packet (V3) ----

    static final class Packet {
        int src, dst, cmdSet, cmdId;
        byte[] payload;

        static byte[] build(int seq, int src, int dst, int cmdSet, int cmdId, byte[] payload) {
            byte[] o = new byte[18 + payload.length + 2];
            o[0] = (byte) 0xAA;
            o[1] = 0x03;
            o[2] = (byte) payload.length;
            o[3] = (byte) (payload.length >> 8);
            o[4] = (byte) crc8(o, 0, 4);
            o[5] = 0x0D;
            o[6] = (byte) seq;
            o[7] = (byte) (seq >> 8);
            o[8] = (byte) (seq >> 16);
            o[9] = (byte) (seq >> 24);
            o[12] = (byte) src;
            o[13] = (byte) dst;
            o[14] = 1;
            o[15] = 1;
            o[16] = (byte) cmdSet;
            o[17] = (byte) cmdId;
            System.arraycopy(payload, 0, o, 18, payload.length);
            int crc = crc16(o, 0, 18 + payload.length);
            o[o.length - 2] = (byte) crc;
            o[o.length - 1] = (byte) (crc >> 8);
            return o;
        }

        static Packet parse(byte[] d, boolean xor) {
            if (d == null || d.length < 20 || (d[0] & 0xFF) != 0xAA) return null;
            int ver = d[1] & 0xFF;
            int len = (d[2] & 0xFF) | ((d[3] & 0xFF) << 8);
            if (crc8(d, 0, 4) != (d[4] & 0xFF)) return null;
            boolean v3 = (ver & 0x0F) == 3;
            int off = v3 ? 18 : 16;
            if (d.length < off + len) return null;
            Packet p = new Packet();
            p.src = d[12] & 0xFF;
            p.dst = d[13] & 0xFF;
            p.cmdSet = d[off - 2] & 0xFF;
            p.cmdId = d[off - 1] & 0xFF;
            p.payload = java.util.Arrays.copyOfRange(d, off, off + len);
            int s0 = d[6] & 0xFF;
            if (xor && s0 != 0) for (int i = 0; i < p.payload.length; i++) p.payload[i] ^= (byte) s0;
            return p;
        }
    }

    // ---- protobuf: just enough to pull numbers out of DisplayPropertyUpload ----

    static final class Proto {
        static void read(byte[] b, Map<Integer, Number> out) {
            int[] i = {0};
            try {
                while (i[0] < b.length) {
                    long key = varint(b, i);
                    int field = (int) (key >>> 3), wt = (int) (key & 7);
                    if (wt == 0) out.put(field, varint(b, i));
                    else if (wt == 5) {
                        int bits = (b[i[0]] & 0xFF) | ((b[i[0] + 1] & 0xFF) << 8) | ((b[i[0] + 2] & 0xFF) << 16) | ((b[i[0] + 3] & 0xFF) << 24);
                        out.put(field, Float.intBitsToFloat(bits));
                        i[0] += 4;
                    } else if (wt == 1) i[0] += 8;
                    else if (wt == 2) i[0] += (int) varint(b, i);
                    else return;
                }
            } catch (RuntimeException ignored) {
            }
        }

        static long varint(byte[] b, int[] i) {
            long r = 0;
            int shift = 0;
            while (true) {
                int x = b[i[0]++] & 0xFF;
                r |= (long) (x & 0x7F) << shift;
                if ((x & 0x80) == 0) return r;
                shift += 7;
                if (shift > 63) throw new RuntimeException("bad varint");
            }
        }
    }

    // ---- CRCs ----

    static int crc8(byte[] d, int off, int len) {
        int c = 0;
        for (int i = off; i < off + len; i++) {
            c ^= d[i] & 0xFF;
            for (int k = 0; k < 8; k++) c = (c & 0x80) != 0 ? ((c << 1) ^ 0x07) & 0xFF : (c << 1) & 0xFF;
        }
        return c;
    }

    /** CRC-16/ARC. */
    static int crc16(byte[] d, int off, int len) {
        int c = 0;
        for (int i = off; i < off + len; i++) {
            c ^= d[i] & 0xFF;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? (c >>> 1) ^ 0xA001 : c >>> 1;
        }
        return c & 0xFFFF;
    }

    /** The serial number from an EcoFlow advertisement's manufacturer data (company 0xB5B5), or "". */
    public static String serialFromRecord(byte[] rec) {
        if (rec == null) return "";
        int i = 0;
        while (i < rec.length - 1) {
            int len = rec[i] & 0xFF;
            if (len == 0 || i + len >= rec.length) break;
            int type = rec[i + 1] & 0xFF;
            if (type == 0xFF && len >= 4 && (rec[i + 2] & 0xFF) == 0xB5 && (rec[i + 3] & 0xFF) == 0xB5) {
                StringBuilder s = new StringBuilder();
                for (int j = i + 5; j < i + 1 + len && s.length() < 16; j++) {
                    char ch = (char) (rec[j] & 0xFF);
                    if (Character.isLetterOrDigit(ch)) s.append(ch);
                    else break;
                }
                return s.toString();
            }
            i += len + 1;
        }
        return "";
    }
}
