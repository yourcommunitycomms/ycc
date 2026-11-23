package org.communitycomms.app;

import org.communitycomms.app.geo.Poi;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TAK markers -> YCC map points (POIs). TAK sends a marker someone shares ("Send" / broadcast) as a
 * Cursor on Target event on the SA multicast (239.2.3.1:6969), like a position report but with a
 * marker type and detail:
 * <ul>
 * <li>points: b-m-p-* (spot map marker b-m-p-s-m, waypoint b-m-p-w, checkpoint b-m-p-c ...) and
 * 2525 point-dropper markers a-h-G (hostile), a-f-G (friendly), a-u-G (unknown), a-n-G (neutral),
 * told apart from people by &lt;archive/&gt; or a p-p link with a parent_callsign and no &lt;takv&gt;;</li>
 * <li>shapes: u-d-c-c (circle, &lt;ellipse major&gt;), u-d-r (rectangle, four &lt;link point&gt;),
 * u-d-f (freehand line: first and last point);</li>
 * <li>deletes: t-x-d-d with &lt;link uid&gt; of the marker.</li>
 * </ul>
 * The YCC point's id comes from the TAK uid, so a moved or renamed marker updates the same point, and
 * the note keeps the uid in a hidden [tak:uid] tag. Name and remarks become the note, on layer "TAK".
 */
final class TakPoi {
    private TakPoi() {
    }

    static final String LAYER = "TAK";

    private static final Pattern POINT = Pattern.compile("<point[^>]*\\blat=['\"]([-0-9.eE]+)['\"][^>]*\\blon=['\"]([-0-9.eE]+)['\"]");
    private static final Pattern CALLSIGN = Pattern.compile("<contact[^>]*\\bcallsign=['\"]([^'\"]*)['\"]");
    private static final Pattern REMARKS = Pattern.compile("<remarks[^>]*>([\\s\\S]*?)</remarks>");
    private static final Pattern PARENT = Pattern.compile("\\bparent_callsign=['\"]([^'\"]*)['\"]");
    private static final Pattern COLOR = Pattern.compile("<color[^>]*\\bargb=['\"](-?\\d+)['\"]");
    private static final Pattern STROKE = Pattern.compile("<strokeColor[^>]*\\bvalue=['\"](-?\\d+)['\"]");
    private static final Pattern FILL = Pattern.compile("<fillColor[^>]*\\bvalue=['\"](-?\\d+)['\"]");
    private static final Pattern ELLIPSE = Pattern.compile("<ellipse[^>]*\\bmajor=['\"]([0-9.eE]+)['\"]");
    private static final Pattern LINK_POINT = Pattern.compile("<link[^>]*\\bpoint=['\"](-?[0-9.]+),\\s*(-?[0-9.]+)");
    private static final Pattern LINK_UID = Pattern.compile("<link[^>]*\\buid=['\"]([^'\"]+)['\"]");
    private static final Pattern ICONSET = Pattern.compile("<usericon[^>]*\\biconsetpath=['\"]([^'\"]*)['\"]");

    /** A marker (not a person's position report, not chat). */
    static boolean isMarker(String type, String xml) {
        if (type.startsWith("b-m-p") || type.startsWith("u-d-")) return true;
        if (!type.startsWith("a-")) return false;
        if (xml.contains("<takv") || xml.contains("<__group")) return false; // a TAK user
        return xml.contains("<archive") || PARENT.matcher(xml).find();
    }

    static boolean isDelete(String type) {
        return type.startsWith("t-x-d-d");
    }

    /** The uid a delete event removes, or null. */
    static String deletedUid(String xml) {
        return find(LINK_UID, xml);
    }

