package org.communitycomms.app;

import android.content.Context;
import android.util.Log;

import org.communitycomms.app.geo.TileMapView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Mesh testing: Map mesh (coverage mapping). A phone marked "fixed here" records every position it takes in from
 * other phones that came straight off the network (or through YCC / mesh hops it can count), never through a
 * relay or the internet, with time, callsign, channel, hops and the sender's recent link quality (and, with the
 * fixed node polled, the walker's node signal and throughput). Kept on the phone (oldest dropped past 20,000),
 * drawn as dots, exported as CSV or GPX. Fully offline.
 */
public final class MeshMap {
    private static final String TAG = "MeshMap";
    public static final int CAP = 20000;
    public static final int SRC_POSITION = 0, SRC_PROBE = 1;

    public static final class Point {
        public long t;
        public String name = "", chan = "", node = "";
        public double lat, lon;
        public float acc;
        public int hops = MeshTest.HOPS_UNKNOWN, lossPm = -1, jitterMs = -1, grade = -1, kbps = -1, src;
        public int signalDbm = Integer.MIN_VALUE;

        String csv() {
            return t + "," + clean(name) + "," + clean(chan) + "," + String.format(Locale.US, "%.6f,%.6f,%.0f", lat, lon, acc) + ","
                    + hops + "," + lossPm + "," + jitterMs + "," + grade + "," + (signalDbm == Integer.MIN_VALUE ? "" : signalDbm) + ","
                    + kbps + "," + src + "," + clean(node);
        }

