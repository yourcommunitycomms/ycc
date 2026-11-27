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

    static final String UPDATES_PATH = "/Updates/YCC.apk";

    /** A YCC storage server's own update address for a data (LAN storage) address: base + /Updates/YCC.apk. */
    static String updateUrlFor(String base) {
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b + UPDATES_PATH;
    }

    /** "http://host:port" of an address (scheme, host and port only), or "" when it is not one. */
    static String origin(String url) {
        String u = url == null ? "" : url.trim();
        int s = u.indexOf("://");
        if (s <= 0) return "";
        int p = u.indexOf('/', s + 3);
        return p < 0 ? u : u.substring(0, p);
    }

    /** The path part of an address (from the first / after the host; "" when none). */
    static String pathOf(String url) {
        String u = url == null ? "" : url.trim();
        String o = origin(u);
        return o.length() == 0 ? "" : u.substring(o.length());
    }

    static boolean sameOrigin(String x, String y) {
        String a = origin(x), b = origin(y);
        return a.length() > 0 && a.equalsIgnoreCase(b);
    }

    private static boolean isGithub(String url) {
        return origin(url).toLowerCase(java.util.Locale.US).contains("github");
    }

    /**
     * After saving the update server: if the data server is on another host or port, offer to move it to this one.
     * Only the host and port change; each address keeps its own path (the data and update paths are different).
     */
    static void offerDataServer(final Activity a, String updateUrl) {
        final String o = origin(updateUrl);
        if (o.length() == 0 || isGithub(updateUrl)) return; // GitHub: not a storage server
        final LanTiles lan = new LanTiles(a);
        if (lan.configured() && sameOrigin(lan.base(), updateUrl)) return; // already the same host and port
        // keep the data server's own path; with none set yet, guess from a standard storage server layout
        final String base = lan.configured() ? o + pathOf(lan.base())
                : updateUrl.endsWith(UPDATES_PATH) ? updateUrl.substring(0, updateUrl.length() - UPDATES_PATH.length()) : o;
        new AlertDialog.Builder(a).setTitle("Data server too?")
                .setMessage("Use the same host and port for data (LAN storage: maps cache, sync, backups) as well?\n\n" + base
                        + (lan.configured() ? "\n\nNow: " + lan.base() + "\n(only the host and port change; its path stays)"
                        : "\n\nNo data server is set yet: check its path in Settings > LAN storage location.")
                        + (lan.configured() && lan.user().length() > 0 ? "\n\nIts user and password are kept." : ""))
                .setPositiveButton("Use the same", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (lan.configured()) {
                            lan.save(base, lan.user(), null, lan.shareWithManet());
                            Toast.makeText(a, "Data server set to " + base, Toast.LENGTH_LONG).show();
                        } else {
                            lan.save(base, "", null, false);
                            Toast.makeText(a, "Data server set to " + base + " (add its login in Settings > LAN storage if it needs one)", Toast.LENGTH_LONG).show();
                        }
                    }
                }).setNegativeButton("Keep it as it is", null).show();
    }

    /**
     * After saving the data server: if the update server is on another host or port, offer to move it to this one.
     * Only the host and port change and the update address keeps its own path; from GitHub (or none) it becomes
     * the storage server's standard update address.
     */
    static void offerUpdateServer(final Activity a, String base) {
        final String o = origin(base);
        if (o.length() == 0) return;
        String now = sp(a).getString("updateUrl", "").trim();
        boolean keepPath = now.length() > 0 && origin(now).length() > 0 && !isGithub(now);
        if (keepPath && sameOrigin(now, base)) return; // already the same host and port
        final String want = keepPath ? o + pathOf(now) : updateUrlFor(base);
        new AlertDialog.Builder(a).setTitle("Update server too?")
                .setMessage("Get app updates from the same host and port as well?\n\n" + want + "\n\nNow: "
                        + (now.length() > 0 ? now : DEFAULT_URL + " (GitHub)")
                        + (keepPath ? "\n(only the host and port change; its path stays)" : ""))
                .setPositiveButton("Use the same", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        sp(a).edit().putString("updateUrl", want).commit();
                        Toast.makeText(a, "Update server set to " + want, Toast.LENGTH_LONG).show();
                    }
                }).setNegativeButton("Keep it as it is", null).show();
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
        // What was typed survives the dialog closing (rotation, Cancel by mistake): kept as a draft until saved.
        final String draft = sp(a).getString("updateUrlDraft", "");
        if (draft.length() > 0) e.setText(draft);
        e.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                sp(a).edit().putString("updateUrlDraft", s.toString()).commit();
            }
        });
        final android.widget.TextView result = new android.widget.TextView(a);
        result.setTextSize(14);
        result.setPadding(0, (int) (8 * dp), 0, (int) (4 * dp));
        box.addView(result);
        final String was = url(a);
        final int[] tested = {0}; // 0 not tested, 1 OK, -1 failed (for the text now in the field)
        final String[] testedFor = {""};
        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("Update URL")
                .setMessage("Where to get the app from: the project's GitHub release (the default, always the newest), "
                        + "or a local storage server, e.g. http://192.168.1.10:8647/YCC/Updates/YCC.apk. Local server asks for "
                        + "its name and fills in the rest. Test checks the address without closing this; Save keeps it. A "
                        + "group's network defaults can set it for every phone.")
                .setView(box)
                .setPositiveButton(thenUpdate ? "Save and update" : "Save", null)
                .setNeutralButton("Test", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(android.view.View v) {
                        final String u = e.getText().toString().trim();
                        result.setTextColor(0xffcfd8dc);
                        result.setText("Testing " + u + " …");
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                final String[] r = test(a, u);
                                a.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!dlg.isShowing()) return;
                                        boolean ok = r[0] != null;
                                        tested[0] = ok ? 1 : -1;
                                        testedFor[0] = u;
                                        result.setTextColor(ok ? 0xffa5d6a7 : 0xffff8a80);
                                        result.setText(ok ? "OK: " + r[0] : "Failed: " + r[1]);
                                    }
                                });
                            }
                        }, "update-url-test").start();
                    }
                });
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(android.view.View v) {
                        final String u = e.getText().toString().trim();
                        if (!u.startsWith("http://") && !u.startsWith("https://")) {
                            result.setTextColor(0xffff8a80);
                            result.setText("The address starts with http:// or https://");
                            return;
                        }
                        final Runnable save = new Runnable() {
                            @Override
                            public void run() {
                                sp(a).edit().putString("updateUrl", u).remove("updateUrlDraft").commit();
                                dlg.dismiss();
                                Toast.makeText(a, "Update address saved", Toast.LENGTH_SHORT).show();
                                if (thenUpdate) download(a, u);
                                else offerDataServer(a, u);
                            }
                        };
                        final Runnable checked = new Runnable() {
                            @Override
                            public void run() {
                                if (tested[0] == 1 && u.equals(testedFor[0])) {
                                    save.run();
                                    return;
                                }
                                new AlertDialog.Builder(a).setTitle(tested[0] == -1 && u.equals(testedFor[0]) ? "The test failed" : "Not tested")
                                        .setMessage((tested[0] == -1 && u.equals(testedFor[0]) ? "The last test of this address failed. "
                                                : "This address has not been tested. ") + "Save it anyway?")
                                        .setPositiveButton("Save anyway", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface d2, int w2) {
                                                save.run();
                                            }
                                        })
                                        .setNegativeButton("Back", null).show();
                            }
                        };
                        if (was.contains("/Personal/") && !u.equals(was)) { // a personal APK's own address: ask first
                            new AlertDialog.Builder(a).setTitle("Replace this app's own update address?")
                                    .setMessage("This app came from your storage server set up for you, with its own update "
                                            + "address that keeps your setup in each update. Use the new address instead?")
                                    .setPositiveButton("Replace", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d2, int w2) {
                                            checked.run();
                                        }
                                    })
                                    .setNegativeButton("Keep mine", null).show();
                            return;
                        }
                        checked.run();
                    }
                });
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(android.view.View v) {
                        sp(a).edit().remove("updateUrlDraft").commit();
                        dlg.dismiss();
                    }
                });
            }
        });
        dlg.show();
    }

    /**
     * Checks an update address without downloading the whole app: version.txt beside it when there is one (the LAN
     * storage layout), else the start of the file itself. Returns {what was found, null} or {null, why it failed}.
     */
    static String[] test(Activity a, String u) {
        if (!u.startsWith("http://") && !u.startsWith("https://")) return new String[]{null, "the address starts with http:// or https://"};
        try {
            URL url = new URL(u);
            if (url.getHost().length() == 0) return new String[]{null, "there is no server name in the address"};
            LanTiles lan = new LanTiles(a);
            if (lan.configured() && u.startsWith(lan.base())) {
                byte[] v = lan.getPath("Updates/version.txt");
                if (v != null) return new String[]{"the LAN storage offers version " + new String(v, "UTF-8").trim().split("\\s+")[0], null};
            }
            String vUrl = u.substring(0, u.lastIndexOf('/') + 1) + "version.txt";
            if (!u.contains("github.com")) {
                HttpURLConnection c = org.communitycomms.app.net.LanNet.open(new java.net.URL(vUrl));
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() == 200) {
                    String t = TestAudio.readText(c.getInputStream()).trim();
                    c.disconnect();
                    String code = t.split("\\s+")[0];
                    if (code.matches("\\d+")) return new String[]{"version " + code + " found (version.txt beside the app)", null};
                }
                c.disconnect();
            }
            HttpURLConnection c = org.communitycomms.app.net.LanNet.open(url);
            org.communitycomms.app.geo.Tls12SocketFactory.apply(c);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Range", "bytes=0-1023");
            int code = c.getResponseCode();
            if (code != 200 && code != 206) {
                c.disconnect();
                return new String[]{null, "the server answered " + code + (code == 404 ? " (not found: check the path)" : code == 401 || code == 403 ? " (it wants a login)" : "")};
            }
            InputStream in = c.getInputStream();
            byte[] head = new byte[2];
            int n = in.read(head);
            in.close();
            long len = c.getHeaderFieldInt("Content-Length", -1);
            String range = c.getHeaderField("Content-Range");
            if (range != null && range.contains("/")) {
                try {
                    len = Long.parseLong(range.substring(range.lastIndexOf('/') + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            c.disconnect();
            if (n < 2 || head[0] != 'P' || head[1] != 'K') return new String[]{null, "the address answers, but it is not an app file (APK)"};
            return new String[]{"an app file is there" + (len > 0 ? " (" + (len / 1024) + " KB)" : "") + ". Save, then Update from URL installs it.", null};
        } catch (java.net.UnknownHostException e) {
            return new String[]{null, "server not found (" + e.getMessage() + "): check the name, or use its address"};
        } catch (java.net.SocketTimeoutException e) {
            return new String[]{null, "no answer (timed out): the server is off or not reachable from this network"};
        } catch (java.net.ConnectException e) {
            return new String[]{null, "nothing answers there (" + e.getMessage() + "): check the port"};
        } catch (java.net.MalformedURLException e) {
            return new String[]{null, "not a valid address (" + e.getMessage() + ")"};
        } catch (Exception e) {
            return new String[]{null, org.communitycomms.app.geo.Tls12SocketFactory.explain(e).getMessage()};
        }
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
                c = org.communitycomms.app.net.LanNet.open(new java.net.URL(u));
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
