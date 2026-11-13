package org.communitycomms.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.communitycomms.app.msg.MessageLog;
import org.communitycomms.app.power.BluettiBle;
import org.communitycomms.app.power.BluettiProtocol;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bluetti power station over Bluetooth (a Specialised menu, off by default). One worker thread: every
 * 10 s while the Bluetti screen is open (link kept up), every 60 s in the background when "watch in
 * the background" is ticked (link dropped between reads, so the Bluetti app can still connect; every
 * 5 min while the phone saves power). The watching phone shares a short status on the channel (every
 * few minutes, at once on a 5% change) and sends alerts like the Reolink ones (text, optionally read
 * aloud): low and critical battery, charging started / stopped, mains input lost / back, overload,
 * unit out of contact. Offline only: never the Bluetti cloud.
 */
public final class BluettiMonitor {
    private static final String TAG = "Bluetti";
    /** Every shared status and alert starts with this, so other phones can find the latest. */
    public static final String PREFIX = "Power station ";

    public static final class Status {
        public long time;
        public int soc = -1, acIn, dcIn, acOut, dcOut;
        /** 1 on, 0 off, -1 not known. */
        public int acOn = -1, dcOn = -1;
        public String model = "";

        public int in() {
            return acIn + dcIn;
        }

        public int out() {
            return acOut + dcOut;
        }
    }

    private static final Set<String> holders = new HashSet<String>();
    private static Context app;
    private static Thread worker;
    private static final Object wake = new Object();
    private static BluettiBle link;
    public static volatile Status last;
    public static volatile String state = "";
    public static volatile long lastOk;
    /** The screen redraws when this runs (posted to its own thread by the screen). */
    public static volatile Runnable ui;
    private static volatile boolean probeWanted;
    private static volatile int[] pendingWrite;
    public static volatile String writeResult = "";
    private static final LinkedList<String> logLines = new LinkedList<String>();

