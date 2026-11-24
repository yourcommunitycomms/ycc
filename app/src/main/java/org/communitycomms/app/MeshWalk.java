package org.communitycomms.app;

import android.content.Context;
import android.location.Location;
import android.util.Log;

import org.communitycomms.app.geo.TileMapView;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mesh testing: Mapping walk. The walking phone sends a small probe (its position, a number and its send time) to
 * the fixed station every few seconds; the fixed station answers each one with what it measured: how the probe
 * arrived (direct / N hops / unknown / through a relay), loss and jitter over its last 20 probes, and, when it polls
 * its own OpenMANET node, the walker's node signal and throughput. The walker shows a big status (green direct,
 * blue via relay, amber weak, red no link after 3 unanswered probes) and a trail of its walk in those colours.
 * Probes and answers are chat bodies of their own kind (30, 31), sent once each; older apps ignore them.
 */
public final class MeshWalk {
    private static final String TAG = "MeshWalk";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    public static final int ST_STARTING = -1, ST_DIRECT = 0, ST_RELAY = 1, ST_WEAK = 2, ST_NONE = 3;

    private MeshWalk() {
    }

    // ---- walker state ----

    static final class Probe {
        int seq;
        long sentAt;
        double lat, lon;
        boolean answered, lost;
    }

    public static final class TrailPoint {
        public double lat, lon;
        public int status;
        public long t;
        public int rtt = -1, hops = MeshTest.HOPS_UNKNOWN, signalDbm = Integer.MIN_VALUE, kbps = -1, lossPm = -1, jitterMs = -1;

        /** "Direct link OK", "Working via relay (2 hops)", "Weak / losing packets", "No link to fixed station". */
        public String statusText() {
            return wordFor(status, hops);
        }

