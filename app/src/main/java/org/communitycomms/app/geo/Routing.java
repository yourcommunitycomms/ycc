package org.communitycomms.app.geo;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline street routing data on the phone: OpenStreetMap roads in cells of a quarter degree (about
 * 28 km), one small file each in maps/_routing/. A cell comes from this phone, the LAN storage
 * ("Maps cache/_routing/", where other phones leave theirs), or the internet (Overpass), and the LAN
 * storage is given a copy of anything new, like map tiles. {@link RoadGraph} does the routing.
 */
public final class Routing {
    private Routing() {
    }

    public static final double CELL = 0.25;
    /** On the LAN storage, inside the maps cache folder ("_" so it is not taken for a tile set). */
    public static final String LAN_DIR = "_routing";
    /** Biggest trip worked out offline: cells loaded at once (each up to a few MB for a city). */
    public static final int MAX_CELLS = 36;
    private static final String[] OVERPASS = {"https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"};

    public static File dir(Context c) {
        File d = new File(MapPrefs.mapsRoot(c), "_routing");
        d.mkdirs();
        return d;
    }

    public static int idx(double deg) {
        return (int) Math.floor(deg / CELL);
    }

    public static String name(int la, int lo) {
        return "r" + la + "_" + lo + ".ycr";
    }

    public static File file(Context c, int la, int lo) {
        return new File(dir(c), name(la, lo));
    }

    /** The cells a circle of km around a point touches. */
    public static List<int[]> cellsAround(double lat, double lon, double km) {
        double dLat = km / 111.32, dLon = km / (111.32 * Math.max(0.1, Math.cos(Math.toRadians(lat))));
        return cellsBox(lat - dLat, lon - dLon, lat + dLat, lon + dLon);
    }

    public static List<int[]> cellsBox(double s, double w, double n, double e) {
        List<int[]> out = new ArrayList<int[]>();
        for (int la = idx(Math.max(-85, s)); la <= idx(Math.min(85, n)); la++) {
            for (int lo = idx(Math.max(-180, w)); lo <= idx(Math.min(179.999, e)); lo++) out.add(new int[]{la, lo});
        }
        return out;
    }

    /** The cells a trip needs: the box around both ends, with 3 km to spare for roads that wander out of it. */
    public static List<int[]> cellsFor(double lat1, double lon1, double lat2, double lon2) {
        double m = 3 / 111.32, mLon = m / Math.max(0.1, Math.cos(Math.toRadians((lat1 + lat2) / 2)));
        return cellsBox(Math.min(lat1, lat2) - m, Math.min(lon1, lon2) - mLon, Math.max(lat1, lat2) + m, Math.max(lon1, lon2) + mLon);
    }

    /** The cells for a route through several points (lat, lon pairs). */
    public static List<int[]> cellsFor(double[] pts) {
        double s = 90, w = 180, n = -90, e = -180;
        for (int i = 0; i + 1 < pts.length; i += 2) {
            s = Math.min(s, pts[i]);
            n = Math.max(n, pts[i]);
            w = Math.min(w, pts[i + 1]);
            e = Math.max(e, pts[i + 1]);
        }
        return cellsFor(s, w, n, e);
    }

    public static List<int[]> missing(Context c, List<int[]> cells) {
        List<int[]> out = new ArrayList<int[]>();
        for (int[] k : cells) if (!file(c, k[0], k[1]).isFile()) out.add(k);
        return out;
    }

    /** Files on this phone and their total size. */
    public static long[] stats(Context c) {
        File[] f = dir(c).listFiles();
        long n = 0, bytes = 0;
        if (f != null) for (File x : f) {
            if (!x.getName().endsWith(".ycr")) continue;
            n++;
            bytes += x.length();
        }
        return new long[]{n, bytes};
    }

    public static final int HERE = 0, FROM_LAN = 1, FROM_NET = 2;

