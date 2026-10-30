package org.communitycomms.app.geo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.Prefs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Map menu > Data Tile Management: what is stored (on this phone and the LAN maps cache), and
 * prefetching within 5 to 200 km of the map's +, down to a chosen zoom level. Each tile comes from the
 * first place that has it (this phone, the LAN cache and its named sets, then the internet); anything
 * the LAN cache is missing is given to it, so other phones get it there.
 */
public final class TilePrefetch {
    private TilePrefetch() {
    }

    /** Zoom levels fetched: country view down to house level, or to a shallower level for a big area. */
    public static final int MIN_Z = 8, MAX_Z = 17;
    /** Default deepest level for an area bigger than this many km: every level would be millions of tiles. */
    private static final int BIG_KM = 20, BIG_MAX_Z = 14;
    private static final int[] DETAIL_Z = {17, 16, 15, 14, 13, 12};
    private static final String[] DETAIL_NAMES = {"houses and footpaths", "buildings", "street names", "all roads and tracks", "main roads and towns", "highways and big towns"};
    /** Between internet requests: public tile servers do not allow fast bulk fetching. */
    private static final long NET_GAP_MS = 200;
    /** Super slow: for huge areas, days of gentle fetching so a public server is not annoyed. After a refused tile, a long rest. */
    private static final long SLOW_GAP_MS = 2000, SLOW_REST_S = 30;
    /** Typical size of a stored tile, for the estimate. */
    private static final int KB_PER_TILE = 12;

    public static final class Job {
        public final int km, total;
        public volatile int done, local, fromLan, fromNet, sent, failed;
        public volatile boolean finished, paused;
        volatile boolean stop;
        public volatile String error;
        /** What the screens call it, and (server sync) where it has got to. */
        public String title;
        public volatile String where = "";

        public void stop() {
            stop = true;
            paused = false;
        }

        public boolean stopped() {
            return stop;
        }

        Job(int km, int total) {
            this.km = km;
            this.total = total;
            title = km + " km prefetch";
        }

        /** One line for the notification. */
        public String line() {
            if (km == 0) return (paused ? "Paused · " : "") + sent + " copied · " + local + " already there" + (failed > 0 ? " · " + failed + " failed" : "");
            return (paused ? "Paused at " : "") + done + " of " + total + " tiles · " + fromLan + " LAN · " + fromNet + " internet";
        }
    }

    /** The job running (or last run), so the screen can be left and come back to it. */
    public static volatile Job job;

    /** Calls t.tile for each tile within km of the point, every level. */
    private interface Each {
        boolean tile(int z, int x, int y) throws Exception;
    }

