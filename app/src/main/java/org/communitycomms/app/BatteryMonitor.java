package org.communitycomms.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.communitycomms.app.msg.MessageLog;
import org.communitycomms.app.power.BatteryReader;
import org.communitycomms.app.power.BatteryStatus;
import org.communitycomms.app.power.BatteryUnit;
import org.communitycomms.app.power.BluettiReader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Battery power (a Specialised menu, off by default, key "bluetti"): watches any number of batteries,
 * each through the one reader for its type (Bluetti, Tesla Powerwall 2, Anker SOLIX*, EcoFlow*;
 * * = untested). READ-ONLY: nothing here switches outputs or changes settings. One worker thread:
 * every 10 s while the screen is open (Bluetooth links kept up), every 60 s in the background when
 * "watch in the background" is ticked (links dropped between reads so the maker's app can still
 * connect; every 5 min while the phone saves power). The watching phone shares a short status on the
 * channel and sends alerts (low, critical, charging, mains / grid lost and back, overload, out of
 * contact), only once a unit is at or below "send at %" (10 by default).
 */
public final class BatteryMonitor {
    private static final String TAG = "Battery";
    /** Every shared status and alert starts with this, so other phones can find the latest. */
    public static final String PREFIX = "Power station ";

    /** Live state of one watched unit. */
    public static final class Watch {
        public final BatteryUnit unit;
        BatteryReader reader;
        public volatile BatteryStatus last;
        public volatile String state = "";
        public volatile long lastOk;
        long nextPoll;
        boolean lowSent, critSent, overSent;
        int chargeVotes, mainsVotes;
        Boolean charging, mains;
        int lastSharedSoc = -1;
        long lastSharedAt;
        String lastShared = "";

        Watch(BatteryUnit u) {
            unit = u;
        }
    }

    private static final Set<String> holders = new HashSet<String>();
    private static Context app;
    private static Thread worker;
    private static final Object wake = new Object();
    private static final Map<String, Watch> watches = new HashMap<String, Watch>();
    /** The screen redraws when this runs (posted to its own thread by the screen). */
    public static volatile Runnable ui;
    private static volatile String probeWanted;
    private static final LinkedList<String> logLines = new LinkedList<String>();

