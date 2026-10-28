package org.communitycomms.app.geo;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.SecureRandom;

/**
 * A shared map point: location, note, icon and expiry. Anyone can edit and resend it; the copy
 * with the higher version wins. Expiry travels as "time left" so phones with wrong clocks agree.
 *
 * Wire body (inside an encrypted TYPE_MARK packet, after the callsign):
 * <pre>
 *  op(1)  1 = upsert, 2 = sync request (no further fields)
 *  id(8) version(4) lat(8) lon(8) msLeft(8, -1 = never expires) icon(1)
 *  creatorLen(1) creator  editorLen(1) editor  noteLen(2) note    (UTF-8)
 * </pre>
 */
public final class Poi {
    public static final byte OP_UPSERT = 1, OP_SYNC_REQUEST = 2;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final String[] ICON_NAMES = {
            "Person (blue)", "Person (red)", "Person (yellow)", "Car", "Truck", "Bus", "Ambulance",
            "Helicopter", "Plane", "Drone", "Red square", "Blue square", "Photo",
            // 13+ added with the quick map point profiles (older app versions show these as icon 0)
            "Toilets", "Meeting point", "Parked car", "Fire front", "Spot fire", "Fire truck", "Tanker truck",
            "Water point", "Hostile (enemy)", "Friendly", "Unknown", "Observation post", "Danger / hazard",
            "First aid / casualty", "Landing zone", "Checkpoint", "Food / camp", "Road closed",
            // 31+ (2026-10-25): military unit markers and more
            "Friendly infantry", "Friendly armour", "Friendly artillery", "Friendly recon", "Friendly HQ",
            "Friendly medical", "Hostile infantry", "Hostile armour", "Hostile artillery", "Neutral",
            "Decision point", "Target", "Tree down", "Power down", "Shelter", "Boat / ship",
            // 47 (2026-10-29): bulk import of an icon this app does not have
            "Grey dot"};
    /** A plain grey dot: bulk-imported points whose icon is not known (its name goes in brackets in the note). */
    public static final int ICON_DOT = 47;

    public static final int ICON_PHOTO = 12;
    private static final java.util.regex.Pattern PHOTO_TAG = java.util.regex.Pattern.compile("\\[photo:([0-9a-f]{1,16})\\]");

    /** Note line that links a POI to a picture sent on the channel (RadioService.fileKey). */
    public static String photoTag(long fileKey) {
        return "[photo:" + Long.toHexString(fileKey) + "]";
    }

    /** The linked picture's file key, or null. */
    public static Long photoKey(String note) {
        java.util.regex.Matcher m = PHOTO_TAG.matcher(note);
        return m.find() ? new java.math.BigInteger(m.group(1), 16).longValue() : null;
    }

    /** The note as people should read it (without the photo link, aim, shape, public or quiet marks). */
    public static String readable(String note) {
        return LAYER_TAG.matcher(POSE_TAG.matcher(AIM_TAG.matcher(SHAPE_TAG.matcher(PHOTO_TAG.matcher(note).replaceAll("")).replaceAll(""))
                .replaceAll("")).replaceAll("")).replaceAll("").replace(QUIET_TAG, "").replace(PUBLIC_TAG, "").trim();
    }

    // ---- layer: a group of points (e.g. one bulk import) that the map can show on its own ----

    private static final java.util.regex.Pattern LAYER_TAG = java.util.regex.Pattern.compile("\\[layer:([^\\]\\n]{1,40})\\]");

    /** The point's layer, or "" when it has none. */
    public static String layer(String note) {
        java.util.regex.Matcher m = LAYER_TAG.matcher(note);
        return m.find() ? m.group(1).trim() : "";
    }

    public static String withLayer(String note, String layer) {
        String n = LAYER_TAG.matcher(note).replaceAll("").trim();
        String l = layer == null ? "" : layer.replace("]", ")").replace("\n", " ").trim();
        if (l.length() > 40) l = l.substring(0, 40);
        return l.length() == 0 ? n : n + "\n[layer:" + l + "]";
    }

    // ---- pose: the icon turned to face a direction, drawn larger or smaller, with an optional arrow ----

    private static final java.util.regex.Pattern POSE_TAG = java.util.regex.Pattern.compile("\\[pose:(\\d{1,3});(\\d{1,4});([01])\\]");