        static Point parse(String l) {
            String[] f = l.split(",", -1);
            if (f.length < 13) return null;
            try {
                Point p = new Point();
                p.t = Long.parseLong(f[0]);
                p.name = f[1];
                p.chan = f[2];
                p.lat = Double.parseDouble(f[3]);
                p.lon = Double.parseDouble(f[4]);
                p.acc = Float.parseFloat(f[5]);
                p.hops = Integer.parseInt(f[6]);
                p.lossPm = Integer.parseInt(f[7]);
                p.jitterMs = Integer.parseInt(f[8]);
                p.grade = Integer.parseInt(f[9]);
                p.signalDbm = f[10].length() == 0 ? Integer.MIN_VALUE : Integer.parseInt(f[10]);
                p.kbps = Integer.parseInt(f[11]);
                p.src = Integer.parseInt(f[12]);
                p.node = f.length > 13 ? f[13] : "";
                return p;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private static final List<Point> points = new ArrayList<Point>();
    private static File file;
    private static volatile int version;
    private static final Map<Long, double[]> lastFix = new HashMap<Long, double[]>();

    private MeshMap() {
    }

    static String clean(String s) {
        return s == null ? "" : s.replace(',', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    // ---- settings ----

    public static boolean featureOn() {
        return MeshTest.app != null && new Prefs(MeshTest.app).menuOn(MeshTest.K_MAP);
    }

    /** Recording is on: the feature, "Map mesh on" and this phone fixed with a point. */
    public static boolean recording() {
        return featureOn() && MeshTest.sp().getBoolean("map_on", false) && fixedPoint() != null;
    }

    public static boolean fixed() {
        return MeshTest.sp().getBoolean("fixed_on", false);
    }

    public static double[] fixedPoint() {
        if (!fixed()) return null;
        String s = MeshTest.sp().getString("fixed_at", "");
        String[] f = s.split(",");
        if (f.length != 2) return null;
        try {
            return new double[]{Double.parseDouble(f[0]), Double.parseDouble(f[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void setFixedPoint(double lat, double lon) {
        MeshTest.sp().edit().putBoolean("fixed_on", true).putString("fixed_at", String.format(Locale.US, "%.6f,%.6f", lat, lon)).commit();
        version++;
    }

    public static boolean showOnMainMap() {
        return featureOn() && MeshTest.sp().getBoolean("map_main", true);
    }

    public static boolean colourBySignal() {
        return MeshTest.sp().getBoolean("map_by_signal", false);
    }

    // ---- store ----

    static synchronized void load(Context c) {
        file = new File(c.getFilesDir(), "meshmap.csv");
        points.clear();
        if (!file.exists()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(file));
            for (String l = r.readLine(); l != null; l = r.readLine()) {
                Point p = Point.parse(l);
                if (p != null) points.add(p);
            }
        } catch (IOException e) {
            Log.w(TAG, "load", e);
        } finally {
            if (r != null) try {
                r.close();
            } catch (IOException ignored) {
            }
        }
        if (points.size() > CAP) points.subList(0, points.size() - CAP).clear();
        version++;
    }

    public static void add(Point p) {
        synchronized (MeshMap.class) {
            points.add(p);
            boolean rewrite = points.size() > CAP + CAP / 10;
            if (rewrite) points.subList(0, points.size() - CAP).clear();
            version++;
            try {
                if (rewrite) save();
                else append(p);
            } catch (IOException e) {
                Log.w(TAG, "save", e);
            }
        }
        Log.i(TAG, "point " + p.name + " " + MeshTest.hopsWord(p.hops) + " loss " + p.lossPm + "‰ jitter " + p.jitterMs
                + " ms signal " + (p.signalDbm == Integer.MIN_VALUE ? "?" : p.signalDbm + " dBm") + (p.src == SRC_PROBE ? " (probe)" : ""));
    }

    private static void append(Point p) throws IOException {
        Writer w = new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8");
        try {
            w.write(p.csv() + "\n");
        } finally {
            w.close();
        }
    }

    private static void save() throws IOException {
        Writer w = new OutputStreamWriter(new FileOutputStream(file, false), "UTF-8");
        try {
            for (Point p : points) w.write(p.csv() + "\n");
        } finally {
            w.close();
        }
    }

    public static synchronized void clear() {
        points.clear();
        version++;
        if (file != null) file.delete();
    }

    public static synchronized List<Point> all() {
        return new ArrayList<Point>(points);
    }

    public static int version() {
        return version;
    }

    // ---- taking positions in ----

    static void onLocation(RadioEngine e, RadioEngine.Station s, InetAddress from, int pktHops) {
        if (!recording() || e.isMonitor() || s.echo || s.name.equalsIgnoreCase(EchoTest.name())) return;
        if (!MeshTest.isPublic(e) || MeshTest.allowPublic("map")) {
            synchronized (lastFix) { // positions are often sent twice (and meshes re-flood): one point per fix
                double[] was = lastFix.get(s.id);
                if (was != null && was[0] == s.lat && was[1] == s.lon && Math.abs(was[2] - s.fixTimeMs) < 5000) return;
                lastFix.put(s.id, new double[]{s.lat, s.lon, s.fixTimeMs});
            }
            int hops = MeshTest.hops(e, from, pktHops);
            if (hops == MeshTest.HOPS_RELAY) {
                Log.i(TAG, "not recorded: " + s.name + " came through a relay / the internet");
                return;
            }
            MeshTest.Link l = MeshTest.link(e, s.id);
            long now = System.currentTimeMillis();
            Point p = new Point();
            p.t = now;
            p.name = s.name;
            p.chan = MeshTest.label(e);
            p.lat = s.lat;
            p.lon = s.lon;
            p.acc = s.accuracy;
            p.hops = hops;
            double loss = l.loss(now);
            p.lossPm = loss < 0 ? -1 : (int) Math.round(loss * 1000);
            p.jitterMs = l.jitterMs(now);
            p.grade = MeshTest.grade(loss, p.jitterMs);
            p.src = SRC_POSITION;
            add(p);
        }
    }

    // ---- drawing ----

    public static final int H_DIRECT = 1, H_TWO = 2, H_THREE = 4, H_UNKNOWN = 8;

    static int hopsBit(int h) {
        return h == 1 || h == 0 ? H_DIRECT : h == 2 ? H_TWO : h >= 3 ? H_THREE : H_UNKNOWN;
    }

    public static int signalGrade(int dbm) {
        if (dbm == Integer.MIN_VALUE) return -1;
        return dbm >= -70 ? 0 : dbm >= -85 ? 1 : 2;
    }

    /** Dots for a filter: callsign (null = all), since (0 = all time), hop kinds (H_* bits). Fixed station on top. */
    public static List<TileMapView.Dot> dots(String name, long since, int hopsMask, boolean bySignal) {
        List<TileMapView.Dot> out = new ArrayList<TileMapView.Dot>();
        for (Point p : all()) {
            if (since > 0 && p.t < since) continue;
            if (name != null && !name.equals(p.name)) continue;
            if ((hopsBit(p.hops) & hopsMask) == 0) continue;
            int g = bySignal ? signalGrade(p.signalDbm) : p.grade;
            int c = MeshTest.gradeColour(g);
            int style = TileMapView.Dot.FILLED;
            if (p.hops == 2) {
                c = 0xff1e88e5;
                style = TileMapView.Dot.RING;
            } else if (p.hops >= 3) {
                c = 0xff8e24aa;
                style = TileMapView.Dot.RING;
            } else if (p.hops < 0) {
                style = TileMapView.Dot.SQUARE;
            }
            out.add(new TileMapView.Dot(p.lat, p.lon, c, style).tag(p));
        }
        double[] f = fixedPoint();
        if (f != null) out.add(new TileMapView.Dot(f[0], f[1], 0xff263238, TileMapView.Dot.STATION));
        return out;
    }

    private static List<TileMapView.Dot> mainCache;
    private static int mainVersion = -1;

    /** For the full map (Map mesh layer ticked): every point, cached until something changes. */
    public static synchronized List<TileMapView.Dot> mainMapDots() {
        if (!showOnMainMap()) return null;
        int v = version * 31 + (colourBySignal() ? 1 : 0) + MeshWalk.trailVersion() * 7;
        if (v != mainVersion) {
            mainCache = dots(null, 0, 15, colourBySignal());
            mainCache.addAll(MeshWalk.trailDots());
            mainVersion = v;
        }
        return mainCache;
    }

    // ---- export ----

    private static String iso(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(t));
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Writes the points (filtered list) as CSV or GPX into the Received folder; returns the file. */
    public static File export(Context c, List<Point> list, boolean gpx, String prefix) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File f = new File(RadioService.receivedDir(c), prefix + "-" + stamp + (gpx ? ".gpx" : ".csv"));
        Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        try {
            if (gpx) {
                w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"YCC Map mesh\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
                double[] fx = fixedPoint();
                if (fx != null) w.write(String.format(Locale.US, "<wpt lat=\"%.6f\" lon=\"%.6f\"><name>Fixed station</name></wpt>\n", fx[0], fx[1]));
                for (Point p : list) {
                    w.write(String.format(Locale.US, "<wpt lat=\"%.6f\" lon=\"%.6f\"><time>%s</time><name>%s</name><desc>%s</desc></wpt>\n",
                            p.lat, p.lon, iso(p.t), xml(p.name), xml(describe(p))));
                }
                w.write("</gpx>\n");
            } else {
                w.write("time_utc,callsign,channel,lat,lon,accuracy_m,hops,loss_permille,jitter_ms,quality,signal_dbm,throughput_kbps,source,node,status_colour\n");
                for (Point p : list) {
                    w.write(iso(p.t) + "," + clean(p.name) + "," + clean(p.chan) + "," + String.format(Locale.US, "%.6f,%.6f,%.0f", p.lat, p.lon, p.acc)
                            + "," + (p.hops == MeshTest.HOPS_UNKNOWN ? "unknown" : String.valueOf(p.hops)) + "," + (p.lossPm < 0 ? "" : p.lossPm)
                            + "," + (p.jitterMs < 0 ? "" : p.jitterMs) + "," + MeshTest.gradeWord(p.grade) + ","
                            + (p.signalDbm == Integer.MIN_VALUE ? "" : p.signalDbm) + "," + (p.kbps < 0 ? "" : p.kbps) + ","
                            + (p.src == SRC_PROBE ? "probe" : "position") + "," + clean(p.node) + "," + statusColour(p) + "\n");
                }
            }
        } finally {
            w.close();
        }
        return f;
    }

    /** The walk-style status colour of a point: red never (no answer means no point), amber poor, blue 2+ hops, else green. */
    public static String statusColour(Point p) {
        return p.grade == 2 ? "amber" : p.hops >= 2 ? "blue" : "green";
    }

    public static String describe(Point p) {
        return statusColour(p) + ": " + MeshTest.hopsWord(p.hops) + ", " + MeshTest.gradeWord(p.grade)
                + (p.lossPm >= 0 ? ", loss " + (p.lossPm / 10.0) + "%" : "") + (p.jitterMs >= 0 ? ", jitter " + p.jitterMs + " ms" : "")
                + (p.signalDbm != Integer.MIN_VALUE ? ", signal " + p.signalDbm + " dBm" : "") + (p.kbps >= 0 ? ", " + p.kbps + " kbit/s" : "")
                + (p.node.length() > 0 ? ", node " + p.node : "") + ", " + p.chan + (p.src == SRC_PROBE ? ", walk probe" : "");
    }
}
