package org.communitycomms.app.geo;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Map tiles kept on a LAN storage location (a NAS, an OpenMANET node or any web server with WebDAV)
 * so phones share one tile collection even with no internet. Tiles live at
 * base/Maps cache/(source)/z/x/y.jpg. Maps read from it before going online, and every tile fetched
 * online is copied to it. Address, user and password are in Settings (the password is never shown
 * again); they go out with Share config only if the user agreed.
 * <p>
 * Plain HTTP(S) with WebDAV (GET, HEAD, PUT, MKCOL), so it needs no extra libraries and works on
 * Android 4.1. Windows file shares (SMB) are not spoken directly: point a WebDAV server at the share.
 */
public final class LanTiles {
    public static final String FOLDER = "Maps cache";
    private final SharedPreferences sp;
    /** Folders known to exist on the server (so MKCOL is not repeated). */
    private static final Set<String> made = Collections.synchronizedSet(new HashSet<String>());
    /** After a connection failure, leave the server alone for a minute (offline LAN, wrong address). */
    private static volatile long downUntil;

    /** Typed-in values to try (the Test button) without saving them; null = use the saved ones. */
    private String tryUrl, tryUser, tryPass;

    public LanTiles(Context c) {
        sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** For testing typed-in details; pass == null uses the saved password. */
    public LanTiles(Context c, String url, String user, String pass) {
        this(c);
        tryUrl = url;
        tryUser = user;
        tryPass = pass;
    }

    public String base() {
        String b = (tryUrl != null ? tryUrl : sp.getString("lanUrl", "")).trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    public String user() {
        return tryUser != null ? tryUser : sp.getString("lanUser", "");
    }

    private String pass() {
        return tryPass != null ? tryPass : sp.getString("lanPass", "");
    }

    public boolean hasPassword() {
        return pass().length() > 0;
    }

    public boolean shareWithManet() {
        return sp.getBoolean("lanShare", false);
    }

    public boolean configured() {
        return base().startsWith("http://") || base().startsWith("https://");
    }

    /** pass == null keeps the saved password. */
    public void save(String url, String user, String pass, boolean share) {
        SharedPreferences.Editor e = sp.edit().putString("lanUrl", url.trim()).putString("lanUser", user.trim())
                .putBoolean("lanShare", share);
        if (pass != null) e.putString("lanPass", pass);
        e.commit();
        made.clear();
        downUntil = 0;
    }

    /** A tile source's folder name: host plus a short hash, e.g. "mt1.google.com-3fa2c1". */
    public static String sourceId(String tileUrl) {
        String host = "custom";
        try {
            host = new URL(tileUrl.replace("{z}", "0").replace("{x}", "0").replace("{y}", "0")).getHost();
        } catch (Exception ignored) {
        }
        return host + "-" + Integer.toHexString(tileUrl.hashCode() & 0xffffff);
    }

    /**
     * Named tile sets in the maps cache folder (e.g. "ATAK Home", a set made for ATAK or copied in), as
     * opposed to the per-source folders the app fills itself (name-hash, e.g. tile.openstreetmap.org-3d0cdf)
     * and helper folders starting with _. Read from the server's folder listing; empty if it has none.
     */
    public java.util.List<String> namedSets() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (!up()) return out;
        HttpURLConnection c = null;
        try {
            c = open(base() + "/" + enc(FOLDER) + "/", "GET");
            if (c.getResponseCode() != 200) return out;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 512 * 1024) bo.write(buf, 0, n);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("href=\"([^\"?#/]+)/\"").matcher(new String(bo.toByteArray(), "UTF-8"));
            while (m.find()) {
                String name = java.net.URLDecoder.decode(m.group(1), "UTF-8");
                if (name.startsWith("_") || name.startsWith(".") || name.matches("[A-Za-z0-9.\\-]+-[0-9a-f]{6}")) continue;
                if (!out.contains(name)) out.add(name);
            }
        } catch (Exception ignored) {
            // A store without folder listings: no named sets.
        } finally {
            if (c != null) c.disconnect();
        }
        return out;
    }