    /** {rotation in degrees from north, size in percent, direction arrow 0 or 1}; {0, 100, 0} when not set. */
    public static int[] pose(String note) {
        java.util.regex.Matcher m = POSE_TAG.matcher(note);
        if (!m.find()) return new int[]{0, 100, 0};
        return new int[]{Integer.parseInt(m.group(1)) % 360, Math.max(25, Math.min(1000, Integer.parseInt(m.group(2)))),
                Integer.parseInt(m.group(3))};
    }

    public static String withPose(String note, int rotation, int sizePct, boolean arrow) {
        String n = POSE_TAG.matcher(note).replaceAll("").trim();
        if (rotation % 360 == 0 && sizePct == 100 && !arrow) return n;
        return n + "\n[pose:" + ((rotation % 360 + 360) % 360) + ";" + sizePct + ";" + (arrow ? 1 : 0) + "]";
    }

    private static final java.util.regex.Pattern AIM_TAG = java.util.regex.Pattern.compile("\\[aim:(\\d{1,3});(\\d{1,3})\\]");

    /** Which way a photo was taken (degrees from true north) and how wide it sees (degrees). */
    public static String aimTag(float bearing, float fov) {
        return "[aim:" + Math.round((bearing % 360 + 360) % 360) % 360 + ";" + Math.round(fov) + "]";
    }