    /** Stable YCC point id for a TAK uid. */
    static long idFor(String uid) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(("tak:" + uid).getBytes("UTF-8"));
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (h[i] & 0xff);
            return v;
        } catch (Exception e) {
            return uid.hashCode() * 0x9E3779B97F4A7C15L;
        }
    }

    /** The TAK marker as a YCC point (version 1; the caller bumps it over a stored copy), or null if unusable. */
    static Poi toPoi(String uid, String type, String xml) {
        Matcher pm = POINT.matcher(xml);
        if (!pm.find()) return null;
        double lat, lon;
        try {
            lat = Double.parseDouble(pm.group(1));
            lon = Double.parseDouble(pm.group(2));
        } catch (NumberFormatException e) {
            return null;
        }
        String name = unesc(nz(find(CALLSIGN, xml)));
        String remarks = unesc(nz(find(REMARKS, xml))).trim();
        String sender = unesc(nz(find(PARENT, xml)));
        Poi.Shape shape = null;
        if (type.startsWith("u-d-")) {
            List<double[]> pts = new ArrayList<double[]>();
            Matcher lm = LINK_POINT.matcher(xml);
            while (lm.find()) {
                try {
                    pts.add(new double[]{Double.parseDouble(lm.group(1)), Double.parseDouble(lm.group(2))});
                } catch (NumberFormatException ignored) {
                }
            }
            int stroke = color(find(STROKE, xml), 0xffff5252), fill = color(find(FILL, xml), 0x40ff5252);
            if (type.startsWith("u-d-c-c")) { // circle around the point
                String maj = find(ELLIPSE, xml);
                shape = new Poi.Shape();
                shape.type = Poi.CIRCLE;
                shape.radiusM = maj == null ? 50 : Math.max(1, Double.parseDouble(maj));
                shape.lat2 = lat;
                shape.lon2 = lon;
            } else if (type.startsWith("u-d-r") && pts.size() >= 2) { // rectangle from its corners
                double la1 = 90, lo1 = 180, la2 = -90, lo2 = -180;
                for (double[] q : pts) {
                    la1 = Math.min(la1, q[0]);
                    lo1 = Math.min(lo1, q[1]);
                    la2 = Math.max(la2, q[0]);
                    lo2 = Math.max(lo2, q[1]);
                }
                shape = new Poi.Shape();
                shape.type = Poi.RECT;
                lat = la1;
                lon = lo1;
                shape.lat2 = la2;
                shape.lon2 = lo2;
            } else if (pts.size() >= 2) { // freehand or a route: a line from its first to its last point
                shape = new Poi.Shape();
                shape.type = Poi.LINE;
                lat = pts.get(0)[0];
                lon = pts.get(0)[1];
                shape.lat2 = pts.get(pts.size() - 1)[0];
                shape.lon2 = pts.get(pts.size() - 1)[1];
            }
            if (shape != null) {
                shape.fg = stroke;
                shape.bg = fill;
            }
        }
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180 || (lat == 0 && lon == 0)) return null;
        StringBuilder note = new StringBuilder();
        note.append(name.length() > 0 ? name : kind(type));
        if (remarks.length() > 0) note.append(": ").append(remarks);
        String text = note.toString();
        if (text.length() > 300) text = text.substring(0, 300) + "…";
        text = text + "\n[tak:" + uid.replace("]", ")") + "]";
        text = Poi.withLayer(text, LAYER);
        if (shape != null) text = Poi.withShape(text, shape);
        Poi p = Poi.create(lat, lon, icon(type, find(COLOR, xml), find(ICONSET, xml)), text, 0,
                (sender.length() > 0 ? sender : "TAK") + " (TAK)");
        p.id = idFor(uid);
        return p;
    }

    /** Same place, icon and words: nothing to update. */
    static boolean same(Poi a, Poi b) {
        return a.icon == b.icon && a.note.equals(b.note) && Math.abs(a.lat - b.lat) < 1e-6 && Math.abs(a.lon - b.lon) < 1e-6;
    }

    private static String kind(String type) {
        if (type.startsWith("u-d-c-c")) return "TAK circle";
        if (type.startsWith("u-d-r")) return "TAK rectangle";
        if (type.startsWith("u-d-")) return "TAK drawing";
        if (type.startsWith("b-m-p-w")) return "TAK waypoint";
        if (type.startsWith("a-h")) return "Hostile (TAK)";
        if (type.startsWith("a-f")) return "Friendly (TAK)";
        if (type.startsWith("a-n")) return "Neutral (TAK)";
        if (type.startsWith("a-u")) return "Unknown (TAK)";
        return "TAK marker";
    }

    /** The nearest YCC icon (Poi.ICON_NAMES). */
    static int icon(String type, String argb, String iconset) {
        if (type.startsWith("a-h")) return 21; // Hostile (enemy)
        if (type.startsWith("a-f")) return 22; // Friendly
        if (type.startsWith("a-u") || type.startsWith("a-p")) return 23; // Unknown
        if (type.startsWith("a-n")) return 40; // Neutral
        if (type.startsWith("b-m-p-c")) return 28; // Checkpoint
        if (type.startsWith("b-m-p-w")) return 41; // Decision point (waypoint)
        if (type.startsWith("b-m-p-s-p-op")) return 24; // Observation post
        if (type.startsWith("b-m-p-s-p-loc")) return 14; // Meeting point
        if (type.startsWith("b-m-p-s-p-i")) return 42; // Target (point of interest)
        if (type.startsWith("b-m-p-s-m") || type.startsWith("b-m-p")) { // spot map marker: by its colour
            int c = color(argb, 0);
            int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
            if (c == 0) return Poi.ICON_DOT;
            if (b > 150 && b >= g && r < 120) return 11; // Blue square (blue and cyan)
            if (r > 150 && g < 120 && b < 120) return 10; // Red square
            return Poi.ICON_DOT;
        }
        return Poi.ICON_DOT;
    }

    private static int color(String v, int dflt) {
        if (v == null) return dflt;
        try {
            return (int) Long.parseLong(v);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static String find(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String unesc(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    /** "[tak:uid]" in a note: the TAK marker a YCC point came from, or null. */
    static String takUid(String note) {
        Matcher m = TAK_TAG.matcher(note);
        return m.find() ? m.group(1) : null;
    }

    static final Pattern TAK_TAG = Pattern.compile("\\[tak:([^\\]\\n]{1,120})\\]");

    // ---- YCC point -> TAK marker ----

    /** The TAK uid for a YCC point (fixed, so TAK updates the same marker). */
    static String cotUid(long id) {
        return "YCC-POI-" + Long.toHexString(id);
    }

    /** CoT for a YCC point: a 2525 marker or a coloured spot marker, or a circle / rectangle / line drawing. */
    static String toCot(Poi p, String myUid, String myCallsign, long now, String channel) {
        String name = Poi.readable(p.note);
        String first = name.indexOf('\n') >= 0 ? name.substring(0, name.indexOf('\n')) : name;
        if (first.length() == 0) first = Poi.ICON_NAMES[p.icon < Poi.ICON_NAMES.length ? p.icon : 0];
        if (first.length() > 50) first = first.substring(0, 50) + "…";
        first = first + " (YCC" + (channel == null || channel.length() == 0 ? "" : " " + channel) + ")";
        String who = p.editor.length() > 0 ? p.editor : p.creator;
        String remarks = (name.length() > 0 ? name.trim() + "\n" : "") + "From YCC" + (who.length() > 0 ? " (" + who + ")" : "")
                + ". Not encrypted on the mesh.";
        Poi.Shape sh = Poi.shape(p.note);
        String type, extra = "";
        double lat = p.lat, lon = p.lon;
        if (sh != null && sh.type == Poi.CIRCLE) {
            type = "u-d-c-c";
            extra = String.format(Locale.US, "<shape><ellipse major=\"%.1f\" minor=\"%.1f\" angle=\"360\"/></shape>", sh.radiusM, sh.radiusM)
                    + "<strokeColor value=\"" + sh.fg + "\"/><strokeWeight value=\"3.0\"/><fillColor value=\"" + sh.bg + "\"/>";
        } else if (sh != null && (sh.type == Poi.RECT || sh.type == Poi.TEXT)) {
            type = "u-d-r";
            double la1 = Math.min(p.lat, sh.lat2), la2 = Math.max(p.lat, sh.lat2), lo1 = Math.min(p.lon, sh.lon2), lo2 = Math.max(p.lon, sh.lon2);
            extra = String.format(Locale.US, "<link point=\"%.7f,%.7f\"/><link point=\"%.7f,%.7f\"/><link point=\"%.7f,%.7f\"/><link point=\"%.7f,%.7f\"/>",
                    la2, lo1, la2, lo2, la1, lo2, la1, lo1)
                    + "<strokeColor value=\"" + sh.fg + "\"/><strokeWeight value=\"3.0\"/><fillColor value=\"" + sh.bg + "\"/>";
            lat = (la1 + la2) / 2;
            lon = (lo1 + lo2) / 2;
        } else if (sh != null && (sh.type == Poi.LINE || sh.type == Poi.ARROW || sh.type == Poi.OVAL)) {
            type = "u-d-f";
            extra = String.format(Locale.US, "<link point=\"%.7f,%.7f\"/><link point=\"%.7f,%.7f\"/>", p.lat, p.lon, sh.lat2, sh.lon2)
                    + "<strokeColor value=\"" + sh.fg + "\"/><strokeWeight value=\"3.0\"/>";
        } else {
            type = cotType(p.icon);
            if (type.startsWith("b-m-p-s-m")) {
                int argb = spotColor(p.icon);
                extra = "<color argb=\"" + argb + "\"/><usericon iconsetpath=\"COT_MAPPING_SPOTMAP/b-m-p-s-m/" + argb + "\"/>";
            }
        }
        // Stale = the point's time to live (a point that never expires: a year; YCC resends it, and deletes it when removed).
        String t = time(now), stale = time(p.expiresAt > now ? p.expiresAt : now + 365L * 86400000L);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<event version=\"2.0\" uid=\"" + cotUid(p.id) + "\" type=\"" + type + "\" how=\"h-g-i-g-o\" time=\"" + t
                + "\" start=\"" + t + "\" stale=\"" + stale + "\">"
                + String.format(Locale.US, "<point lat=\"%.7f\" lon=\"%.7f\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>", lat, lon)
                + "<detail><contact callsign=\"" + esc(first) + "\"/>"
                + "<link uid=\"" + esc(myUid) + "\" type=\"a-f-G-U-C\" parent_callsign=\"" + esc(myCallsign) + "\" relation=\"p-p\"/>"
                + extra + "<remarks>" + esc(remarks) + "</remarks></detail></event>";
    }

    static String deleteCot(long id, long now) {
        String t = time(now);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<event version=\"2.0\" uid=\"YCC-del-" + Long.toHexString(id) + "-" + now + "\" type=\"t-x-d-d\" how=\"h-g-i-g-o\" time=\"" + t
                + "\" start=\"" + t + "\" stale=\"" + time(now + 20000) + "\"><point lat=\"0\" lon=\"0\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>"
                + "<detail><link uid=\"" + cotUid(id) + "\" relation=\"none\" type=\"none\"/><__forcedelete/></detail></event>";
    }

    /** YCC icon -> CoT type: hostile / friendly / unknown / neutral icons as 2525 markers, the rest as spot markers. */
    static String cotType(int icon) {
        switch (icon) {
            case 21: return "a-h-G";
            case 37: return "a-h-G-U-C-I"; // hostile infantry
            case 38: return "a-h-G-U-C-A"; // hostile armour
            case 39: return "a-h-G-U-C-F"; // hostile artillery
            case 31: return "a-f-G-U-C-I"; // friendly infantry
            case 32: return "a-f-G-U-C-A";
            case 33: return "a-f-G-U-C-F";
            case 34: return "a-f-G-U-C-R";
            case 35: return "a-f-G-U-H"; // HQ
            case 22: case 36: return "a-f-G";
            case 23: return "a-u-G";
            case 40: return "a-n-G";
            case 28: return "b-m-p-c";
            case 41: return "b-m-p-w";
            default: return "b-m-p-s-m";
        }
    }

    private static int spotColor(int icon) {
        switch (icon) {
            case 11: case 0: return 0xff2196f3; // blue
            case 2: case 25: return 0xffffeb3b; // yellow: person (yellow), danger
            case 16: case 17: case 21: case 1: case 10: case 26: case 42: return 0xffff0000; // red: fire, casualty, target
            case 20: return 0xff00bcd4; // water point
            case 22: return 0xff4caf50;
            default: return 0xffffffff; // white
        }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String time(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(ms));
    }

    static String debug(String type, Poi p) {
        return String.format(Locale.US, "%s -> icon %d at %.5f,%.5f", type, p.icon, p.lat, p.lon);
    }
}
