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
 * prefetching every zoom level within 5, 10 or 20 km of the map's +. Each tile comes from the
 * first place that has it (this phone, the LAN cache and its named sets, then the internet); anything
 * the LAN cache is missing is given to it, so other phones get it there.
 */
public final class TilePrefetch {
    private TilePrefetch() {
    }

    /** Zoom levels fetched: country view down to house level. */
    public static final int MIN_Z = 10, MAX_Z = 17;
    /** Between internet requests: public tile servers do not allow fast bulk fetching. */
    private static final long NET_GAP_MS = 200;
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

    private static void walk(double lat, double lon, double km, Each t) throws Exception {
        double dLat = km / 111.32, dLon = km / (111.32 * Math.max(0.1, Math.cos(Math.toRadians(lat))));
        for (int z = MIN_Z; z <= MAX_Z; z++) {
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

    public static int count(double lat, double lon, double km) {
        final int[] n = {0};
        try {
            walk(lat, lon, km, new Each() {
                @Override
                public boolean tile(int z, int x, int y) {
                    n[0]++;
                    return true;
                }
            });
        } catch (Exception ignored) {
        }
        return n[0];
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
        else begin(c, u[0], u[1], (int) u[2]);
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

    /** Runs in TilePrefetchService; one job at a time. */
    public static Job begin(Context ctx, final double lat, final double lon, final int km) {
        Job old = job;
        if (old != null && !old.finished) return old;
        final Context c = ctx.getApplicationContext();
        saved(c).edit().putLong("lat", Double.doubleToLongBits(lat)).putLong("lon", Double.doubleToLongBits(lon)).putInt("km", km).commit();
        final LanTiles second = LanTiles.secondary(c);
        final MapPrefs mp = new MapPrefs(c);
        final String url = mp.onlineUrl();
        final File cache = mp.onlineCache();
        final LanTiles lan = new LanTiles(c);
        final Job j = new Job(km, count(lat, lon, km));
        job = j;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean useLan = lan.configured() || second != null;
                final String src = LanTiles.sourceId(url);
                List<String> found = lan.configured() ? lan.namedSets() : new ArrayList<String>();
                if (found.isEmpty() && second != null) found = second.namedSets();
                final List<String> sets = found;
                final long[] lastNet = {0};
                try {
                    walk(lat, lon, km, new Each() {
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
                                    long wait = NET_GAP_MS - (System.currentTimeMillis() - lastNet[0]);
                                    if (wait > 0) Thread.sleep(wait);
                                    lastNet[0] = System.currentTimeMillis();
                                    try {
                                        d = fromNet(url, z, x, y);
                                    } catch (Exception e) {
                                        d = null;
                                    }
                                    if (d == null) {
                                        j.failed++;
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
                } catch (Exception e) {
                    j.error = e.getMessage();
                }
                saved(c).edit().clear().commit();
                j.finished = true;
            }
        }, "tile-prefetch").start();
        return j;
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
        h.setText("\nPrefetch every zoom level (" + MIN_Z + " to " + MAX_Z + ") around the + (" + Geo.latLon(lat, lon) + "):");
        box.addView(h);
        LinearLayout row = new LinearLayout(a);
        for (final int km : new int[]{5, 10, 20}) {
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
        box.addView(row);
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
                    else org.communitycomms.app.TilePrefetchService.start(a, left[0], left[1], (int) left[2]);
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
                    if (!sets.isEmpty()) s.append("Named sets there: ").append(sets).append('\n');
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
        int n = count(lat, lon, km);
        long mb = n * (long) KB_PER_TILE / 1024;
        new AlertDialog.Builder(a).setTitle("Prefetch " + km + " km?")
                .setMessage("High speed connection required: this will cause a lot of data from the LAN cache and the internet.\n\n"
                        + "Up to " + n + " tiles, about " + mb + " MB, every zoom level " + MIN_Z + " to " + MAX_Z + ". Tiles already on this "
                        + "phone or in the LAN cache are not downloaded again, and anything the LAN cache is missing is added to it.\n\n"
                        + "Internet tiles are fetched slowly (at most 5 a second) because public map servers do not allow fast bulk "
                        + "downloads, so a large area can take hours. Stay on WiFi; it can stop and carry on later.")
                .setPositiveButton("Prefetch", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        org.communitycomms.app.TilePrefetchService.start(a, lat, lon, km);
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
                        : j.done + " of " + j.total + " tiles\nAlready here: " + j.local + "\nFrom the LAN cache: " + j.fromLan
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
