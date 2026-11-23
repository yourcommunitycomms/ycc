package org.communitycomms.app.geo;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "T.REX Arms Topo Map": TAK .mbtiles files baked on the T.REX Arms Maps site (OpenStreetMap and
 * hillshade, usually zoom 12 to 15), imported into the app's own storage (maps/_trex, copied, never
 * linked) and read straight from SQLite as a read-only offline tile source. Kept apart from the
 * offline tile sets and the TAK imports: nothing here is unpacked, sent or synced.
 * MBTiles rows count from the south (TMS): y_xyz = (2^z - 1) - y_tms.
 */
public final class TrexMaps {
    private TrexMaps() {
    }

    public static final String NAME = "T.REX Arms Topo Map";
    public static final String SITE = "https://maps.trex-arms.com";
    public static final String OSM = "© OpenStreetMap contributors";

    private static final Object LOCK = new Object();
    private static List<Entry> loaded; // null = not read yet

    /** One imported file and what its metadata (or its tiles) say. */
    public static final class Entry {
        public final File file;
        public String name = "", attribution = "", format = "";
        public double west = -180, south = -85.0511, east = 180, north = 85.0511;
        public boolean hasBounds;
        public int minZoom, maxZoom;
        public long tiles;
        SQLiteDatabase db;

        Entry(File f) {
            file = f;
        }

        /** Whether the tile z/x/y (XYZ numbering) overlaps this file's bounds. */
        boolean covers(int z, int x, int y) {
            if (!hasBounds) return true;
            double w = Geo.xToLon(x, z), e = Geo.xToLon(x + 1, z), n = Geo.yToLat(y, z), s = Geo.yToLat(y + 1, z);
            return e >= west && w <= east && n >= south && s <= north;
        }

        boolean overlaps(double n, double w, double s, double e) {
            return e >= west && w <= east && n >= south && s <= north;
        }

        public String title() {
            return name.length() > 0 ? name : file.getName();
        }
    }

    /** The app's own folder for these files (not an offline set: the _ keeps it out of that list). */
    public static File dir(Context c) {
        File d = new File(MapPrefs.mapsRoot(c), "_trex");
        d.mkdirs();
        return d;
    }

    /** The Specialised menu (Settings > Advanced and Region features, off by default): with it off, nothing T.REX shows or runs. */
    public static boolean menuOn(Context c) {
        return new org.communitycomms.app.Prefs(c).menuOn("trex");
    }

    /** Chosen under Layer, and the Specialised menu is on. */
    public static boolean enabled(Context c) {
        return menuOn(c) && c.getSharedPreferences("map", Context.MODE_PRIVATE).getBoolean("trexOn", false);
    }

