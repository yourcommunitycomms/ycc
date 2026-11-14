package org.communitycomms.app.power;

import android.content.Context;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Anker SOLIX power stations over Bluetooth LE: C300, C300 DC, C800, C1000, C1000 Gen 2. UNTESTED on
 * real hardware (nobody here owns one). Our own Java port of the read-only telemetry part of
 * SolixBLE (MIT, Harvey Lelliott; see NOTICE): the fixed negotiation the Anker app sends, P-256
 * ECDH, AES-128-CBC telemetry in TLV form. Read-only: the only thing written after the handshake is
 * the C1000 Gen 2 "send me telemetry" subscription; no output, display or light commands exist here.
 */
public final class SolixReader extends BatteryReader {
    public static final String SERVICE_ID = "0000ff09-0000-1000-8000-00805f9b34fb";
    private static final String TELEMETRY = "8c850003-0302-41c5-b46e-cf057c562025";
    private static final String COMMAND = "8c850002-0302-41c5-b46e-cf057c562025";
    public static final String[] MODELS = {"C300", "C300DC", "C800", "C1000", "C1000G2"};
    public static final String[] MODEL_NAMES = {"C300 / C300X (A1722)", "C300 DC (A1728)", "C800 (A1755)", "C1000 / C1000X (A1761)", "C1000 Gen 2 (A1763)"};

    private static final String[][] NEGOTIATE = {
            {"0801", "ff093d000300010003a10442ad8c69a22462326463306231372d623735642d346162662d626136652d656337633939376332336537a30120a40200f064"},
            {"0803", "ff0936000300010029a10442ad8c69a22462326463306231372d623735642d346162662d626136652d65633763393937633233653791"},
            {"0829", "ff0940000300010005a10443ad8c69a22462326463306231372d623735642d346162662d626136652d656337633939376332336537a30120a40200f0a50140fa"},
            {"0805", "ff094c000300010021a140060ea168f232aedb37fb2d120c49180329ac72ab5ec3eb8fd30a2f252dc5e151dabccd9b1dc1e288704ca760a0d8c918e5c94823a1f609a4bf07fb4c33ee219085"},
    };
    private static final String NEG0 = "ff0936000300010001a10442ad8c69a22462326463306231372d623735642d346162662d626136652d656337633939376332336537b9";
    private static final String NEG5 = "ff095a000300014022580bc0532a53c739adf3da7b994a7b5f221bcc16bab6392c215cb4faaf41d9d58e2c81c016e474c78eed5569147cb74a1f22ca2b3fad2e209dbbcfbdaca352034a6c479f055f68581b5f1e22348809f526";
    private static final BigInteger PRIVATE = new BigInteger("7dfbea61cd95cee49c458ad7419e817f1ade9a66136de3c7d5787af1458e39f4", 16);
    private static final int BASE_TIME = 0x698cad42; // "42ad8c69" little-endian

    private final Context ctx;
    private BleLink link;
    private byte[] key, iv;
    private boolean ready;
    private long stage3At;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private final Map<String, TreeMap<Integer, byte[]>> frags = new HashMap<String, TreeMap<Integer, byte[]>>();
    /** Latest value of every parameter seen (telemetry may come in parts). */
    private final Map<Integer, byte[]> params = new HashMap<Integer, byte[]>();
    private long lastTelemetry;

    SolixReader(Context c, BatteryUnit u, Log log) {
        super(u, log);
        ctx = c.getApplicationContext();
    }

    private boolean gen2() {
        return "C1000G2".equals(unit.model);
    }

