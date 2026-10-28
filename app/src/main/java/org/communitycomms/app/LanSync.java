package org.communitycomms.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import org.communitycomms.app.dryad.DryadSheet;
import org.communitycomms.app.dryad.SheetStore;
import org.communitycomms.app.geo.LanTiles;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Keeps phones in step through the LAN storage location (Settings > LAN sync and updates), each
 * part off until ticked:
 * <ul>
 * <li>Auto updates: Updates/version.txt (first line = version code, second = APK file name) and the
 * APK next to it. A newer one is downloaded and offered (Android asks before installing).</li>
 * <li>Logs: this app's log, to Logs/(callsign)/(date-time).txt, for debugging.</li>
 * <li>Data files: received files, voice recordings and photos, to Data/(callsign)/ (new ones only).</li>
 * <li>DRYAD sheets: DRYAD/sheets.txt is merged both ways (new sheets from the LAN are imported).</li>
 * <li>Config backup: Configs/(callsign).ucconfig (everything except the PIN).</li>
 * </ul>
 * Runs on demand (Sync now) or weekly, daily or every minute (for testing) while the radio runs.
 */
public final class LanSync {
    private LanSync() {
    }

    public static final String[] EVERY_NAMES = {"On demand only", "Weekly", "Daily", "Every minute (testing)"};
    private static final long[] EVERY_MS = {0, 7 * 24 * 3600000L, 24 * 3600000L, 60000L};
    private static volatile boolean running;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static boolean autoUpdate(Context c) {
        return sp(c).getBoolean("lanAutoUpdate", false);
    }

    public static int every(Context c) {
        return sp(c).getInt("lanSyncEvery", 0);
    }

    /** A ticked item, or (except logs) anything at all while on the home network. */
    public static boolean on(Context c, String what) {
        if (!"lanSyncLogs".equals(what) && atHome(c)) return true;
        return sp(c).getBoolean(what, false);
    }

    // ---- home network: on this WiFi, everything but logs syncs (and at least hourly) ----

    public static boolean homeEnabled(Context c) {
        return sp(c).getBoolean("homeNetOn", false);
    }

    public static String homeSsid(Context c) {
        return sp(c).getString("homeNetSsid", "").trim();
    }

