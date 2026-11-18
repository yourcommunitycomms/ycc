package org.communitycomms.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.RandomAccessFile;

/**
 * A setup stamped into this app's own APK by a LAN storage server (its Phones tab): a site APK (the server's
 * address) or a personal APK (the server, a callsign, a long random token and its own update address). It sits
 * in the APK signing block, which the signatures do not cover, so the APK installs and updates as usual.
 * Read on first start; the rest of the setup (private keys and channels) is fetched from the server.
 */
final class ApkStamp {
    private static final int STAMP_ID = 0x59434331; // "YCC1"
    private static final byte[] MAGIC = "APK Sig Block 42".getBytes();

    private ApkStamp() {
    }

    /** The stamp in the installed APK, or null (a plain APK). */
    static JSONObject read(Context c) {
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile(c.getApplicationInfo().sourceDir, "r");
            long len = f.length();
            int tail = (int) Math.min(len, 22 + 65535);
            byte[] t = new byte[tail];
            f.seek(len - tail);
            f.readFully(t);
            int e = -1;
            for (int i = tail - 22; i >= 0; i--) {
                if (t[i] == 0x50 && t[i + 1] == 0x4b && t[i + 2] == 5 && t[i + 3] == 6) {
                    e = i;
                    break;
                }
            }
            if (e < 0) return null;
            long cd = u32(t, e + 16);
            byte[] foot = new byte[24];
            f.seek(cd - 24);
            f.readFully(foot);
            for (int i = 0; i < 16; i++) if (foot[8 + i] != MAGIC[i]) return null;
            long size = u64(foot, 0);
            long p = cd - (size + 8) + 8, end = cd - 24;
            byte[] h = new byte[12];
            while (p < end) {
                f.seek(p);
                f.readFully(h);
                long n = u64(h, 0);
                if (n < 4 || n > Integer.MAX_VALUE) return null;
                if ((int) u32(h, 8) == STAMP_ID) {
                    if (n > 65536) return null;
                    byte[] v = new byte[(int) n - 4];
                    f.readFully(v);
                    return new JSONObject(new String(v, "UTF-8"));
                }
                p += 8 + n;
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (f != null) f.close();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xffL) | (b[o + 1] & 0xffL) << 8 | (b[o + 2] & 0xffL) << 16 | (b[o + 3] & 0xffL) << 24;
    }

    private static long u64(byte[] b, int o) {
        return u32(b, o) | u32(b, o + 4) << 32;
    }

    static boolean personal(JSONObject s) {
        return s != null && "personal".equals(s.optString("kind"));
    }

    /** Stage 1: what the stamp itself carries (callsign and lock, update address, Public key, a cloud group). */
    static void applyStage1(Context c, JSONObject s) {
        Prefs p = new Prefs(c);
        String call = s.optString("call").trim();
        if (call.length() > 0) {
            p.setCallsign(call.toUpperCase(java.util.Locale.US));
            p.setNameLock(s.optBoolean("nameLock", false));
        }
        String up = s.optString("update").trim();
        if (up.length() > 0) c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("updateUrl", up).commit();
        if (s.optString("pub").trim().length() > 0) p.setPublicKey(s.optString("pub"));
        SetupQr.applyCloud(p, s.optJSONObject("cloud"));
    }

    /** Stage 2: the rest of the setup from the server (a setup code), or an error message starting with "!". */
    static String fetchSetup(JSONObject s) {
        String u = s.optString("setup").trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) return "!This app's setup address is missing.";
        java.net.HttpURLConnection h = null;
        try {
            h = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            h.setConnectTimeout(8000);
            h.setReadTimeout(15000);
            int code = h.getResponseCode();
            if (code == 404) return "!The server no longer has a setup for this app (revoked, or a new code was made). Ask for a new APK.";
            if (code == 410) return "!This setup was already used, and the server does not allow setting up again. Ask the admin.";
            if (code != 200) return "!The server answered " + code + ".";
            java.io.InputStream in = h.getInputStream();
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < 65536) b.write(buf, 0, n);
            in.close();
            String t = new String(b.toByteArray(), "UTF-8").trim();
            return SetupQr.is(t) ? t : "!The server's answer was not a setup code.";
        } catch (Exception e) {
            return "!Could not reach " + s.optString("server") + " (" + e.getClass().getSimpleName()
                    + "). Join that network's WiFi and try again.";
        } finally {
            if (h != null) h.disconnect();
        }
    }
}