    public static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences("map", Context.MODE_PRIVATE).edit().putBoolean("trexOn", on).commit();
    }

    /** The files to draw from now (switched on and at least one imported), or null. */
    public static List<Entry> active(Context c) {
        if (!enabled(c)) return null;
        List<Entry> l = list(c);
        return l.isEmpty() ? null : l;
    }

    /** Imported files, oldest name first. */
    public static List<Entry> list(Context c) {
        synchronized (LOCK) {
            if (loaded == null) {
                loaded = new ArrayList<Entry>();
                File[] fs = dir(c).listFiles();
                if (fs != null) {
                    Arrays.sort(fs);
                    for (File f : fs) {
                        if (!f.getName().toLowerCase(Locale.US).endsWith(".mbtiles")) continue;
                        try {
                            loaded.add(read(f));
                        } catch (IOException e) {
                            android.util.Log.w("TrexMaps", f.getName() + ": " + e.getMessage());
                        }
                    }
                }
            }
            return new ArrayList<Entry>(loaded);
        }
    }

    /** Never let Android delete a file it thinks is corrupt (its default handler does). */
    private static final DatabaseErrorHandler KEEP = new DatabaseErrorHandler() {
        @Override
        public void onCorruption(SQLiteDatabase db) {
        }
    };

    /** Checks the file is SQLite with an MBTiles tiles table, reads its metadata, and keeps it open. */
    static Entry read(File f) throws IOException {
        byte[] h = new byte[16];
        int got = 0;
        InputStream in = new FileInputStream(f);
        try {
            int k;
            while (got < 16 && (k = in.read(h, got, 16 - got)) > 0) got += k;
        } finally {
            in.close();
        }
        if (got < 16 || !new String(h, 0, 15, "US-ASCII").equals("SQLite format 3")) {
            throw new IOException("it is not an SQLite file, so not an .mbtiles map");
        }
        SQLiteDatabase d;
        try {
            d = SQLiteDatabase.openDatabase(f.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS, KEEP);
        } catch (RuntimeException e) {
            throw new IOException("SQLite could not open it (" + e.getMessage() + ")");
        }
        try {
            Cursor c = d.rawQuery("SELECT 1 FROM sqlite_master WHERE (type='table' OR type='view') AND name='tiles'", null);
            boolean has;
            try {
                has = c.moveToFirst();
            } finally {
                c.close();
            }
            if (!has) throw new IOException("it has no tiles table, so it is not an MBTiles map");
            for (String col : new String[]{"zoom_level", "tile_column", "tile_row", "tile_data"}) {
                if (!hasColumn(d, col)) throw new IOException("its tiles table is not MBTiles (no " + col + " column)");
            }
            Entry e = new Entry(f);
            int metaMin = -1, metaMax = -1;
            try {
                Cursor m = d.rawQuery("SELECT name, value FROM metadata", null);
                try {
                    while (m.moveToNext()) {
                        String k = m.getString(0) == null ? "" : m.getString(0).trim().toLowerCase(Locale.US);
                        String v = m.getString(1) == null ? "" : m.getString(1).trim();
                        if (k.equals("name")) e.name = v;
                        else if (k.equals("attribution")) e.attribution = clean(v);
                        else if (k.equals("format")) e.format = v;
                        else if (k.equals("minzoom")) metaMin = parseInt(v);
                        else if (k.equals("maxzoom")) metaMax = parseInt(v);
                        else if (k.equals("bounds")) {
                            String[] p = v.split(",");
                            if (p.length == 4) {
                                try {
                                    double w = Double.parseDouble(p[0].trim()), s = Double.parseDouble(p[1].trim()),
                                            ea = Double.parseDouble(p[2].trim()), n = Double.parseDouble(p[3].trim());
                                    if (w < ea && s < n && w >= -180 && ea <= 180 && s >= -90 && n <= 90) {
                                        e.west = w;
                                        e.south = s;
                                        e.east = ea;
                                        e.north = n;
                                        e.hasBounds = true;
                                    }
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                    }
                } finally {
                    m.close();
                }
            } catch (RuntimeException ignored) {
                // No metadata table: work it out from the tiles.
            }
            c = d.rawQuery("SELECT COUNT(*), MIN(zoom_level), MAX(zoom_level) FROM tiles", null);
            try {
                if (c.moveToFirst()) {
                    e.tiles = c.getLong(0);
                    e.minZoom = c.getInt(1);
                    e.maxZoom = c.getInt(2);
                }
            } finally {
                c.close();
            }
            if (e.tiles == 0) throw new IOException("its tiles table is empty");
            // The metadata is only a hint; the tiles say what is really there.
            if (metaMin >= 0 && metaMin > e.minZoom && metaMin <= e.maxZoom) e.minZoom = metaMin;
            if (metaMax >= 0 && metaMax < e.maxZoom && metaMax >= e.minZoom) e.maxZoom = metaMax;
            if (!e.hasBounds) {
                int z = e.maxZoom;
                c = d.rawQuery("SELECT MIN(tile_column), MAX(tile_column), MIN(tile_row), MAX(tile_row) FROM tiles WHERE zoom_level=" + z, null);
                try {
                    if (c.moveToFirst()) {
                        int n1 = (1 << z) - 1;
                        e.west = Geo.xToLon(c.getInt(0), z);
                        e.east = Geo.xToLon(c.getInt(1) + 1, z);
                        e.north = Geo.yToLat(n1 - c.getInt(3), z);
                        e.south = Geo.yToLat(n1 - c.getInt(2) + 1, z);
                        e.hasBounds = true;
                    }
                } finally {
                    c.close();
                }
            }
            e.db = d;
            return e;
        } catch (IOException ex) {
            d.close();
            throw ex;
        } catch (RuntimeException ex) {
            d.close();
            throw new IOException("could not read it as MBTiles (" + ex.getMessage() + ")");
        }
    }

    private static boolean hasColumn(SQLiteDatabase db, String col) {
        Cursor c = db.rawQuery("PRAGMA table_info(tiles)", null);
        try {
            while (c.moveToNext()) if (col.equalsIgnoreCase(c.getString(1))) return true;
            return false;
        } finally {
            c.close();
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Attribution often holds HTML links: keep the words. */
    static String clean(String s) {
        return s.replaceAll("<[^>]*>", "").replace("&copy;", "©").replace("&amp;", "&").replace("&nbsp;", " ")
                .replaceAll("\\s+", " ").trim();
    }

    /** The tile z/x/y (XYZ numbering) from the first file that has it, or null. */
    public static byte[] tile(List<Entry> es, int z, int x, int y) {
        if (es == null || z < 0 || z > 24) return null;
        synchronized (LOCK) {
            for (Entry e : es) {
                if (z < e.minZoom || z > e.maxZoom || e.db == null || !e.db.isOpen() || !e.covers(z, x, y)) continue;
                int row = (1 << z) - 1 - y; // TMS: rows count from the south
                Cursor c = null;
                try {
                    c = e.db.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=" + z + " AND tile_column=" + x
                            + " AND tile_row=" + row + " LIMIT 1", null);
                    if (c.moveToFirst()) {
                        byte[] b = c.getBlob(0);
                        if (b != null && b.length > 0) return b;
                    }
                } catch (RuntimeException ex) {
                    android.util.Log.w("TrexMaps", "tile read: " + ex.getMessage());
                } finally {
                    if (c != null) c.close();
                }
            }
        }
        return null;
    }

    /** The highest zoom any file has. */
    public static int maxZoom(List<Entry> es) {
        int m = -1;
        if (es != null) for (Entry e : es) m = Math.max(m, e.maxZoom);
        return m;
    }

    /** Whether any file overlaps the box {north, west, south, east}. */
    public static boolean overlaps(List<Entry> es, double[] box) {
        if (es == null || box == null) return false;
        for (Entry e : es) if (e.overlaps(box[0], box[1], box[2], box[3])) return true;
        return false;
    }

    /** The credit line for the map: OpenStreetMap, then any others the files name. */
    public static String attribution(List<Entry> es) {
        List<String> all = new ArrayList<String>();
        if (es != null) for (Entry e : es) all.add(e.attribution);
        return attributionOf(all);
    }

    /** The credit line from attribution texts (local files and files on the cache server). */
    public static String attributionOf(List<String> texts) {
        Set<String> parts = new LinkedHashSet<String>();
        boolean osm = false;
        if (texts != null) {
            for (String t : texts) {
                String a = t == null ? "" : t.trim();
                if (a.length() == 0) continue;
                if (a.toLowerCase(Locale.US).contains("openstreetmap")) osm = true; // already credits OSM
                parts.add(a);
            }
        }
        if (!osm) {
            Set<String> withOsm = new LinkedHashSet<String>();
            withOsm.add(OSM);
            withOsm.addAll(parts);
            parts = withOsm;
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p);
        }
        return sb.toString();
    }

    /** Size, tiles, zooms and bounds, for the list. */
    public static String describe(Entry e) {
        return String.format(Locale.US, "%s · %,d tiles · zoom %d to %d%s\nBounds: %s to %s\nFile: %s",
                size(e.file.length()), e.tiles, e.minZoom, e.maxZoom, e.format.length() > 0 ? " · " + e.format : "",
                Geo.latLon(e.north, e.west), Geo.latLon(e.south, e.east), e.file.getName());
    }

    public static String size(long b) {
        if (b >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
        if (b >= 1024L * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.0f KB", Math.max(1, b / 1024.0));
    }

    /** Progress while copying: bytes so far and the total (-1 if unknown). */
    public interface Progress {
        void copied(long done, long total);
    }

    /**
     * Copies the picked file into the app's folder, checks it, and adds it to the list. A file that
     * is not SQLite or has no tiles table stops here and nothing is kept.
     */
    public static Entry importFrom(Context c, Uri uri, Progress p) throws IOException {
        ContentResolver cr = c.getContentResolver();
        String shown = displayName(c, uri);
        long total = -1;
        try {
            Cursor q = cr.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (q != null) {
                try {
                    if (q.moveToFirst() && !q.isNull(0)) total = q.getLong(0);
                } finally {
                    q.close();
                }
            }
        } catch (RuntimeException ignored) {
        }
        if (total < 0 && "file".equals(uri.getScheme()) && uri.getPath() != null) total = new File(uri.getPath()).length();
        File d = dir(c);
        if (total > 0 && d.getUsableSpace() < total + 8L * 1024 * 1024) {
            throw new IOException("not enough free space (" + size(total) + " needed, " + size(d.getUsableSpace()) + " free)");
        }
        File tmp = new File(d, "import.tmp");
        tmp.delete();
        InputStream in;
        try {
            in = cr.openInputStream(uri);
        } catch (SecurityException e) {
            throw new IOException("the app was not allowed to read it");
        }
        if (in == null) throw new IOException("could not open the file");
        try {
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[65536];
                long done = 0, lastShown = 0;
                int k;
                while ((k = in.read(buf)) > 0) {
                    out.write(buf, 0, k);
                    done += k;
                    if (p != null && done - lastShown >= 1024 * 1024) {
                        lastShown = done;
                        p.copied(done, total);
                    }
                }
            } finally {
                out.close();
            }
        } catch (IOException e) {
            tmp.delete();
            throw new IOException("copy failed (" + e.getMessage() + ")");
        } finally {
            in.close();
        }
        return importTmp(c, tmp, shown);
    }

    /**
     * Checks a file already in the app's T.REX folder (a copy or a download), then names it after
     * {@code shown} and adds it to the list. Not valid: the file is deleted and nothing is kept.
     */
    public static Entry importTmp(Context c, File tmp, String shown) throws IOException {
        File d = dir(c);
        Entry e;
        try {
            e = read(tmp);
        } catch (IOException ex) {
            tmp.delete();
            throw ex;
        }
        String base = shown.replaceAll("(?i)\\.mbtiles$", "");
        if (base.length() == 0 || base.equalsIgnoreCase("import.tmp") || base.endsWith(".part")) base = e.name;
        base = base.replaceAll("[^A-Za-z0-9 _.()-]", "").trim();
        if (base.length() == 0) base = "trex-topo";
        if (base.length() > 60) base = base.substring(0, 60);
        synchronized (LOCK) {
            e.db.close();
            File dest = new File(d, base + ".mbtiles");
            for (int i = 2; dest.exists(); i++) dest = new File(d, base + " (" + i + ").mbtiles");
            if (!tmp.renameTo(dest)) {
                tmp.delete();
                throw new IOException("could not move it into the app's folder");
            }
            if (loaded == null) list(c); // reads every file, this one included
            else loaded.add(read(dest));
            for (Entry x : loaded) if (x.file.equals(dest)) return x;
            throw new IOException("it could not be read after copying");
        }
    }

    /** Closes and deletes an imported file. */
    public static void remove(Context c, Entry e) {
        synchronized (LOCK) {
            if (e.db != null) e.db.close();
            e.file.delete();
            new File(e.file.getPath() + "-journal").delete();
            if (loaded != null) {
                for (int i = loaded.size() - 1; i >= 0; i--) if (loaded.get(i).file.equals(e.file)) loaded.remove(i);
            }
        }
    }

    static String displayName(Context c, Uri uri) {
        String n = null;
        try {
            Cursor q = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (q != null) {
                try {
                    if (q.moveToFirst()) n = q.getString(0);
                } finally {
                    q.close();
                }
            }
        } catch (RuntimeException ignored) {
        }
        if (n == null) n = uri.getLastPathSegment();
        return n == null ? "" : n.substring(n.lastIndexOf('/') + 1);
    }
}