    private static void walk(double lat, double lon, double km, int maxZ, Each t) throws Exception {
        double dLat = km / 111.32, dLon = km / (111.32 * Math.max(0.1, Math.cos(Math.toRadians(lat))));
        for (int z = MIN_Z; z <= maxZ; z++) {
            int x0 = (int) Geo.lonToX(lon - dLon, z), x1 = (int) Geo.lonToX(lon + dLon, z);
            int y0 = (int) Geo.latToY(lat + dLat, z), y1 = (int) Geo.latToY(lat - dLat, z);
            double cx = Geo.lonToX(lon, z), cy = Geo.latToY(lat, z);
            double rx = cx - Geo.lonToX(lon - dLon, z), ry = cy - Geo.latToY(lat + dLat, z);
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    // Inside the circle (tile centre, with a tile's width to spare).
                    double ex = (x + 0.5 - cx) / (rx + 1), ey = (y + 0.5 - cy) / (ry + 1);
                    if (ex * ex + ey * ey > 1) continue;
                    if (!t.tile(z, x, y)) return;
                }
            }
        }
    }

    /** The tiles walk would visit, a column at a time: quick even for millions of tiles. */
    public static int count(double lat, double lon, double km, int maxZ) {
        double dLat = km / 111.32, dLon = km / (111.32 * Math.max(0.1, Math.cos(Math.toRadians(lat))));
        int n = 0;
        for (int z = MIN_Z; z <= maxZ; z++) {
            int x0 = (int) Geo.lonToX(lon - dLon, z), x1 = (int) Geo.lonToX(lon + dLon, z);
            int y0 = (int) Geo.latToY(lat + dLat, z), y1 = (int) Geo.latToY(lat - dLat, z);
            double cx = Geo.lonToX(lon, z), cy = Geo.latToY(lat, z);
            double rx = cx - Geo.lonToX(lon - dLon, z), ry = cy - Geo.latToY(lat + dLat, z);
            for (int x = x0; x <= x1; x++) {
                double ex = (x + 0.5 - cx) / (rx + 1);
                if (ex * ex > 1) continue;
                double h = Math.sqrt(1 - ex * ex) * (ry + 1);
                int top = Math.max(y0, (int) Math.ceil(cy - 0.5 - h)), bottom = Math.min(y1, (int) Math.floor(cy - 0.5 + h));
                if (bottom >= top) n += bottom - top + 1;
            }
        }
        return n;
    }

    private static boolean haveLocal(File cache, int z, int x, int y) {
        String base = z + File.separator + x + File.separator + y;
        for (String ext : new String[]{".jpg", ".png", ".jpeg", ".webp"}) {
            if (new File(cache, base + ext).isFile()) return true;
        }
        return false;
    }

    private static void save(File cache, int z, int x, int y, byte[] data) throws Exception {
        File out = new File(cache, z + "/" + x + "/" + y + ".jpg");
        out.getParentFile().mkdirs();
        FileOutputStream os = new FileOutputStream(out);
        os.write(data);
        os.close();
    }

    private static byte[] fromNet(String url, int z, int x, int y) throws Exception {
        String u = url.replace("{z}", String.valueOf(z)).replace("{x}", String.valueOf(x)).replace("{y}", String.valueOf(y));
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        Tls12SocketFactory.apply(c);
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "YourCommunityCommunications/0.2 (Android; sideloaded field radio app)");
        try {
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static SharedPreferences saved(Context c) {
        return c.getSharedPreferences("prefetch", Context.MODE_PRIVATE);
    }

    /** The prefetch saved as unfinished (the app was killed part way), or null: {lat, lon, km}. */
    public static double[] unfinished(Context c) {
        SharedPreferences s = saved(c);
        if (!s.contains("km")) return null;
        return new double[]{Double.longBitsToDouble(s.getLong("lat", 0)), Double.longBitsToDouble(s.getLong("lon", 0)), s.getInt("km", 5)};
    }

    /** Carries on a saved prefetch (tiles already fetched are skipped). False if there is none. */
    public static boolean resume(Context c) {
        double[] u = unfinished(c);
        if (u == null) return false;
        if (u[2] == 0) beginSync(c, saved(c).getInt("sync", 0));
        else begin(c, u[0], u[1], (int) u[2], saved(c).getString("types", "0"), saved(c).getInt("maxZ", MAX_Z), saved(c).getBoolean("slow", false));
        return true;
    }

    // ---- copying map data between the two map servers (only the maps cache folder, nothing else) ----

    public static final int PRIMARY_TO_SECONDARY = 0, SECONDARY_TO_PRIMARY = 1, BOTH_WAYS = 2;

    /** Copies every tile the other server lacks, through this phone; never deletes. Runs in TilePrefetchService. */
    public static Job beginSync(Context ctx, final int mode) {
        Job old = job;
        if (old != null && !old.finished) return old;
        final Context c = ctx.getApplicationContext();
        saved(c).edit().putInt("km", 0).putInt("sync", mode).commit();
        final Job j = new Job(0, 0);
        j.title = "Map server sync";
        job = j;
        new Thread(new Runnable() {
            @Override
            public void run() {
                LanTiles a = new LanTiles(c), b = LanTiles.secondary(c);
                try {
                    if (!a.configured() || b == null) throw new Exception("both map servers must be set");
                    if (mode != SECONDARY_TO_PRIMARY) copyAll(a, b, j);
                    if (mode != PRIMARY_TO_SECONDARY) copyAll(b, a, j);
                } catch (Exception e) {
                    j.error = e.getMessage();
                }
                saved(c).edit().clear().commit();
                j.finished = true;
            }
        }, "tile-server-sync").start();
        return j;
    }

    private static List<String> list(LanTiles s, String rel) throws Exception {
        List<String> l = s.listMaps(rel);
        if (l == null) throw new Exception("a server stopped answering (or has no folder listings): start the sync again to carry on");
        return l;
    }

    private static void copyAll(LanTiles from, LanTiles to, Job j) throws Exception {
        for (String set : list(from, "")) {
            if (!set.endsWith("/") || set.startsWith("_")) continue;
            String s = set.substring(0, set.length() - 1);
            for (String z : list(from, s)) {
                if (!z.endsWith("/")) continue;
                String zz = z.substring(0, z.length() - 1);
                for (String x : list(from, s + "/" + zz)) {
                    if (!x.endsWith("/")) continue;
                    String xx = x.substring(0, x.length() - 1);
                    String rel = s + "/" + zz + "/" + xx;
                    j.where = s + " zoom " + zz;
                    List<String> have = list(to, rel);
                    for (String y : list(from, rel)) {
                        while (j.paused && !j.stop) Thread.sleep(500);
                        if (j.stop) return;
                        if (!y.endsWith(".jpg")) continue;
                        if (have.contains(y)) {
                            j.local++;
                        } else {
                            byte[] d = from.get(s, Integer.parseInt(zz), Integer.parseInt(xx), Integer.parseInt(y.substring(0, y.length() - 4)));
                            if (d != null && to.put(s, Integer.parseInt(zz), Integer.parseInt(xx), Integer.parseInt(y.substring(0, y.length() - 4)), d)) j.sent++;
                            else j.failed++;
                        }
                        j.done++;
                    }
                }
            }
        }
    }

    /**
     * Once: removes satellite tiles a few earlier versions saved in a street or topo map's folder on this
     * phone (OpenStreetMap and OpenTopoMap tiles are always PNG, so a JPEG there is out of place).
     */
    public static void cleanMixedCache(Context ctx) {
        final Context c = ctx.getApplicationContext();
        final SharedPreferences s = saved2(c);
        if (s.getBoolean("mixedCleaned", false)) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                MapPrefs mp = new MapPrefs(c);
                String[] urls = {MapPrefs.ONLINE_URLS[0], MapPrefs.ONLINE_URLS[3]};
                for (String u : urls) dropJpegs(mp.cacheOf(u));
                s.edit().putBoolean("mixedCleaned", true).commit();
            }
        }, "tile-clean").start();
    }

    private static SharedPreferences saved2(Context c) {
        return c.getSharedPreferences("map", Context.MODE_PRIVATE);
    }

    private static void dropJpegs(File d) {
        File[] f = d.listFiles();
        if (f == null) return;
        for (File x : f) {
            if (x.isDirectory()) {
                dropJpegs(x);
                continue;
            }
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(x);
                int a = in.read(), b = in.read();
                in.close();
                if (a == 0xff && b == 0xd8) x.delete();
            } catch (Exception ignored) {
            }
        }
    }

    /** Map types that can be prefetched: names and their MapPrefs presets. */
    static final String[] TYPE_NAMES = {"Street map (OpenStreetMap)", "Satellite (Google, with labels)", "Topographic (OpenTopoMap)"};
    static final int[] TYPE_PRESETS = {0, 1, 3};

    /** Runs in TilePrefetchService; one job at a time. types = presets, comma separated (e.g. "0,1"). */
    public static Job begin(Context ctx, final double lat, final double lon, final int km, final String types, final int maxZ, final boolean slow) {
        Job old = job;
        if (old != null && !old.finished) return old;
        final Context c = ctx.getApplicationContext();
        saved(c).edit().putLong("lat", Double.doubleToLongBits(lat)).putLong("lon", Double.doubleToLongBits(lon)).putInt("km", km)
                .putString("types", types).putInt("maxZ", maxZ).putBoolean("slow", slow).commit();
        final String[] list = types.split(",");
        final Job j = new Job(km, count(lat, lon, km, maxZ) * list.length);
        if (slow) j.title = km + " km prefetch (super slow)";
        job = j;
        new Thread(new Runnable() {
            @Override
            public void run() {
                MapPrefs mp = new MapPrefs(c);
                try {
                    for (String t : list) {
                        int preset = Integer.parseInt(t.trim());
                        String url = mp.urlOfPreset(preset);
                        for (int i = 0; i < TYPE_PRESETS.length; i++) if (TYPE_PRESETS[i] == preset) j.where = TYPE_NAMES[i];
                        fetchType(c, j, url, mp.cacheOf(url), lat, lon, km, maxZ, slow);
                        if (j.stop) break;
                    }
                } catch (Exception e) {
                    j.error = e.getMessage();
                }
                saved(c).edit().clear().commit();
                j.finished = true;
            }
        }, "tile-prefetch").start();
        return j;
    }

    /** One map type: each tile from this phone, the LAN cache, then the internet; the LAN cache gets what it lacks. */
    private static void fetchType(Context c, final Job j, final String url, final File cache, double lat, double lon, int km, int maxZ, final boolean slow) throws Exception {
        final LanTiles lan = new LanTiles(c), second = LanTiles.secondary(c);
        final boolean useLan = lan.configured() || second != null;
        final String src = LanTiles.sourceId(url);
        List<String> found = lan.configured() ? lan.namedSets() : new ArrayList<String>();
        if (found.isEmpty() && second != null) found = second.namedSets();
        final List<String> sets = LanTiles.isImagery(url) ? found : new ArrayList<String>(); // satellite sets only suit a satellite map
        final long[] lastNet = {0};
        walk(lat, lon, km, maxZ, new Each() {
            @Override
            public boolean tile(int z, int x, int y) throws Exception {
                while (j.paused && !j.stop) Thread.sleep(500);
                if (j.stop) return false;
                if (haveLocal(cache, z, x, y)) {
                    j.local++;
                    // Already here: make sure the LAN cache has it too.
                    File f = new File(cache, z + "/" + x + "/" + y + ".jpg");
                    if (lan.configured() && f.isFile() && !lan.has(src, z, x, y) && lan.put(src, z, x, y, f)) j.sent++;
                } else {
                    byte[] d = null;
                    if (useLan) {
                        d = lan.configured() ? lan.get(src, z, x, y) : null;
                        if (d == null && second != null) d = second.get(src, z, x, y);
                        for (int i = 0; d == null && i < sets.size(); i++) {
                            d = lan.configured() ? lan.get(sets.get(i), z, x, y) : null;
                            if (d == null && second != null) d = second.get(sets.get(i), z, x, y);
                        }
                    }
                    if (d != null) {
                        j.fromLan++;
                        save(cache, z, x, y, d);
                    } else {
                        long wait = (slow ? SLOW_GAP_MS : NET_GAP_MS) - (System.currentTimeMillis() - lastNet[0]);
                        if (wait > 0) Thread.sleep(wait);
                        lastNet[0] = System.currentTimeMillis();
                        try {
                            d = fromNet(url, z, x, y);
                        } catch (Exception e) {
                            d = null;
                        }
                        if (d == null) {
                            j.failed++;
                            for (int i = 0; slow && i < SLOW_REST_S && !j.stop; i++) Thread.sleep(1000);
                        } else {
                            j.fromNet++;
                            save(cache, z, x, y, d);
                            // The primary, or the secondary map server if the primary is off.
                            if ((lan.configured() && lan.put(src, z, x, y, d)) || (second != null && second.put(src, z, x, y, d))) j.sent++;
                        }
                    }
                }
                j.done++;
                return true;
            }
        });
    }

    // ---- the screen ----

    private static String size(long bytes) {
        return bytes >= 1024 * 1024 * 1024 ? String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
                : String.format(Locale.US, "%.0f MB", bytes / 1048576.0);
    }

    private static void measure(File d, long[] out) {
        File[] f = d.listFiles();
        if (f == null) return;
        for (File x : f) {
            if (x.isDirectory()) {
                measure(x, out);
            } else {
                out[0]++;
                out[1] += x.length();
            }
        }
    }

    /** Map menu > Data Tile Management. lat, lon = the map's +. after = refresh the map when done. */
    public static void show(final Activity a, final double lat, final double lon, final Runnable after) {
        float d = a.getResources().getDisplayMetrics().density;
        final MapPrefs mp = new MapPrefs(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        final TextView stats = new TextView(a);
        stats.setText("Counting the tiles…");
        box.addView(stats);
        TextView h = new TextView(a);
        h.setText("\nPrefetch around the + (" + Geo.latLon(lat, lon) + "). You choose how much detail next:");
        box.addView(h);
        LinearLayout row = null;
        int[] ranges = {5, 10, 20, 50, 100, 200};
        for (int i = 0; i < ranges.length; i++) {
            final int km = ranges[i];
            if (i % 3 == 0) {
                row = new LinearLayout(a);
                box.addView(row);
            }
            Button b = new Button(a);
            b.setAllCaps(false);
            b.setText(km + " km");
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirm(a, lat, lon, km, after);
                }
            });
            row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        Job now = job;
        final double[] left = unfinished(a);
        if (now != null && !now.finished) {
            Button b = new Button(a);
            b.setAllCaps(false);
            b.setText((now.paused ? "Paused: " : "Running: ") + now.title + " (" + now.done + (now.total > 0 ? " of " + now.total : " tiles") + "): open");
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    progress(a, job, after);
                }
            });
            box.addView(b);
        } else if (left != null) {
            Button b = new Button(a);
            b.setAllCaps(false);
            b.setText(left[2] == 0 ? "Carry on the unfinished map server sync" : "Carry on the unfinished " + (int) left[2] + " km prefetch");
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (left[2] == 0) org.communitycomms.app.TilePrefetchService.startSync(a, saved(a).getInt("sync", 0));
                    else org.communitycomms.app.TilePrefetchService.start(a, left[0], left[1], (int) left[2], saved(a).getString("types", "0"), saved(a).getInt("maxZ", MAX_Z),
                            saved(a).getBoolean("slow", false));
                    a.getWindow().getDecorView().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (job != null) progress(a, job, after);
                        }
                    }, 800);
                }
            });
            box.addView(b);
        }
        TextView sh = new TextView(a);
        sh.setText("\nSecondary map server (map tiles only): used if the primary is off, and for moving map data to a new server.");
        box.addView(sh);
        LinearLayout row2 = new LinearLayout(a);
        Button second = new Button(a), sync = new Button(a);
        second.setAllCaps(false);
        sync.setAllCaps(false);
        second.setText("Secondary server…");
        sync.setText("Sync the two servers…");
        second.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editSecondary(a);
            }
        });
        sync.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseSync(a, after);
            }
        });
        row2.addView(second, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row2.addView(sync, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        box.addView(row2);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a).setTitle("Data Tile Management").setView(sv)
                .setPositiveButton(android.R.string.ok, null).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder s = new StringBuilder();
                long[] m = new long[2];
                measure(mp.onlineCache(), m);
                int preset = Math.max(0, Math.min(MapPrefs.ONLINE_NAMES.length - 1, mp.onlinePreset()));
                s.append("Map source: ").append(MapPrefs.ONLINE_NAMES[preset].split(" \\(")[0]).append('\n');
                s.append("On this phone: ").append(m[0]).append(" tiles, ").append(size(m[1])).append('\n');
                String off = mp.offlineSetName();
                s.append("Offline set: ").append(off.length() > 0 ? off : "none").append('\n');
                LanTiles lan = new LanTiles(a);
                if (lan.configured()) {
                    s.append("LAN maps cache: ").append(lan.base()).append('\n');
                    List<String> sets = lan.namedSets();
                    if (!sets.isEmpty()) s.append("Named sets there (used with a satellite map): ").append(sets).append('\n');
                    s.append("Since the app started: ").append(TileMapView.lanGot.get()).append(" tiles from it, ")
                            .append(TileMapView.lanSent.get()).append(" given to it");
                    if (TileMapView.lanFailed.get() > 0) s.append(", ").append(TileMapView.lanFailed.get()).append(" failed");
                } else {
                    s.append("LAN maps cache: not set up");
                }
                LanTiles two = LanTiles.secondary(a);
                s.append("\nSecondary map server: ").append(two == null ? "none" : two.base());
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        stats.setText(s);
                    }
                });
            }
        }, "tile-stats").start();
    }

    private static void editSecondary(final Activity a) {
        float d = a.getResources().getDisplayMetrics().density;
        LanTiles cur = LanTiles.secondary(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        TextView t = new TextView(a);
        t.setText("Another LAN storage server for map tiles only (mission data stays on the primary). Same kind of address as the "
                + "primary, e.g. http://<server>:8647/YCC");
        box.addView(t);
        final EditText url = new EditText(a), user = new EditText(a), pass = new EditText(a);
        url.setSingleLine(true);
        url.setHint("Address");
        url.setText(cur == null ? "" : cur.base());
        user.setSingleLine(true);
        user.setHint("User (optional)");
        user.setText(cur == null ? "" : cur.user());
        pass.setSingleLine(true);
        pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setHint(cur != null && cur.hasPassword() ? "(saved: leave empty to keep it)" : "Password");
        box.addView(url);
        box.addView(user);
        box.addView(pass);
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("Secondary map server").setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String p = pass.getText().toString();
                        LanTiles.saveSecondary(a, url.getText().toString(), user.getText().toString(), p.length() > 0 ? p : null);
                        Toast.makeText(a, url.getText().toString().trim().length() > 0 ? "Secondary map server saved" : "Secondary map server removed",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("Test", null)
                .setNegativeButton(android.R.string.cancel, null).show();
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String u = url.getText().toString().trim(), us = user.getText().toString().trim(), p = pass.getText().toString();
                final LanTiles saved = LanTiles.secondary(a);
                Toast.makeText(a, "Testing…", Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String r = new LanTiles(a, u, us, p.length() > 0 || saved == null ? p : saved.passwordForTest()).test();
                        a.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                new AlertDialog.Builder(a).setTitle("Test").setMessage(r == null ? "Working." : r)
                                        .setPositiveButton(android.R.string.ok, null).show();
                            }
                        });
                    }
                }, "lan2-test").start();
            }
        });
    }

    private static void chooseSync(final Activity a, final Runnable after) {
        LanTiles b = LanTiles.secondary(a);
        if (!new LanTiles(a).configured() || b == null) {
            Toast.makeText(a, "Set both the LAN storage and the secondary map server first", Toast.LENGTH_LONG).show();
            return;
        }
        Job running = job;
        if (running != null && !running.finished) {
            progress(a, running, after);
            return;
        }
        final String p = new LanTiles(a).base(), s = b.base();
        new AlertDialog.Builder(a).setTitle("Sync the two map servers")
                .setItems(new String[]{"Primary to secondary (e.g. fill a new server)", "Secondary to primary", "Both ways"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface di, final int mode) {
                                new AlertDialog.Builder(a).setTitle("Start the sync?")
                                        .setMessage("Copies map tiles only (no mission data) through this phone, on WiFi with no internet needed:\n"
                                                + p + "\n" + s + "\n\nOnly tiles the other server lacks are copied and nothing is deleted. "
                                                + "A big map set takes hours and a lot of data through this phone: stay on fast WiFi, on charge, near "
                                                + "both servers. It runs in the background and can be paused.")
                                        .setPositiveButton("Sync", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface d2, int w) {
                                                org.communitycomms.app.TilePrefetchService.startSync(a, mode);
                                                a.getWindow().getDecorView().postDelayed(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        if (job != null) progress(a, job, after);
                                                    }
                                                }, 800);
                                            }
                                        })
                                        .setNegativeButton(android.R.string.cancel, null).show();
                            }
                        }).show();
    }

    private static void confirm(final Activity a, final double lat, final double lon, final int km, final Runnable after) {
        if (new Prefs(a).radioSilent()) {
            Toast.makeText(a, "Radio silent is on: no prefetch", Toast.LENGTH_LONG).show();
            return;
        }
        if (new MapPrefs(a).onlineUrl().length() == 0) {
            Toast.makeText(a, "No online map source set (Data / files > Maps)", Toast.LENGTH_LONG).show();
            return;
        }
        Job running = job;
        if (running != null && !running.finished) {
            progress(a, running, after);
            return;
        }
        float dp = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * dp), (int) (8 * dp), (int) (20 * dp), 0);
        TextView top = new TextView(a);
        top.setText("High speed connection required: this will cause a lot of data from the LAN cache and the internet.\n\nDetail (the deepest zoom level to get):");
        box.addView(top);
        final android.widget.RadioGroup detail = new android.widget.RadioGroup(a);
        final int wanted = km > BIG_KM ? BIG_MAX_Z : MAX_Z;
        for (int i = 0; i < DETAIL_Z.length; i++) {
            android.widget.RadioButton r = new android.widget.RadioButton(a);
            r.setId(DETAIL_Z[i]);
            r.setText(String.format(Locale.US, "Zoom %d: %s (%,d tiles)", DETAIL_Z[i], DETAIL_NAMES[i], count(lat, lon, km, DETAIL_Z[i])));
            detail.addView(r);
        }
        detail.check(wanted);
        box.addView(detail);
        TextView types = new TextView(a);
        types.setText("\nMap types to get:");
        box.addView(types);
        final android.widget.CheckBox[] ticks = new android.widget.CheckBox[TYPE_NAMES.length];
        final TextView size = new TextView(a);
        final android.widget.CheckBox slow = new android.widget.CheckBox(a);
        slow.setText("Super slow: one internet tile every 2 seconds, resting after a refused tile. Huge areas take days, far less likely to get blocked. Keep it on charge.");
        slow.setChecked(km > BIG_KM);
        final Runnable showSize = new Runnable() {
            @Override
            public void run() {
                int kinds = 0;
                for (android.widget.CheckBox b : ticks) if (b.isChecked()) kinds++;
                int z = detail.getCheckedRadioButtonId();
                long n = (long) count(lat, lon, km, z) * kinds;
                double hours = n * (slow.isChecked() ? SLOW_GAP_MS : NET_GAP_MS) / 3600000.0;
                size.setText(String.format(Locale.US, "\nUp to %,d tiles, about %,d MB (satellite tiles are larger), zoom levels %d to %d. All from the internet "
                        + "would take about %s. Tiles already on this phone or in the LAN cache are not downloaded again, and anything the "
                        + "LAN cache is missing is added to it.\n\nInternet tiles are fetched slowly (at most 5 a second, or 1 every 2 seconds on super slow) because public map servers do "
                        + "not allow fast bulk downloads, and a very large fetch can get this phone blocked by them. Stay on WiFi; it can stop and carry on later.",
                        n, n * KB_PER_TILE / 1024, MIN_Z, z, hours >= 48 ? String.format(Locale.US, "%.1f days", hours / 24) : String.format(Locale.US, "%.1f hours", hours)));
            }
        };
        slow.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                showSize.run();
            }
        });
        detail.setOnCheckedChangeListener(new android.widget.RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.RadioGroup g, int id) {
                showSize.run();
            }
        });
        int cur = new MapPrefs(a).onlinePreset();
        boolean any = false;
        for (int i = 0; i < ticks.length; i++) {
            ticks[i] = new android.widget.CheckBox(a);
            ticks[i].setText(TYPE_NAMES[i]);
            ticks[i].setChecked(TYPE_PRESETS[i] == cur);
            any |= ticks[i].isChecked();
            ticks[i].setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    showSize.run();
                }
            });
            box.addView(ticks[i]);
        }
        if (!any) ticks[0].setChecked(true);
        box.addView(slow);
        box.addView(size);
        showSize.run();
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a).setTitle("Prefetch " + km + " km?").setView(sv)
                .setPositiveButton("Prefetch", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        StringBuilder chosen = new StringBuilder();
                        for (int i = 0; i < ticks.length; i++) {
                            if (ticks[i].isChecked()) chosen.append(chosen.length() > 0 ? "," : "").append(TYPE_PRESETS[i]);
                        }
                        if (chosen.length() == 0) {
                            Toast.makeText(a, "Tick at least one map type", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        org.communitycomms.app.TilePrefetchService.start(a, lat, lon, km, chosen.toString(), detail.getCheckedRadioButtonId(), slow.isChecked());
                        a.getWindow().getDecorView().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (job != null) progress(a, job, after);
                            }
                        }, 800);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    /** The progress box: Background leaves it running (the notification shows it), and it can be opened again here. */
    private static void progress(final Activity a, final Job j, final Runnable after) {
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle(j.title).setMessage("Starting…")
                .setCancelable(false)
                .setPositiveButton("Background", null)
                .setNeutralButton(j.paused ? "Resume" : "Pause", null)
                .setNegativeButton("Stop", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        j.stop();
                    }
                }).show();
        final Button pause = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
        pause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                j.paused = !j.paused;
                pause.setText(j.paused ? "Resume" : "Pause");
            }
        });
        final android.os.Handler h = new android.os.Handler();
        h.post(new Runnable() {
            @Override
            public void run() {
                if (!dlg.isShowing()) {
                    if (after != null) after.run();
                    return;
                }
                dlg.setMessage((j.paused ? "Paused\n" : "") + (j.km == 0 ? "Copied: " + j.sent + "\nAlready there: " + j.local + "\nNow on: " + j.where
                        : (j.where.length() > 0 ? "Now: " + j.where + "\n" : "") + j.done + " of " + j.total + " tiles\nAlready here: " + j.local + "\nFrom the LAN cache: " + j.fromLan
                        + "\nFrom the internet: " + j.fromNet + "\nGiven to the LAN cache: " + j.sent)
                        + (j.failed > 0 ? "\nFailed: " + j.failed : "")
                        + (j.finished ? "\n\n" + (j.stopped() ? "Stopped." : j.error != null ? "Stopped: " + j.error : "Done.") : ""));
                if (j.finished) {
                    dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setVisibility(View.GONE);
                    pause.setVisibility(View.GONE);
                    dlg.getButton(AlertDialog.BUTTON_POSITIVE).setText(android.R.string.ok);
                }
                h.postDelayed(this, 700);
            }
        });
    }
}
