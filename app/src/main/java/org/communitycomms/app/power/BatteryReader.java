package org.communitycomms.app.power;

/**
 * Reads one battery. READ-ONLY by design: no reader sends output switches, settings or sign-ins;
 * people keep using the maker's own app for those. Blocking calls from the monitor's worker thread.
 */
public abstract class BatteryReader {
    public interface Log {
        void log(String s);
    }

    public static final String BLUETTI = "bluetti", POWERWALL = "powerwall", SOLIX = "solix", ECOFLOW = "ecoflow";
    public static final String[] TYPES = {BLUETTI, POWERWALL, SOLIX, ECOFLOW};

    protected final BatteryUnit unit;
    protected final Log log;
    public volatile String lastError = "";
    /** Log raw traffic (the probe / debug view). */
    public volatile boolean rawLog;

    protected BatteryReader(BatteryUnit unit, Log log) {
        this.unit = unit;
        this.log = log;
    }

    /** A reading, or null (see lastError). Connects if needed. */
    public abstract BatteryStatus read();

    /** Drops any link (Bluetooth units allow one phone at a time). */
    public abstract void close();

    /** Reads with raw logging on, plus anything else useful for working out a new model. */
    public BatteryStatus probe() {
        close();
        rawLog = true;
        try {
            BatteryStatus s = read();
            log.log(s == null ? "no reading: " + lastError : "reading: " + s.soc + "%, in " + s.in() + " W, out " + s.out() + " W");
            return s;
        } finally {
            rawLog = false;
            close();
        }
    }

    /** Untested on real hardware: shown with a * in the app. */
    public static boolean untested(String type) {
        return SOLIX.equals(type) || ECOFLOW.equals(type);
    }

    public static String typeName(String type) {
        if (POWERWALL.equals(type)) return "Tesla Powerwall 2";
        if (SOLIX.equals(type)) return "Anker SOLIX*";
        if (ECOFLOW.equals(type)) return "EcoFlow*";
        return "Bluetti";
    }

    public static boolean bluetooth(String type) {
        return !POWERWALL.equals(type);
    }

    public static BatteryReader create(android.content.Context c, BatteryUnit u, Log log) {
        if (POWERWALL.equals(u.type)) return new PowerwallReader(u, log);
        if (SOLIX.equals(u.type)) return new SolixReader(c, u, log);
        if (ECOFLOW.equals(u.type)) return new EcoFlowReader(c, u, log);
        return new BluettiReader(c, u, log);
    }

    public static String hex(byte[] b, int off, int len) {
        StringBuilder s = new StringBuilder();
        for (int i = off; i < off + len && i < b.length; i++) s.append(String.format("%02x", b[i] & 0xFF));
        return s.toString();
    }

    public static String hex(byte[] b) {
        return b == null ? "null" : hex(b, 0, b.length);
    }

    public static byte[] unhex(String h) {
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
