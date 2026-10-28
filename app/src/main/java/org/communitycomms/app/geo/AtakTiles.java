package org.communitycomms.app.geo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Map tiles in the files ATAK, MOBAC and QGIS use: MBTiles (tiles(zoom_level, tile_column,
 * tile_row, tile_data), rows counted from the bottom) and osmdroid SQLite (tiles(key, provider,
 * tile) with key = (((z &lt;&lt; z) + x) &lt;&lt; z) + y), which is what ATAK writes when it downloads.
 * Import unpacks one into an offline map set (folders of z/x/y tiles, as the app's own maps use);
 * export writes an area of this phone's tiles into either format to share (drop it into ATAK's
 * atak/imagery folder). Plain SQLite, so it works offline and on Android 4.1.
 */
public final class AtakTiles {
    private AtakTiles() {
    }

    public static final int MBTILES = 0, OSMDROID = 1;

    public static boolean isTileDb(File f) {
        String n = f.getName().toLowerCase(Locale.US);
        return n.endsWith(".mbtiles") || n.endsWith(".sqlite") || n.endsWith(".sqlitedb");
    }

    private static boolean hasColumn(SQLiteDatabase db, String table, String col) {
        Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
        try {
            while (c.moveToNext()) if (col.equalsIgnoreCase(c.getString(1))) return true;
            return false;
        } finally {
            c.close();
        }
    }