    /** {bearing, field of view} of a photo point, or null. */
    public static float[] aim(String note) {
        java.util.regex.Matcher m = AIM_TAG.matcher(note);
        return m.find() ? new float[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : null;
    }

    /**
     * Shared on Public too: the point (and every later edit of it, by anyone) also goes out on the
     * Public channel. Hidden from people like the photo link.
     */
    public static final String PUBLIC_TAG = "[public]";

    public static boolean isPublic(String note) {
        return note.contains(PUBLIC_TAG);
    }

    public static String withPublic(String note, boolean pub) {
        String n = note.replace("\n" + PUBLIC_TAG, "").replace(PUBLIC_TAG, "");
        return pub ? n + "\n" + PUBLIC_TAG : n;
    }

    // ---- shapes: a point can be drawn as a shape instead of an icon (older phones show the icon) ----

    public static final String[] SHAPE_NAMES = {"Rectangle", "Circle", "Oval", "Line", "Arrow", "Text box"};
    public static final int RECT = 0, CIRCLE = 1, OVAL = 2, LINE = 3, ARROW = 4, TEXT = 5;
    private static final java.util.regex.Pattern SHAPE_TAG = java.util.regex.Pattern.compile(
            "\\[shape:(\\d);(-?[0-9.]+);(-?[0-9.]+);([0-9.]+);([0-9a-f]{8});([0-9a-f]{8})\\]");

    /** A shape: the point is its first corner / centre / start; lat2, lon2 its other corner or end. */
    public static final class Shape {
        public int type;
        public double lat2, lon2, radiusM;
        public int fg = 0xffff5252, bg = 0x40ff5252;
    }

    public static String shapeTag(Shape s) {
        return String.format(java.util.Locale.US, "[shape:%d;%.6f;%.6f;%.1f;%08x;%08x]", s.type, s.lat2, s.lon2, s.radiusM, s.fg, s.bg);
    }

    public static Shape shape(String note) {
        java.util.regex.Matcher m = SHAPE_TAG.matcher(note);
        if (!m.find()) return null;
        try {
            Shape s = new Shape();
            s.type = Integer.parseInt(m.group(1));
            s.lat2 = Double.parseDouble(m.group(2));
            s.lon2 = Double.parseDouble(m.group(3));
            s.radiusM = Double.parseDouble(m.group(4));
            s.fg = (int) Long.parseLong(m.group(5), 16);
            s.bg = (int) Long.parseLong(m.group(6), 16);
            return s.type >= 0 && s.type < SHAPE_NAMES.length ? s : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String withShape(String note, Shape s) {
        String n = SHAPE_TAG.matcher(note).replaceAll("").trim();
        return s == null ? n : n + "\n" + shapeTag(s);
    }

    /**
     * Marks an edit or removal that should update everyone's map without a spoken announcement
     * (the editor chose not to announce it). Hidden from people like the photo link.
     */
    public static final String QUIET_TAG = "[quiet]";

    public static boolean quiet(String note) {
        return note.contains(QUIET_TAG);
    }

    /** Sets or clears the quiet mark on a note. */
    public static String withQuiet(String note, boolean quiet) {
        String n = note.replace("\n" + QUIET_TAG, "").replace(QUIET_TAG, "");
        return quiet ? n + "\n" + QUIET_TAG : n;
    }

    public long id;
    public int version;
    public double lat, lon;
    /** Local clock time it expires, 0 = never. */
    public long expiresAt;
    public int icon;
    public String note = "", creator = "", editor = "";

    public static Poi create(double lat, double lon, int icon, String note, long lifeMs, String me) {
        Poi p = new Poi();
        p.id = new SecureRandom().nextLong();
        p.version = 1;
        p.lat = lat;
        p.lon = lon;
        p.icon = icon;
        p.note = note;
        p.expiresAt = lifeMs <= 0 ? 0 : System.currentTimeMillis() + lifeMs;
        p.creator = me;
        p.editor = me;
        return p;
    }

    public boolean expired(long now) {
        return expiresAt != 0 && now >= expiresAt;
    }

    public byte[] encode() {
        byte[] c = trunc(creator.getBytes(UTF8), 32), e = trunc(editor.getBytes(UTF8), 32);
        byte[] n = trunc(note.getBytes(UTF8), 600);
        ByteBuffer b = ByteBuffer.allocate(1 + 8 + 4 + 8 + 8 + 8 + 1 + 1 + c.length + 1 + e.length + 2 + n.length);
        long left = expiresAt == 0 ? -1 : Math.max(0, expiresAt - System.currentTimeMillis());
        b.put(OP_UPSERT).putLong(id).putInt(version).putDouble(lat).putDouble(lon).putLong(left).put((byte) icon);
        b.put((byte) c.length).put(c).put((byte) e.length).put(e).putShort((short) n.length).put(n);
        return b.array();
    }

    /** Parses an upsert body starting after the op byte. Returns null if malformed. */
    public static Poi decode(byte[] buf, int off, int len) {
        try {
            ByteBuffer b = ByteBuffer.wrap(buf, off, len);
            Poi p = new Poi();
            p.id = b.getLong();
            p.version = b.getInt();
            p.lat = b.getDouble();
            p.lon = b.getDouble();
            long left = b.getLong();
            p.expiresAt = left < 0 ? 0 : System.currentTimeMillis() + left;
            p.icon = b.get() & 0xff;
            if (p.icon >= ICON_NAMES.length) p.icon = 0;
            p.creator = str(b, b.get() & 0xff);
            p.editor = str(b, b.get() & 0xff);
            p.note = str(b, b.getShort() & 0xffff);
            if (p.lat < -90 || p.lat > 90 || p.lon < -180 || p.lon > 180) return null;
            return p;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(ByteBuffer b, int n) {
        byte[] x = new byte[n];
        b.get(x);
        return new String(x, UTF8);
    }

    private static byte[] trunc(byte[] b, int max) {
        if (b.length <= max) return b;
        byte[] t = new byte[max];
        System.arraycopy(b, 0, t, 0, max);
        return t;
    }

    // ---- persistence (one line per POI) ----

    public String toLine() {
        return Long.toHexString(id) + "\t" + version + "\t" + lat + "\t" + lon + "\t" + expiresAt + "\t" + icon
                + "\t" + esc(creator) + "\t" + esc(editor) + "\t" + esc(note);
    }

    public static Poi fromLine(String l) {
        String[] f = l.split("\t", -1);
        if (f.length != 9) return null;
        try {
            Poi p = new Poi();
            p.id = new java.math.BigInteger(f[0], 16).longValue();
            p.version = Integer.parseInt(f[1]);
            p.lat = Double.parseDouble(f[2]);
            p.lon = Double.parseDouble(f[3]);
            p.expiresAt = Long.parseLong(f[4]);
            p.icon = Integer.parseInt(f[5]);
            p.creator = unesc(f[6]);
            p.editor = unesc(f[7]);
            p.note = unesc(f[8]);
            return p;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
