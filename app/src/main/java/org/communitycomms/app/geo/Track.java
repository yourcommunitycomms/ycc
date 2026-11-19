package org.communitycomms.app.geo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * One recorded path (Map > Tools > Start tracking my path), kept only on this phone in
 * files/tracks/<start>.trk and erased by the data wipe. A plain text file, one line per event, so a
 * track survives the app being killed mid-way (each line is appended as it happens):
 * <pre>
 * H,start ms,mode          header
 * p,t,lat,lon,accuracy     a position
 * P,t / R,t                pause / resume (a break)
 * L,t                      a lap (crossed the start point again)
 * S,t,steps                steps counted since the start
 * E,t                      stopped
 * </pre>
 * Pure Java (no Android) apart from the folder, so the stats and the lap logic can be tested on a PC.
 */
public final class Track {
    /** Tracking speeds: how often a position is taken. */
    public static final int MODE_LOW = 0, MODE_WALK = 1, MODE_RUN = 2, MODE_RIDE = 3, MODE_DRIVE = 4;
    public static final String[] MODE_NAMES = {"Low (every 3 minutes, saves battery)", "Walking (every 10 s)",
            "Running (every 5 s)", "Riding (every 3 s)", "Driving (every 2 s)"};
    public static final String[] MODE_WORDS = {"low power", "walking", "running", "riding", "driving"};
    private static final long[] MODE_MS = {180000, 10000, 5000, 3000, 2000};

    public static long intervalMs(int mode) {
        return MODE_MS[Math.max(0, Math.min(MODE_MS.length - 1, mode))];
    }

    /** Fixes less accurate than this are not recorded (low power gets more slack: the GPS has little time to settle). */
    public static float maxAccuracyM(int mode) {
        return mode == MODE_LOW ? 100 : 50;
    }

    public static final class Point {
        public final long t;
        public final double lat, lon;
        public final float acc;
        /** First point after a start or a resume: no line is drawn to it from the point before. */
        public final boolean newSegment;

        public Point(long t, double lat, double lon, float acc, boolean newSegment) {
            this.t = t;
            this.lat = lat;
            this.lon = lon;
            this.acc = acc;
            this.newSegment = newSegment;
        }
    }

    public final long start;
    public final int mode;
    public final File file;
    public final List<Point> points = new ArrayList<Point>();
    /** Pause and resume times, in order: pauses[i] .. resumes[i] (the last pause may be open). */
    public final List<Long> pauses = new ArrayList<Long>(), resumes = new ArrayList<Long>(), laps = new ArrayList<Long>();
    public long end; // 0 while running
    public int steps = -1; // -1: not counted
    private boolean segmentBreak = true;
    private Writer out;

    private Track(File file, long start, int mode) {
        this.file = file;
        this.start = start;
        this.mode = mode;
    }

    public static File dir(File filesDir) {
        return new File(filesDir, "tracks");
    }

    /** A new track, its header written straight away. */
    public static Track create(File filesDir, long now, int mode) throws IOException {
        File d = dir(filesDir);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("Cannot make " + d);
        Track t = new Track(new File(d, now + ".trk"), now, mode);
        t.line("H," + now + "," + mode);
        return t;
    }

    public synchronized boolean paused() {
        return pauses.size() > resumes.size();
    }

    public synchronized boolean running() {
        return end == 0;
    }

    public synchronized void addPoint(long t, double lat, double lon, float acc) {
        Point p = new Point(t, lat, lon, acc, segmentBreak);
        segmentBreak = false;
        points.add(p);
        line(String.format(Locale.US, "p,%d,%.6f,%.6f,%.0f", t, lat, lon, acc));
    }

    public synchronized void pause(long t) {
        if (paused() || !running()) return;
        pauses.add(t);
        segmentBreak = true;
        line("P," + t);
    }

    public synchronized void resume(long t) {
        if (!paused()) return;
        resumes.add(t);
        segmentBreak = true;
        line("R," + t);
    }

    public synchronized void lap(long t) {
        laps.add(t);
        line("L," + t);
    }

    public synchronized void steps(long t, int n) {
        if (n == steps) return;
        steps = n;
        line("S," + t + "," + n);
    }

    public synchronized void stop(long t) {
        if (!running()) return;
        if (paused()) resume(t);
        end = t;
        line("E," + t);
        close();
    }

    private void line(String s) {
        try {
            if (out == null) out = new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8");
            out.write(s);
            out.write('\n');
            out.flush();
        } catch (IOException ignored) {
            // full storage: the track keeps running in memory
        }
    }

    public synchronized void close() {
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }

