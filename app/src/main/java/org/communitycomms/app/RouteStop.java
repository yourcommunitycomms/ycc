package org.communitycomms.app;

import java.util.ArrayList;
import java.util.List;

/**
 * A point a route goes through, in order between the start and the destination:
 * a via point (just shapes the route, a small dot), a waypoint (a named point, shown and announced) or
 * a planned rest / meeting stop (named, with an optional time and length, shown and announced).
 * Each can have a note (e.g. "meet here 10am"), shared with the route.
 */
public final class RouteStop {
    public static final int VIA = 0, WAYPOINT = 1, REST = 2;
    public static final String[] KIND_NAMES = {"Via point", "Waypoint", "Rest / meeting stop"};

    public double lat, lon;
    /** When it was added (ms). */
    public long t;
    public int kind;
    public String name = "", note = "", when = "";
    /** Planned stop length in minutes (rest stops), 0 = not given. */
    public int mins;

    public RouteStop(double lat, double lon, int kind) {
        this.lat = lat;
        this.lon = lon;
        this.kind = kind;
        t = System.currentTimeMillis();
    }

    public RouteStop copy() {
        RouteStop s = new RouteStop(lat, lon, kind);
        s.t = t;
        s.name = name;
        s.note = note;
        s.when = when;
        s.mins = mins;
        return s;
    }

    /** "Rest / meeting stop: Servo, 10:00, 30 min" (no note). */
    public String title() {
        StringBuilder sb = new StringBuilder(KIND_NAMES[kind]);
        if (name.length() > 0) sb.append(": ").append(name);
        if (kind == REST && when.length() > 0) sb.append(", ").append(when);
        if (kind == REST && mins > 0) sb.append(", ").append(mins).append(" min");
        return sb.toString();
    }

    /** The label beside it on the map: its name (and time). Via points have none. */
    public String mapLabel() {
        if (kind == VIA) return "";
        String s = name.length() > 0 ? name : kind == REST ? "Stop" : "Waypoint";
        if (kind == REST && when.length() > 0) s += " " + when;
        return s;
    }

    public static double[] flat(List<RouteStop> l) {
        if (l == null || l.isEmpty()) return null;
        double[] out = new double[l.size() * 2];
        for (int i = 0; i < l.size(); i++) {
            out[i * 2] = l.get(i).lat;
            out[i * 2 + 1] = l.get(i).lon;
        }
        return out;
    }

    public static int[] kinds(List<RouteStop> l) {
        int[] k = new int[l.size()];
        for (int i = 0; i < k.length; i++) k[i] = l.get(i).kind;
        return k;
    }

    public static String[] labels(List<RouteStop> l) {
        String[] s = new String[l.size()];
        for (int i = 0; i < s.length; i++) s[i] = l.get(i).mapLabel();
        return s;
    }

    public static int count(List<RouteStop> l, int kind) {
        int n = 0;
        for (RouteStop s : l) if (s.kind == kind) n++;
        return n;
    }

    /** "via 2, 1 waypoint, 1 stop" (empty when none). */
    public static String summary(List<RouteStop> l) {
        List<String> parts = new ArrayList<String>();
        int v = count(l, VIA), w = count(l, WAYPOINT), r = count(l, REST);
        if (v > 0) parts.add("via " + v);
        if (w > 0) parts.add(w + (w == 1 ? " waypoint" : " waypoints"));
        if (r > 0) parts.add(r + (r == 1 ? " stop" : " stops"));
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(sb.length() > 0 ? ", " : "").append(p);
        return sb.toString();
    }

    /**
     * Where a new point goes in the order: the place that adds the least straight-line distance between
     * start, the points already there and the destination.
     */
    public static int bestIndex(List<RouteStop> l, double startLat, double startLon, double endLat, double endLon, double lat, double lon) {
        int best = l.size();
        double bestAdd = Double.MAX_VALUE;
        for (int i = 0; i <= l.size(); i++) {
            double aLat = i == 0 ? startLat : l.get(i - 1).lat, aLon = i == 0 ? startLon : l.get(i - 1).lon;
            double bLat = i == l.size() ? endLat : l.get(i).lat, bLon = i == l.size() ? endLon : l.get(i).lon;
            double add = org.communitycomms.app.geo.Geo.distanceM(aLat, aLon, lat, lon) + org.communitycomms.app.geo.Geo.distanceM(lat, lon, bLat, bLon)
                    - org.communitycomms.app.geo.Geo.distanceM(aLat, aLon, bLat, bLon);
            if (add < bestAdd - 0.5) {
                bestAdd = add;
                best = i;
            }
        }
        return best;
    }

    // ---- text for the shared route line (no ; | = or new lines inside) ----

    static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    static String dec(String s) {
        try {
            return java.net.URLDecoder.decode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    static String clip(String s, int max) {
        s = s == null ? "" : s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }
}
