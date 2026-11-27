package org.communitycomms.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.communitycomms.app.geo.LanTiles;

/**
 * Is a newer app published? Reads only the small version.txt next to the app (the LAN storage's
 * Updates/version.txt, or the one beside the update address: a GitHub release has one too; a personal APK's
 * address uses its server's Updates/version.txt). Checked by itself at most every 6 hours while the app is
 * open (never in Offline power mode), or on demand from the Shortcuts page. A found update shows as a dot on
 * the Shortcuts icon and "Update available" at the top of the Shortcuts page.
 */
final class UpdateCheck {
    private static final long EVERY_MS = 6 * 3600000L;

    private UpdateCheck() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** The newer version code found, or 0. */
    static int available(Context c) {
        int v = sp(c).getInt("updateAvail", 0);
        return v > LanSync.installedVersion(c) ? v : 0;
    }

    /** Where to download the update found (the app's own update address, or the LAN storage). */
    static String source(Activity a) {
        String s = sp(a).getString("updateSrc", "");
        return s.length() > 0 ? s : UpdateFromUrl.url(a);
    }

    static void checkIfDue(final Activity a, final Runnable changed) {
        if (PowerMode.offline() || !new Prefs(a).setupDone()) return;
        if (System.currentTimeMillis() - sp(a).getLong("updateCheckedAt", 0) < EVERY_MS) return;
        checkNow(a, changed, false);
    }

    /** toast: say the result (the Shortcuts page's Check for updates). */
    static void checkNow(final Activity a, final Runnable changed, final boolean toast) {
        sp(a).edit().putLong("updateCheckedAt", System.currentTimeMillis()).commit();
        if (toast) android.widget.Toast.makeText(a, "Checking for updates…", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String[] src = {""};
                // The automatic check reads the internet only if Data control allows it; the LAN storage is always read.
                final int found = latest(a, src, toast || DataControl.autoUpdateInternet(a));
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        int mine = LanSync.installedVersion(a);
                        if (found > 0) sp(a).edit().putInt("updateAvail", found).putString("updateSrc", src[0]).commit();
                        if (toast) {
                            android.widget.Toast.makeText(a, found < 0 ? "Could not check: no answer from the update address or the LAN storage"
                                    : found > mine ? "Update available: version 0.1." + found
                                    : "This is the newest version (0.1." + mine + ")", android.widget.Toast.LENGTH_LONG).show();
                        }
                        if (changed != null) changed.run();
                    }
                });
            }
        }, "update-check").start();
    }

    /** The newest version code published (-1 if nothing answered); src[0] = where its APK is. */
    private static int latest(Activity a, String[] src, boolean internet) {
        int best = -1;
        String url = UpdateFromUrl.url(a);
        boolean personal = url.contains("/Personal/");
        LanTiles lan = new LanTiles(a);
        if (lan.configured()) {
            int v = parse(lan.getPath("Updates/version.txt"));
            if (v > best) {
                best = v;
                src[0] = personal ? url : lan.base() + "/Updates/YCC.apk"; // a personal APK updates from its own address
            }
        }
        String vurl = personal ? url.substring(0, url.indexOf("/Personal/")) + "/Updates/version.txt"
                : url.substring(0, url.lastIndexOf('/') + 1) + "version.txt";
        int v = internet || DataControl.isLocal(vurl) ? parse(get(vurl)) : -1;
        if (v > best) {
            best = v;
            src[0] = url;
        }
        return best;
    }

    private static int parse(byte[] b) {
        if (b == null) return -1;
        try {
            return Integer.parseInt(new String(b, "UTF-8").trim().split("\\s+")[0]);
        } catch (Exception e) {
            return -1;
        }
    }

    private static byte[] get(String u) {
        java.net.HttpURLConnection h = null;
        try {
            for (int hop = 0; hop < 4; hop++) { // GitHub's release links redirect (to another host)
                h = org.communitycomms.app.net.LanNet.open(new java.net.URL(u));
                h.setInstanceFollowRedirects(false);
                h.setConnectTimeout(8000);
                h.setReadTimeout(10000);
                int code = h.getResponseCode();
                if (code >= 300 && code < 400 && h.getHeaderField("Location") != null) {
                    u = new java.net.URL(new java.net.URL(u), h.getHeaderField("Location")).toString();
                    h.disconnect();
                    continue;
                }
                if (code != 200) return null;
                java.io.InputStream in = h.getInputStream();
                byte[] buf = new byte[256];
                int n = in.read(buf);
                in.close();
                return n > 0 ? java.util.Arrays.copyOf(buf, n) : null;
            }
        } catch (Exception ignored) {
        } finally {
            if (h != null) h.disconnect();
        }
        return null;
    }
}
