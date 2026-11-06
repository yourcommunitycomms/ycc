package org.communitycomms.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline routes on this phone: the last ones planned (kept automatically) and ones saved for later,
 * each with its via points and closed roads; and every closed-road marker ever set or received, with
 * when, so a later route through one can ask "closed X ago: avoid it or ignore it?".
 */
public final class RouteStore {
    private RouteStore() {
    }

    private static final int RECENT = 10;

    public static final class Saved {
        public String name = "", dest = "";
        public long time;
        public boolean kept;
        public int mode;
        /** Start, via points, destination: lat, lon pairs. */
        public double[] pts = new double[0];
        /** Closed roads: lat, lon pairs. */
        public double[] closed = new double[0];
    }

    public static final class Closed {
        public double lat, lon;
        public long time;
        public String by = "";
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("routes", Context.MODE_PRIVATE);
    }

    private static JSONArray arr(double[] a) throws JSONException {
        JSONArray j = new JSONArray();
        for (double d : a) j.put(d);
        return j;
    }

    private static double[] dbl(JSONArray j) {
        if (j == null) return new double[0];
        double[] a = new double[j.length()];
        for (int i = 0; i < a.length; i++) a[i] = j.optDouble(i);
        return a;
    }

    public static List<Saved> all(Context c) {
        List<Saved> out = new ArrayList<Saved>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("routes", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Saved s = new Saved();
                s.name = o.optString("name");
                s.dest = o.optString("dest");
                s.time = o.optLong("time");
                s.kept = o.optBoolean("kept");
                s.mode = o.optInt("mode");
                s.pts = dbl(o.optJSONArray("pts"));
                s.closed = dbl(o.optJSONArray("closed"));
                if (s.pts.length >= 4) out.add(s);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private static void write(Context c, List<Saved> l) {
        JSONArray a = new JSONArray();
        try {
            for (Saved s : l) {
                a.put(new JSONObject().put("name", s.name).put("dest", s.dest).put("time", s.time).put("kept", s.kept)
                        .put("mode", s.mode).put("pts", arr(s.pts)).put("closed", arr(s.closed)));
            }
        } catch (JSONException ignored) {
        }
        sp(c).edit().putString("routes", a.toString()).commit();
    }

    private static boolean same(Saved a, Saved b) {
        return a.mode == b.mode && java.util.Arrays.equals(a.pts, b.pts) && java.util.Arrays.equals(a.closed, b.closed);
    }

    /** A planned route: kept among the recent ones (a repeat moves to the top); kept = saved for later. */
    public static void remember(Context c, Saved s) {
        List<Saved> l = all(c);
        for (int i = l.size() - 1; i >= 0; i--) {
            if (same(l.get(i), s)) {
                s.kept |= l.get(i).kept;
                if (s.name.length() == 0) s.name = l.get(i).name;
                l.remove(i);
            }
        }
        l.add(0, s);
        int recent = 0;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).kept) continue;
            if (++recent > RECENT) l.remove(i--);
        }
        write(c, l);
    }

    public static void delete(Context c, Saved s) {
        List<Saved> l = all(c);
        for (int i = l.size() - 1; i >= 0; i--) if (same(l.get(i), s) && l.get(i).time == s.time) l.remove(i);
        write(c, l);
    }

    // ---- closed roads remembered ----

    public static List<Closed> closed(Context c) {
        List<Closed> out = new ArrayList<Closed>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("closed", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Closed k = new Closed();
                k.lat = o.optDouble("lat");
                k.lon = o.optDouble("lon");
                k.time = o.optLong("time");
                k.by = o.optString("by");
                out.add(k);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private static void writeClosed(Context c, List<Closed> l) {
        JSONArray a = new JSONArray();
        try {
            for (Closed k : l) a.put(new JSONObject().put("lat", k.lat).put("lon", k.lon).put("time", k.time).put("by", k.by));
        } catch (JSONException ignored) {
        }
        sp(c).edit().putString("closed", a.toString()).commit();
    }

    /** A closed road set here or in a route received; the same spot (within 25 m) just gets the newer time. */
    public static void addClosed(Context c, double lat, double lon, long time, String by) {
        List<Closed> l = closed(c);
        for (int i = l.size() - 1; i >= 0; i--) {
            Closed k = l.get(i);
            if (org.communitycomms.app.geo.Geo.distanceM(k.lat, k.lon, lat, lon) < 25) {
                if (k.time >= time) return;
                l.remove(i);
            }
        }
        Closed k = new Closed();
        k.lat = lat;
        k.lon = lon;
        k.time = time;
        k.by = by == null ? "" : by;
        l.add(k);
        while (l.size() > 200) l.remove(0);
        writeClosed(c, l);
    }

    public static void forgetClosed(Context c, List<Closed> gone) {
        List<Closed> l = closed(c);
        for (Closed g : gone) {
            for (int i = l.size() - 1; i >= 0; i--) {
                if (l.get(i).lat == g.lat && l.get(i).lon == g.lon) l.remove(i);
            }
        }
        writeClosed(c, l);
    }

    public static void forgetAllClosed(Context c) {
        sp(c).edit().remove("closed").commit();
    }

    /** Remembered closed roads within 25 m of the route (lat, lon pairs), not counting those in skip. */
    public static List<Closed> onRoute(Context c, double[] route, List<double[]> skip) {
        List<Closed> out = new ArrayList<Closed>();
        for (Closed k : closed(c)) {
            boolean already = false;
            for (double[] s : skip) if (org.communitycomms.app.geo.Geo.distanceM(s[0], s[1], k.lat, k.lon) < 25) already = true;
            if (already) continue;
            double kx = 111320 * Math.cos(Math.toRadians(k.lat)), ky = 110540;
            for (int i = 0; i + 3 < route.length; i += 2) {
                double ax = (route[i + 1] - k.lon) * kx, ay = (route[i] - k.lat) * ky;
                double bx = (route[i + 3] - k.lon) * kx, by = (route[i + 2] - k.lat) * ky;
                double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
                double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l2));
                double px = ax + t * dx, py = ay + t * dy;
                if (Math.sqrt(px * px + py * py) <= 25) {
                    out.add(k);
                    break;
                }
            }
        }
        return out;
    }

    /** "3 hours ago", "2 days ago". */
    public static String ago(long time) {
        long m = Math.max(0, (System.currentTimeMillis() - time) / 60000);
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        if (m < 48 * 60) return (m / 60) + " hour" + (m / 60 == 1 ? "" : "s") + " ago";
        return (m / 1440) + " days ago";
    }
}