    /** Unpacks the tiles into setDir/z/x/y.jpg. Returns how many were new. */
    public static int importInto(File db, File setDir) throws IOException {
        SQLiteDatabase d;
        try {
            d = SQLiteDatabase.openDatabase(db.getPath(), null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        } catch (RuntimeException e) {
            throw new IOException("not a tile database (" + e.getMessage() + ")");
        }
        int n = 0;
        try {
            boolean mb = hasColumn(d, "tiles", "zoom_level");
            boolean osm = !mb && hasColumn(d, "tiles", "key");
            if (!mb && !osm) throw new IOException("no MBTiles or osmdroid tiles table in it");
            Cursor c = mb ? d.rawQuery("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles", null)
                    : d.rawQuery("SELECT key, tile FROM tiles", null);
            try {
                while (c.moveToNext()) {
                    int z, x, y;
                    byte[] data;
                    if (mb) {
                        z = c.getInt(0);
                        x = c.getInt(1);
                        y = (1 << z) - 1 - c.getInt(2); // MBTiles rows count from the south
                        data = c.getBlob(3);
                    } else {
                        int[] zxy = fromKey(c.getLong(0));
                        if (zxy == null) continue;
                        z = zxy[0];
                        x = zxy[1];
                        y = zxy[2];
                        data = c.getBlob(1);
                    }
                    if (data == null || data.length == 0 || z < 0 || z > 22) continue;
                    File out = new File(setDir, z + "/" + x + "/" + y + ".jpg");
                    if (out.isFile()) continue;
                    out.getParentFile().mkdirs();
                    FileOutputStream os = new FileOutputStream(out);
                    os.write(data);
                    os.close();
                    n++;
                }
            } finally {
                c.close();
            }
        } finally {
            d.close();
        }
        return n;
    }

    /** osmdroid key → {z, x, y}, or null. key = (((z << z) + x) << z) + y. */
    static int[] fromKey(long key) {
        for (int z = 0; z <= 22; z++) {
            long y = key & ((1L << z) - 1);
            long rest = key >> z;
            long x = rest - ((long) z << z);
            if (x >= 0 && x < (1L << z) && (rest >> z) == z) return new int[]{z, (int) x, (int) y};
        }
        return null;
    }

    static long toKey(int z, int x, int y) {
        return ((((long) z << z) + x) << z) + y;
    }

    /** "Import these map tiles?" for an MBTiles / osmdroid file (from Files or a share). */
    public static void offerImport(final Activity a, final File db) {
        String base = db.getName().replaceAll("\\.(mbtiles|sqlite|sqlitedb)$", "").replaceAll("[^A-Za-z0-9 _.()-]", "");
        final String set = base.length() == 0 ? "ATAK tiles" : base;
        new AlertDialog.Builder(a)
                .setTitle("Map tiles (ATAK / MBTiles)")
                .setMessage("Import the tiles in " + db.getName() + " as the offline map set \"" + set + "\"?\n\n"
                        + "They show on the map without internet.")
                .setPositiveButton("Import", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        new AsyncTask<Void, Void, String>() {
                            @Override
                            protected String doInBackground(Void... v) {
                                try {
                                    File dir = new File(MapPrefs.mapsRoot(a), set);
                                    int n = importInto(db, dir);
                                    MapPrefs mp = new MapPrefs(a);
                                    if (mp.offlineSetName().length() == 0) mp.setOfflineSet(set);
                                    return "Imported " + n + " tiles into \"" + set + "\""
                                            + (set.equals(mp.offlineSetName()) ? "" : ". Pick it in Map options to use it.");
                                } catch (IOException e) {
                                    return "Import failed: " + e.getMessage();
                                }
                            }

                            @Override
                            protected void onPostExecute(String msg) {
                                Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
                            }
                        }.execute();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Writes the tiles this phone has for an area (north, west, south, east) from zFrom to zTo,
     * looking in each root folder (offline set first, then the maps cache). Returns the tile count.
     */
    public static int export(File[] roots, double north, double west, double south, double east, int zFrom, int zTo,
                             int format, String name, File out) throws IOException {
        if (out.exists()) out.delete();
        SQLiteDatabase d = SQLiteDatabase.openOrCreateDatabase(out, null);
        int n = 0;
        try {
            d.beginTransaction();
            if (format == MBTILES) {
                d.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)");
                d.execSQL("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)");
                d.execSQL("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)");
                String[][] meta = {{"name", name}, {"type", "baselayer"}, {"version", "1"}, {"description", "Your Community Communications"},
                        {"format", "png"}, {"minzoom", String.valueOf(zFrom)}, {"maxzoom", String.valueOf(zTo)},
                        {"bounds", String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", west, south, east, north)}};
                for (String[] m : meta) {
                    ContentValues v = new ContentValues();
                    v.put("name", m[0]);
                    v.put("value", m[1]);
                    d.insert("metadata", null, v);
                }
            } else {
                d.execSQL("CREATE TABLE tiles (key INTEGER PRIMARY KEY, provider TEXT, tile BLOB)");
            }
            for (int z = zFrom; z <= zTo; z++) {
                int x0 = (int) Math.floor(Geo.lonToX(west, z)), x1 = (int) Math.floor(Geo.lonToX(east, z));
                int y0 = (int) Math.floor(Geo.latToY(north, z)), y1 = (int) Math.floor(Geo.latToY(south, z));
                for (int x = x0; x <= x1; x++) {
                    for (int y = y0; y <= y1; y++) {
                        byte[] data = find(roots, z, x, y);
                        if (data == null) continue;
                        ContentValues v = new ContentValues();
                        if (format == MBTILES) {
                            v.put("zoom_level", z);
                            v.put("tile_column", x);
                            v.put("tile_row", (1 << z) - 1 - y);
                            v.put("tile_data", data);
                        } else {
                            v.put("key", toKey(z, x, y));
                            v.put("provider", name);
                            v.put("tile", data);
                        }
                        d.insert("tiles", null, v);
                        n++;
                    }
                }
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
            d.close();
        }
        new File(out.getPath() + "-journal").delete();
        return n;
    }

    private static byte[] find(File[] roots, int z, int x, int y) {
        for (File r : roots) {
            if (r == null) continue;
            for (String ext : new String[]{".jpg", ".png", ".jpeg", ".webp"}) {
                File f = new File(r, z + "/" + x + "/" + y + ext);
                if (f.isFile()) {
                    try {
                        InputStream in = new FileInputStream(f);
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        byte[] buf = new byte[16384];
                        int k;
                        while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
                        in.close();
                        return bo.toByteArray();
                    } catch (IOException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /**
     * An ATAK / MOBAC map source pointing at the LAN tile store's copy of a tile source. The address
     * must work without a login (ATAK map sources have no place for one).
     */
    public static String mapSourceXml(String name, String lanBase, String sourceId, int minZoom, int maxZoom, boolean png) {
        String url = lanBase + "/" + LanTiles.FOLDER.replace(" ", "%20") + "/" + sourceId + "/{$z}/{$x}/{$y}.jpg";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<customMapSource>\n"
                + "    <name>" + esc(name) + "</name>\n"
                + "    <minZoom>" + minZoom + "</minZoom>\n"
                + "    <maxZoom>" + maxZoom + "</maxZoom>\n"
                + "    <tileType>" + (png ? "png" : "jpg") + "</tileType>\n"
                + "    <tileUpdate>None</tileUpdate>\n"
                + "    <url>" + esc(url) + "</url>\n"
                + "    <backgroundColor>#000000</backgroundColor>\n"
                + "</customMapSource>\n";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