    @Override
    public BatteryStatus read() {
        if (link == null || !link.isConnected()) {
            close();
            link = new BleLink(ctx, unit.addr, log);
            link.rawLog = rawLog;
            if (!link.connect(new String[]{TELEMETRY}, new String[]{COMMAND}, "an Anker SOLIX power station")) {
                lastError = link.lastError;
                link = null;
                return null;
            }
            if (!negotiate()) {
                close();
                return null;
            }
        }
        link.rawLog = rawLog;
        long end = SystemClock.elapsedRealtime() + 8000;
        long since = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() < end) {
            byte[] p = nextPacket(end - SystemClock.elapsedRealtime());
            if (p == null) break;
            handleSession(p);
            if (lastTelemetry >= since && SystemClock.elapsedRealtime() - lastTelemetry > 300) break;
        }
        if (params.isEmpty() || System.currentTimeMillis() - lastTelemetryWall > 120000) {
            lastError = link.isConnected() ? "connected, but no readings came" : "link lost";
            if (!link.isConnected()) close();
            return null;
        }
        return status();
    }

    private long lastTelemetryWall;

    /** The fixed exchange the Anker app does, then ECDH. Resends step 0 if the unit stays quiet. */
    private boolean negotiate() {
        ready = false;
        key = null;
        long end = SystemClock.elapsedRealtime() + 90000, lastSend = 0;
        while (SystemClock.elapsedRealtime() < end && link.isConnected()) {
            if (SystemClock.elapsedRealtime() - lastSend > 15000) {
                link.write(unhex(NEG0));
                lastSend = SystemClock.elapsedRealtime();
            }
            byte[] p = nextPacket(1000);
            if (p == null) continue;
            String pattern = hex(p, 4, 3), cmd = hex(p, 7, 2);
            if (!"030001".equals(pattern)) {
                if (ready) handleSession(p);
                continue;
            }
            lastSend = SystemClock.elapsedRealtime();
            if ("0821".equals(cmd)) {
                byte[] pub = tlv(java.util.Arrays.copyOfRange(p, 9, p.length - 1)).get(0xa1);
                if (pub == null) {
                    lastError = "no key from the unit";
                    return false;
                }
                int off = pub.length == 65 ? 1 : 0;
                if (pub.length - off != 64) {
                    lastError = "unexpected key length " + pub.length;
                    return false;
                }
                byte[] secret = Ec.P256.ecdh(PRIVATE, Ec.P256.point(pub, off));
                if (secret == null) {
                    lastError = "the unit's key is not on the curve";
                    return false;
                }
                key = java.util.Arrays.copyOfRange(secret, 0, 16);
                iv = java.util.Arrays.copyOfRange(secret, 16, 32);
                link.write(unhex(NEG5));
                ready = true;
                log.log("Anker SOLIX: handshake done");
                if (gen2()) subscribe();
                return true;
            }
            for (String[] n : NEGOTIATE) {
                if (n[0].equals(cmd)) {
                    if ("0829".equals(cmd)) stage3At = SystemClock.elapsedRealtime();
                    link.write(unhex(n[1]));
                }
            }
        }
        lastError = "the handshake did not finish";
        return false;
    }

    /** C1000 Gen 2 sends nothing until asked: command 4100, "a10121" (a telemetry request, not a setting). */
    private void subscribe() {
        try {
            int ts = BASE_TIME + (int) ((SystemClock.elapsedRealtime() - stage3At) / 1000);
            byte[] body = unhex("a10121fe0503" + String.format("%02x%02x%02x%02x", ts & 0xFF, (ts >> 8) & 0xFF, (ts >> 16) & 0xFF, (ts >> 24) & 0xFF));
            link.write(frame("03000f", "4100", aes(Cipher.ENCRYPT_MODE, body)));
        } catch (Exception e) {
            log.log("subscribe failed: " + e);
        }
    }

    static byte[] frame(String pattern, String cmd, byte[] payload) {
        int len = 2 + 2 + 3 + 2 + payload.length + 1;
        byte[] out = new byte[len];
        out[0] = (byte) 0xff;
        out[1] = 0x09;
        out[2] = (byte) len;
        out[3] = (byte) (len >> 8);
        System.arraycopy(unhex(pattern), 0, out, 4, 3);
        System.arraycopy(unhex(cmd), 0, out, 7, 2);
        System.arraycopy(payload, 0, out, 9, payload.length);
        byte x = 0;
        for (int i = 0; i < len - 1; i++) x ^= out[i];
        out[len - 1] = x;
        return out;
    }

    private byte[] aes(int mode, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(data);
    }

    /** One whole FF09 packet with a good checksum (notifications may split or join them), or null. */
    private byte[] nextPacket(long ms) {
        long end = SystemClock.elapsedRealtime() + ms;
        while (true) {
            byte[] b = buf.toByteArray();
            int start = -1;
            for (int i = 0; i + 1 < b.length; i++) if ((b[i] & 0xFF) == 0xff && b[i + 1] == 0x09) {
                start = i;
                break;
            }
            if (start > 0 || (start < 0 && b.length > 1)) {
                buf.reset();
                if (start > 0) buf.write(b, start, b.length - start);
                else if (b.length > 0 && (b[b.length - 1] & 0xFF) == 0xff) buf.write(0xff);
                continue;
            }
            if (start == 0 && b.length >= 4) {
                int len = (b[2] & 0xFF) | ((b[3] & 0xFF) << 8);
                if (len < 10 || len > 4096) {
                    buf.reset();
                    buf.write(b, 1, b.length - 1);
                    continue;
                }
                if (b.length >= len) {
                    buf.reset();
                    buf.write(b, len, b.length - len);
                    byte x = 0;
                    for (int i = 0; i < len - 1; i++) x ^= b[i];
                    if (x != b[len - 1]) {
                        log.log("Anker SOLIX: bad checksum, dropped");
                        continue;
                    }
                    return java.util.Arrays.copyOf(b, len);
                }
            }
            long left = end - SystemClock.elapsedRealtime();
            if (left <= 0 || link == null) return null;
            byte[] n = link.next(left);
            if (n == null) return null;
            buf.write(n, 0, n.length);
        }
    }

    private void handleSession(byte[] p) {
        String pattern = hex(p, 4, 3), cmd = hex(p, 7, 2);
        if (!ready || !("03010f".equals(pattern) || "030111".equals(pattern))) return;
        boolean telemetry = gen2() ? ("c421".equals(cmd) || "c900".equals(cmd)) : ("c402".equals(cmd) || "4300".equals(cmd) || "c405".equals(cmd));
        if (!telemetry || p.length < 11) return;
        int fb = p[9] & 0xFF, idx = fb >> 4, total = fb & 0x0F;
        byte[] part = java.util.Arrays.copyOfRange(p, 10, p.length - 1);
        byte[] whole;
        if (total > 1) {
            TreeMap<Integer, byte[]> m = frags.get(cmd);
            if (m == null || idx == 1) {
                m = new TreeMap<Integer, byte[]>();
                frags.put(cmd, m);
            }
            m.put(idx, part);
            if (m.size() < total) return;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            for (byte[] x : m.values()) o.write(x, 0, x.length);
            frags.remove(cmd);
            whole = o.toByteArray();
        } else whole = part;
        try {
            byte[] plain = aes(Cipher.DECRYPT_MODE, whole);
            if (rawLog) log.log("telemetry " + cmd + ": " + hex(plain));
            params.putAll(tlv(plain));
            lastTelemetry = SystemClock.elapsedRealtime();
            lastTelemetryWall = System.currentTimeMillis();
        } catch (Exception e) {
            log.log("Anker SOLIX: could not decrypt " + cmd + ": " + e.getMessage());
        }
    }

    /** id, length, value ... (a leading 0x00 is skipped). */
    static Map<Integer, byte[]> tlv(byte[] d) {
        Map<Integer, byte[]> m = new HashMap<Integer, byte[]>();
        int i = d.length > 0 && d[0] == 0 ? 1 : 0;
        while (i + 2 <= d.length) {
            int id = d[i] & 0xFF, len = d[i + 1] & 0xFF;
            if (i + 2 + len > d.length) break;
            m.put(id, java.util.Arrays.copyOfRange(d, i + 2, i + 2 + len));
            i += 2 + len;
        }
        return m;
    }

    /** Little-endian unsigned from value bytes [from, to) (to clipped to the value); -1 if missing. */
    private int le(int id, int from, int to) {
        byte[] v = params.get(id);
        if (v == null || v.length <= from) return -1;
        to = Math.min(to, v.length);
        long r = 0;
        for (int i = to - 1; i >= from; i--) r = (r << 8) | (v[i] & 0xFF);
        return (int) Math.min(Integer.MAX_VALUE, r);
    }

    /** Value after its type byte. */
    private int val(int id) {
        return le(id, 1, 5);
    }

    private static int z(int v) {
        return Math.max(0, v);
    }

    BatteryStatus status() {
        BatteryStatus s = new BatteryStatus();
        s.time = System.currentTimeMillis();
        s.model = "Anker SOLIX " + unit.model;
        int solar = -1, total = -1, totalOut = -1, usb = 0, dc = -1;
        String m = unit.model;
        if ("C1000G2".equals(m)) {
            s.soc = le(0xa5, 3, 4);
            totalOut = le(0xa6, 1, 3);
            s.acIn = z(le(0xa6, 3, 5));
            s.acOn = le(0xa7, 1, 2);
            s.acOut = z(le(0xa7, 2, 4));
            solar = le(0xa8, 2, 6);
            s.dcOn = le(0xb2, 1, 2);
            dc = le(0xb2, 2, 6);
            for (int id : new int[]{0xaa, 0xab, 0xac, 0xae}) usb += z(le(id, 2, 6));
        } else if ("C300DC".equals(m)) {
            s.soc = val(0xb7);
            for (int id = 0xa4; id <= 0xa9; id++) usb += z(val(id));
            dc = val(0xaa);
            solar = val(0xab);
            total = val(0xac);
            totalOut = val(0xad);
            s.dcOn = val(0xbf);
            int t = val(0xa3);
            if (t > 0) s.minutesLeft = t * 6;
        } else if ("C800".equals(m)) {
            s.soc = val(0xc1);
            s.acIn = z(val(0xa5));
            s.acOut = z(val(0xa6));
            for (int id = 0xa7; id <= 0xaa; id++) usb += z(val(id));
            solar = val(0xae);
            total = val(0xaf);
            totalOut = val(0xb0);
            s.acOn = val(0xbb);
        } else if ("C1000".equals(m)) {
            s.soc = val(0xc1);
            s.acIn = z(val(0xa5));
            s.acOut = z(val(0xa6));
            for (int id = 0xa7; id <= 0xaa; id++) usb += z(val(id));
            solar = val(0xae);
            total = val(0xaf);
            totalOut = val(0xb0);
            s.dcOn = le(0xb2, 1, 2);
            dc = le(0xb2, 2, 6);
            s.acOn = val(0xbb);
        } else { // C300
            s.soc = val(0xbb);
            s.acIn = z(val(0xa5));
            s.acOut = z(val(0xa6));
            for (int id = 0xa7; id <= 0xaa; id++) usb += z(val(id));
            dc = val(0xab);
            solar = val(0xac);
            total = val(0xad);
            totalOut = val(0xae);
            s.acOn = val(0xb7);
            s.dcOn = val(0xc1);
            int t = val(0xa4);
            if (t > 0) s.minutesLeft = t * 6;
        }
        if (s.soc > 100) s.soc = -1;
        s.dcIn = Math.max(z(solar), z(total) - s.acIn);
        s.dcOut = totalOut >= 0 ? Math.max(0, totalOut - s.acOut) : z(dc) + usb;
        if (s.acOn > 1) s.acOn = -1;
        if (s.dcOn > 1) s.dcOn = -1;
        return s;
    }

    @Override
    public void close() {
        if (link != null) {
            link.close();
            link = null;
        }
        ready = false;
        buf.reset();
        frags.clear();
    }
}