        public String details() {
            StringBuilder b = new StringBuilder(statusText());
            b.append("\n").append(new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date(t)));
            b.append("\nRound trip: ").append(rtt < 0 ? "no answer" : rtt + " ms");
            b.append("\nHops: ").append(MeshTest.hopsWord(hops));
            if (lossPm >= 0) b.append("\nLoss at the fixed station: ").append(lossPm / 10.0).append("%");
            if (jitterMs >= 0) b.append("\nJitter: ").append(jitterMs).append(" ms");
            if (signalDbm != Integer.MIN_VALUE) b.append("\nSignal: ").append(signalDbm).append(" dBm");
            if (kbps >= 0) b.append("\nThroughput: ").append(kbps).append(" kbit/s");
            b.append(String.format(Locale.US, "\n%.5f, %.5f", lat, lon));
            return b.toString();
        }
    }

    static String wordFor(int st, int hops) {
        switch (st) {
            case ST_DIRECT:
                return hops >= 1 ? "Direct link OK" : "Link OK (hops unknown)";
            case ST_RELAY:
                return "Working via relay (" + hops + " hops)";
            case ST_WEAK:
                return "Weak / losing packets";
            case ST_NONE:
                return "No link to fixed station";
            default:
                return "Starting…";
        }
    }

    static String colourName(int st) {
        return st == ST_DIRECT ? "green" : st == ST_RELAY ? "blue" : st == ST_WEAK ? "amber" : st == ST_NONE ? "red" : "grey";
    }

    private static volatile int probesSent, probesAnswered;
    private static volatile String pendingSummary;
    private static volatile List<TrailPoint> shownOld;

    private static volatile boolean on;
    private static volatile String target = "";
    private static Thread thread;
    private static final Map<Integer, Probe> outstanding = new HashMap<Integer, Probe>();
    private static final ArrayDeque<Boolean> window = new ArrayDeque<Boolean>(); // last probes: answered?
    private static final List<TrailPoint> trail = new ArrayList<TrailPoint>();
    private static volatile int trailVersion;
    private static int seq;
    private static volatile int status = ST_STARTING, unanswered;
    private static volatile long lastAckMs, startedMs;
    private static volatile int lastRtt = -1, lastHops = MeshTest.HOPS_UNKNOWN, lastLossPm = -1, lastJitter = -1,
            lastSignal = Integer.MIN_VALUE, lastKbps = -1;
    private static volatile String fixedNode = "", lastNote = "";
    private static volatile double[] fixedAt;
    private static volatile List<String> nodeList = new ArrayList<String>();

    public static boolean running() {
        return on;
    }

    public static String target() {
        return target;
    }

    public static int status() {
        return status;
    }

    public static int intervalSec() {
        return Math.max(2, Math.min(60, MeshTest.sp().getInt("walk_every", 5)));
    }

    public static String myNode() {
        return MeshTest.sp().getString("walk_node", "").trim();
    }

    public static boolean vibrate() {
        return MeshTest.sp().getBoolean("walk_vibe", false);
    }

    public static boolean simulate() {
        return MeshTest.sp().getBoolean("walk_sim", false);
    }

    public static List<String> nodeList() {
        return nodeList;
    }

    public static String statusWord() {
        return wordFor(status, lastHops);
    }

    /** One line for the map strip: status · loss · round trip · hops · last answer. */
    public static String stripText() {
        int sent, ok;
        synchronized (window) {
            sent = window.size();
            ok = 0;
            for (Boolean b : window) if (b) ok++;
        }
        long age = lastAckMs == 0 ? -1 : (System.currentTimeMillis() - lastAckMs) / 1000;
        return statusWord() + "\nloss " + (sent == 0 ? "–" : Math.round(100.0 * (sent - ok) / sent) + "%")
                + " · rtt " + (lastRtt < 0 ? "–" : lastRtt + " ms") + " · " + MeshTest.hopsWord(lastHops)
                + " · answer " + (age < 0 ? "none yet" : age + " s ago");
    }

    public static int statusColour() {
        switch (status) {
            case ST_DIRECT:
                return 0xff2e7d32;
            case ST_RELAY:
                return 0xff1565c0;
            case ST_WEAK:
                return 0xffef8f00;
            case ST_NONE:
                return 0xffc62828;
            default:
                return 0xff546e7a;
        }
    }

    /** The detail lines under the big status. */
    public static String details() {
        long now = System.currentTimeMillis();
        int sent, ok;
        synchronized (window) {
            sent = window.size();
            ok = 0;
            for (Boolean b : window) if (b) ok++;
        }
        StringBuilder b = new StringBuilder();
        b.append("To ").append(target).append(fixedNode.length() > 0 ? " (node " + fixedNode + ")" : "");
        b.append("\nLoss (my last ").append(sent).append(" probes): ").append(sent == 0 ? "–" : Math.round(100.0 * (sent - ok) / sent) + "%");
        if (lastLossPm >= 0) b.append(" · at the fixed station ").append(lastLossPm / 10.0).append("%");
        b.append("\nRound trip: ").append(lastRtt < 0 ? "–" : lastRtt + " ms");
        b.append(" · jitter: ").append(lastJitter < 0 ? "–" : lastJitter + " ms");
        b.append("\nHops: ").append(MeshTest.hopsWord(lastHops));
        if (lastSignal != Integer.MIN_VALUE || lastKbps >= 0) {
            b.append("\nMy node ").append(myNode().length() > 0 ? myNode() : "?").append(" to the fixed node: ")
                    .append(lastSignal == Integer.MIN_VALUE ? "" : lastSignal + " dBm ").append(lastKbps < 0 ? "" : lastKbps + " kbit/s");
        }
        b.append("\nLast answer: ").append(lastAckMs == 0 ? "none yet" : (now - lastAckMs) / 1000 + " s ago");
        double[] f = fixedAt;
        Location me = MeshTest.app == null ? null : myLocationNow();
        if (f != null && me != null) {
            float[] r = new float[2];
            Location.distanceBetween(me.getLatitude(), me.getLongitude(), f[0], f[1], r);
            b.append(" · fixed station ").append(org.communitycomms.app.geo.Geo.distance(r[0])).append(" away");
        }
        if (lastNote.length() > 0) b.append("\n").append(lastNote);
        return b.toString();
    }

    // ---- position (real GPS, fake GPS, or a simulated walk for testing) ----

    static Location myLocation(RadioService rs) {
        Location f = org.communitycomms.app.geo.FakeGps.location(rs);
        if (f != null) {
            if (on && simulate()) {
                // Test route: round a 150 m circle from the fake spot at walking pace (1.4 m/s), about 11 minutes a lap.
                double t = (System.currentTimeMillis() - startedMs) / 1000.0;
                double ang = (t * 1.4 / 150.0) % (2 * Math.PI);
                double[] p = org.communitycomms.app.geo.Geo.destination(f.getLatitude(), f.getLongitude(), Math.toDegrees(ang), 150);
                f.setLatitude(p[0]);
                f.setLongitude(p[1]);
            }
            return f;
        }
        return rs.lastFix();
    }

    private static Location myLocationNow() {
        RadioService rs = RadioService.instance();
        return rs == null ? null : myLocation(rs);
    }

    // ---- start / stop ----

    public static synchronized String start(String fixedName) {
        if (on) return null;
        RadioService rs = RadioService.instance();
        if (rs == null || rs.compat() != null || rs.engine() == null) return "Join a YCC channel first (not the OM/TAK talk group)";
        if (rs.engine().listenOnly()) return "This channel is listen-only on this phone";
        if (fixedName == null || fixedName.trim().length() == 0) return "Pick the fixed station first";
        target = fixedName.trim();
        on = true;
        status = ST_STARTING;
        unanswered = 0;
        lastAckMs = 0;
        lastRtt = lastLossPm = lastJitter = lastKbps = -1;
        lastSignal = Integer.MIN_VALUE;
        lastHops = MeshTest.HOPS_UNKNOWN;
        lastNote = "";
        startedMs = System.currentTimeMillis();
        probesSent = 0;
        probesAnswered = 0;
        clearTrail(); // the trail of this walk only (old walks are saved and can be reopened)
        shownOld = null;
        synchronized (outstanding) {
            outstanding.clear();
        }
        synchronized (window) {
            window.clear();
        }
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "mesh-walk");
        thread.start();
        Log.i(TAG, "walk started to " + target + " every " + intervalSec() + " s");
        return null;
    }

    public static synchronized void stop() {
        if (!on) return;
        on = false;
        if (thread != null) thread.interrupt();
        thread = null;
        Log.i(TAG, "walk stopped");
        try {
            String s = summary(trail(), target, startedMs, System.currentTimeMillis(), probesSent, probesAnswered, fixedAt);
            saveWalk(s);
            pendingSummary = s;
            Log.i(TAG, "SUMMARY " + s.replace("\n", " | "));
            if (MeshTest.sp().getBoolean("walk_post", false)) postToChat(s);
        } catch (Exception e) {
            Log.w(TAG, "summary", e);
        }
    }

    /** The summary of a walk that just stopped, once (the page or the map shows it). */
    public static String takeSummary() {
        String s = pendingSummary;
        pendingSummary = null;
        return s;
    }

    private static void postToChat(String s) {
        RadioService rs = RadioService.instance();
        if (rs == null || rs.messages() == null || RadioEngine.radioSilent) return;
        RadioEngine e = rs.engine();
        if (e == null || (MeshTest.isPublic(e) && !MeshTest.allowPublic("walk"))) return;
        rs.messages().sendText(s.length() > 900 ? s.substring(0, 900) + "…" : s);
    }

    // ---- the end-of-walk summary ----

    static double dist(double la1, double lo1, double la2, double lo2) {
        float[] r = new float[1];
        Location.distanceBetween(la1, lo1, la2, lo2, r);
        return r[0];
    }

    static String hm(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d h %02d min", s / 3600, s / 60 % 60) : String.format(Locale.US, "%d min %02d s", s / 60, s % 60);
    }

    /** The field-test summary: times, distance, probes, time per colour, loss, round trip, jitter, signal, outages, hops. */
    static String summary(List<TrailPoint> pts, String fixed, long start, long end, int sent, int answered, double[] fixedPt) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        StringBuilder b = new StringBuilder("Mapping walk summary");
        b.append("\nFixed station: ").append(fixed);
        b.append("\nStart ").append(f.format(new java.util.Date(start))).append(", end ").append(f.format(new java.util.Date(end)))
                .append(", ").append(hm(end - start));
        double walked = 0;
        int[] count = new int[4];
        long lossSum = 0;
        int lossN = 0, lossMax = -1, rttSum = 0, rttN = 0, rttMax = -1, jitSum = 0, jitN = 0, jitMax = -1;
        int sigMin = Integer.MAX_VALUE, sigMax = Integer.MIN_VALUE;
        Map<String, Integer> hopCount = new java.util.TreeMap<String, Integer>();
        long outStart = -1, bestOut = 0;
        TrailPoint outFirst = null, bestOutAt = null;
        double furthest = -1;
        TrailPoint prev = null;
        for (TrailPoint p : pts) {
            if (prev != null && !Double.isNaN(p.lat) && !Double.isNaN(prev.lat)) walked += dist(prev.lat, prev.lon, p.lat, p.lon);
            if (p.status >= 0 && p.status < 4) count[p.status]++;
            if (p.lossPm >= 0) {
                lossSum += p.lossPm;
                lossN++;
                lossMax = Math.max(lossMax, p.lossPm);
            }
            if (p.rtt >= 0) {
                rttSum += p.rtt;
                rttN++;
                rttMax = Math.max(rttMax, p.rtt);
            }
            if (p.jitterMs >= 0) {
                jitSum += p.jitterMs;
                jitN++;
                jitMax = Math.max(jitMax, p.jitterMs);
            }
            if (p.signalDbm != Integer.MIN_VALUE) {
                sigMin = Math.min(sigMin, p.signalDbm);
                sigMax = Math.max(sigMax, p.signalDbm);
            }
            if (p.rtt >= 0) {
                String h = MeshTest.hopsWord(p.hops);
                Integer c = hopCount.get(h);
                hopCount.put(h, c == null ? 1 : c + 1);
            }
            if (p.status == ST_NONE) {
                if (outStart < 0) {
                    outStart = p.t;
                    outFirst = p;
                }
                if (p.t - outStart >= bestOut) {
                    bestOut = p.t - outStart;
                    bestOutAt = outFirst;
                }
            } else {
                outStart = -1;
            }
            if (p.status == ST_DIRECT && fixedPt != null && !Double.isNaN(p.lat)) furthest = Math.max(furthest, dist(fixedPt[0], fixedPt[1], p.lat, p.lon));
            prev = p;
        }
        b.append("\nDistance walked: ").append(org.communitycomms.app.geo.Geo.distance(walked));
        b.append("\nProbes: ").append(sent).append(" sent, ").append(answered).append(" answered")
                .append(sent > 0 ? " (" + Math.round(100.0 * answered / sent) + "%)" : "");
        int total = count[0] + count[1] + count[2] + count[3];
        String[] names = {"green direct", "blue via relay", "amber weak", "red no link"};
        for (int i = 0; i < 4; i++) {
            b.append("\n  ").append(names[i]).append(": ").append(count[i])
                    .append(total > 0 ? " (" + Math.round(100.0 * count[i] / total) + "% of the time)" : "");
        }
        b.append("\nLoss at the fixed station: ").append(lossN == 0 ? "–" : String.format(Locale.US, "average %.1f%%, worst %.1f%%", lossSum / 10.0 / lossN, lossMax / 10.0));
        b.append("\nRound trip: ").append(rttN == 0 ? "–" : "average " + rttSum / rttN + " ms, max " + rttMax + " ms");
        b.append("\nJitter: ").append(jitN == 0 ? "–" : "average " + jitSum / jitN + " ms, max " + jitMax + " ms");
        b.append("\nSignal: ").append(sigMin == Integer.MAX_VALUE ? "not read (fixed node not polled)" : sigMin + " to " + sigMax + " dBm");
        b.append("\nLongest no-link outage: ").append(bestOutAt == null ? "none"
                : hm(Math.max(bestOut, intervalSec() * 1000L)) + String.format(Locale.US, " starting at %.5f, %.5f", bestOutAt.lat, bestOutAt.lon));
        b.append("\nFurthest direct point from the fixed station: ").append(furthest < 0 ? "–" : org.communitycomms.app.geo.Geo.distance(furthest));
        b.append("\nHops (answered probes):");
        if (hopCount.isEmpty()) b.append(" –");
        for (Map.Entry<String, Integer> en : hopCount.entrySet()) b.append("\n  ").append(en.getKey()).append(": ").append(en.getValue());
        return b.toString();
    }

    // ---- walk history: each stopped walk saved with its trail and summary ----

    static java.io.File walksDir() {
        java.io.File d = new java.io.File(MeshTest.app.getFilesDir(), "walks");
        d.mkdirs();
        return d;
    }

    private static void saveWalk(String summary) throws Exception {
        org.json.JSONObject o = new org.json.JSONObject();
        o.put("start", startedMs);
        o.put("end", System.currentTimeMillis());
        o.put("target", target);
        o.put("summary", summary);
        org.json.JSONArray a = new org.json.JSONArray();
        for (TrailPoint p : trail()) {
            a.put(new org.json.JSONArray().put(p.t).put(p.lat).put(p.lon).put(p.status).put(p.rtt).put(p.hops)
                    .put(p.signalDbm == Integer.MIN_VALUE ? 999 : p.signalDbm).put(p.kbps).put(p.lossPm).put(p.jitterMs));
        }
        o.put("points", a);
        java.io.File f = new java.io.File(walksDir(), "walk-" + startedMs + ".json");
        java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), "UTF-8");
        try {
            w.write(o.toString());
        } finally {
            w.close();
        }
    }

    /** Saved walks, newest first. */
    public static List<java.io.File> walks() {
        java.io.File[] fs = walksDir().listFiles();
        List<java.io.File> out = new ArrayList<java.io.File>();
        if (fs != null) for (java.io.File f : fs) if (f.getName().endsWith(".json")) out.add(f);
        java.util.Collections.sort(out, new java.util.Comparator<java.io.File>() {
            @Override
            public int compare(java.io.File a, java.io.File b) {
                return b.getName().compareTo(a.getName());
            }
        });
        return out;
    }

    public static org.json.JSONObject readWalk(java.io.File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            return new org.json.JSONObject(TestAudio.readText(in));
        } finally {
            in.close();
        }
    }

    public static List<TrailPoint> pointsOf(org.json.JSONObject o) throws Exception {
        List<TrailPoint> out = new ArrayList<TrailPoint>();
        org.json.JSONArray a = o.getJSONArray("points");
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONArray q = a.getJSONArray(i);
            TrailPoint p = new TrailPoint();
            p.t = q.getLong(0);
            p.lat = q.getDouble(1);
            p.lon = q.getDouble(2);
            p.status = q.getInt(3);
            p.rtt = q.getInt(4);
            p.hops = q.getInt(5);
            int s = q.getInt(6);
            p.signalDbm = s == 999 ? Integer.MIN_VALUE : s;
            p.kbps = q.getInt(7);
            p.lossPm = q.getInt(8);
            p.jitterMs = q.getInt(9);
            out.add(p);
        }
        return out;
    }

    /** Show an old walk's trail on the maps (null = none). */
    public static void showOld(List<TrailPoint> pts) {
        shownOld = pts;
        trailVersion++;
        if (pts != null && !pts.isEmpty()) focus = new double[]{pts.get(0).lat, pts.get(0).lon};
    }

    /** Where the full map should move to next (View on map), once; null = nowhere. */
    public static volatile double[] focus;

    public static synchronized void clearTrail() {
        synchronized (trail) {
            trail.clear();
        }
        trailVersion++;
    }

    public static List<TrailPoint> trail() {
        synchronized (trail) {
            return new ArrayList<TrailPoint>(trail);
        }
    }

    public static int trailVersion() {
        return trailVersion;
    }

    static int colourFor(int st) {
        return st == ST_DIRECT ? 0xff43a047 : st == ST_RELAY ? 0xff1e88e5 : st == ST_WEAK ? 0xffffb300 : st == ST_NONE ? 0xffe53935 : 0xff9e9e9e;
    }

    public static List<TileMapView.Dot> trailDots() {
        List<TileMapView.Dot> out = new ArrayList<TileMapView.Dot>();
        List<TrailPoint> old = shownOld;
        if (old != null) for (TrailPoint p : old) out.add(new TileMapView.Dot(p.lat, p.lon, colourFor(p.status), TileMapView.Dot.FILLED).tag(p));
        for (TrailPoint p : trail()) out.add(new TileMapView.Dot(p.lat, p.lon, colourFor(p.status), TileMapView.Dot.FILLED).tag(p));
        double[] f = fixedAt;
        if (f != null && !out.isEmpty()) out.add(new TileMapView.Dot(f[0], f[1], 0xff263238, TileMapView.Dot.STATION));
        return out;
    }

    private static void addTrail(double lat, double lon, int st, int rtt) {
        if (Double.isNaN(lat)) return;
        TrailPoint p = new TrailPoint();
        p.lat = lat;
        p.lon = lon;
        p.status = st;
        p.t = System.currentTimeMillis();
        p.rtt = rtt;
        p.hops = lastHops;
        p.signalDbm = lastSignal;
        p.kbps = lastKbps;
        p.lossPm = rtt < 0 ? -1 : lastLossPm;
        p.jitterMs = rtt < 0 ? -1 : lastJitter;
        if (rtt < 0) {
            p.hops = MeshTest.HOPS_UNKNOWN;
            p.signalDbm = Integer.MIN_VALUE;
            p.kbps = -1;
        }
        synchronized (trail) {
            trail.add(p);
            if (trail.size() > 5000) trail.remove(0);
        }
        trailVersion++;
    }

    private static void setStatus(int st) {
        if (st == status) return;
        int old = status;
        status = st;
        Log.i(TAG, "status " + statusWord());
        if (old != ST_STARTING && vibrate() && MeshTest.app != null) {
            try {
                android.os.Vibrator v = (android.os.Vibrator) MeshTest.app.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(st == ST_NONE ? new long[]{0, 80, 120, 80} : new long[]{0, 60}, -1);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void loop() {
        while (on) {
            try {
                RadioService rs = RadioService.instance();
                RadioEngine e = rs == null || rs.compat() != null ? null : rs.engine();
                if (e == null) {
                    lastNote = "Not on a YCC channel";
                    Thread.sleep(1000);
                    continue;
                }
                if (MeshTest.isPublic(e) && !MeshTest.allowPublic("walk")) {
                    lastNote = "Paused: " + MeshTest.channelNow() + " is public (not allowed)";
                    Thread.sleep(1000);
                    continue;
                }
                timeouts();
                Location l = myLocation(rs);
                Probe p = new Probe();
                p.seq = ++seq;
                p.sentAt = System.currentTimeMillis();
                p.lat = l == null ? Double.NaN : l.getLatitude();
                p.lon = l == null ? Double.NaN : l.getLongitude();
                synchronized (outstanding) {
                    outstanding.put(p.seq, p);
                }
                e.sendTestBody(probeBody(p, l, rs));
                probesSent++;
                if (l == null) lastNote = "No GPS fix yet: probes go without a position";
                Thread.sleep(intervalSec() * 1000L);
            } catch (InterruptedException ex) {
                return;
            } catch (RuntimeException ex) {
                Log.w(TAG, "walk", ex);
            }
        }
    }

    /** Probes not answered in time count as lost; three in a row is "no link". */
    private static void timeouts() {
        long now = System.currentTimeMillis();
        long limit = Math.max(4000, intervalSec() * 2000L);
        List<Probe> lost = new ArrayList<Probe>();
        synchronized (outstanding) {
            Iterator<Probe> it = outstanding.values().iterator();
            while (it.hasNext()) {
                Probe p = it.next();
                if (now - p.sentAt > limit) {
                    it.remove();
                    lost.add(p);
                }
            }
        }
        for (Probe p : lost) {
            p.lost = true;
            unanswered++;
            noteWindow(false);
            if (unanswered >= 3) setStatus(ST_NONE);
            addTrail(p.lat, p.lon, unanswered >= 3 ? ST_NONE : ST_WEAK, -1);
            Log.i(TAG, "probe " + p.seq + " not answered");
        }
    }

    private static void noteWindow(boolean ok) {
        synchronized (window) {
            window.addLast(ok);
            while (window.size() > 10) window.removeFirst();
        }
    }

    private static int windowLossPct() {
        synchronized (window) {
            if (window.isEmpty()) return 0;
            int bad = 0;
            for (Boolean b : window) if (!b) bad++;
            return bad * 100 / window.size();
        }
    }

    private static byte[] probeBody(Probe p, Location l, RadioService rs) {
        byte[] t = trunc(target.getBytes(UTF8));
        byte[] n = trunc(myNode().getBytes(UTF8));
        ByteBuffer b = ByteBuffer.allocate(1 + 1 + 4 + 8 + 8 + 8 + 4 + 1 + 1 + 1 + t.length + 1 + n.length);
        b.put((byte) MeshTest.KIND_PROBE).put((byte) 1).putInt(p.seq).putLong(p.sentAt);
        b.putDouble(l == null ? Double.NaN : l.getLatitude()).putDouble(l == null ? Double.NaN : l.getLongitude());
        b.putFloat(l == null ? 0f : l.getAccuracy());
        b.put((byte) intervalSec());
        b.put((byte) (nodeList.isEmpty() || p.seq % 12 == 1 ? 1 : 0)); // bit0: send me the node list
        b.put((byte) t.length).put(t).put((byte) n.length).put(n);
        return b.array();
    }

    private static byte[] trunc(byte[] b) {
        return b.length <= 32 ? b : java.util.Arrays.copyOf(b, 32);
    }

    private static String str(ByteBuffer b) {
        int n = b.get() & 0xff;
        byte[] s = new byte[n];
        b.get(s);
        return new String(s, UTF8);
    }

    // ---- walker: an answer came back ----

    static void onAnswer(RadioEngine e, long sender, String fromName, byte[] p, int off) {
        if (!on) return;
        ByteBuffer b = ByteBuffer.wrap(p, off, p.length - off);
        if (b.get() != 1) return;
        int s = b.getInt();
        long sentAt = b.getLong();
        String walker = str(b);
        RadioService rs = RadioService.instance();
        if (rs == null || !walker.equalsIgnoreCase(new Prefs(rs).airName())) return;
        int hops = b.get();
        int loss = b.getShort() & 0xffff;
        int jit = b.getShort() & 0xffff;
        b.get(); // window size
        int sig = b.get();
        int kbps = b.getInt();
        double fl = b.getDouble(), fo = b.getDouble();
        String fnode = str(b);
        int ll = b.getShort() & 0xffff;
        byte[] lb = new byte[ll];
        b.get(lb);
        Probe pr;
        synchronized (outstanding) {
            pr = outstanding.remove(s);
        }
        if (pr == null) return; // a second copy of an answer, or one so late the probe already counted as lost
        probesAnswered++;
        long now = System.currentTimeMillis();
        lastAckMs = now;
        unanswered = 0;
        lastRtt = (int) (now - sentAt);
        lastHops = hops;
        lastLossPm = loss == 0xffff ? -1 : loss;
        lastJitter = jit == 0xffff ? -1 : jit;
        lastSignal = sig == 127 ? Integer.MIN_VALUE : sig;
        lastKbps = kbps;
        fixedNode = fnode;
        if (!Double.isNaN(fl)) fixedAt = new double[]{fl, fo};
        if (ll > 0) {
            List<String> l = new ArrayList<String>();
            for (String x : new String(lb, UTF8).split(",")) if (x.trim().length() > 0) l.add(x.trim());
            nodeList = l;
        }
        lastNote = hops == MeshTest.HOPS_RELAY ? "The fixed station hears you through a relay / the internet: not a mesh test" : "";
        if (pr != null) noteWindow(true);
        int st;
        if (hops == MeshTest.HOPS_RELAY) st = ST_WEAK;
        else if (windowLossPct() >= 20 || (lastLossPm >= 200) || lastRtt > 1500 || lastJitter > 150) st = ST_WEAK;
        else if (hops >= 2) st = ST_RELAY;
        else st = ST_DIRECT;
        setStatus(st);
        if (pr != null) addTrail(pr.lat, pr.lon, st, lastRtt);
        Log.i(TAG, "answer " + s + " rtt " + lastRtt + " ms, " + MeshTest.hopsWord(hops) + ", loss " + lastLossPm + "‰, jitter " + lastJitter
                + " ms" + (lastSignal != Integer.MIN_VALUE ? ", signal " + lastSignal + " dBm" : "") + " -> " + statusWord());
    }

    // ---- fixed station: answering probes ----

    static final class Seen {
        final ArrayDeque<long[]> last = new ArrayDeque<long[]>(); // {seq, arrival, sentAt}
        double jitter = -1;
        long lastAnswer;
    }

    private static final Map<String, Seen> seen = new HashMap<String, Seen>();

    static void onProbe(RadioEngine e, long sender, String walkerName, InetAddress from, byte[] p, int off) {
        if (!MeshMap.featureOn() || !MeshMap.fixed() || e.isMonitor()) return;
        ByteBuffer b = ByteBuffer.wrap(p, off, p.length - off);
        if (b.get() != 1) return;
        int s = b.getInt();
        long sentAt = b.getLong();
        double lat = b.getDouble(), lon = b.getDouble();
        float acc = b.getFloat();
        b.get(); // their interval
        int flags = b.get();
        String tgt = str(b);
        String node = b.hasRemaining() ? str(b).trim() : "";
        RadioService rs = RadioService.instance();
        if (rs == null) return;
        String me = new Prefs(rs).airName();
        if (!tgt.equalsIgnoreCase(me) && !(EchoTest.running() && tgt.equalsIgnoreCase(EchoTest.name()))) return;
        if (MeshTest.isPublic(e) && !MeshTest.allowPublic("walk")) return;
        long now = System.currentTimeMillis();
        // Loss and jitter over the walker's last 20 probes (jitter against the walker's own send times).
        Seen sn;
        synchronized (seen) {
            sn = seen.get(walkerName);
            if (sn == null) {
                sn = new Seen();
                seen.put(walkerName, sn);
            }
        }
        int lossPm, jit;
        synchronized (sn) {
            for (long[] x : sn.last) if (x[0] == s) return; // a second copy of the same probe
            long[] prev = sn.last.peekLast();
            if (prev != null && s <= prev[0] - 50) sn.last.clear(); // the walk restarted
            if (prev != null && s > prev[0]) {
                double d = Math.abs((now - prev[1]) - (sentAt - prev[2]));
                sn.jitter = sn.jitter < 0 ? d : sn.jitter + (d - sn.jitter) / 16.0;
            }
            sn.last.addLast(new long[]{s, now, sentAt});
            while (sn.last.size() > 20) sn.last.removeFirst();
            long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
            for (long[] x : sn.last) {
                min = Math.min(min, x[0]);
                max = Math.max(max, x[0]);
            }
            lossPm = (int) Math.round(1000 * Math.max(0, 1 - sn.last.size() / (double) (max - min + 1)));
            jit = sn.jitter < 0 ? -1 : (int) Math.round(sn.jitter);
            if (now - sn.lastAnswer < 800) return; // at most about one answer a second per walker
            sn.lastAnswer = now;
        }
        // How it arrived: relay, the fixed node's view of the walker's node, else the plain estimate.
        int hops;
        int sig = Integer.MIN_VALUE, kbps = -1;
        boolean relay = e.viaRelay(from) && !(MeshTest.labPeers() && from != null && !from.isLoopbackAddress());
        MeshNode.Info ni = node.length() > 0 ? MeshNode.lookup(node) : null;
        if (relay) hops = MeshTest.HOPS_RELAY;
        else if (ni != null) {
            hops = ni.hops <= 1 ? 1 : ni.hops; // same node or a direct neighbour of the fixed node = direct
            if (ni.neighbour) {
                sig = ni.signalDbm;
                kbps = ni.kbps;
            }
        } else if (MeshNode.polling()) hops = MeshTest.HOPS_UNKNOWN; // a mesh, but the walker's node is not known
        else hops = MeshTest.topologyHops(from == null ? null : from.getHostAddress());
        if (!Double.isNaN(lat) && MeshMap.recording() && hops != MeshTest.HOPS_RELAY) {
            MeshMap.Point mp = new MeshMap.Point();
            mp.t = now;
            mp.name = walkerName;
            mp.chan = MeshTest.label(e);
            mp.lat = lat;
            mp.lon = lon;
            mp.acc = acc;
            mp.hops = hops;
            mp.lossPm = lossPm;
            mp.jitterMs = jit;
            mp.grade = MeshTest.grade(lossPm / 1000.0, jit);
            mp.signalDbm = sig;
            mp.kbps = kbps;
            mp.src = MeshMap.SRC_PROBE;
            mp.node = node;
            MeshMap.add(mp);
        }
        // The answer.
        byte[] w = trunc(walkerName.getBytes(UTF8));
        byte[] fnode = trunc(MeshNode.selfName().getBytes(UTF8));
        byte[] list = new byte[0];
        if ((flags & 1) != 0) {
            StringBuilder sb = new StringBuilder();
            for (String n : MeshNode.names()) {
                if (sb.length() + n.length() + 1 > 300) break;
                sb.append(sb.length() > 0 ? "," : "").append(n);
            }
            list = sb.toString().getBytes(UTF8);
        }
        double[] fx = MeshMap.fixedPoint();
        ByteBuffer a = ByteBuffer.allocate(1 + 1 + 4 + 8 + 1 + w.length + 1 + 2 + 2 + 1 + 1 + 4 + 16 + 1 + fnode.length + 2 + list.length);
        a.put((byte) MeshTest.KIND_PROBE_ACK).put((byte) 1).putInt(s).putLong(sentAt).put((byte) w.length).put(w);
        a.put((byte) Math.max(-2, Math.min(127, hops)));
        a.putShort((short) Math.min(0xfffe, lossPm)).putShort((short) (jit < 0 ? 0xffff : Math.min(0xfffe, jit)));
        a.put((byte) 20);
        a.put((byte) (sig == Integer.MIN_VALUE ? 127 : Math.max(-126, Math.min(126, sig))));
        a.putInt(kbps);
        a.putDouble(fx == null ? Double.NaN : fx[0]).putDouble(fx == null ? Double.NaN : fx[1]);
        a.put((byte) fnode.length).put(fnode);
        a.putShort((short) list.length).put(list);
        e.sendTestBody(a.array());
        Log.i(TAG, "answered probe " + s + " from " + walkerName + ": " + MeshTest.hopsWord(hops) + ", loss " + lossPm + "‰, jitter " + jit + " ms"
                + (node.length() > 0 ? ", node " + node + (ni == null ? " (not known to the fixed node)" : "") : ""));
    }

    // ---- export of the walker's own trail ----

    public static java.io.File exportTrail(Context c, boolean gpx) throws java.io.IOException {
        return exportWalk(c, trail(), gpx, null, System.currentTimeMillis());
    }

    /**
     * A walk as CSV (one row per probe, with its status colour) or GPX (a track plus a waypoint per probe, the status
     * in the name, description and a ycc:status extension), with the summary at the top of the file when known.
     */
    public static java.io.File exportWalk(Context c, List<TrailPoint> pts, boolean gpx, String summary, long when) throws java.io.IOException {
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new java.util.Date(when));
        java.io.File f = new java.io.File(RadioService.receivedDir(c), "walk-" + stamp + (gpx ? ".gpx" : ".csv"));
        java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), "UTF-8");
        try {
            if (gpx) {
                w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"YCC Mapping walk\" "
                        + "xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:ycc=\"https://github.com/yourcommunitycomms/ycc\">\n");
                if (summary != null) w.write("<metadata><desc>" + xml(summary) + "</desc></metadata>\n");
                for (TrailPoint p : pts) {
                    w.write(String.format(Locale.US, "<wpt lat=\"%.6f\" lon=\"%.6f\"><time>%s</time><name>%s</name><desc>%s</desc>"
                                    + "<extensions><ycc:status>%s</ycc:status></extensions></wpt>\n", p.lat, p.lon, iso.format(new java.util.Date(p.t)),
                            colourName(p.status), xml(p.details().replace('\n', ';')), colourName(p.status)));
                }
                w.write("<trk><name>Mapping walk</name><trkseg>\n");
                for (TrailPoint p : pts) {
                    w.write(String.format(Locale.US, "<trkpt lat=\"%.6f\" lon=\"%.6f\"><time>%s</time><desc>%s</desc></trkpt>\n",
                            p.lat, p.lon, iso.format(new java.util.Date(p.t)), colourName(p.status)));
                }
                w.write("</trkseg></trk>\n</gpx>\n");
            } else {
                if (summary != null) for (String l : summary.split("\n")) w.write("# " + l + "\n");
                w.write("time_utc,lat,lon,status_colour,status,rtt_ms,hops,loss_permille,jitter_ms,signal_dbm,throughput_kbps\n");
                for (TrailPoint p : pts) {
                    w.write(iso.format(new java.util.Date(p.t)) + "," + String.format(Locale.US, "%.6f,%.6f", p.lat, p.lon) + ","
                            + colourName(p.status) + "," + p.statusText().replace(',', ' ') + "," + (p.rtt < 0 ? "" : p.rtt) + ","
                            + (p.hops == MeshTest.HOPS_UNKNOWN ? "unknown" : String.valueOf(p.hops)) + "," + (p.lossPm < 0 ? "" : p.lossPm) + ","
                            + (p.jitterMs < 0 ? "" : p.jitterMs) + "," + (p.signalDbm == Integer.MIN_VALUE ? "" : p.signalDbm) + ","
                            + (p.kbps < 0 ? "" : p.kbps) + "\n");
                }
            }
        } finally {
            w.close();
        }
        return f;
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String fmt(double v) {
        return String.format(Locale.US, "%.1f", v);
    }
}