    /** Reads a saved track (a track cut short by the app being killed reads as stopped at its last line). */
    public static Track read(File f) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            Track t = null;
            long last = 0;
            String s;
            while ((s = r.readLine()) != null) {
                String[] a = s.split(",");
                if (a.length < 2) continue;
                try {
                    long time = Long.parseLong(a[1]);
                    if (t == null) {
                        if (!"H".equals(a[0])) continue;
                        t = new Track(f, time, a.length > 2 ? Integer.parseInt(a[2]) : MODE_WALK);
                        last = time;
                        continue;
                    }
                    last = Math.max(last, time);
                    char k = a[0].charAt(0);
                    if (k == 'p' && a.length >= 5) {
                        t.points.add(new Point(time, Double.parseDouble(a[2]), Double.parseDouble(a[3]), Float.parseFloat(a[4]), t.segmentBreak));
                        t.segmentBreak = false;
                    } else if (k == 'P') {
                        t.pauses.add(time);
                        t.segmentBreak = true;
                    } else if (k == 'R') {
                        t.resumes.add(time);
                        t.segmentBreak = true;
                    } else if (k == 'L') {
                        t.laps.add(time);
                    } else if (k == 'S' && a.length >= 3) {
                        t.steps = Integer.parseInt(a[2]);
                    } else if (k == 'E') {
                        t.end = time;
                    }
                } catch (RuntimeException ignored) {
                    // a damaged line (cut off by power loss)
                }
            }
            if (t == null) throw new IOException("Not a track file");
            if (t.end == 0) t.end = last; // never stopped properly
            if (t.paused()) t.resumes.add(t.end);
            return t;
        } finally {
            r.close();
        }
    }

    /** Saved tracks, newest first. */
    public static List<File> saved(File filesDir) {
        File[] fs = dir(filesDir).listFiles();
        List<File> out = new ArrayList<File>();
        if (fs == null) return out;
        Arrays.sort(fs);
        for (int i = fs.length - 1; i >= 0; i--) if (fs[i].getName().endsWith(".trk")) out.add(fs[i]);
        return out;
    }

    // ---- stats ----

    public static final class Stats {
        public long total, breaks, moving, currentLap, fastestLap, slowestLap, lastLap, longestBreak;
        public int breakCount, lapCount, fastestLapNo, steps = -1;
        public double distanceM;
        public final List<Long> lapTimes = new ArrayList<Long>();
    }

    /** Break time inside [a, b] (now = end of an open pause). */
    private long breakBetween(long a, long b, long now) {
        long sum = 0;
        for (int i = 0; i < pauses.size(); i++) {
            long p = pauses.get(i), r = i < resumes.size() ? resumes.get(i) : now;
            long s = Math.max(a, p), e = Math.min(b, r);
            if (e > s) sum += e - s;
        }
        return sum;
    }

    /** Times, distance, breaks and laps. Lap times count moving time only (breaks are left out). */
    public synchronized Stats stats(long now) {
        Stats s = new Stats();
        long stop = end != 0 ? end : now;
        s.total = Math.max(0, stop - start);
        s.breaks = breakBetween(start, stop, stop);
        s.moving = s.total - s.breaks;
        s.breakCount = pauses.size();
        for (int i = 0; i < pauses.size(); i++) {
            long r = i < resumes.size() ? resumes.get(i) : stop;
            s.longestBreak = Math.max(s.longestBreak, r - pauses.get(i));
        }
        Point prev = null;
        for (Point p : points) {
            if (prev != null && !p.newSegment) s.distanceM += Geo.distanceM(prev.lat, prev.lon, p.lat, p.lon);
            prev = p;
        }
        long from = start;
        for (int i = 0; i < laps.size(); i++) {
            long l = laps.get(i);
            long lt = (l - from) - breakBetween(from, l, stop);
            s.lapTimes.add(lt);
            if (s.fastestLap == 0 || lt < s.fastestLap) {
                s.fastestLap = lt;
                s.fastestLapNo = i + 1;
            }
            s.slowestLap = Math.max(s.slowestLap, lt);
            s.lastLap = lt;
            from = l;
        }
        s.lapCount = laps.size();
        s.currentLap = Math.max(0, (stop - from) - breakBetween(from, stop, stop));
        s.steps = steps;
        return s;
    }

    public static String hms(long ms) {
        long sec = Math.max(0, ms / 1000);
        return sec >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
                : String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    /** "5:12 /km" (walking, running) or "12.3 km/h" (riding, driving); imperial when set. */
    public static String speed(double m, long ms, int mode) {
        if (m < 10 || ms < 1000) return "-";
        double kmh = m / 1000.0 / (ms / 3600000.0);
        if (Geo.imperial) {
            double mph = kmh / 1.609344;
            if (mode == MODE_WALK || mode == MODE_RUN) return hms((long) (3600000.0 / mph)) + " /mi";
            return String.format(Locale.US, "%.1f mph", mph);
        }
        if (mode == MODE_WALK || mode == MODE_RUN) return hms((long) (3600000.0 / kmh)) + " /km";
        return String.format(Locale.US, "%.1f km/h", kmh);
    }

    // ---- laps: passing the start point again, the same way round, with hysteresis ----

    /**
     * Laps: the first good fix is the start, and the way you head off from it (the bearing from the start to
     * where you are once 20 m away) is the lap direction. A lap counts when the path comes back within NEAR
     * metres of the start, but only after going more than ARM metres away (standing at the start, or GPS
     * wander there, never counts) and at least MIN_LAP_MS after the last lap, and only when you then head
     * off the same way as at the start (within 75 degrees, checked once 30 m away). So an out-and-back that
     * passes the start going the other way is not a lap, but a loop with the start on a corner is.
     * The lap time is when you passed the start. The whole step between two fixes is checked, so a car
     * passing the start between fixes still counts. Stopping at the start counts the lap if you arrived
     * heading the lap direction.
     */
    public static final class LapDetector {
        public static final double ARM_M = 60, NEAR_M = 25, DIR_M = 20, LEAVE_M = 30, DIR_TOLERANCE = 75;
        public static final long MIN_LAP_MS = 20000;
        public double startLat = Double.NaN, startLon;
        /** The way you headed off from the start (degrees), NaN until 20 m away. */
        public double startBearing = Double.NaN;
        private boolean armed;
        private long lastLap;
        private double prevLat = Double.NaN, prevLon;
        /** Passed the start: the lap waits until the way you head off is known. */
        private long pendingAt;
        private boolean pendingArrivedRightWay;

        public LapDetector(long started) {
            lastLap = started;
        }

        public void setStart(double lat, double lon) {
            startLat = lat;
            startLon = lon;
        }

        private static double angle(double a, double b) {
            double d = Math.abs(a - b) % 360;
            return d > 180 ? 360 - d : d;
        }

        /** Feed each recorded fix; returns the lap's time (when the start was passed), or 0. */
        public long fix(long t, double lat, double lon, float acc) {
            if (Double.isNaN(startLat)) {
                setStart(lat, lon);
                prevLat = lat;
                prevLon = lon;
                return 0;
            }
            double near = Math.max(NEAR_M, Math.min(acc, 40));
            double d = Geo.distanceM(startLat, startLon, lat, lon);
            if (Double.isNaN(startBearing) && d >= Math.max(DIR_M, near)) startBearing = Geo.bearing(startLat, startLon, lat, lon);
            long lap = 0;
            if (pendingAt != 0) {
                // Passed the start: which way are you heading off?
                if (d >= Math.max(LEAVE_M, near + 5)) {
                    boolean same = Double.isNaN(startBearing)
                            || angle(Geo.bearing(startLat, startLon, lat, lon), startBearing) <= DIR_TOLERANCE;
                    if (same) {
                        lap = pendingAt;
                        lastLap = pendingAt;
                    }
                    pendingAt = 0;
                }
            } else if (armed && t - lastLap >= MIN_LAP_MS) {
                double closest = Double.isNaN(prevLat) ? d : segmentDistance(prevLat, prevLon, lat, lon);
                if (closest <= near) {
                    pendingAt = t;
                    armed = false;
                    pendingArrivedRightWay = Double.isNaN(startBearing) || Double.isNaN(prevLat)
                            || angle(Geo.bearing(prevLat, prevLon, lat, lon), startBearing) <= 90;
                }
            }
            if (!armed && pendingAt == 0 && d > Math.max(ARM_M, near * 2)) armed = true;
            prevLat = lat;
            prevLon = lon;
            return lap;
        }

        /** Stopping: a pass of the start still waiting counts if you arrived heading the lap direction. Its time, or 0. */
        public long finish() {
            long p = pendingAt;
            pendingAt = 0;
            return p != 0 && pendingArrivedRightWay ? p : 0;
        }

        /** After a pause: no line from the old spot, so the step across the break is not checked. */
        public void breakSegment() {
            prevLat = Double.NaN;
        }

        /** Closest distance from the start to the step a→b (flat earth: fine at these distances). */
        private double segmentDistance(double aLat, double aLon, double bLat, double bLon) {
            double k = Math.cos(Math.toRadians(startLat)) * 111320.0;
            double ax = (aLon - startLon) * k, ay = (aLat - startLat) * 110540.0;
            double bx = (bLon - startLon) * k, by = (bLat - startLat) * 110540.0;
            double dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
            double u = len == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len));
            double px = ax + u * dx, py = ay + u * dy;
            return Math.sqrt(px * px + py * py);
        }
    }
}