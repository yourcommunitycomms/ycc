package org.communitycomms.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Leaders' list of teams (Channel array page): each row is a team's channel and what its setup QR carries.
 * Team keys start with TEAM_PREFIX: they are long and random, so they skip the slow key stretching
 * (ChannelCrypto) and hundreds of them can be listened to at once.
 */
public final class ChannelArray {
    public static final String TEAM_PREFIX = "YCCT1-";
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** One team. Fields as in the CSV columns, in order. */
    public static final class Row {
        public String code = "", name = "", priv = "", pub = "", remote = "", tunnelKey = "", lan = "", profile = "", area = "", member = "";
        /** Listened to by this phone, and heard live rather than only recorded. */
        public boolean listen = true, hear;

        String label() {
            return name.length() > 0 ? code + " " + name : code;
        }
    }

    public static final String[] COLUMNS = {"Code", "Team name", "Private key", "Public key", "Remote server", "Tunnel key", "Local cache",
            "Profile", "Map area", "Approved code"};

    static String get(Row r, int col) {
        switch (col) {
            case 0: return r.code;
            case 1: return r.name;
            case 2: return r.priv;
            case 3: return r.pub;
            case 4: return r.remote;
            case 5: return r.tunnelKey;
            case 6: return r.lan;
            case 7: return r.profile;
            case 8: return r.area;
            default: return r.member;
        }
    }

    static void set(Row r, int col, String v) {
        switch (col) {
            case 0: r.code = v; break;
            case 1: r.name = v; break;
            case 2: r.priv = v; break;
            case 3: r.pub = v; break;
            case 4: r.remote = v; break;
            case 5: r.tunnelKey = v; break;
            case 6: r.lan = v; break;
            case 7: r.profile = v; break;
            case 8: r.area = v; break;
            default: r.member = v; break;
        }
    }

    private static File file(Context c, String name) {
        return new File(c.getFilesDir(), name);
    }

    public static String read(Context c, String name) {
        try {
            File f = file(c, name);
            if (!f.isFile()) return "";
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            try {
                int n = 0;
                while (n < b.length) {
                    int r = in.read(b, n, b.length - n);
                    if (r < 0) break;
                    n += r;
                }
            } finally {
                in.close();
            }
            return new String(b, "UTF-8");
        } catch (java.io.IOException e) {
            return "";
        }
    }

    public static void write(Context c, String name, String text) {
        try {
            FileOutputStream o = new FileOutputStream(file(c, name));
            try {
                o.write(text.getBytes("UTF-8"));
            } finally {
                o.close();
            }
        } catch (java.io.IOException ignored) {
        }
    }

    public static List<Row> load(Context c) {
        List<Row> out = new ArrayList<Row>();
        try {
            String t = read(c, "channel-array.json");
            if (t.length() == 0) return out;
            JSONArray a = new JSONArray(t);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Row r = new Row();
                for (int k = 0; k < COLUMNS.length; k++) set(r, k, o.optString("c" + k));
                r.listen = o.optBoolean("listen", true);
                r.hear = o.optBoolean("hear");
                out.add(r);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static void save(Context c, List<Row> rows) {
        JSONArray a = new JSONArray();
        try {
            for (Row r : rows) {
                JSONObject o = new JSONObject();
                for (int k = 0; k < COLUMNS.length; k++) o.put("c" + k, get(r, k));
                o.put("listen", r.listen).put("hear", r.hear);
                a.put(o);
            }
        } catch (JSONException ignored) {
        }
        write(c, "channel-array.json", a.toString());
    }

    /** A new random team key. */
    public static String newKey() {
        byte[] b = new byte[20];
        new SecureRandom().nextBytes(b);
        return encode(b);
    }

    /** The team key for a code, worked out from a DRYAD sheet's id and name, so any leader with the sheet gets the same keys. */
    public static String keyFrom(String seed, String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((seed + "|team|" + code).getBytes("UTF-8"));
            return encode(java.util.Arrays.copyOf(h, 20));
        } catch (Exception e) {
            return newKey();
        }
    }

    private static String encode(byte[] b) {
        StringBuilder sb = new StringBuilder(TEAM_PREFIX);
        int buf = 0, bits = 0;
        for (byte x : b) {
            buf = (buf << 8) | (x & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buf >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(BASE32.charAt((buf << (5 - bits)) & 31));
        return sb.toString();
    }

    /** AA, AB … ZZ. */
    public static String code(int i) {
        return "" + (char) ('A' + i / 26 % 26) + (char) ('A' + i % 26);
    }

    /** A map area, "lat,lon,km": where a team's phone should download tiles. Null if not one. */
    public static double[] area(String s) {
        String[] p = s == null ? new String[0] : s.trim().split("[, ]+");
        if (p.length != 3) return null;
        try {
            return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String upper(String s) {
        return s.trim().toUpperCase(Locale.US);
    }
}
