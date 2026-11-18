package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.widget.EditText;
import android.widget.Toast;

import org.communitycomms.app.geo.LanTiles;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Settings > Update from URL: downloads the app from an address (e.g. the LAN storage's
 * Updates/YCC.apk) and opens Android's installer. The address is asked for the first time, tested,
 * and then kept as "updateUrl", a shared setting, so a group's network defaults can set it.
 */
public final class UpdateFromUrl {
    private UpdateFromUrl() {
    }

    private static SharedPreferences sp(Activity a) {
        return a.getSharedPreferences("settings", Activity.MODE_PRIVATE);
    }

    /** The project's GitHub releases: always the newest published app. */
    public static final String DEFAULT_URL = "https://github.com/yourcommunitycomms/ycc/releases/latest/download/YCC.apk";

    /** The saved address, or the project's GitHub release when none was set. */
    public static String url(Activity a) {
        String u = sp(a).getString("updateUrl", "").trim();
        return u.length() > 0 ? u : DEFAULT_URL;
    }

    /** The LAN storage's published app, or null without LAN storage. */
    private static String lanUrl(Activity a) {
        LanTiles lan = new LanTiles(a);
        return lan.configured() ? lan.base() + "/Updates/YCC.apk" : null;
    }

    /** "Update from URL": the saved address (GitHub by default). */
    public static void run(final Activity a) {
        download(a, url(a));
    }