    private String url(String source, int z, int x, int y) {
        return base() + "/" + enc(FOLDER) + "/" + enc(source) + "/" + z + "/" + x + "/" + y + ".jpg";
    }

    private static String enc(String s) {
        return s.replace(" ", "%20");
    }

    private HttpURLConnection open(String u, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        c.setUseCaches(false);
        if (user().length() > 0 || hasPassword()) {
            String auth = user() + ":" + pass();
            c.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(auth.getBytes("UTF-8"), Base64.NO_WRAP));
        }
        return c;
    }

    private boolean up() {
        return configured() && System.currentTimeMillis() >= downUntil;
    }

    private static final java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();

    /** Several network failures in a row (not one slow answer from a small server): leave it a minute. */
    private void markDown() {
        if (failures.incrementAndGet() >= 5) {
            downUntil = System.currentTimeMillis() + 60000;
            failures.set(0);
        }
    }

    private static void ok() {
        failures.set(0);
    }

    /** The tile's bytes from the LAN store, or null. */
    public byte[] get(String source, int z, int x, int y) {
        if (!up()) return null;
        HttpURLConnection c = null;
        try {
            c = open(url(source, z, x, y), "GET");
            int code = c.getResponseCode();
            ok();
            if (code != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } catch (java.io.IOException e) {
            markDown();
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public boolean has(String source, int z, int x, int y) {
        if (!up()) return false;
        HttpURLConnection c = null;
        try {
            c = open(url(source, z, x, y), "HEAD");
            boolean found = c.getResponseCode() == 200;
            ok();
            return found;
        } catch (java.io.IOException e) {
            markDown();
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Stores a tile on the LAN (making its folders first). Returns false if it could not. */
    public boolean put(String source, int z, int x, int y, byte[] data) {
        if (!up()) return false;
        try {
            String dir = base() + "/" + enc(FOLDER);
            for (String part : new String[]{enc(source), String.valueOf(z), String.valueOf(x)}) {
                mkcol(dir);
                dir = dir + "/" + part;
            }
            mkcol(dir);
            HttpURLConnection c = open(url(source, z, x, y), "PUT");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "image/jpeg");
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.close();
            int code = c.getResponseCode();
            c.disconnect();
            ok();
            return code >= 200 && code < 300;
        } catch (java.io.IOException e) {
            markDown();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean put(String source, int z, int x, int y, File f) {
        try {
            InputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return put(source, z, x, y, bo.toByteArray());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Makes a folder (WebDAV MKCOL). HttpURLConnection refuses that method, so it is written by hand
     * over a plain socket; for https the folders must already exist (or the server makes them on PUT).
     */
    private void mkcol(String dir) {
        if (made.contains(dir)) return;
        int code = 0;
        java.net.Socket s = null;
        try {
            URL u = new URL(dir + "/");
            if (!"http".equals(u.getProtocol())) return;
            s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress(u.getHost(), u.getPort() > 0 ? u.getPort() : 80), 3000);
            s.setSoTimeout(5000);
            StringBuilder req = new StringBuilder();
            req.append("MKCOL ").append(u.getFile()).append(" HTTP/1.1\r\nHost: ").append(u.getHost())
                    .append(u.getPort() > 0 ? ":" + u.getPort() : "").append("\r\nContent-Length: 0\r\nConnection: close\r\n");
            if (user().length() > 0 || hasPassword()) {
                String auth = user() + ":" + pass();
                req.append("Authorization: Basic ").append(Base64.encodeToString(auth.getBytes("UTF-8"), Base64.NO_WRAP)).append("\r\n");
            }
            req.append("\r\n");
            s.getOutputStream().write(req.toString().getBytes("UTF-8"));
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(), "UTF-8"));
            String status = r.readLine(); // "HTTP/1.1 201 Created"
            if (status != null && status.length() >= 12) code = Integer.parseInt(status.substring(9, 12));
        } catch (Exception ignored) {
            // try the PUT anyway
        } finally {
            try {
                if (s != null) s.close();
            } catch (java.io.IOException ignored) {
            }
        }
        // 201 made, 405 already there.
        if (code == 201 || code == 405 || code == 301) made.add(dir);
    }

    /** "OK" or why the LAN store cannot be used (for the settings screen's Test button). */
    public String test() {
        if (!configured()) return "Enter an address starting http:// or https://";
        downUntil = 0;
        try {
            HttpURLConnection c = open(base() + "/", "GET");
            int code = c.getResponseCode();
            c.disconnect();
            if (code == 401 || code == 403) return "Refused: check the user and password (" + code + ")";
            byte[] probe = "ok".getBytes("UTF-8");
            boolean wrote = put("_test", 0, 0, 0, probe);
            return wrote ? "OK: reached and can store tiles" : "Reached (" + code + "), but it would not store a file (needs WebDAV write access)";
        } catch (Exception e) {
            return "Cannot reach it: " + e.getMessage();
        }
    }

    // ---- any file on the LAN storage (updates, logs, data, DRYAD, configs) ----

    private String pathUrl(String rel) {
        StringBuilder sb = new StringBuilder(base());
        for (String part : rel.split("/")) {
            if (part.length() == 0) continue;
            try {
                sb.append('/').append(java.net.URLEncoder.encode(part, "UTF-8").replace("+", "%20"));
            } catch (java.io.UnsupportedEncodingException e) {
                sb.append('/').append(part);
            }
        }
        return sb.toString();
    }

    /** A file's bytes (rel like "Updates/version.txt"), or null if missing / unreachable. */
    public byte[] getPath(String rel) {
        if (!up()) return null;
        HttpURLConnection c = null;
        try {
            c = open(pathUrl(rel), "GET");
            int code = c.getResponseCode();
            ok();
            if (code != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } catch (java.io.IOException e) {
            markDown();
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Downloads a (possibly large) file straight to disk. */
    public boolean getPathToFile(String rel, File out) {
        if (!up()) return false;
        HttpURLConnection c = null;
        try {
            c = open(pathUrl(rel), "GET");
            if (c.getResponseCode() != 200) return false;
            InputStream in = c.getInputStream();
            File tmp = new File(out.getPath() + ".part");
            java.io.FileOutputStream os = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.close();
            in.close();
            return tmp.renameTo(out) || (out.delete() && tmp.renameTo(out));
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public boolean hasPath(String rel) {
        if (!up()) return false;
        HttpURLConnection c = null;
        try {
            c = open(pathUrl(rel), "HEAD");
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Stores a file (making its folders first). */
    public boolean putPath(String rel, byte[] data) {
        if (!up()) return false;
        try {
            String[] parts = rel.split("/");
            StringBuilder dir = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                if (parts[i].length() == 0) continue;
                dir.append('/').append(parts[i]);
                mkcol(pathUrl(dir.toString()));
            }
            HttpURLConnection c = open(pathUrl(rel), "PUT");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.close();
            int code = c.getResponseCode();
            c.disconnect();
            ok();
            return code >= 200 && code < 300;
        } catch (java.io.IOException e) {
            markDown();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean putPath(String rel, File f) {
        if (f.length() > 4L * 1024 * 1024) return putPathStream(rel, f); // big files (videos): streamed, not held in memory
        try {
            InputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return putPath(rel, bo.toByteArray());
        } catch (Exception e) {
            return false;
        }
    }

    /** Uploads a large file straight from disk (up to 2 GB). */
    private boolean putPathStream(String rel, File f) {
        if (!up() || f.length() > Integer.MAX_VALUE) return false;
        try {
            String[] parts = rel.split("/");
            StringBuilder dir = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                if (parts[i].length() == 0) continue;
                dir.append('/').append(parts[i]);
                mkcol(pathUrl(dir.toString()));
            }
            HttpURLConnection c = open(pathUrl(rel), "PUT");
            c.setDoOutput(true);
            c.setReadTimeout(120000);
            c.setFixedLengthStreamingMode((int) f.length());
            c.setRequestProperty("Content-Type", "application/octet-stream");
            OutputStream os = c.getOutputStream();
            InputStream in = new FileInputStream(f);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            in.close();
            os.close();
            int code = c.getResponseCode();
            c.disconnect();
            ok();
            return code >= 200 && code < 300;
        } catch (java.io.IOException e) {
            markDown();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- syncing a map area with the phone's own maps cache ----

    public interface Progress {
        /** Off the main thread. */
        void update(String text);
    }

    /**
     * Two-way sync for the tiles in an area: tiles only on the LAN come into the phone's cache,
     * and tiles only on the phone go up to the LAN. Returns a summary.
     */
    public String syncArea(final String source, final File cacheDir, double north, double west, double south, double east,
                           int zFrom, int zTo, final Progress p) {
        if (!configured()) return "No LAN storage location set";
        downUntil = 0;
        final java.util.List<int[]> tiles = new java.util.ArrayList<int[]>();
        for (int z = zFrom; z <= zTo; z++) {
            int x0 = (int) Math.floor(Geo.lonToX(west, z)), x1 = (int) Math.floor(Geo.lonToX(east, z));
            int y0 = (int) Math.floor(Geo.latToY(north, z)), y1 = (int) Math.floor(Geo.latToY(south, z));
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) tiles.add(new int[]{z, x, y});
        }
        final int total = tiles.size();
        final java.util.concurrent.atomic.AtomicInteger down = new java.util.concurrent.atomic.AtomicInteger(),
                upCount = new java.util.concurrent.atomic.AtomicInteger(), done = new java.util.concurrent.atomic.AtomicInteger(),
                next = new java.util.concurrent.atomic.AtomicInteger();
        // A few at once: each tile is a small request, so waiting on one at a time is slow.
        Thread[] workers = new Thread[4];
        for (int w = 0; w < workers.length; w++) {
            workers[w] = new Thread(new Runnable() {
                @Override
                public void run() {
                    int i;
                    while ((i = next.getAndIncrement()) < total && !Thread.currentThread().isInterrupted()
                            && System.currentTimeMillis() >= downUntil) {
                        int[] t = tiles.get(i);
                        File local = new File(cacheDir, t[0] + "/" + t[1] + "/" + t[2] + ".jpg");
                        if (local.isFile()) {
                            if (!has(source, t[0], t[1], t[2]) && put(source, t[0], t[1], t[2], local)) upCount.incrementAndGet();
                        } else {
                            byte[] b = get(source, t[0], t[1], t[2]);
                            if (b != null) {
                                try {
                                    local.getParentFile().mkdirs();
                                    java.io.FileOutputStream os = new java.io.FileOutputStream(local);
                                    os.write(b);
                                    os.close();
                                    down.incrementAndGet();
                                } catch (java.io.IOException ignored) {
                                }
                            }
                        }
                        int d = done.incrementAndGet();
                        if (p != null && d % 10 == 0) p.update("Syncing tiles " + d + " / " + total
                                + "\n" + down.get() + " from the LAN, " + upCount.get() + " to the LAN");
                    }
                }
            }, "lan-sync-" + w);
            workers[w].start();
        }
        try {
            for (Thread w : workers) w.join();
        } catch (InterruptedException e) {
            for (Thread w : workers) w.interrupt();
            return "Stopped: " + down.get() + " from the LAN, " + upCount.get() + " to the LAN";
        }
        if (System.currentTimeMillis() < downUntil) return "The LAN storage location stopped answering ("
                + down.get() + " from the LAN, " + upCount.get() + " to the LAN)";
        return "Sync done: " + down.get() + " tile(s) from the LAN, " + upCount.get() + " to the LAN (" + total + " checked)";
    }
}