    /**
     * Gets one cell: already here (and the LAN storage is given it if it lacks it), from the LAN
     * storage (primary, then the secondary map server), or from the internet. Throws if none worked.
     */
    public static int fetch(Context c, int la, int lo) throws Exception {
        File f = file(c, la, lo);
        LanTiles lan = new LanTiles(c), second = LanTiles.secondary(c);
        String rel = LanTiles.FOLDER + "/" + LAN_DIR + "/" + name(la, lo);
        if (f.isFile()) {
            if (lan.configured() && !lan.hasPath(rel)) lan.putPath(rel, f);
            return HERE;
        }
        if ((lan.configured() && lan.getPathToFile(rel, f)) || (second != null && second.getPathToFile(rel, f))) {
            if (valid(f)) return FROM_LAN;
            f.delete();
        }
        if (new org.communitycomms.app.Prefs(c).radioSilent()) throw new Exception("radio silent is on");
        Exception last = null;
        for (String server : OVERPASS) {
            try {
                fromNet(server, la, lo, f);
                if ((lan.configured() && lan.putPath(rel, f)) || (second != null && second.putPath(rel, f))) {
                    // given to the LAN storage for the other phones
                }
                return FROM_NET;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("no internet");
    }

    private static boolean valid(File f) {
        try {
            java.io.DataInputStream in = new java.io.DataInputStream(new java.util.zip.GZIPInputStream(new java.io.FileInputStream(f)));
            byte[] m = new byte[4];
            in.readFully(m);
            in.close();
            return new String(m, "US-ASCII").equals(RoadGraph.MAGIC);
        } catch (Exception e) {
            return false;
        }
    }

    private static void fromNet(String server, int la, int lo, File out) throws Exception {
        double s = la * CELL, w = lo * CELL, n = s + CELL, e = w + CELL;
        HttpURLConnection c = (HttpURLConnection) new URL(server).openConnection();
        Tls12SocketFactory.apply(c);
        c.setConnectTimeout(15000);
        c.setReadTimeout(330000); // the server may think for minutes over a city
        c.setDoOutput(true);
        c.setRequestProperty("User-Agent", "YourCommunityCommunications/0.2 (Android; offline street routing)");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try {
            OutputStream os = c.getOutputStream();
            os.write(("data=" + URLEncoder.encode(RoadGraph.query(s, w, n, e), "UTF-8")).getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            if (code != 200) throw new Exception(code == 429 ? "the routing data server is busy: try again in a few minutes" : "routing data server answered " + code);
            InputStream in = c.getInputStream();
            File tmp = new File(out.getPath() + ".part");
            FileOutputStream fo = new FileOutputStream(tmp);
            try {
                RoadGraph.buildCell(in, s, w, n, e, fo);
            } finally {
                fo.close();
                in.close();
            }
            if (!tmp.renameTo(out)) {
                out.delete();
                if (!tmp.renameTo(out)) throw new Exception("could not save the routing data");
            }
        } finally {
            c.disconnect();
        }
    }

    // ---- the graph, kept while the same cells are in use ----

    private static String loadedKey = "";
    private static RoadGraph loaded;

    /** The graph of these cells (only those on the phone), loaded once and kept for the next route. */
    public static synchronized RoadGraph graph(Context c, List<int[]> cells) throws Exception {
        StringBuilder key = new StringBuilder();
        List<File> files = new ArrayList<File>();
        for (int[] k : cells) {
            File f = file(c, k[0], k[1]);
            if (!f.isFile()) continue;
            files.add(f);
            key.append(k[0]).append(',').append(k[1]).append(',').append(f.length()).append(';');
        }
        if (files.isEmpty()) return null;
        if (key.toString().equals(loadedKey) && loaded != null) return loaded;
        loaded = null; // let the old one go before loading the new
        loaded = RoadGraph.load(files);
        loadedKey = key.toString();
        return loaded;
    }

    public static String cellWords(int n) {
        return String.format(Locale.US, "%d area%s of about 28 km", n, n == 1 ? "" : "s");
    }
}
