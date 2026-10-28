package org.communitycomms.app.geo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Map tiles sent as a file: a zip named "SET.uctiles.zip" holding z/x/y.jpg|png entries plus a
 * small "uctiles.txt" (set name, centre, zooms). The receiver imports it into its own offline
 * tile store (maps/SET/), merging with any tiles already there.
 */
public final class TilePack {
    public static final String SUFFIX = ".uctiles.zip";
    public static final String MIME = "application/zip";
    private static final Pattern TILE = Pattern.compile("^(\\d{1,2})/(\\d{1,7})/(\\d{1,7})\\.(jpg|jpeg|png|webp)$");

    public static boolean isTilePack(File f) {
        if (f == null) return false;
        String n = f.getName().toLowerCase(java.util.Locale.US);
        return n.endsWith(".zip") && n.contains(".uctiles"); // a repeat arrives as "X.uctiles (2).zip"
    }

    /** The tiles around a point: every zoom from zMin to zMax, within radiusKm. */
    public static final class Area {
        public final double lat, lon, radiusKm;
        public final int zMin, zMax;

        public Area(double lat, double lon, double radiusKm, int zMin, int zMax) {
            this.lat = lat;
            this.lon = lon;
            this.radiusKm = radiusKm;
            this.zMin = zMin;
            this.zMax = zMax;
        }

        int[] range(int z) {
            double dLat = radiusKm / 111.32;
            double dLon = radiusKm / (111.32 * Math.max(0.01, Math.cos(Math.toRadians(lat))));
            int x0 = (int) Math.floor(Geo.lonToX(Math.max(-180, lon - dLon), z));
            int x1 = (int) Math.floor(Geo.lonToX(Math.min(179.9999, lon + dLon), z));
            int y0 = (int) Math.floor(Geo.latToY(Math.min(85, lat + dLat), z));
            int y1 = (int) Math.floor(Geo.latToY(Math.max(-85, lat - dLat), z));
            return new int[]{x0, x1, y0, y1};
        }
    }

    /** Tiles found and their total size in bytes, for "N tiles, X MB" before sending. */
    public static long[] count(File[] roots, Area a) {
        long n = 0, bytes = 0;
        for (int z = a.zMin; z <= a.zMax; z++) {
            int[] r = a.range(z);
            for (int x = r[0]; x <= r[1]; x++) {
                for (int y = r[2]; y <= r[3]; y++) {
                    File f = find(roots, z, x, y);
                    if (f != null) {
                        n++;
                        bytes += f.length();
                    }
                }
            }
        }
        return new long[]{n, bytes};
    }

    private static File find(File[] roots, int z, int x, int y) {
        for (File root : roots) {
            if (root == null) continue;
            for (String ext : new String[]{".jpg", ".png", ".jpeg", ".webp"}) {
                File f = new File(root, z + "/" + x + "/" + y + ext);
                if (f.isFile()) return f;
            }
        }
        return null;
    }

    /** Writes the pack. Earlier roots win where both have a tile. Returns the number of tiles. */
    public static int build(File[] roots, Area a, String setName, File out) throws IOException {
        ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(out));
        int n = 0;
        try {
            zo.setLevel(0); // tiles are already compressed images
            zo.putNextEntry(new ZipEntry("uctiles.txt"));
            String info = "set=" + setName + "\nlat=" + a.lat + "\nlon=" + a.lon + "\nradiusKm=" + a.radiusKm
                    + "\nzMin=" + a.zMin + "\nzMax=" + a.zMax + "\n";
            zo.write(info.getBytes("UTF-8"));
            zo.closeEntry();
            byte[] buf = new byte[16384];
            for (int z = a.zMin; z <= a.zMax; z++) {
                int[] r = a.range(z);
                for (int x = r[0]; x <= r[1]; x++) {
                    for (int y = r[2]; y <= r[3]; y++) {
                        File f = find(roots, z, x, y);
                        if (f == null) continue;
                        String name = f.getName();
                        zo.putNextEntry(new ZipEntry(z + "/" + x + "/" + name));
                        InputStream in = new FileInputStream(f);
                        try {
                            int k;
                            while ((k = in.read(buf)) > 0) zo.write(buf, 0, k);
                        } finally {
                            in.close();
                        }
                        zo.closeEntry();
                        n++;
                    }
                }
            }
        } finally {
            zo.close();
        }
        return n;
    }

    /** Set name from the pack's uctiles.txt, else from the file name. */
    public static String setName(File pack) {
        String fallback = pack.getName();
        fallback = fallback.substring(0, Math.max(0, fallback.toLowerCase(java.util.Locale.US).indexOf(".uctiles")))
                .replaceFirst("^\\d+-", "");
        try {
            ZipInputStream zi = new ZipInputStream(new BufferedInputStream(new FileInputStream(pack)));
            try {
                ZipEntry e;
                while ((e = zi.getNextEntry()) != null) {
                    if (!e.getName().equals("uctiles.txt")) continue;
                    byte[] b = new byte[2048];
                    int len = 0, k;
                    while (len < b.length && (k = zi.read(b, len, b.length - len)) > 0) len += k;
                    for (String line : new String(b, 0, len, "UTF-8").split("\n")) {
                        if (line.startsWith("set=")) return clean(line.substring(4), fallback);
                    }
                }
            } finally {
                zi.close();
            }
        } catch (IOException ignored) {
        }
        return clean(fallback, "Received tiles");
    }

    private static String clean(String s, String fallback) {
        s = s.replaceAll("[^A-Za-z0-9 _.()-]", "").trim();
        while (s.startsWith(".") || s.startsWith("_")) s = s.substring(1); // "_" folders are caches
        return s.length() == 0 ? fallback : s;
    }

    /** Unpacks into maps/SET/. Only z/x/y image entries are taken; existing tiles are kept. */
    public static int importInto(File pack, File setDir) throws IOException {
        int n = 0;
        ZipInputStream zi = new ZipInputStream(new BufferedInputStream(new FileInputStream(pack)));
        try {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = zi.getNextEntry()) != null) {
                Matcher m = TILE.matcher(e.getName());
                if (e.isDirectory() || !m.matches()) continue;
                File out = new File(setDir, e.getName());
                if (out.exists()) continue;
                out.getParentFile().mkdirs();
                OutputStream os = new FileOutputStream(out);
                try {
                    int k;
                    while ((k = zi.read(buf)) > 0) os.write(buf, 0, k);
                } finally {
                    os.close();
                }
                n++;
            }
        } finally {
            zi.close();
        }
        return n;
    }

    /** "Import these map tiles?" then unpacks in the background and says how many. */
    public static void offerImport(final Activity a, final File pack) {
        final String set = setName(pack);
        new AlertDialog.Builder(a)
                .setTitle("Map tiles")
                .setMessage("Import these map tiles into the offline map set \"" + set + "\"?\n\n"
                        + "They join any tiles already there and show on the map without internet.")
                .setPositiveButton("Import", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        new AsyncTask<Void, Void, String>() {
                            @Override
                            protected String doInBackground(Void... v) {
                                try {
                                    File dir = new File(MapPrefs.mapsRoot(a), set);
                                    int n = importInto(pack, dir);
                                    MapPrefs mp = new MapPrefs(a);
                                    if (mp.offlineSetName().length() == 0) mp.setOfflineSet(set);
                                    return "Imported " + n + " new tiles into \"" + set + "\""
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
}
