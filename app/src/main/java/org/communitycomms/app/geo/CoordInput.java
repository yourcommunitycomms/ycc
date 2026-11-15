package org.communitycomms.app.geo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A typed position (plain Java, testable on a PC): either GPS latitude / longitude (decimal degrees, or degrees
 * minutes seconds with N S E W) or a military grid reference as the map shows it (MGRS: "55H CU 12345 67890",
 * "CU 123 678", or just 6, 8 or 10 figures, which take the zone and 100 km square from a reference point,
 * usually the map's +). 6 / 8 / 10 figures = 100 m / 10 m / 1 m; the point goes in the middle of that square.
 */
public final class CoordInput {
    private CoordInput() {
    }

    /** What was typed, where it is, and how to label it. */
    public static final class Result {
        public double lat, lon;
        /** True for a grid reference, false for latitude / longitude. */
        public boolean grid;
        /** The grid reference as entered, written out in full (zone, square, figures), or the lat / lon. */
        public String text;
    }

    private static final String BANDS = "CDEFGHJKLMNPQRSTUVWX";
    private static final String[] COL_SETS = {"ABCDEFGH", "JKLMNPQR", "STUVWXYZ"};
    private static final String ROWS = "ABCDEFGHJKLMNPQRSTUV";
    private static final double A = 6378137.0, F = 1 / 298.257223563, K0 = 0.9996;
    private static final double E2 = F * (2 - F), EP2 = E2 / (1 - E2);

    private static final Pattern GRID = Pattern.compile(
            "^(?:(\\d{1,2})\\s*([C-HJ-NP-X])\\s*)?(?:([A-HJ-NP-Z])\\s*([A-HJ-NP-V])\\s*)?(\\d+)(?:\\s+(\\d+))?$");

    /**
     * Reads a typed position. refLat / refLon (the map's +) give the zone and square for a short grid
     * reference. Throws IllegalArgumentException with a message for people when it cannot be read.
     */
    public static Result parse(String typed, double refLat, double refLon) {
        String s = typed == null ? "" : typed.trim().toUpperCase(Locale.US);
        if (s.length() == 0) throw new IllegalArgumentException("Type a position first");
        Matcher m = GRID.matcher(s);
        if (m.matches()) {
            String d1 = m.group(5), d2 = m.group(6);
            String digits = d2 == null ? d1 : d1 + d2;
            if (d2 != null && d1.length() != d2.length()) throw new IllegalArgumentException("The easting and northing need the same number of figures");
            if (digits.length() == 6 || digits.length() == 8 || digits.length() == 10) return grid(m, digits, refLat, refLon);
            if (m.group(1) != null || m.group(3) != null) {
                throw new IllegalArgumentException("A grid reference needs 6, 8 or 10 figures (100 m, 10 m or 1 m)");
            }
            // otherwise it may be whole degrees: try latitude / longitude
        }
        return latLon(s);
    }

    // ---- grid reference ----

    private static Result grid(Matcher m, String digits, double refLat, double refLon) {
        if (m.group(1) != null && m.group(3) == null) throw new IllegalArgumentException("Add the two-letter 100 km square after the zone (e.g. 55H CU …)");
        String ref = Mgrs.of(refLat, refLon); // "55H CU 12345 67890"
        if (ref.length() == 0 && m.group(1) == null) throw new IllegalArgumentException("Type the zone and square too (e.g. 55H CU 123 678)");
        int zone;
        char band;
        if (m.group(1) != null) {
            zone = Integer.parseInt(m.group(1));
            band = m.group(2).charAt(0);
            if (zone < 1 || zone > 60) throw new IllegalArgumentException("The zone must be 1 to 60");
        } else {
            String zb = ref.substring(0, ref.indexOf(' '));
            zone = Integer.parseInt(zb.substring(0, zb.length() - 1));
            band = zb.charAt(zb.length() - 1);
        }
        char colL, rowL;
        if (m.group(3) != null) {
            colL = m.group(3).charAt(0);
            rowL = m.group(4).charAt(0);
        } else {
            String sq = ref.split(" ")[1];
            colL = sq.charAt(0);
            rowL = sq.charAt(1);
        }
        int half = digits.length() / 2;
        double unit = Math.pow(10, 5 - half);
        double e = Long.parseLong(digits.substring(0, half)) * unit + unit / 2; // middle of the square named
        double n = Long.parseLong(digits.substring(half)) * unit + unit / 2;

        int set = (zone - 1) % 3;
        int col = COL_SETS[set].indexOf(colL);
        if (col < 0) throw new IllegalArgumentException("Square " + colL + rowL + " is not in zone " + zone + " (the first letter is wrong)");
        double easting = (col + 1) * 100000.0 + e;
        int row = ROWS.indexOf(rowL);
        if (row < 0) throw new IllegalArgumentException("Square " + colL + rowL + " has a wrong second letter");
        if (zone % 2 == 0) row = (row - 5 + 20) % 20;
        double north100 = row * 100000.0 + n; // northing mod 2000 km
        int bandIdx = BANDS.indexOf(band);
        if (bandIdx < 0) throw new IllegalArgumentException("Band letter " + band + " is not a grid band");
        // Which 2000 km cycle: the one nearest the middle of the band (or the reference point's own northing).
        double approxLat = m.group(1) != null || ref.length() == 0 ? -80 + 8 * bandIdx + 4 : refLat;
        double lon0 = (zone - 1) * 6 - 180 + 3;
        double approxN = utm(approxLat, lon0, zone)[1];
        double best = north100, bestD = Double.MAX_VALUE;
        for (int k = 0; k <= 5; k++) {
            double cand = north100 + k * 2000000.0;
            if (Math.abs(cand - approxN) < bestD) {
                bestD = Math.abs(cand - approxN);
                best = cand;
            }
        }
        boolean south = band < 'N';
        double[] ll = toLatLon(zone, south, easting, best);
        Result r = new Result();
        r.lat = ll[0];
        r.lon = ll[1];
        r.grid = true;
        r.text = String.format(Locale.US, "%d%c %c%c %s %s", zone, band, colL, rowL, digits.substring(0, half), digits.substring(half));
        if (Math.abs(r.lat - (approxLat)) > 12 && m.group(1) != null) {
            throw new IllegalArgumentException("That square is not in band " + band);
        }
        return r;
    }

    /** UTM easting, northing (with the 10 000 km false northing in the south) in a given zone. */
    static double[] utm(double lat, double lon, int zone) {
        double lon0 = Math.toRadians((zone - 1) * 6 - 180 + 3);
        double phi = Math.toRadians(lat), lam = Math.toRadians(lon);
        double n = A / Math.sqrt(1 - E2 * Math.sin(phi) * Math.sin(phi));
        double t = Math.tan(phi) * Math.tan(phi);
        double c = EP2 * Math.cos(phi) * Math.cos(phi);
        double aa = Math.cos(phi) * (lam - lon0);
        double m = A * ((1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256) * phi
                - (3 * E2 / 8 + 3 * E2 * E2 / 32 + 45 * E2 * E2 * E2 / 1024) * Math.sin(2 * phi)
                + (15 * E2 * E2 / 256 + 45 * E2 * E2 * E2 / 1024) * Math.sin(4 * phi)
                - (35 * E2 * E2 * E2 / 3072) * Math.sin(6 * phi));
        double easting = K0 * n * (aa + (1 - t + c) * aa * aa * aa / 6
                + (5 - 18 * t + t * t + 72 * c - 58 * EP2) * Math.pow(aa, 5) / 120) + 500000.0;
        double northing = K0 * (m + n * Math.tan(phi) * (aa * aa / 2 + (5 - t + 9 * c + 4 * c * c) * Math.pow(aa, 4) / 24
                + (61 - 58 * t + t * t + 600 * c - 330 * EP2) * Math.pow(aa, 6) / 720));
        if (lat < 0) northing += 10000000.0;
        return new double[]{easting, northing};
    }

    /** UTM back to latitude / longitude (WGS84). */
    static double[] toLatLon(int zone, boolean south, double easting, double northing) {
        double x = easting - 500000.0, y = south ? northing - 10000000.0 : northing;
        double lon0 = Math.toRadians((zone - 1) * 6 - 180 + 3);
        double m = y / K0;
        double mu = m / (A * (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256));
        double e1 = (1 - Math.sqrt(1 - E2)) / (1 + Math.sqrt(1 - E2));
        double phi1 = mu + (3 * e1 / 2 - 27 * Math.pow(e1, 3) / 32) * Math.sin(2 * mu)
                + (21 * e1 * e1 / 16 - 55 * Math.pow(e1, 4) / 32) * Math.sin(4 * mu)
                + (151 * Math.pow(e1, 3) / 96) * Math.sin(6 * mu)
                + (1097 * Math.pow(e1, 4) / 512) * Math.sin(8 * mu);
        double n1 = A / Math.sqrt(1 - E2 * Math.sin(phi1) * Math.sin(phi1));
        double t1 = Math.tan(phi1) * Math.tan(phi1);
        double c1 = EP2 * Math.cos(phi1) * Math.cos(phi1);
        double r1 = A * (1 - E2) / Math.pow(1 - E2 * Math.sin(phi1) * Math.sin(phi1), 1.5);
        double d = x / (n1 * K0);
        double lat = phi1 - (n1 * Math.tan(phi1) / r1) * (d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * Math.pow(d, 4) / 24
                + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * Math.pow(d, 6) / 720);
        double lon = lon0 + (d - (1 + 2 * t1 + c1) * Math.pow(d, 3) / 6
                + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * Math.pow(d, 5) / 120) / Math.cos(phi1);
        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon)};
    }

    // ---- latitude / longitude ----

    private static final Pattern NUM = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private static Result latLon(String s) {
        // Hemisphere letters (N S E W) and the numbers, in order.
        List<Double> nums = new ArrayList<Double>();
        List<Integer> numAt = new ArrayList<Integer>();
        Matcher m = NUM.matcher(s);
        while (m.find()) {
            nums.add(Double.parseDouble(m.group()));
            numAt.add(m.start());
        }
        List<Character> hemi = new ArrayList<Character>();
        List<Integer> hemiAt = new ArrayList<Integer>();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == 'N' || ch == 'S' || ch == 'E' || ch == 'W') {
                hemi.add(ch);
                hemiAt.add(i);
            } else if (Character.isLetter(ch)) {
                throw new IllegalArgumentException("Not a position: use -37.81234, 144.96123 or 37°48'44\"S 144°57'40\"E, or a grid reference");
            }
        }
        if (nums.size() != 2 && nums.size() != 4 && nums.size() != 6) {
            throw new IllegalArgumentException("Type two numbers (latitude, longitude), e.g. -37.81234, 144.96123");
        }
        int per = nums.size() / 2;
        double[] v = new double[2];
        for (int g = 0; g < 2; g++) {
            double deg = nums.get(g * per), val = Math.abs(deg);
            if (per >= 2) {
                double min = nums.get(g * per + 1);
                if (min < 0 || min >= 60) throw new IllegalArgumentException("Minutes must be 0 to 59");
                val += min / 60;
            }
            if (per == 3) {
                double sec = nums.get(g * per + 2);
                if (sec < 0 || sec >= 60) throw new IllegalArgumentException("Seconds must be 0 to 59");
                val += sec / 3600;
            }
            v[g] = deg < 0 ? -val : val;
        }
        boolean swap = false;
        if (!hemi.isEmpty()) {
            if (hemi.size() != 2) throw new IllegalArgumentException("Give both hemispheres (N or S, and E or W), or none");
            // The letters go with the numbers in the same order (N37 E145 or 37S 145E).
            char h1 = hemi.get(0), h2 = hemi.get(1);
            if (h1 == 0 || h2 == 0) throw new IllegalArgumentException("Put one hemisphere letter with each number");
            boolean latFirst = h1 == 'N' || h1 == 'S';
            if (latFirst == (h2 == 'N' || h2 == 'S')) throw new IllegalArgumentException("One letter must be N or S and the other E or W");
            if (h1 == 'S' || h1 == 'W') v[0] = -Math.abs(v[0]);
            if (h2 == 'S' || h2 == 'W') v[1] = -Math.abs(v[1]);
            swap = !latFirst;
        }
        double lat = swap ? v[1] : v[0], lon = swap ? v[0] : v[1];
        if (Math.abs(lat) > 90) throw new IllegalArgumentException("Latitude must be -90 to 90 (type latitude first)");
        if (Math.abs(lon) > 180) throw new IllegalArgumentException("Longitude must be -180 to 180");
        Result r = new Result();
        r.lat = lat;
        r.lon = lon;
        r.grid = false;
        r.text = Geo.latLon(lat, lon);
        return r;
    }
}
