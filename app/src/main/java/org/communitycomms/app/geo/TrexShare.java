package org.communitycomms.app.geo;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sharing the T.REX Arms Topo Map through the LAN cache server, in its own folder (not the Maps cache,
 * not TAK): "T.REX Arms Topo Map/NAME.mbtiles" (the whole file, for Download), NAME.mbtiles.info (its
 * name, zooms, bounds and credit), and tiles/z/x/y.jpg (each tile, so other phones can view the map as
 * they pan before, or without, downloading the file). One upload or download at a time, in the
 * background; the screen shows {@link #status()}.
 */
public final class TrexShare {
    private TrexShare() {
    }

    public static final String FOLDER = "T.REX Arms Topo Map";
    private static final long STALE_MS = 5 * 60 * 1000L;

    /** A file on the cache server, from its .info. */
    public static final class Remote {
        public String file = "", title = "", attribution = "";
        public long size = -1, tiles, tilesShared;
        public int minZoom = 0, maxZoom = -1;
        public double west = -180, south = -85.0511, east = 180, north = 85.0511;
        public boolean hasBounds, tilesDone;

        boolean covers(int z, int x, int y) {
            if (z < minZoom || z > maxZoom || tilesShared == 0) return false;
            if (!hasBounds) return true;
            double w = Geo.xToLon(x, z), e = Geo.xToLon(x + 1, z), n = Geo.yToLat(y, z), s = Geo.yToLat(y + 1, z);
            return e >= west && w <= east && n >= south && s <= north;
        }

        public boolean overlaps(double n, double w, double s, double e) {
            return e >= west && w <= east && n >= south && s <= north;
        }
    }

    static String tilePath(int z, int x, int y) {
        return FOLDER + "/tiles/" + z + "/" + x + "/" + y + ".jpg";
    }

    // ---- status for the screen ----

    private static volatile String status = "";
    private static volatile boolean busy;

    public static String status() {
        return status;
    }

    public static boolean busy() {
        return busy;
    }

    // ---- .info: one key=value per line ----

    static String info(TrexMaps.Entry e, long shared, boolean done) {
        return "title=" + e.title().replace('\n', ' ') + "\nsize=" + e.file.length() + "\ntiles=" + e.tiles
                + "\nminzoom=" + e.minZoom + "\nmaxzoom=" + e.maxZoom
                + (e.hasBounds ? String.format(Locale.US, "\nbounds=%.6f,%.6f,%.6f,%.6f", e.west, e.south, e.east, e.north) : "")
                + "\nattribution=" + e.attribution.replace('\n', ' ') + "\ntilesShared=" + shared + "\ntilesDone=" + (done ? "yes" : "no") + "\n";
    }

    static Remote parse(String file, String text) {
        Remote r = new Remote();
        r.file = file;
        r.title = file.replaceAll("(?i)\\.mbtiles$", "");
        if (text == null) return r;
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
            try {
                if (k.equals("title") && v.length() > 0) r.title = v;
                else if (k.equals("size")) r.size = Long.parseLong(v);
                else if (k.equals("tiles")) r.tiles = Long.parseLong(v);
                else if (k.equals("minzoom")) r.minZoom = Integer.parseInt(v);
                else if (k.equals("maxzoom")) r.maxZoom = Integer.parseInt(v);
                else if (k.equals("attribution")) r.attribution = v;
                else if (k.equals("tilesShared")) r.tilesShared = Long.parseLong(v);
                else if (k.equals("tilesDone")) r.tilesDone = v.equals("yes");
                else if (k.equals("bounds")) {
                    String[] p = v.split(",");
                    if (p.length == 4) {
                        r.west = Double.parseDouble(p[0]);
                        r.south = Double.parseDouble(p[1]);
                        r.east = Double.parseDouble(p[2]);
                        r.north = Double.parseDouble(p[3]);
                        r.hasBounds = true;
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return r;
    }

    // ---- the list of files on the cache server (kept on the phone so the map needs no network to start) ----

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("map", Context.MODE_PRIVATE);
    }

    /** The last list read from the cache server (may be empty; never null). */
    public static List<Remote> remote(Context c) {
        List<Remote> out = new ArrayList<Remote>();
        String all = sp(c).getString("trexRemote", "");
        if (all.length() == 0) return out;
        for (String block : all.split("\n\u0001\n")) {
            int nl = block.indexOf('\n');
            if (nl <= 0) continue;
            out.add(parse(block.substring(0, nl), block.substring(nl + 1)));
        }
        return out;
    }

    private static void saveRemote(Context c, List<String[]> fileAndInfo) {
        StringBuilder sb = new StringBuilder();
        for (String[] fi : fileAndInfo) {
            if (sb.length() > 0) sb.append("\n\u0001\n");
            sb.append(fi[0]).append('\n').append(fi[1]);
        }
        sp(c).edit().putString("trexRemote", sb.toString()).putLong("trexRemoteAt", System.currentTimeMillis()).commit();
    }

    /** Reads the list from the cache server (network: not on the main thread). Null if it could not be asked. */
    public static List<Remote> refresh(Context c) {
        LanTiles lan = new LanTiles(c);
        if (!lan.configured()) return null;
        List<String> names = lan.listPath(FOLDER);
        if (names == null) return null;
        List<String[]> found = new ArrayList<String[]>();
        for (String n : names) {
            if (!n.toLowerCase(Locale.US).endsWith(".mbtiles")) continue;
            byte[] info = lan.getPath(FOLDER + "/" + n + ".info");
            String text = info == null ? "" : new String(info, java.nio.charset.Charset.forName("UTF-8"));
            Remote r = parse(n, text);
            if (r.size < 0) text += "\nsize=" + lan.sizeOf(FOLDER + "/" + n);
            found.add(new String[]{n, text});
        }
        saveRemote(c.getApplicationContext(), found);
        return remote(c);
    }

    /** Refreshes the list in the background when it is old; done runs on that thread afterwards (if it changed). */
    public static void refreshIfStale(final Context c, final Runnable done) {
        if (!TrexMaps.enabled(c)) return; // only while T.REX is the chosen layer
        if (System.currentTimeMillis() - sp(c).getLong("trexRemoteAt", 0) < STALE_MS) return;
        if (!new LanTiles(c).configured()) return;
        sp(c).edit().putLong("trexRemoteAt", System.currentTimeMillis()).commit(); // one try at a time
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String before = sp(app).getString("trexRemote", "");
                if (refresh(app) != null && !before.equals(sp(app).getString("trexRemote", "")) && done != null) done.run();
            }
        }, "trex-list").start();
    }

    /** Files on the cache server that this phone has no copy of (to view from the server as it pans). */
    public static List<Remote> remoteOnly(Context c, List<TrexMaps.Entry> local) {
        List<Remote> out = new ArrayList<Remote>();
        for (Remote r : remote(c)) {
            boolean have = false;
            if (local != null) for (TrexMaps.Entry e : local) if (e.file.getName().equalsIgnoreCase(r.file)) have = true;
            if (!have) out.add(r);
        }
        return out;
    }

    // ---- upload ----

    /**
     * Sends an imported file to the cache server: the file, its .info, then every tile. Returns false
     * if something is already being sent or downloaded. Progress and the result are in {@link #status()}.
     */
    public static synchronized boolean startUpload(Context c, final TrexMaps.Entry e) {
        if (busy || !TrexMaps.menuOn(c)) return false;
        busy = true;
        final Context app = c.getApplicationContext();
        status = "Sending " + e.title() + " to the cache server…";
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    status = upload(app, e);
                } catch (RuntimeException ex) {
                    status = "Sending stopped: " + ex.getMessage();
                } finally {
                    busy = false;
                }
            }
        }, "trex-upload").start();
        return true;
    }

    private static String upload(Context app, TrexMaps.Entry e) {
        final LanTiles lan = new LanTiles(app);
        if (!lan.configured()) return "No cache server is set (Settings > LAN storage location), so " + e.title() + " is only on this phone.";
        final String name = e.file.getName(), title = e.title();
        boolean ok = lan.putPath(FOLDER + "/" + name, e.file, new LanTiles.Progress() {
            @Override
            public void update(String text) {
                status = "Sending " + title + " to the cache server: " + text;
            }
        });
        if (!ok) return "Could not send " + title + ": the cache server did not answer or would not store it. Check Settings > LAN storage location and try Send to cache server again.";
        lan.putPath(FOLDER + "/" + name + ".info", info(e, 0, false).getBytes(java.nio.charset.Charset.forName("UTF-8")));
        // Each tile, so other phones can view the map from the server as they pan.
        SQLiteDatabase d;
        try {
            d = SQLiteDatabase.openDatabase(e.file.getPath(), null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        } catch (RuntimeException ex) {
            return "The file is on the cache server, but its tiles could not be read for viewing (" + ex.getMessage() + ").";
        }
        final BlockingQueue<Object[]> q = new ArrayBlockingQueue<Object[]>(64);
        final Object[] end = new Object[0];
        final AtomicInteger sent = new AtomicInteger(), failed = new AtomicInteger();
        final long total = e.tiles;
        Thread[] workers = new Thread[4];
        for (int w = 0; w < workers.length; w++) {
            workers[w] = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        Object[] t;
                        try {
                            t = q.take();
                        } catch (InterruptedException ie) {
                            return;
                        }
                        if (t == end) return;
                        if (lan.putPath((String) t[0], (byte[]) t[1])) sent.incrementAndGet();
                        else failed.incrementAndGet();
                        int n = sent.get() + failed.get();
                        if (n % 50 == 0) status = "Sharing " + title + " tiles for viewing: " + n + " / " + total
                                + (failed.get() > 0 ? " (" + failed.get() + " failed)" : "");
                    }
                }
            }, "trex-tiles-" + w);
            workers[w].start();
        }
        try {
            Cursor c = d.rawQuery("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles", null);
            try {
                while (c.moveToNext()) {
                    if (failed.get() > 200 && sent.get() < failed.get()) break; // the server has gone away
                    int z = c.getInt(0), x = c.getInt(1), y = (1 << z) - 1 - c.getInt(2);
                    byte[] b = c.getBlob(3);
                    if (b == null || b.length == 0) continue;
                    q.put(new Object[]{tilePath(z, x, y), b});
                }
            } finally {
                c.close();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            android.util.Log.w("TrexShare", "tiles: " + ex.getMessage());
        } finally {
            d.close();
            for (int w = 0; w < workers.length; w++) {
                try {
                    q.put(end);
                } catch (InterruptedException ignored) {
                }
            }
            for (Thread w : workers) {
                try {
                    w.join();
                } catch (InterruptedException ignored) {
                }
            }
        }
        boolean done = failed.get() == 0 && sent.get() >= total;
        lan.putPath(FOLDER + "/" + name + ".info", info(e, sent.get(), done).getBytes(java.nio.charset.Charset.forName("UTF-8")));
        refresh(app);
        if (done) return "On the cache server: " + title + " (" + TrexMaps.size(e.file.length()) + ") and all "
                + String.format(Locale.US, "%,d", sent.get()) + " tiles for viewing.";
        return "On the cache server: " + title + ", but only " + sent.get() + " of " + total + " tiles were shared for viewing ("
                + failed.get() + " failed). Tap Send to cache server to try again.";
    }

    // ---- download ----

    /** Downloads a file from the cache server into the phone's T.REX folder and imports it. */
    public static synchronized boolean startDownload(Context c, final Remote r) {
        if (busy || !TrexMaps.menuOn(c)) return false;
        busy = true;
        final Context app = c.getApplicationContext();
        status = "Downloading " + r.title + "…";
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    status = download(app, r);
                } catch (RuntimeException ex) {
                    status = "Download stopped: " + ex.getMessage();
                } finally {
                    busy = false;
                }
            }
        }, "trex-download").start();
        return true;
    }

    private static String download(Context app, final Remote r) {
        LanTiles lan = new LanTiles(app);
        if (!lan.configured()) return "No cache server is set (Settings > LAN storage location).";
        File d = TrexMaps.dir(app);
        long size = r.size > 0 ? r.size : lan.sizeOf(FOLDER + "/" + r.file);
        if (size > 0 && d.getUsableSpace() < size + 8L * 1024 * 1024) {
            return "Not enough free space for " + r.title + " (" + TrexMaps.size(size) + " needed, " + TrexMaps.size(d.getUsableSpace()) + " free).";
        }
        // Kept between tries, so a stopped download can carry on where the server allows it.
        File part = new File(d, r.file.replaceAll("[^A-Za-z0-9 _.()-]", "") + ".part");
        String why = lan.getPathResumable(FOLDER + "/" + r.file, part, size, new LanTiles.Progress() {
            @Override
            public void update(String text) {
                status = "Downloading " + r.title + ": " + text;
            }
        });
        if (why.length() > 0) return "Download of " + r.title + " did not finish: " + why + ". Tap Download to try again.";
        try {
            TrexMaps.Entry e = TrexMaps.importTmp(app, part, r.file);
            return "Downloaded " + e.title() + " (" + TrexMaps.size(e.file.length()) + "): it is now on this phone for offline use.";
        } catch (java.io.IOException ex) {
            return "The downloaded file can't be used: " + ex.getMessage() + ". Nothing was kept.";
        }
    }
}
