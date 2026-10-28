package org.communitycomms.app.geo;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Fake GPS (testing and demos, Settings): every position this phone uses or sends is a spot picked
 * at random within 2 km of a chosen centre, the same spot each time until the centre changes.
 * Maps label it "Me (Fake)". On by default for now.
 */
public final class FakeGps {
    private FakeGps() {
    }

    public static final String PROVIDER = "fake";
    public static final String DEFAULT_CENTRE = "-37.94501, 145.29838";
    /** Settings > Obscure location: 0 to 10 km, 2 km by default (also the fake spot's distance from its centre). */
    public static final int OBSCURE_MAX_M = 10000, OBSCURE_DEFAULT_M = 2000;
    private static volatile Context app;

    /** Called once at app start (CommsApp) so code without a Context can ask. */
    public static void init(Context c) {
        app = c.getApplicationContext();
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return c != null && sp(c).getBoolean("fakeGps", true);
    }

    public static String centre(Context c) {
        return sp(c).getString("fakeGpsCentre", DEFAULT_CENTRE);
    }

    /** Returns false if the text is not "lat, lon". */
    public static boolean set(Context c, boolean on, String centre) {
        double[] p = parse(centre);
        if (p == null) return false;
        sp(c).edit().putBoolean("fakeGps", on)
                .putString("fakeGpsCentre", String.format(Locale.US, "%.5f, %.5f", p[0], p[1])).commit();
        return true;
    }

    static double[] parse(String s) {
        if (s == null) return null;
        String[] f = s.trim().split("[,\\s]+");
        if (f.length != 2) return null;
        try {
            double lat = Double.parseDouble(f[0]), lon = Double.parseDouble(f[1]);
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
            return new double[]{lat, lon};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The fake fix, or null when fake GPS is off. */
    public static Location location(Context c) {
        if (!enabled(c)) return null;
        double[] centre = parse(centre(c));
        if (centre == null) return null;
        SharedPreferences s = sp(c);
        String key = centre(c) + "|" + obscureM(c);
        if (!key.equals(s.getString("fakeGpsFor", "")) || !s.contains("fakeGpsLat")) {
            // A new random spot, evenly spread over the Obscure location circle round the centre.
            SecureRandom r = new SecureRandom();
            double dist = obscureM(c) * Math.sqrt(r.nextDouble()), brg = 360 * r.nextDouble();
            double[] p = Geo.destination(centre[0], centre[1], brg, dist);
            s.edit().putString("fakeGpsFor", key).putString("fakeGpsLat", Double.toString(p[0]))
                    .putString("fakeGpsLon", Double.toString(p[1])).commit();
        }
        Location l = new Location(PROVIDER);
        l.setLatitude(Double.parseDouble(s.getString("fakeGpsLat", "0")));
        l.setLongitude(Double.parseDouble(s.getString("fakeGpsLon", "0")));
        l.setAccuracy(10);
        l.setTime(System.currentTimeMillis());
        return l;
    }

    /** "+ Fake being here": move my fake spot to this point (kept until the centre changes). */
    public static void setHere(Context c, double lat, double lon) {
        sp(c).edit().putString("fakeGpsFor", centre(c) + "|" + obscureM(c)).putString("fakeGpsLat", Double.toString(lat))
                .putString("fakeGpsLon", Double.toString(lon)).commit();
    }

    public static int obscureM(Context c) {
        return c == null ? OBSCURE_DEFAULT_M : Math.max(0, Math.min(OBSCURE_MAX_M, sp(c).getInt("obscureM", OBSCURE_DEFAULT_M)));
    }

    public static void setObscureM(Context c, int m) {
        sp(c).edit().putInt("obscureM", Math.max(0, Math.min(OBSCURE_MAX_M, m))).commit();
    }

    /**
     * What others get for a real position: the same random offset each time (up to the Obscure
     * location range; a new one when the range changes), with the accuracy widened to the range so maps
     * show it is approximate. Range 0, or fake GPS on (already a made-up spot), sends it as it is.
     * Returns {lat, lon, accuracy}.
     */
    public static double[] obscure(Context c, double lat, double lon, double acc) {
        int m = obscureM(c);
        if (c == null || m == 0 || enabled(c)) return new double[]{lat, lon, acc};
        SharedPreferences s = sp(c);
        if (s.getInt("obscureFor", -1) != m || !s.contains("obscureBrg")) {
            SecureRandom r = new SecureRandom();
            s.edit().putInt("obscureFor", m).putFloat("obscureBrg", (float) (360 * r.nextDouble()))
                    .putFloat("obscureDist", (float) (m * Math.sqrt(r.nextDouble()))).commit();
        }
        double[] p = Geo.destination(lat, lon, s.getFloat("obscureBrg", 0), s.getFloat("obscureDist", 0));
        return new double[]{p[0], p[1], Math.max(acc, m)};
    }

    public static double[] obscure(double lat, double lon, double acc) {
        return obscure(app, lat, lon, acc);
    }

    /** For code with no Context to hand (after init). */
    public static Location location() {
        return location(app);
    }

    public static boolean isFake(Location l) {
        return l != null && PROVIDER.equals(l.getProvider());
    }
}