    private BluettiMonitor() {
    }

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("bluetti", Context.MODE_PRIVATE);
    }

    public static String address(Context c) {
        return sp(c).getString("addr", "");
    }

    public static String label(Context c) {
        String n = sp(c).getString("name", "");
        if (n.length() > 0) return n;
        Status s = last;
        return s != null && s.model.length() > 0 ? s.model : "Bluetti";
    }

    /** Battery size: the setting, else a known model's size, else 300 Wh. */
    public static int capacityWh(Context c) {
        int v = sp(c).getInt("capWh", 0);
        return v > 0 ? v : autoCapacity();
    }

    /** A known model's battery size (Wh), else 300. */
    public static int autoCapacity() {
        Status s = last;
        String m = s == null ? "" : s.model.toUpperCase(Locale.US);
        if (m.startsWith("EL30")) return 288; // Elite 30 V2
        if (m.startsWith("EB3A")) return 268;
        if (m.startsWith("AC2A")) return 204;
        if (m.startsWith("AC70")) return 768;
        if (m.startsWith("AC180")) return 1152;
        return 300;
    }

    static boolean bgWatch(Context c) {
        return sp(c).getBoolean("bgWatch", false);
    }

    /** who = "screen" or "bg". */
    public static synchronized void acquire(Context c, String who) {
        app = c.getApplicationContext();
        holders.add(who);
        refresh();
    }

    public static synchronized void release(String who) {
        holders.remove(who);
        refresh();
    }

    /** Settings changed, or something to do now. */
    public static synchronized void refresh() {
        if (app == null) return;
        if (worker == null && (holders.contains("screen") || (holders.contains("bg") && bgWatch(app) && address(app).length() > 0))) {
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "bluetti");
            worker.start();
        }
        synchronized (wake) {
            wake.notifyAll();
        }
    }

    public static void probe() {
        probeWanted = true;
        refresh();
    }

    /** AC (true) or DC output on/off; the result shows in writeResult. */
    public static void setOutput(boolean ac, boolean on) {
        pendingWrite = new int[]{ac ? 1 : 0, on ? 1 : 0};
        writeResult = "sending...";
        refresh();
    }

    public static List<String> log() {
        synchronized (logLines) {
            return new ArrayList<String>(logLines);
        }
    }

    static void log(String s) {
        Log.i(TAG, s);
        String t = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date());
        synchronized (logLines) {
            logLines.add(t + " " + s);
            while (logLines.size() > 600) logLines.removeFirst();
        }
    }

    private static void changed() {
        Runnable r = ui;
        if (r != null) r.run();
    }

    private static boolean screenOpen() {
        synchronized (BluettiMonitor.class) {
            return holders.contains("screen");
        }
    }

    private static boolean wanted() {
        synchronized (BluettiMonitor.class) {
            return holders.contains("screen") || (holders.contains("bg") && bgWatch(app));
        }
    }

    // ---- the worker ----

    private static void loop() {
        long nextPoll = 0;
        while (true) {
            String addr = address(app);
            if (!wanted() && !probeWanted) {
                closeLink();
                synchronized (BluettiMonitor.class) {
                    if (!wanted() && !probeWanted) {
                        worker = null;
                        return;
                    }
                }
            }
            if (probeWanted && addr.length() > 0) {
                probeWanted = false;
                runProbe(addr);
                nextPoll = 0;
            }
            if (pendingWrite != null && addr.length() > 0) doWrite(addr);
            long now = SystemClock.elapsedRealtime();
            if (addr.length() > 0 && now >= nextPoll) {
                poll(addr);
                boolean screen = screenOpen();
                if (!screen) closeLink(); // background: let go between reads
                long every = screen ? 10000 : PowerMode.sleeping() || PowerMode.mode() == PowerMode.ULTRA ? 300000 : 60000;
                nextPoll = SystemClock.elapsedRealtime() + every;
            }
            checkLost();
            flushOutbox();
            synchronized (wake) {
                long wait = addr.length() == 0 ? 30000 : Math.max(200, nextPoll - SystemClock.elapsedRealtime());
                try {
                    wake.wait(Math.min(wait, 30000));
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (screenOpen() && last != null && SystemClock.elapsedRealtime() - lastPollAt > 10000) nextPoll = 0;
        }
    }

    private static long lastPollAt;

    private static void closeLink() {
        if (link != null) {
            link.close();
            link = null;
        }
    }

    private static boolean ensureLink(String addr) {
        if (link != null && link.isConnected()) return true;
        closeLink();
        state = "connecting...";
        changed();
        BluettiBle l = new BluettiBle(app, addr, new BluettiBle.Log() {
            @Override
            public void log(String s) {
                BluettiMonitor.log(s);
            }
        });
        if (!l.connect()) {
            state = l.lastError;
            log("connect failed: " + l.lastError);
            changed();
            return false;
        }
        link = l;
        if (l.encrypted()) log("connected (encrypted)");
        else log("connected");
        return true;
    }

    private static void poll(String addr) {
        lastPollAt = SystemClock.elapsedRealtime();
        if (!ensureLink(addr)) return;
        Status s = readStatus(link);
        if (s == null) {
            state = "no answer: " + link.lastError;
            closeLink();
            changed();
            return;
        }
        Status before = last;
        last = s;
        lastOk = System.currentTimeMillis();
        state = "";
        changed();
        if (bgWatch(app)) {
            alerts(before, s);
            share(s);
        }
    }

    /** Reads the status, working out which register layout the unit uses the first time. */
    static Status readStatus(BluettiBle l) {
        SharedPreferences p = sp(app);
        String layout = p.getString("layout", "");
        Status s = null;
        if (!"v1".equals(layout)) {
            s = readV2(l);
            if (s != null && layout.length() == 0) p.edit().putString("layout", "v2").commit();
        }
        if (s == null && !"v2".equals(layout)) {
            s = readV1(l);
            if (s != null && layout.length() == 0) p.edit().putString("layout", "v1").commit();
        }
        return s;
    }

    /** Newer units (AC2A, AC70, AC180 and similar): registers 100 on. */
    private static Status readV2(BluettiBle l) {
        int[] r = l.read(100, 62);
        if (r == null || r.length < 47) return null;
        Status s = new Status();
        s.soc = r[2];
        if (s.soc > 100) return null;
        s.model = text(r, 10, 6, true);
        s.dcOut = r[40];
        s.acOut = r[42];
        s.dcIn = r[44];
        s.acIn = r[46];
        int[] sw = l.read(2011, 2);
        if (sw != null && sw.length == 2) {
            s.acOn = sw[0] != 0 ? 1 : 0;
            s.dcOn = sw[1] != 0 ? 1 : 0;
        }
        s.time = System.currentTimeMillis();
        return s;
    }

    /** Older units (AC200, AC300, EB3A): registers 10 to 49. */
    private static Status readV1(BluettiBle l) {
        int[] r = l.read(10, 40);
        if (r == null || r.length < 40) return null;
        Status s = new Status();
        s.model = text(r, 0, 6, false);
        s.dcIn = r[26];
        s.acIn = r[27];
        s.acOut = r[28];
        s.dcOut = r[29];
        s.soc = r[33];
        if (s.soc > 100) return null;
        s.acOn = r[38] != 0 ? 1 : 0;
        s.dcOn = r[39] != 0 ? 1 : 0;
        s.time = System.currentTimeMillis();
        return s;
    }

    static String text(int[] r, int off, int n, boolean swap) {
        StringBuilder b = new StringBuilder();
        for (int i = off; i < off + n && i < r.length; i++) {
            char hi = (char) ((r[i] >> 8) & 0xFF), lo = (char) (r[i] & 0xFF);
            if (swap) {
                b.append(lo).append(hi);
            } else {
                b.append(hi).append(lo);
            }
        }
        return b.toString().replaceAll("[^A-Za-z0-9 \\-]", "").trim();
    }

    private static void doWrite(String addr) {
        int[] w = pendingWrite;
        pendingWrite = null;
        if (w == null || !ensureLink(addr)) {
            writeResult = "not connected";
            changed();
            return;
        }
        boolean v1 = "v1".equals(sp(app).getString("layout", ""));
        int reg = w[0] == 1 ? (v1 ? 3007 : 2011) : (v1 ? 3008 : 2012);
        boolean ok = link.write(reg, w[1]);
        writeResult = ok ? "done" : "the unit did not take it (" + link.lastError + ")";
        log("write " + reg + "=" + w[1] + ": " + writeResult);
        changed();
        if (ok) poll(addr);
    }

    // ---- probe (raw log, for working out a new model) ----

    private static void runProbe(String addr) {
        closeLink();
        log("---- probe " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date()) + " ----");
        state = "probing...";
        changed();
        BluettiBle l = new BluettiBle(app, addr, new BluettiBle.Log() {
            @Override
            public void log(String s) {
                BluettiMonitor.log(s);
            }
        });
        l.rawLog = true;
        boolean ok = l.connect();
        log("connect: " + (ok ? "ok" : l.lastError) + ", challenge seen: " + l.sawChallenge + ", encrypted: " + l.encrypted());
        if (ok) {
            SystemClock.sleep(5000); // anything the unit says by itself
            int[][] ranges = {{10, 40}, {100, 40}, {140, 40}, {1100, 20}, {1200, 20}, {1300, 20}, {1400, 20}, {1500, 20}, {2000, 30}, {2200, 20}, {3000, 20}, {6000, 20}, {6100, 20}};
            for (int[] g : ranges) {
                int[] r = l.read(g[0], g[1]);
                if (r == null) {
                    log("read " + g[0] + " x" + g[1] + ": " + l.lastError);
                    if (!l.isConnected()) break;
                    continue;
                }
                StringBuilder b = new StringBuilder("regs " + g[0] + ":");
                for (int i = 0; i < r.length; i++) b.append(i % 10 == 0 ? "\n  " + (g[0] + i) + ": " : " ").append(r[i]);
                log(b.toString());
            }
            Status s = readStatus(l);
            log(s == null ? "status: not understood" : String.format(Locale.US, "status: %s %d%%, AC in %d W, DC in %d W, AC out %d W, DC out %d W, AC %d, DC %d",
                    s.model, s.soc, s.acIn, s.dcIn, s.acOut, s.dcOut, s.acOn, s.dcOn));
            if (s != null) {
                last = s;
                lastOk = System.currentTimeMillis();
            }
        }
        l.close();
        state = ok ? "" : l.lastError;
        log("---- probe end ----");
        changed();
    }

    // ---- alerts and sharing ----

    private static final List<String[]> outbox = new ArrayList<String[]>();

    /** Queued until this phone is on a channel (dropped after 30 minutes). */
    private static void send(String text, boolean alert) {
        // Quiet by default: nothing goes to the channel until the unit is down to this % (Settings; 100 = always).
        int at = sendAtPct(app);
        Status now = last;
        if (now != null && now.soc > at) {
            log("not sent (above " + at + "%): " + text);
            return;
        }
        boolean say = alert && sp(app).getBoolean("announce", false);
        synchronized (outbox) {
            outbox.add(new String[]{say ? RadioService.SAY + text : text, Long.toString(System.currentTimeMillis())});
        }
        log("to the channel: " + text);
        flushOutbox();
    }

    /** Status and alerts go to the channel only at or below this battery %. 10 by default. */
    public static int sendAtPct(Context c) {
        return sp(c).getInt("sendAtPct", 10);
    }

    private static void flushOutbox() {
        synchronized (outbox) {
            if (outbox.isEmpty()) return;
            RadioService s = RadioService.instance();
            long now = System.currentTimeMillis();
            while (!outbox.isEmpty()) {
                String[] m = outbox.get(0);
                if (now - Long.parseLong(m[1]) > 1800000) {
                    outbox.remove(0);
                    continue;
                }
                if (s == null || s.messages() == null || s.engine() == null) return;
                if (!s.messages().sendText(m[0])) return;
                outbox.remove(0);
            }
        }
    }

    private static int lastSharedSoc = -1;
    private static long lastSharedAt;
    private static String lastShared = "";

    private static void share(Status s) {
        SharedPreferences p = sp(app);
        if (!p.getBoolean("share", true)) return;
        long every = p.getInt("shareMin", 5) * 60000L;
        long now = System.currentTimeMillis();
        String text = statusLine(app, s);
        boolean jump = lastSharedSoc < 0 || Math.abs(s.soc - lastSharedSoc) >= 5;
        boolean due = now - lastSharedAt >= every && !text.equals(lastShared);
        if (!jump && !due) return;
        lastSharedSoc = s.soc;
        lastSharedAt = now;
        lastShared = text;
        send(text, false);
    }

    /** "Power station AC30: 76%, in 120 W, out 45 W, full in 1 h 20 min". */
    public static String statusLine(Context c, Status s) {
        StringBuilder b = new StringBuilder(PREFIX + label(c) + ": " + s.soc + "%");
        b.append(", in ").append(s.in()).append(" W, out ").append(s.out()).append(" W");
        String t = timeText(c, s);
        if (t.length() > 0) b.append(", ").append(t);
        return b.toString();
    }

    /** "full in 1 h 20 min" / "about 4 h 10 min left" / "". */
    public static String timeText(Context c, Status s) {
        int net = s.in() - s.out();
        double wh = capacityWh(c);
        if (net > 5 && s.soc < 100) return "full in " + hm((100 - s.soc) / 100.0 * wh / net);
        if (net < -5 && s.soc > 0) return "about " + hm(s.soc / 100.0 * wh / -net) + " left";
        return "";
    }

    static String hm(double hours) {
        int min = (int) Math.round(hours * 60);
        if (min >= 6000) return "over 99 h";
        return min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min + " min";
    }

    private static boolean lowSent, critSent, overSent;
    private static int chargeVotes, mainsVotes;
    private static Boolean charging, mains;

    private static void alerts(Status before, Status s) {
        SharedPreferences p = sp(app);
        if (!p.getBoolean("alerts", true)) return;
        String n = PREFIX + label(app) + ": ";
        int low = p.getInt("lowPct", 10), crit = p.getInt("critPct", 5);
        if (s.soc <= crit && !critSent) {
            critSent = lowSent = true;
            send(n + "battery critical, " + s.soc + "%", true);
        } else if (s.soc <= low && !lowSent) {
            lowSent = true;
            send(n + "battery low, " + s.soc + "%", true);
        }
        if (s.soc >= crit + 3) critSent = false;
        if (s.soc >= low + 3) lowSent = false;
        // Mains: two reads in a row agreeing, so a blip does not alert.
        boolean m = s.acIn > 5;
        if (mains == null) mains = m;
        else if (m != mains) {
            if (++mainsVotes >= 2) {
                mains = m;
                mainsVotes = 0;
                if (p.getBoolean("mainsAlert", true)) send(n + (m ? "mains power back, charging " + s.acIn + " W" : "mains power lost, " + s.soc + "%"), true);
                if (charging != null) charging = s.in() > 10; // said already
                chargeVotes = 0;
            }
        } else mainsVotes = 0;
        boolean ch = s.in() > 10;
        if (charging == null) charging = ch;
        else if (ch != charging) {
            if (++chargeVotes >= 2) {
                charging = ch;
                chargeVotes = 0;
                if (p.getBoolean("chargeAlert", true)) send(n + (ch ? "charging started, " + s.in() + " W" : "charging stopped at " + s.soc + "%"), true);
            }
        } else chargeVotes = 0;
        int over = p.getInt("overloadW", 300);
        if (over > 0) {
            if (s.out() >= over && !overSent) {
                overSent = true;
                send(n + "overload, " + s.out() + " W out", true);
            } else if (s.out() < over * 9 / 10) overSent = false;
        }
    }

    private static void checkLost() {
        if (app == null || !bgWatch(app) || address(app).length() == 0 || !sp(app).getBoolean("alerts", true)) return;
        int min = sp(app).getInt("lostMin", 10);
        if (min <= 0) return;
        long now = System.currentTimeMillis();
        SharedPreferences p = sp(app);
        boolean told = p.getBoolean("toldLost", false);
        if (lastOk == 0) lastOk = now; // counts from when watching started
        boolean lost = now - lastOk > min * 60000L;
        if (lost && !told) {
            p.edit().putBoolean("toldLost", true).commit();
            send(PREFIX + label(app) + ": out of contact for " + min + " min", true);
        } else if (!lost && told) {
            p.edit().putBoolean("toldLost", false).commit();
            send(PREFIX + label(app) + ": back in contact" + (last != null ? ", " + last.soc + "%" : ""), true);
        }
    }

    /** The battery % in a shared status or alert ("...: 76%, in..." or "battery low, 9%"), or -1. */
    public static int percentIn(String body) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3})%").matcher(body);
        return m.find() ? Math.min(100, Integer.parseInt(m.group(1))) : -1;
    }

    /** Callsign -> that phone's latest power station message (last 24 h), for the commander view. */
    public static java.util.Map<String, MessageLog.Entry> heardByName(Context c) {
        java.util.Map<String, MessageLog.Entry> m = new java.util.HashMap<String, MessageLog.Entry>();
        for (MessageLog.Entry e : heard(c)) m.put(e.creator, e);
        return m;
    }

    /** Latest power station status from each other phone on the channel (last 24 h), newest first. */
    public static List<MessageLog.Entry> heard(Context c) {
        List<MessageLog.Entry> out = new ArrayList<MessageLog.Entry>();
        Set<String> seen = new HashSet<String>();
        List<MessageLog.Entry> all = MessageLog.get(c).all(0);
        long since = System.currentTimeMillis() - 86400000L;
        for (int i = all.size() - 1; i >= 0; i--) {
            MessageLog.Entry e = all.get(i);
            if (e.time < since) break;
            if (e.mine || e.type != MessageLog.TYPE_TEXT) continue;
            String body = e.body.startsWith(RadioService.SAY) ? e.body.substring(RadioService.SAY.length()) : e.body;
            if (!body.startsWith(PREFIX)) continue;
            if (seen.add(e.creator)) out.add(e);
        }
        return out;
    }
}
