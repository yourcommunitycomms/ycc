package org.communitycomms.app.power;

import android.content.Context;
import android.os.SystemClock;

import java.util.Locale;

/**
 * Bluetti power stations over Bluetooth LE (Modbus over GATT, encrypted handshake when the unit
 * asks): reads registers only. The output switches were taken out on purpose (read-only app).
 */
public final class BluettiReader extends BatteryReader {
    private final Context ctx;
    private BluettiBle link;
    /** The unit's layout was worked out: the monitor saves it. */
    public volatile boolean layoutChanged;

    BluettiReader(Context c, BatteryUnit u, Log log) {
        super(u, log);
        ctx = c.getApplicationContext();
    }

    private boolean ensureLink() {
        if (link != null && link.isConnected()) return true;
        close();
        BluettiBle l = new BluettiBle(ctx, unit.addr, new BluettiBle.Log() {
            @Override
            public void log(String s) {
                log.log(s);
            }
        });
        l.rawLog = rawLog;
        if (!l.connect()) {
            lastError = l.lastError;
            log.log("connect failed: " + l.lastError);
            return false;
        }
        link = l;
        log.log(l.encrypted() ? "connected (encrypted)" : "connected");
        return true;
    }

    @Override
    public BatteryStatus read() {
        if (!ensureLink()) return null;
        if (rawLog) dumpRegisters();
        BatteryStatus s = readStatus(link);
        if (s == null) {
            lastError = "no answer: " + link.lastError;
            close();
        }
        return s;
    }

    @Override
    public void close() {
        if (link != null) {
            link.close();
            link = null;
        }
    }

    /** The probe: anything the unit says by itself, then the register ranges seen on known models. */
    private void dumpRegisters() {
        log.log("connect: challenge seen: " + link.sawChallenge + ", encrypted: " + link.encrypted());
        SystemClock.sleep(5000);
        int[][] ranges = {{10, 40}, {100, 40}, {140, 40}, {1100, 20}, {1200, 20}, {1300, 20}, {1400, 20}, {1500, 20}, {2000, 30}, {2200, 20}, {3000, 20}, {6000, 20}, {6100, 20}};
        for (int[] g : ranges) {
            int[] r = link.read(g[0], g[1]);
            if (r == null) {
                log.log("read " + g[0] + " x" + g[1] + ": " + link.lastError);
                if (!link.isConnected()) break;
                continue;
            }
            StringBuilder b = new StringBuilder("regs " + g[0] + ":");
            for (int i = 0; i < r.length; i++) b.append(i % 10 == 0 ? "\n  " + (g[0] + i) + ": " : " ").append(r[i]);
            log.log(b.toString());
        }
    }

    /** Reads the status, working out which register layout the unit uses the first time. */
    BatteryStatus readStatus(BluettiBle l) {
        String layout = unit.layout;
        BatteryStatus s = null;
        if (!"v1".equals(layout)) {
            s = readV2(l);
            if (s != null && layout.length() == 0) {
                unit.layout = "v2";
                layoutChanged = true;
            }
        }
        if (s == null && !"v2".equals(layout)) {
            s = readV1(l);
            if (s != null && layout.length() == 0) {
                unit.layout = "v1";
                layoutChanged = true;
            }
        }
        return s;
    }

    /** Newer units (AC2A, AC70, AC180, Elite 30 V2 and similar): registers 100 on. */
    private static BatteryStatus readV2(BluettiBle l) {
        int[] r = l.read(100, 62);
        if (r == null || r.length < 47) return null;
        BatteryStatus s = new BatteryStatus();
        s.soc = r[2];
        if (s.soc > 100) return null;
        s.model = text(r, 10, 6, true);
        s.dcOut = r[40];
        s.acOut = r[42];
        s.dcIn = r[44];
        s.acIn = r[46];
        int[] sw = l.read(2011, 2); // output state, shown only
        if (sw != null && sw.length == 2) {
            s.acOn = sw[0] != 0 ? 1 : 0;
            s.dcOn = sw[1] != 0 ? 1 : 0;
        }
        s.capWh = capacity(s.model);
        s.time = System.currentTimeMillis();
        return s;
    }

    /** Older units (AC200, AC300, EB3A): registers 10 to 49. */
    private static BatteryStatus readV1(BluettiBle l) {
        int[] r = l.read(10, 40);
        if (r == null || r.length < 40) return null;
        BatteryStatus s = new BatteryStatus();
        s.model = text(r, 0, 6, false);
        s.dcIn = r[26];
        s.acIn = r[27];
        s.acOut = r[28];
        s.dcOut = r[29];
        s.soc = r[33];
        if (s.soc > 100) return null;
        s.acOn = r[38] != 0 ? 1 : 0;
        s.dcOn = r[39] != 0 ? 1 : 0;
        s.capWh = capacity(s.model);
        s.time = System.currentTimeMillis();
        return s;
    }

    /** A known model's battery size (Wh), else 0. */
    static int capacity(String model) {
        String m = model.toUpperCase(Locale.US);
        if (m.startsWith("EL30")) return 288; // Elite 30 V2
        if (m.startsWith("EB3A")) return 268;
        if (m.startsWith("AC2A")) return 204;
        if (m.startsWith("AC70")) return 768;
        if (m.startsWith("AC180")) return 1152;
        return 0;
    }

    static String text(int[] r, int off, int n, boolean swap) {
        StringBuilder b = new StringBuilder();
        for (int i = off; i < off + n && i < r.length; i++) {
            char hi = (char) ((r[i] >> 8) & 0xFF), lo = (char) (r[i] & 0xFF);
            if (swap) b.append(lo).append(hi);
            else b.append(hi).append(lo);
        }
        return b.toString().replaceAll("[^A-Za-z0-9 \\-]", "").trim();
    }

    public static boolean bluettiName(String n) {
        return n.matches("(?i)^(AC|EB|EP|EL|PR|AP|B\\d|HANDSFREE|BLUETTI).*");
    }
}