    private BatteryMonitor() {
    }

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("bluetti", Context.MODE_PRIVATE);
    }

    // ---- the list of units ----

    public static synchronized List<BatteryUnit> units(Context c) {
        SharedPreferences p = sp(c);
        String js = p.getString("units", null);
        if (js == null) { // first run after 0.1.455673: move the one Bluetti over
            List<BatteryUnit> l = new ArrayList<BatteryUnit>();
            String addr = p.getString("addr", "");
            if (addr.length() > 0) {
                BatteryUnit u = new BatteryUnit();
                u.id = "u1";
                u.type = BatteryReader.BLUETTI;
                u.addr = addr;
                u.name = p.getString("name", "");
                u.capWh = p.getInt("capWh", 0);
                u.overloadW = p.contains("overloadW") ? p.getInt("overloadW", 300) : -1;
                u.layout = p.getString("layout", "");
                l.add(u);
            }
            p.edit().putString("units", BatteryUnit.toJson(l)).commit();
            return l;
        }
        return BatteryUnit.fromJson(js);
    }

    public static synchronized void saveUnits(Context c, List<BatteryUnit> l) {
        sp(c).edit().putString("units", BatteryUnit.toJson(l)).commit();
        synchronized (watches) {
            Set<String> keep = new HashSet<String>();
            for (BatteryUnit u : l) keep.add(u.id);
            for (String id : new ArrayList<String>(watches.keySet())) {
                if (!keep.contains(id)) {
                    Watch w = watches.remove(id);
                    if (w.reader != null) w.reader.close();
                }
            }
            // Changed settings: start that unit's reader afresh.
            for (BatteryUnit u : l) {
                Watch w = watches.get(u.id);
                if (w != null && !same(w.unit, u)) {
                    if (w.reader != null) w.reader.close();
                    watches.remove(u.id);
                }
            }
        }
        refresh();
    }

    private static boolean same(BatteryUnit a, BatteryUnit b) {
        return BatteryUnit.toJson(java.util.Collections.singletonList(a)).equals(BatteryUnit.toJson(java.util.Collections.singletonList(b)));
    }

    public static String newId(List<BatteryUnit> l) {
        int n = 1;
        Set<String> ids = new HashSet<String>();
        for (BatteryUnit u : l) ids.add(u.id);
        while (ids.contains("u" + n)) n++;
        return "u" + n;
    }

    /** The live state for this unit (made on first use). */
    public static Watch watch(BatteryUnit u) {
        synchronized (watches) {
            Watch w = watches.get(u.id);
            if (w == null) {
                w = new Watch(u);
                watches.put(u.id, w);
            }
            return w;
        }
    }

    public static String label(BatteryUnit u) {
        if (u.name.length() > 0) return u.name;
        Watch w;
        synchronized (watches) {
            w = watches.get(u.id);
        }
        BatteryStatus s = w == null ? null : w.last;
        if (s != null && s.model.length() > 0) return s.model;
        return BatteryReader.typeName(u.type).replace("*", "");
    }

    /** Battery size: the setting, else what the unit reported, else 300 Wh. */
    public static int capacityWh(BatteryUnit u, BatteryStatus s) {
        if (u.capWh > 0) return u.capWh;
        if (s != null && s.capWh > 0) return s.capWh;
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
        if (worker == null && (holders.contains("screen") || (holders.contains("bg") && bgWatch(app) && !units(app).isEmpty()))) {
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "battery");
            worker.start();
        }
        synchronized (wake) {
            wake.notifyAll();
        }
    }

    /** Raw log of one unit's traffic (menu > Probe), for checking a new model or the field names. */
    public static void probe(Context c, BatteryUnit u) {
        app = c.getApplicationContext();
        probeWanted = u.id;
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
            while (logLines.size() > 800) logLines.removeFirst();
        }
    }

    private static void changed() {
        Runnable r = ui;
        if (r != null) r.run();
    }

    private static boolean screenOpen() {
        synchronized (BatteryMonitor.class) {
            return holders.contains("screen");
        }
    }

    private static boolean wanted() {
        synchronized (BatteryMonitor.class) {
            return holders.contains("screen") || (holders.contains("bg") && bgWatch(app));
        }
    }

    // ---- the worker ----

    private static void loop() {
        while (true) {
            List<BatteryUnit> list = units(app);
            if (!wanted() && probeWanted == null) {
                closeAll();
                synchronized (BatteryMonitor.class) {
                    if (!wanted() && probeWanted == null) {
                        worker = null;
                        return;
                    }
                }
            }
            String pid = probeWanted;
            if (pid != null) {
                probeWanted = null;
                for (BatteryUnit u : list) if (u.id.equals(pid)) runProbe(u);
            }
            long soonest = Long.MAX_VALUE;
            boolean screen = screenOpen();
            if (wanted()) {
                for (BatteryUnit u : list) {
                    Watch w = watch(u);
                    long now = SystemClock.elapsedRealtime();
                    if (screen && w.nextPoll - now > 10000) w.nextPoll = now; // screen just opened: read now
                    if (now >= w.nextPoll) {
                        poll(w);
                        if (!screen && w.reader != null) w.reader.close(); // background: let go between reads
                        long every = screen ? 10000 : PowerMode.sleeping() || PowerMode.mode() == PowerMode.ULTRA ? 300000 : 60000;
                        w.nextPoll = SystemClock.elapsedRealtime() + every;
                    }
                    soonest = Math.min(soonest, w.nextPoll);
                    checkLost(w);
                }
            }
            flushOutbox();
            synchronized (wake) {
                long wait = soonest == Long.MAX_VALUE ? 30000 : Math.max(200, soonest - SystemClock.elapsedRealtime());
                try {
                    wake.wait(Math.min(wait, 30000));
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private static void closeAll() {
        synchronized (watches) {
            for (Watch w : watches.values()) if (w.reader != null) w.reader.close();
        }
    }

    private static BatteryReader reader(final Watch w) {
        if (w.reader == null) {
            w.reader = BatteryReader.create(app, w.unit, new BatteryReader.Log() {
                @Override
                public void log(String s) {
                    BatteryMonitor.log(label(w.unit) + ": " + s);
                }
            });
        }
        return w.reader;
    }

    private static void poll(Watch w) {
        if (w.last == null && w.state.length() == 0) {
            w.state = "connecting...";
            changed();
        }
        BatteryReader r = reader(w);
        BatteryStatus s = r.read();
        saveLayout(w, r);
        if (s == null) {
            w.state = r.lastError;
            changed();
            return;
        }
        BatteryStatus before = w.last;
        w.last = s;
        w.lastOk = System.currentTimeMillis();
        w.state = "";
        changed();
        if (bgWatch(app)) {
            alerts(w, before, s);
            share(w, s);
        }
    }

    private static void saveLayout(Watch w, BatteryReader r) {
        if (r instanceof BluettiReader && ((BluettiReader) r).layoutChanged) {
            ((BluettiReader) r).layoutChanged = false;
            List<BatteryUnit> l = units(app);
            for (BatteryUnit u : l) if (u.id.equals(w.unit.id)) u.layout = w.unit.layout;
            sp(app).edit().putString("units", BatteryUnit.toJson(l)).commit();
        }
    }

    private static void runProbe(BatteryUnit u) {
        Watch w = watch(u);
        log("---- probe " + label(u) + " " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date()) + " ----");
        w.state = "probing...";
        changed();
        BatteryReader r = reader(w);
        BatteryStatus s = r.probe();
        saveLayout(w, r);
        if (s != null) {
            w.last = s;
            w.lastOk = System.currentTimeMillis();
        }
        w.state = s == null ? r.lastError : "";
        log("---- probe end ----");
        changed();
    }

    // ---- alerts and sharing ----

    private static final List<String[]> outbox = new ArrayList<String[]>();

    /** Queued until this phone is on a channel (dropped after 30 minutes). */
    private static void send(Watch w, String text, boolean alert) {
        // Quiet by default: nothing goes to the channel until the unit is down to this % (Settings; 100 = always).
        int at = sendAtPct(app);
        BatteryStatus now = w.last;
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

    private static void share(Watch w, BatteryStatus s) {
        SharedPreferences p = sp(app);
        if (!p.getBoolean("share", true)) return;
        long every = p.getInt("shareMin", 5) * 60000L;
        long now = System.currentTimeMillis();
        String text = statusLine(w.unit, s);
        boolean jump = w.lastSharedSoc < 0 || Math.abs(s.soc - w.lastSharedSoc) >= 5;
        boolean due = now - w.lastSharedAt >= every && !text.equals(w.lastShared);
        if (!jump && !due) return;
        w.lastSharedSoc = s.soc;
        w.lastSharedAt = now;
        w.lastShared = text;
        send(w, text, false);
    }

    /** "Power station AC30: 76%, in 120 W, out 45 W, full in 1 h 20 min" (Powerwall adds solar, home, grid). */
    public static String statusLine(BatteryUnit u, BatteryStatus s) {
        StringBuilder b = new StringBuilder(PREFIX + label(u) + ": " + s.soc + "%");
        if (s.solarW != BatteryStatus.NONE || s.loadW != BatteryStatus.NONE) {
            if (s.batteryW != BatteryStatus.NONE) b.append(s.batteryW < -50 ? ", charging " + s.acIn + " W" : s.batteryW > 50 ? ", giving " + s.acOut + " W" : ", idle");
            if (s.solarW != BatteryStatus.NONE) b.append(", solar ").append(s.solarW).append(" W");
            if (s.loadW != BatteryStatus.NONE) b.append(", home ").append(s.loadW).append(" W");
            if (s.mains == 0) b.append(", OFF GRID");
        } else {
            b.append(", in ").append(s.in()).append(" W, out ").append(s.out()).append(" W");
        }
        String t = timeText(u, s);
        if (t.length() > 0) b.append(", ").append(t);
        return b.toString();
    }

    /** "full in 1 h 20 min" / "about 4 h 10 min left" / "". */
    public static String timeText(BatteryUnit u, BatteryStatus s) {
        int net = s.in() - s.out();
        double wh = capacityWh(u, s);
        if (net > 5 && s.soc < 100) return "full in " + hm((100 - s.soc) / 100.0 * wh / net);
        if (net < -5 && s.soc > 0) return "about " + hm(s.minutesLeft > 0 ? s.minutesLeft / 60.0 : s.soc / 100.0 * wh / -net) + " left";
        return "";
    }

    static String hm(double hours) {
        int min = (int) Math.round(hours * 60);
        if (min >= 6000) return "over 99 h";
        return min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min + " min";
    }

    private static void alerts(Watch w, BatteryStatus before, BatteryStatus s) {
        SharedPreferences p = sp(app);
        if (!p.getBoolean("alerts", true)) return;
        boolean home = BatteryReader.POWERWALL.equals(w.unit.type);
        String n = PREFIX + label(w.unit) + ": ";
        int low = p.getInt("lowPct", 10), crit = p.getInt("critPct", 5);
        if (s.soc >= 0 && s.soc <= crit && !w.critSent) {
            w.critSent = w.lowSent = true;
            send(w, n + "battery critical, " + s.soc + "%", true);
        } else if (s.soc >= 0 && s.soc <= low && !w.lowSent) {
            w.lowSent = true;
            send(w, n + "battery low, " + s.soc + "%", true);
        }
        if (s.soc >= crit + 3) w.critSent = false;
        if (s.soc >= low + 3) w.lowSent = false;
        // Mains / grid: two reads in a row agreeing, so a blip does not alert.
        boolean m = s.mainsUp();
        if (w.mains == null) w.mains = m;
        else if (m != w.mains) {
            if (++w.mainsVotes >= 2) {
                w.mains = m;
                w.mainsVotes = 0;
                if (p.getBoolean("mainsAlert", true)) {
                    if (home) send(w, n + (m ? "grid power back, " + s.soc + "%" : "grid power lost, running on the battery, " + s.soc + "%"), true);
                    else send(w, n + (m ? "mains power back, charging " + s.acIn + " W" : "mains power lost, " + s.soc + "%"), true);
                }
                if (w.charging != null) w.charging = s.in() > 10; // said already
                w.chargeVotes = 0;
            }
        } else w.mainsVotes = 0;
        boolean ch = s.in() > 10;
        if (w.charging == null) w.charging = ch;
        else if (ch != w.charging) {
            if (++w.chargeVotes >= 2) {
                w.charging = ch;
                w.chargeVotes = 0;
                // A home battery starts and stops charging with the sun: only portable units say so.
                if (!home && p.getBoolean("chargeAlert", true)) send(w, n + (ch ? "charging started, " + s.in() + " W" : "charging stopped at " + s.soc + "%"), true);
            }
        } else w.chargeVotes = 0;
        int over = w.unit.overload();
        if (over > 0) {
            if (s.out() >= over && !w.overSent) {
                w.overSent = true;
                send(w, n + "overload, " + s.out() + " W out", true);
            } else if (s.out() < over * 9 / 10) w.overSent = false;
        }
    }

    private static void checkLost(Watch w) {
        if (app == null || !bgWatch(app) || !sp(app).getBoolean("alerts", true)) return;
        int min = sp(app).getInt("lostMin", 10);
        if (min <= 0) return;
        long now = System.currentTimeMillis();
        SharedPreferences p = sp(app);
        String key = "toldLost_" + w.unit.id;
        boolean told = p.getBoolean(key, false);
        if (w.lastOk == 0) w.lastOk = now; // counts from when watching started
        boolean lost = now - w.lastOk > min * 60000L;
        if (lost && !told) {
            p.edit().putBoolean(key, true).commit();
            send(w, PREFIX + label(w.unit) + ": out of contact for " + min + " min", true);
        } else if (!lost && told) {
            p.edit().putBoolean(key, false).commit();
            send(w, PREFIX + label(w.unit) + ": back in contact" + (w.last != null ? ", " + w.last.soc + "%" : ""), true);
        }
    }

    /** The battery % in a shared status or alert ("...: 76%, in..." or "battery low, 9%"), or -1. */
    public static int percentIn(String body) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3})%").matcher(body);
        return m.find() ? Math.min(100, Integer.parseInt(m.group(1))) : -1;
    }

    /** Callsign -> that phone's latest power station message (last 24 h), for the commander view. */
    public static Map<String, MessageLog.Entry> heardByName(Context c) {
        Map<String, MessageLog.Entry> m = new HashMap<String, MessageLog.Entry>();
        List<MessageLog.Entry> h = heard(c);
        for (int i = h.size() - 1; i >= 0; i--) m.put(h.get(i).creator, h.get(i)); // newest wins
        return m;
    }

    /** Latest power station status for each phone and unit on the channel (last 24 h), newest first. */
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
            int colon = body.indexOf(':', PREFIX.length());
            String unitName = colon > 0 ? body.substring(PREFIX.length(), colon) : "";
            if (seen.add(e.creator + "\n" + unitName)) out.add(e);
        }
        return out;
    }
}