    /** The WiFi network this phone is on now ("" if unknown: Android 8+ needs location for it). */
    @SuppressWarnings("deprecation")
    public static String currentSsid(Context c) {
        try {
            android.net.wifi.WifiManager w = (android.net.wifi.WifiManager) c.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo i = w == null ? null : w.getConnectionInfo();
            String s = i == null ? null : i.getSSID();
            if (s == null || s.contains("unknown ssid")) return "";
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) s = s.substring(1, s.length() - 1);
            return s;
        } catch (RuntimeException e) {
            return "";
        }
    }

    public static boolean atHome(Context c) {
        String h = homeSsid(c);
        return homeEnabled(c) && h.length() > 0 && h.equals(currentSsid(c));
    }

    public static boolean anything(Context c) {
        return autoUpdate(c) || atHome(c) || on(c, "lanSyncLogs") || on(c, "lanSyncData") || on(c, "lanSyncDryad") || on(c, "lanSyncConfig");
    }

    /** From RadioService every minute: runs in the background when the schedule says so. */
    public static void maybeRun(final Context c) {
        int e = every(c);
        long gap = e > 0 && e < EVERY_MS.length ? EVERY_MS[e] : Long.MAX_VALUE;
        if (atHome(c)) gap = Math.min(gap, 3600000L); // on the home network: at least hourly
        if (gap == Long.MAX_VALUE || running || !anything(c) || !new LanTiles(c).configured()) return;
        long last = sp(c).getLong("lanSyncLast", 0);
        if (System.currentTimeMillis() - last < gap - 5000) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                runNow(c);
            }
        }, "lan-sync").start();
    }

    // ---- mission mode: everything is archived under Missions/(name)/ while a mission is set ----

    public static String mission(Context c) {
        return sp(c).getString("missionName", "").trim();
    }

    public static java.util.List<String> missions(Context c) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String s : sp(c).getString("missionList", "").split("\n")) if (s.trim().length() > 0) out.add(s.trim());
        return out;
    }

    /** Sets the mission ("" = none), remembering it in the drop-down list. */
    public static void setMission(Context c, String name) {
        name = name == null ? "" : name.trim().replaceAll("[/\\\\:*?\"<>|]", "-");
        java.util.List<String> list = missions(c);
        if (name.length() > 0 && !list.contains(name)) list.add(0, name);
        StringBuilder sb = new StringBuilder();
        for (String s : list) sb.append(s).append('\n');
        sp(c).edit().putString("missionName", name).putString("missionList", sb.toString()).commit();
    }

    /** "Missions/(name)/" while a mission is set, else "". */
    static String prefix(Context c) {
        String m = mission(c);
        return m.length() == 0 ? "" : "Missions/" + m + "/";
    }

    /** Video reports (only with a mission): recorded here, uploaded on the home network. */
    public static File videosDir(Context c) {
        File d = c.getExternalFilesDir("Mission videos");
        if (d == null) d = new File(c.getFilesDir(), "Mission videos");
        d.mkdirs();
        return d;
    }

    /** Does everything ticked now (off the main thread). Returns a summary. */
    public static String runNow(Context c) {
        return runNow(c, false);
    }

    /** final = the last sync before mission data is deleted: everything, videos and logs too. */
    public static synchronized String runNow(Context c, boolean fin) {
        LanTiles lan = new LanTiles(c);
        if (!lan.configured()) return "No LAN storage location set (Settings > LAN storage location)";
        running = true;
        StringBuilder out = new StringBuilder();
        try {
            sp(c).edit().putLong("lanSyncLast", System.currentTimeMillis()).commit();
            String me = new Prefs(c).callsign().replaceAll("[^A-Za-z0-9_-]", "_");
            if (me.length() == 0) me = "unnamed";
            String pfx = prefix(c);
            if (mission(c).length() > 0) out.append("Mission ").append(mission(c)).append('\n');
            if (atHome(c)) out.append("Home network ").append(homeSsid(c)).append(": syncing everything but logs\n");
            if (!fin && (autoUpdate(c) || atHome(c))) out.append(checkUpdate(c, lan)).append('\n');
            if (fin || on(c, "lanSyncLogs")) out.append(uploadLog(lan, pfx, me)).append('\n');
            if (fin || on(c, "lanSyncData")) out.append(uploadData(c, lan, me, fin || atHome(c))).append('\n');
            if (fin || on(c, "lanSyncDryad")) out.append(syncDryad(c, lan)).append('\n');
            if (fin || on(c, "lanSyncConfig")) out.append(uploadConfig(c, lan, me)).append('\n');
            if (out.length() == 0) out.append("Nothing ticked to sync");
        } finally {
            running = false;
        }
        String s = out.toString().trim();
        sp(c).edit().putString("lanSyncResult", new SimpleDateFormat("d MMM HH:mm", Locale.US).format(new Date()) + ": "
                + s.replace('\n', ' ')).commit();
        return s;
    }

    public static String lastResult(Context c) {
        return sp(c).getString("lanSyncResult", "");
    }

    // ---- updates ----

    public static int installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static File updateDir(Context c) {
        // Android 7+: shared through FilesProvider; older: the installer reads it from external storage.
        File d = Build.VERSION.SDK_INT >= 24 ? new File(c.getCacheDir(), "update") : c.getExternalFilesDir("update");
        if (d == null) d = new File(c.getCacheDir(), "update");
        d.mkdirs();
        return d;
    }

    public static File downloaded(Context c) {
        String n = sp(c).getString("lanUpdateFile", "");
        if (n.length() == 0) return null;
        File f = new File(updateDir(c), n);
        return f.isFile() && sp(c).getInt("lanUpdateCode", 0) > installedVersion(c) ? f : null;
    }

    private static String checkUpdate(Context c, LanTiles lan) {
        byte[] v = lan.getPath("Updates/version.txt");
        if (v == null) return "Updates: none published (Updates/version.txt)";
        String[] lines = new String(v).trim().split("\\s*\n\\s*");
        int code;
        try {
            code = Integer.parseInt(lines[0].trim());
        } catch (NumberFormatException e) {
            return "Updates: version.txt is not readable";
        }
        String apk = lines.length > 1 ? lines[1].trim() : "YCC.apk";
        int mine = installedVersion(c);
        if (code <= mine) return "Updates: up to date (" + mine + ")";
        File out = new File(updateDir(c), "YCC-" + code + ".apk");
        if (!out.isFile() && !lan.getPathToFile("Updates/" + apk, out)) return "Updates: " + code + " found but the download failed";
        out.setReadable(true, false);
        sp(c).edit().putString("lanUpdateFile", out.getName()).putInt("lanUpdateCode", code).commit();
        notifyUpdate(c, code);
        return "Updates: version " + code + " downloaded, tap the notification (or Settings) to install";
    }

    @SuppressWarnings("deprecation")
    private static void notifyUpdate(Context c, int code) {
        Intent i = installIntent(c);
        if (i == null) return;
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(c, 7, i, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, "radio") : new Notification.Builder(c);
        Notification n = b.setContentTitle("App update ready")
                .setContentText("Version " + code + " from the LAN storage: tap to install")
                .setSmallIcon(R.drawable.ic_stat_radio)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(7301, n);
    }

    /** Opens Android's installer for the downloaded update, or null when there is none. */
    public static Intent installIntent(Context c) {
        File f = downloaded(c);
        if (f == null) return null;
        Intent i = new Intent(Intent.ACTION_VIEW);
        if (Build.VERSION.SDK_INT >= 24) {
            i.setDataAndType(FilesProvider.update(f), "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            i.setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive");
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    static File updateDirFor(Context c) {
        return updateDir(c);
    }

    // ---- logs ----

    private static String uploadLog(LanTiles lan, String pfx, String me) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time"});
            java.io.InputStream in = p.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 4 * 1024 * 1024) bo.write(buf, 0, n);
            in.close();
            p.destroy();
            String name = pfx + "Logs/" + me + "/" + dayAndStamp(System.currentTimeMillis()) + ".txt";
            String head = "Your Community Communications log from " + me + ", Android " + Build.VERSION.RELEASE
                    + " (" + Build.MODEL + ")\n\n";
            byte[] body = bo.toByteArray();
            byte[] all = new byte[head.length() + body.length];
            System.arraycopy(head.getBytes(), 0, all, 0, head.length());
            System.arraycopy(body, 0, all, head.length(), body.length);
            return lan.putPath(name, all) ? "Logs: sent " + (all.length / 1024) + " KB" : "Logs: could not send";
        } catch (Exception e) {
            return "Logs: " + e.getMessage();
        }
    }

    /** "2026-10-27/20261027143005": a folder per day, then a sortable time stamp (local time). */
    static String dayAndStamp(long ms) {
        Date d = new Date(ms);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d) + "/"
                + new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(d);
    }

    // ---- data files ----

    /** withVideos: mission video reports too (large; on the home network or in the final sync). */
    private static String uploadData(Context c, LanTiles lan, String me, boolean withVideos) {
        int sent = 0, failed = 0;
        File[] dirs = withVideos ? new File[]{RadioService.receivedDir(c), FilesProvider.cameraDir(c), videosDir(c)}
                : new File[]{RadioService.receivedDir(c), FilesProvider.cameraDir(c)};
        for (File dir : dirs) {
            File[] files = dir == null ? null : dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile() || f.getName().endsWith(".part")) continue;
                String rel = prefix(c) + "Data/" + me + "/" + dayAndStamp(f.lastModified()) + "_" + f.getName();
                if (lan.hasPath(rel)) continue;
                if (lan.putPath(rel, f)) sent++;
                else failed++;
            }
        }
        return "Data files: " + sent + " sent" + (failed > 0 ? ", " + failed + " failed" : "");
    }

    // ---- DRYAD ----

    private static String syncDryad(Context c, LanTiles lan) {
        SheetStore store = new SheetStore(c);
        int added = 0;
        byte[] there = lan.getPath(prefix(c) + "DRYAD/sheets.txt");
        if (there != null) {
            try {
                List<DryadSheet> sheets = DryadSheet.parseAll(new String(there, "UTF-8"));
                added = store.importAll(sheets);
            } catch (Exception e) {
                return "DRYAD: the LAN copy could not be read";
            }
        }
        StringBuilder all = new StringBuilder();
        List<SheetStore.Entry> mine = store.loadAll(false);
        for (SheetStore.Entry e : mine) all.append(e.sheet.toText()).append('\n');
        boolean ok;
        try {
            ok = lan.putPath(prefix(c) + "DRYAD/sheets.txt", all.toString().getBytes("UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            ok = false;
        }
        return "DRYAD: " + added + " new from the LAN, " + mine.size() + " on the LAN" + (ok ? "" : " (upload failed)");
    }

    // ---- config backup ----

    private static String uploadConfig(Context c, LanTiles lan, String me) {
        try {
            byte[] b = ConfigShare.export(c, true).toString(2).getBytes("UTF-8");
            return lan.putPath(prefix(c) + "Configs/" + me + ".ucconfig", b) ? "Config: backed up" : "Config: could not send";
        } catch (Exception e) {
            return "Config: " + e.getMessage();
        }
    }
}
