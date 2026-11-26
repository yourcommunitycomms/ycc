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
        /** The walker's own node's side: signal and throughput to its next hop toward the fixed node, and its hop count. */
        public int mySignalDbm = Integer.MIN_VALUE, myKbps = -1, myHops = -1;
        public String nextHop = "";
        /** Taken from fake GPS (testing only): not real coverage. */
        public boolean fake;

        /** The weaker of the two sides' signal (fixed node's view, walker node's view), or MIN_VALUE. */
        public int worseSignal() {
            if (signalDbm == Integer.MIN_VALUE) return mySignalDbm;
            if (mySignalDbm == Integer.MIN_VALUE) return signalDbm;
            return Math.min(signalDbm, mySignalDbm);
        }

        /** "Direct link OK", "Working via relay (2 hops)", "Weak / losing packets", "No link to fixed station". */
        public String statusText() {
            return wordFor(status, hops);
        }

        public String details() {
            StringBuilder b = new StringBuilder(statusText());
            if (fake) b.append(" (FAKE GPS: testing only)");
            b.append("\n").append(new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date(t)));
            b.append("\nRound trip: ").append(rtt < 0 ? "no answer" : rtt + " ms");
            b.append("\nHops: ").append(MeshTest.hopsWord(hops));
            if (lossPm >= 0) b.append("\nLoss at the fixed station: ").append(lossPm / 10.0).append("%");
            if (jitterMs >= 0) b.append("\nJitter: ").append(jitterMs).append(" ms");
            if (signalDbm != Integer.MIN_VALUE) b.append("\nSignal: ").append(signalDbm).append(" dBm");
            if (kbps >= 0) b.append("\nThroughput: ").append(kbps).append(" kbit/s");
            if (mySignalDbm != Integer.MIN_VALUE || myKbps >= 0 || nextHop.length() > 0) {
                b.append("\nMy node's side: ").append(nextHop.length() > 0 ? "next hop " + nextHop + " " : "")
                        .append(mySignalDbm != Integer.MIN_VALUE ? mySignalDbm + " dBm " : "").append(myKbps >= 0 ? myKbps + " kbit/s" : "")
                        .append(myHops >= 0 ? " (" + myHops + (myHops == 1 ? " hop" : " hops") + " to the fixed node)" : "");
            }
            b.append(String.format(Locale.US, "\n%.5f, %.5f", lat, lon));
            return b.toString();
        }
    }

    /** A spot flagged during a walk, with an optional comment ("dead spot behind the shed"). */
    public static final class Flag {
        public long t;
        public double lat, lon;
        public String text = "";

        public String details() {
            return (text.length() > 0 ? text : "(no comment)") + "\n"
                    + new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date(t))
                    + String.format(Locale.US, "\n%.5f, %.5f", lat, lon);
        }

        String line() {
            return new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date(t))
                    + String.format(Locale.US, " (%.5f, %.5f)", lat, lon) + (text.length() > 0 ? ": " + text : "");
        }
    }

    private static final List<Flag> flags = new ArrayList<Flag>();
    private static volatile List<Flag> shownOldFlags;

    /** Flags this spot (at lat/lon, taken when the flag dialog opened). */
    public static void addFlag(double lat, double lon, String text) {
        Flag f = new Flag();
        f.t = System.currentTimeMillis();
        f.lat = lat;
        f.lon = lon;
        f.text = text == null ? "" : text.trim();
        synchronized (flags) {
            flags.add(f);
        }
        trailVersion++;
        Log.i(TAG, "flag " + f.line());
    }

    public static List<Flag> flags() {
        synchronized (flags) {
            return new ArrayList<Flag>(flags);
        }
    }

    /** Where I am now for a flag: {lat, lon}, or null. */
    public static double[] hereNow() {
        Location l = myLocationNow();
        return l == null ? null : new double[]{l.getLatitude(), l.getLongitude()};
    }

    public static List<Flag> flagsOf(org.json.JSONObject o) {
        List<Flag> out = new ArrayList<Flag>();
        org.json.JSONArray a = o.optJSONArray("flags");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONArray q = a.optJSONArray(i);
            if (q == null) continue;
            Flag f = new Flag();
            f.t = q.optLong(0);
            f.lat = q.optDouble(1);
            f.lon = q.optDouble(2);
            f.text = q.optString(3, "");
            out.add(f);
        }
        return out;
    }

    /** The walk's notes (typed after it ended), saved in its file. */
    public static void setNotes(java.io.File walk, String notes) {
        if (walk == null) return;
        try {
            org.json.JSONObject o = readWalk(walk);
            if (notes.equals(o.optString("notes", ""))) return;
            o.put("notes", notes);
            java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(walk), "UTF-8");
            try {
                w.write(o.toString());
            } finally {
                w.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "notes: " + e.getMessage());
        }
    }

    /** The summary with the flags and notes, as shared / exported. */
    public static String fullText(String summary, List<Flag> fl, String notes) {
        StringBuilder b = new StringBuilder(summary == null ? "" : summary);
        if (fl != null && !fl.isEmpty() && !b.toString().contains("\nFlags (")) {
            b.append("\nFlags (").append(fl.size()).append("):");
            for (Flag f : fl) b.append("\n  ").append(f.line());
        }
        if (notes != null && notes.trim().length() > 0) b.append("\nNotes: ").append(notes.trim());
        return b.toString();
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
        if (!MeshTest.sp().getBoolean("walk_every_v2", false)) // 2026-11-26: probes every second by default (was 5 s)
            MeshTest.sp().edit().putInt("walk_every", 1).putBoolean("walk_every_v2", true).commit();
        return Math.max(1, Math.min(60, MeshTest.sp().getInt("walk_every", 1)));
    }

    /** My mesh node: picked, or found from the WiFi name (MeshRadio). */
    public static String myNode() {
        return MeshRadio.myNode();
    }

    static boolean walkLocal() {
        return MeshRadio.walkLocal();
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
            b.append("\nFixed node's side (it hears my node ").append(myNode().length() > 0 ? myNode() : "?").append("): ")
                    .append(lastSignal == Integer.MIN_VALUE ? "" : lastSignal + " dBm ").append(lastKbps < 0 ? "" : lastKbps + " kbit/s");
        }
        if (MeshRadio.onMyNode()) {
            MeshRadio.Toward t = MeshRadio.toward(fixedNode);
            b.append("\nMy node's side (").append(myNode()).append(", ").append(MeshRadio.howFound()).append("): ");
            if (t != null) {
                b.append(t.hops == 0 ? "same node as the fixed station" : t.hops == 1 ? "1 hop to the fixed node" : t.hops > 1 ? t.hops + " hops to the fixed node" : "hops ?").append(t.nextHop.length() > 0 ? ", next hop " + t.nextHop : "")
                        .append(t.signalDbm != Integer.MIN_VALUE ? ", " + t.signalDbm + " dBm" : "").append(t.kbps >= 0 ? ", " + t.kbps + " kbit/s" : "");
            } else b.append(fixedNode.length() > 0 ? "fixed node " + fixedNode + " not in its view" : "waiting for the fixed node's name");
            b.append("\nIts neighbours: ").append(MeshRadio.neighboursLine(4));
            if (MeshNode.LOCAL.busy()) b.append("\nMy node is busy or slow: reading it every 30 s");
        } else if (walkLocal()) {
            String le = MeshNode.LOCAL.lastError();
            b.append("\nMy node: ").append(!MeshRadio.hasLogin() ? "not read (no login)" : le.length() > 0 ? le
                    : myNode().length() == 0 ? MeshRadio.howFound() : "reading " + myNode() + "…");
        }
        b.append("\nLast answer: ").append(lastAckMs == 0 ? "none yet" : (now - lastAckMs) / 1000 + " s ago");
        double[] f = fixedAt;
        Location me = MeshTest.app == null ? null : myLocationNow();
        if (f != null && me != null) {
            float[] r = new float[2];
            Location.distanceBetween(me.getLatitude(), me.getLongitude(), f[0], f[1], r);
            b.append(" · antenna ").append(org.communitycomms.app.geo.Geo.distance(r[0])).append(" away");
        }
        if (lastNote.length() > 0) b.append("\n").append(lastNote);
        return b.toString();
    }

    // ---- position (real GPS, fake GPS, or a simulated walk for testing) ----

    // ---- the walk's own GPS: one fix a second while walking (the shared "last fix" can be minutes old when location
    // sharing is off or resting, which stacked every probe of a stretch on one spot) ----
    private static volatile Location walkFix;
    private static android.location.LocationListener gpsListener;

    @android.annotation.SuppressLint("MissingPermission")
    private static void gpsOn() {
        final Context c = MeshTest.app;
        if (c == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (!on || gpsListener != null) return;
                android.location.LocationManager lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
                if (lm == null) return;
                gpsListener = new android.location.LocationListener() {
                    @Override
                    public void onLocationChanged(Location l) {
                        Location w = walkFix;
                        boolean gps = android.location.LocationManager.GPS_PROVIDER.equals(l.getProvider());
                        // network fixes only until GPS has one (they are coarse)
                        if (gps || w == null || !android.location.LocationManager.GPS_PROVIDER.equals(w.getProvider())
                                || System.currentTimeMillis() - w.getTime() > 10000) walkFix = l;
                    }

                    @Override
                    public void onStatusChanged(String p, int s, android.os.Bundle e) {
                    }

                    @Override
                    public void onProviderEnabled(String p) {
                    }

                    @Override
                    public void onProviderDisabled(String p) {
                    }
                };
                try {
                    for (String p : new String[]{android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER})
                        if (lm.getAllProviders().contains(p)) lm.requestLocationUpdates(p, 1000, 0, gpsListener, android.os.Looper.getMainLooper());
                } catch (RuntimeException e) {
                    Log.w(TAG, "walk GPS: " + e.getMessage());
                }
            }
        });
    }

    private static void gpsOff() {
        final Context c = MeshTest.app;
        if (c == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (gpsListener == null) return;
                android.location.LocationManager lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
                try {
                    if (lm != null) lm.removeUpdates(gpsListener);
                } catch (RuntimeException ignored) {
                }
                gpsListener = null;
                walkFix = null;
            }
        });
    }

    // ---- the last 30 s for the live graph: {sentAt, rtt ms | R_LOST | R_WAIT} ----
    public static final long R_LOST = -1, R_WAIT = -2;
    private static final ArrayDeque<long[]> recent = new ArrayDeque<long[]>(); // {seq, sentAt, result}

    private static void recentSet(int seqNo, long sentAt, long result) {
        synchronized (recent) {
            for (long[] x : recent)
                if (x[0] == seqNo) {
                    x[2] = result;
                    return;
                }
            recent.addLast(new long[]{seqNo, sentAt, result});
            while (recent.size() > 300) recent.removeFirst();
        }
    }

    public static List<long[]> recent(long since) {
        List<long[]> out = new ArrayList<long[]>();
        synchronized (recent) {
            for (long[] x : recent) if (x[1] >= since) out.add(new long[]{x[1], x[2]});
        }
        return out;
    }

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
        Location w = walkFix;
        if (on && w != null && System.currentTimeMillis() - w.getTime() < 15000) return w;
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
        if (walkLocal() && MeshTest.app != null) {
            MeshRadio.ensure(MeshTest.app);
            MeshNode.LOCAL.nudge();
        }
        clearTrail(); // the trail of this walk only (old walks are saved and can be reopened)
        shownOld = null;
        synchronized (outstanding) {
            outstanding.clear();
        }
        synchronized (window) {
            window.clear();
        }
        synchronized (recent) {
            recent.clear();
        }
        synchronized (flags) {
            flags.clear();
        }
        shownOldFlags = null;
        gpsOn();
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
        gpsOff();
        Log.i(TAG, "walk stopped");
        try {
            String s = fullText(summary(trail(), target, startedMs, System.currentTimeMillis(), probesSent, probesAnswered, fixedAt), flags(), null);
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
        int fakeN = 0;
        long lossSum = 0;
        int lossN = 0, lossMax = -1, rttSum = 0, rttN = 0, rttMax = -1, jitSum = 0, jitN = 0, jitMax = -1;
        int sigMin = Integer.MAX_VALUE, sigMax = Integer.MIN_VALUE, mySigMin = Integer.MAX_VALUE, mySigMax = Integer.MIN_VALUE;
        Map<String, Integer> hopCount = new java.util.TreeMap<String, Integer>();
        long outStart = -1, bestOut = 0;
        TrailPoint outFirst = null, bestOutAt = null;
        double furthest = -1;
        TrailPoint prev = null;
        for (TrailPoint p : pts) {
            if (prev != null && !Double.isNaN(p.lat) && !Double.isNaN(prev.lat)) walked += dist(prev.lat, prev.lon, p.lat, p.lon);
            if (p.status >= 0 && p.status < 4) count[p.status]++;
            if (p.fake) fakeN++;
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
            if (p.mySignalDbm != Integer.MIN_VALUE) {
                mySigMin = Math.min(mySigMin, p.mySignalDbm);
                mySigMax = Math.max(mySigMax, p.mySignalDbm);
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
        if (fakeN > 0) b.append("\nFAKE GPS: ").append(fakeN).append(" of ").append(pts.size())
                .append(" positions were fake (testing only, NOT real coverage)");
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
        b.append("\nSignal (fixed node's side): ").append(sigMin == Integer.MAX_VALUE ? "not read (fixed node not polled)" : sigMin + " to " + sigMax + " dBm");
        b.append("\nSignal (my node's side): ").append(mySigMin == Integer.MAX_VALUE ? "not read (my node not polled)" : mySigMin + " to " + mySigMax + " dBm");
        b.append("\nLongest no-link outage: ").append(bestOutAt == null ? "none"
                : hm(Math.max(bestOut, intervalSec() * 1000L)) + String.format(Locale.US, " starting at %.5f, %.5f", bestOutAt.lat, bestOutAt.lon));
        b.append("\nFurthest direct point from the antenna: ").append(furthest < 0 ? "–" : org.communitycomms.app.geo.Geo.distance(furthest));
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
                    .put(p.signalDbm == Integer.MIN_VALUE ? 999 : p.signalDbm).put(p.kbps).put(p.lossPm).put(p.jitterMs)
                    .put(p.mySignalDbm == Integer.MIN_VALUE ? 999 : p.mySignalDbm).put(p.myKbps).put(p.myHops).put(p.nextHop).put(p.fake ? 1 : 0));
        }
        o.put("points", a);
        org.json.JSONArray fa = new org.json.JSONArray();
        for (Flag f : flags()) fa.put(new org.json.JSONArray().put(f.t).put(f.lat).put(f.lon).put(f.text));
        o.put("flags", fa);
        o.put("notes", "");
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
            if (q.length() > 13) { // walks saved with the walker's own node's side
                int ms = q.getInt(10);
                p.mySignalDbm = ms == 999 ? Integer.MIN_VALUE : ms;
                p.myKbps = q.getInt(11);
                p.myHops = q.getInt(12);
                p.nextHop = q.optString(13, "");
            }
            p.fake = q.length() > 14 && q.optInt(14) == 1;
            out.add(p);
        }
        return out;
    }

    /** Show an old walk's trail on the maps (null = none). */
    public static void showOld(List<TrailPoint> pts) {
        showOld(pts, null);
    }

    public static void showOld(List<TrailPoint> pts, List<Flag> fl) {
        shownOld = pts;
        shownOldFlags = fl;
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
        if (old != null) for (TrailPoint p : old) out.add(new TileMapView.Dot(p.lat, p.lon, colourFor(p.status), TileMapView.Dot.FILLED).tag(p).mark(p.fake));
        for (TrailPoint p : trail()) out.add(new TileMapView.Dot(p.lat, p.lon, colourFor(p.status), TileMapView.Dot.FILLED).tag(p).mark(p.fake));
        List<Flag> of = shownOldFlags;
        if (of != null) for (Flag g : of) out.add(new TileMapView.Dot(g.lat, g.lon, 0xffd81b60, TileMapView.Dot.FLAG).tag(g));
        for (Flag g : flags()) out.add(new TileMapView.Dot(g.lat, g.lon, 0xffd81b60, TileMapView.Dot.FLAG).tag(g));
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
        p.fake = MeshTest.app != null && org.communitycomms.app.geo.FakeGps.enabled(MeshTest.app);
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
        // The walker's own node's side, read now (also where the fixed station does not answer).
        MeshRadio.Toward t = MeshRadio.toward(fixedNode);
        if (t != null) {
            p.mySignalDbm = t.signalDbm;
            p.myKbps = t.kbps;
            p.myHops = t.hops;
            p.nextHop = t.nextHop;
        }
        synchronized (trail) {
            trail.add(p);
            if (trail.size() > 20000) trail.remove(0);
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
                recentSet(p.seq, p.sentAt, R_WAIT);
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
            recentSet(p.seq, p.sentAt, R_LOST);
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
        int fl = nodeList.isEmpty() || p.seq % 12 == 1 ? 1 : 0; // bit0: send me the node list
        if (org.communitycomms.app.geo.FakeGps.location() != null) fl |= 2; // bit1: my position is fake GPS (testing)
        b.put((byte) fl);
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
        recentSet(s, pr.sentAt, now - sentAt);
        lastAckMs = now;
        unanswered = 0;
        lastRtt = (int) (now - sentAt);
        fixedNode = fnode;
        // The fixed side could not tell the hops: use my node's view of the path to the fixed node.
        MeshRadio.Toward tw = hops == MeshTest.HOPS_UNKNOWN ? MeshRadio.toward(fnode) : null;
        if (tw != null && tw.hops >= 0) hops = tw.hops <= 1 ? 1 : tw.hops;
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
            mp.fake = (flags & 2) != 0;
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
        return exportWalk(c, trail(), flags(), gpx, null, System.currentTimeMillis());
    }

    public static java.io.File exportWalk(Context c, List<TrailPoint> pts, boolean gpx, String summary, long when) throws java.io.IOException {
        return exportWalk(c, pts, null, gpx, summary, when);
    }

    /**
     * A walk as CSV (one row per probe, with its status colour) or GPX (a track plus a waypoint per probe, the status
     * in the name, description and a ycc:status extension), with the summary at the top of the file when known.
     */
    public static java.io.File exportWalk(Context c, List<TrailPoint> pts, List<Flag> fl, boolean gpx, String summary, long when) throws java.io.IOException {
        if (fl == null) fl = new ArrayList<Flag>();
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
                for (Flag g : fl)
                    w.write(String.format(Locale.US, "<wpt lat=\"%.6f\" lon=\"%.6f\"><time>%s</time><name>%s</name><sym>Flag</sym>"
                                    + "<extensions><ycc:status>flag</ycc:status></extensions></wpt>\n", g.lat, g.lon, iso.format(new java.util.Date(g.t)),
                            xml("Flag" + (g.text.length() > 0 ? ": " + g.text : ""))));
                w.write("<trk><name>Mapping walk</name><trkseg>\n");
                for (TrailPoint p : pts) {
                    w.write(String.format(Locale.US, "<trkpt lat=\"%.6f\" lon=\"%.6f\"><time>%s</time><desc>%s</desc></trkpt>\n",
                            p.lat, p.lon, iso.format(new java.util.Date(p.t)), colourName(p.status)));
                }
                w.write("</trkseg></trk>\n</gpx>\n");
            } else {
                if (summary != null) for (String l : summary.split("\n")) w.write("# " + l + "\n");
                w.write("time_utc,lat,lon,status_colour,status,rtt_ms,hops,loss_permille,jitter_ms,signal_dbm,throughput_kbps,"
                        + "my_node_signal_dbm,my_node_throughput_kbps,my_node_hops,next_hop,worse_signal_dbm,fake_gps\n");
                for (TrailPoint p : pts) {
                    w.write(iso.format(new java.util.Date(p.t)) + "," + String.format(Locale.US, "%.6f,%.6f", p.lat, p.lon) + ","
                            + colourName(p.status) + "," + p.statusText().replace(',', ' ') + "," + (p.rtt < 0 ? "" : p.rtt) + ","
                            + (p.hops == MeshTest.HOPS_UNKNOWN ? "unknown" : String.valueOf(p.hops)) + "," + (p.lossPm < 0 ? "" : p.lossPm) + ","
                            + (p.jitterMs < 0 ? "" : p.jitterMs) + "," + (p.signalDbm == Integer.MIN_VALUE ? "" : p.signalDbm) + ","
                            + (p.kbps < 0 ? "" : p.kbps) + "," + (p.mySignalDbm == Integer.MIN_VALUE ? "" : p.mySignalDbm) + ","
                            + (p.myKbps < 0 ? "" : p.myKbps) + "," + (p.myHops < 0 ? "" : p.myHops) + "," + p.nextHop.replace(',', ' ') + ","
                            + (p.worseSignal() == Integer.MIN_VALUE ? "" : p.worseSignal()) + "," + (p.fake ? "yes" : "") + "\n");
                }
                for (Flag g : fl) // flag rows: status_colour "flag", the comment in the status column
                    w.write(iso.format(new java.util.Date(g.t)) + "," + String.format(Locale.US, "%.6f,%.6f", g.lat, g.lon) + ",flag,"
                            + g.text.replace(',', ' ').replace('\n', ' ') + ",,,,,,,,,,,,\n");
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