    /** "Edit update URL": type it, or fill it from a local server or GitHub; Test downloads it, and a working address is kept. */
    public static void edit(final Activity a, final boolean thenUpdate) {
        final float dp = a.getResources().getDisplayMetrics().density;
        final EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setText(url(a));
        e.setSelectAllOnFocus(true);
        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int p = (int) (16 * dp);
        box.setPadding(p, 0, p, 0);
        box.addView(e);
        android.widget.Button local = new android.widget.Button(a), gh = new android.widget.Button(a);
        local.setText("Local server…");
        gh.setText("GitHub");
        local.setAllCaps(false);
        gh.setAllCaps(false);
        local.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                askLocal(a, e);
            }
        });
        gh.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                e.setText(DEFAULT_URL);
            }
        });
        android.widget.LinearLayout row = new android.widget.LinearLayout(a);
        row.addView(local, new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(gh, new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        box.addView(row);
        final String was = url(a);
        new AlertDialog.Builder(a)
                .setTitle("Update URL")
                .setMessage("Where to get the app from: the project's GitHub release (the default, always the newest), "
                        + "or a local storage server, e.g. http://192.168.1.10:8647/YCC/Updates/YCC.apk. Local server asks for "
                        + "its name and fills in the rest. Test downloads it; it is kept once it works, and a group's network "
                        + "defaults can set it for every phone. A newer app from outside the LAN is also put on the LAN storage, "
                        + "so the other phones update from there.")
                .setView(box)
                .setPositiveButton(thenUpdate ? "Test and update" : "Test and save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final String u = e.getText().toString().trim();
                        if (!u.startsWith("http://") && !u.startsWith("https://")) {
                            Toast.makeText(a, "The address starts with http:// or https://", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (was.contains("/Personal/") && !u.equals(was)) { // a personal APK's own address: ask first
                            new AlertDialog.Builder(a).setTitle("Replace this app's own update address?")
                                    .setMessage("This app came from your storage server set up for you, with its own update "
                                            + "address that keeps your setup in each update. Use the new address instead?")
                                    .setPositiveButton("Replace", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d2, int w2) {
                                            download(a, u);
                                        }
                                    })
                                    .setNegativeButton("Keep mine", null).show();
                            return;
                        }
                        download(a, u);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Local server: its name (or address), port and path, put together into the update address. */
    private static void askLocal(final Activity a, final EditText target) {
        float dp = a.getResources().getDisplayMetrics().density;
        String host = "", port = "8647", path = "/YCC/Updates/YCC.apk";
        LanTiles lan = new LanTiles(a);
        try {
            java.net.URL cur = new java.net.URL(lan.configured() ? lan.base() : target.getText().toString().trim());
            if (!cur.getHost().contains("github")) {
                host = cur.getHost();
                if (cur.getPort() > 0) port = String.valueOf(cur.getPort());
            }
        } catch (Exception ignored) {
        }
        final EditText h = field(a, host, "Server name or address, e.g. mypi.local or 192.168.1.10");
        final EditText po = field(a, port, "Port");
        po.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        final EditText pa = field(a, path, "Path");
        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int p = (int) (16 * dp);
        box.setPadding(p, 0, p, 0);
        box.addView(label(a, "Server name or address"));
        box.addView(h);
        box.addView(label(a, "Port (8647 on a Project NOMAD box or Raspberry Pi)"));
        box.addView(po);
        box.addView(label(a, "Path"));
        box.addView(pa);
        new AlertDialog.Builder(a).setTitle("Local server").setView(box)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String hs = h.getText().toString().trim().replaceAll("^https?://", "").replaceAll("[/:].*$", "");
                        if (hs.length() == 0) {
                            Toast.makeText(a, "Enter the server's name or address", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String ps = po.getText().toString().trim(), pth = pa.getText().toString().trim();
                        if (!pth.startsWith("/")) pth = "/" + pth;
                        target.setText("http://" + hs + (ps.length() > 0 ? ":" + ps : "") + pth);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private static EditText field(Activity a, String v, String hint) {
        EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setText(v);
        e.setHint(hint);
        return e;
    }

    private static android.widget.TextView label(Activity a, String t) {
        android.widget.TextView v = new android.widget.TextView(a);
        v.setText(t);
        v.setTextSize(13);
        return v;
    }

    /** Download and install from this address (the Shortcuts page's "Update available"). */
    static void downloadFrom(Activity a, String u) {
        download(a, u);
    }
    private static void download(final Activity a, final String u) {
        Toast.makeText(a, "Downloading the app from " + u + "…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = fetch(a, u);
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (a.isFinishing()) return;
                        if (result != null) {
                            new AlertDialog.Builder(a).setTitle("Update from URL").setMessage(result)
                                    .setPositiveButton(android.R.string.ok, null).show();
                            return;
                        }
                        android.content.Intent i = LanSync.installIntent(a);
                        if (i != null) a.startActivity(i);
                    }
                });
            }
        }, "update-from-url").start();
    }

    /**
     * Downloaded from outside the LAN (e.g. GitHub): if it is newer than what the LAN storage offers,
     * put it there (Updates/YCC.apk and version.txt), so every phone with auto updates gets it from the
     * cache. Returns a line saying so, or "".
     */
    private static String refreshLanCache(Activity a, String u, File apk, int code) {
        LanTiles lan = new LanTiles(a);
        if (!lan.configured() || u.startsWith(lan.base())) return "";
        int cached = 0;
        byte[] v = lan.getPath("Updates/version.txt");
        if (v != null) {
            try {
                cached = Integer.parseInt(new String(v, "UTF-8").trim().split("\\s+")[0]);
            } catch (Exception ignored) {
            }
        }
        if (code <= cached) return "";
        boolean ok = lan.putPath("Updates/YCC.apk", apk);
        if (ok) {
            try {
                ok = lan.putPath("Updates/version.txt", (code + "\nYCC.apk\n").getBytes("UTF-8"));
            } catch (java.io.UnsupportedEncodingException e) {
                ok = false;
            }
        }
        return ok ? "\n\nThe LAN storage had " + (cached == 0 ? "no update" : "version " + cached) + ": version " + code
                + " is now there too, so the other phones get it from the cache."
                : "\n\nIt could not be copied to the LAN storage (check its login in Settings > Data and backups).";
    }

    /** Downloads and checks the APK; null = ready to install, otherwise what went wrong. */
    private static String fetch(final Activity a, String u) {
        File out = new File(LanSync.updateDirFor(a), "YCC-url.apk");
        try {
            HttpURLConnection c;
            LanTiles lan = new LanTiles(a);
            boolean onLan = lan.configured() && u.startsWith(lan.base());
            if (onLan) {
                // The LAN storage may want its login (our server lets anyone fetch the APK; others may not).
                String rel = u.substring(lan.base().length()).replaceFirst("^/", "");
                if (!lan.getPathToFile(rel, out)) return "Could not download it from the LAN storage (" + u + ").";
            } else {
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(30000);
                int code = c.getResponseCode();
                if (code != 200) return "The server answered " + code + " for " + u + ".";
                InputStream in = c.getInputStream();
                FileOutputStream os = new FileOutputStream(out);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.close();
                in.close();
                c.disconnect();
            }
        } catch (Exception e) {
            return "Could not download it: " + e.getMessage();
        }
        PackageInfo pi = a.getPackageManager().getPackageArchiveInfo(out.getPath(), 0);
        if (pi == null || !a.getPackageName().equals(pi.packageName)) {
            out.delete();
            return "That address did not give this app's APK.";
        }
        sp(a).edit().putString("updateUrl", u).commit(); // it works: keep it
        out.setReadable(true, false);
        String cache = refreshLanCache(a, u, out, pi.versionCode);
        int mine = LanSync.installedVersion(a);
        if (pi.versionCode <= mine) {
            return "The address works and is saved. This phone already has version " + mine
                    + " (the file there is " + pi.versionCode + "), so there is nothing to install." + cache;
        }
        if (cache.length() > 0) {
            final String note = cache.trim();
            a.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(a, note, Toast.LENGTH_LONG).show();
                }
            });
        }
        sp(a).edit().putString("lanUpdateFile", out.getName()).putInt("lanUpdateCode", pi.versionCode).commit();
        return null;
    }
}
