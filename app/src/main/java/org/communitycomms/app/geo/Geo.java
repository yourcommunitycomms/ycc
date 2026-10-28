package org.communitycomms.app.geo;

import java.util.Locale;

/** Small offline geo helpers: distance, bearing, compass words, MGRS-free plain formats. */
public final class Geo {
    private Geo() {
    }

    private static final double R = 6371008.8;

    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Initial bearing in degrees, 0 = north, clockwise. */
    public static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** The point reached going distM metres from (lat, lon) on a bearing: {lat, lon}. */
    public static double[] destination(double lat, double lon, double bearingDeg, double distM) {
        double d = distM / R, b = Math.toRadians(bearingDeg);
        double p1 = Math.toRadians(lat), l1 = Math.toRadians(lon);
        double p2 = Math.asin(Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(b));
        double l2 = l1 + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(p1), Math.cos(d) - Math.sin(p1) * Math.sin(p2));
        return new double[]{Math.toDegrees(p2), (Math.toDegrees(l2) + 540) % 360 - 180};
    }

    private static final String[] POINTS ={"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    public static String compass(double deg) {
        return POINTS[(int) Math.round(deg / 45) % 8];
    }

    public static String arrow(double deg) {
        return ARROWS[(int) Math.round(deg / 45) % 8];
    }

    /** Settings (and the wizard) > Units: false = metric (the default), true = imperial (feet and miles). */
    public static volatile boolean imperial;

    public static void loadUnits(android.content.Context c) {
        imperial = c.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getBoolean("imperial", false);
    }

    public static void setImperial(android.content.Context c, boolean on) {
        imperial = on;
        c.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("imperial", on).commit();
    }

    public static String distance(double m) {
        if (imperial) {
            double ft = m / 0.3048, mi = m / 1609.344;
            if (mi < 0.1) return Math.round(ft) + " ft";
            return String.format(Locale.US, mi < 10 ? "%.2f mi" : "%.1f mi", mi);
        }
        if (m < 1000) return Math.round(m) + " m";
        return String.format(Locale.US, m < 10000 ? "%.2f km" : "%.1f km", m / 1000);
    }

    /** A range such as the Obscure location one: "2.0 km" or "1.2 mi". */
    public static String range(double m) {
        return imperial ? String.format(Locale.US, "%.1f mi", m / 1609.344) : String.format(Locale.US, "%.1f km", m / 1000);
    }

    /** Read aloud: "120 metres", "2.3 kilometres", "400 feet", "1.4 miles". */
    public static String spoken(double m) {
        if (imperial) {
            double mi = m / 1609.344;
            return mi < 0.1 ? Math.round(m / 0.3048 / 10) * 10 + " feet" : String.format(Locale.US, "%.1f miles", mi);
        }
        return m < 1000 ? Math.round(m / 10) * 10 + " metres" : String.format(Locale.US, "%.1f kilometres", m / 1000);
    }

    public static String latLon(double lat, double lon) {
        return String.format(Locale.US, "%.5f, %.5f", lat, lon);
    }

    public static String age(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        if (s < 86400) return (s / 3600) + "h " + (s % 3600 / 60) + "m ago";
        return (s / 86400) + "d ago";
    }

    // Web Mercator tile maths (same numbering as Google / OSM / ATAK imagery folders).

    public static double lonToX(double lon, int z) {
        return (lon + 180) / 360 * (1 << z);
    }

    public static double latToY(double lat, int z) {
        double r = Math.toRadians(Math.max(-85.0511, Math.min(85.0511, lat)));
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << z);
    }

    public static double xToLon(double x, int z) {
        return x / (1 << z) * 360 - 180;
    }

    public static double yToLat(double y, int z) {
        double n = Math.PI - 2 * Math.PI * y / (1 << z);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }
}
