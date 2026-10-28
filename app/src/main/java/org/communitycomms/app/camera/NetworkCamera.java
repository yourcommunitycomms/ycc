package org.communitycomms.app.camera;

import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * UNTESTED (*): one still picture from a network camera's snapshot address (IP, night vision or
 * thermal cameras usually have one, e.g. http://192.168.1.50/snapshot.jpg). Basic sign-in comes
 * from user:password@ in the address. Meant for cameras on the local network; nothing is uploaded.
 */
public final class NetworkCamera {
    private static final int MAX_BYTES = 15 * 1048576;

    private NetworkCamera() {
    }

    /** Runs on a background thread. Returns null when the picture was saved, else why not. */
    public static String snapshot(String address, File out) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(address);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            c.setUseCaches(false);
            String user = u.getUserInfo();
            if (user != null && user.length() > 0) {
                c.setRequestProperty("Authorization", "Basic "
                        + Base64.encodeToString(java.net.URLDecoder.decode(user, "UTF-8").getBytes("UTF-8"), Base64.NO_WRAP));
            }
            int code = c.getResponseCode();
            if (code != 200) return "the camera answered " + code + (code == 401 ? " (needs user:password@ in the address)" : "");
            String type = c.getContentType();
            if (type != null && !type.toLowerCase(java.util.Locale.US).startsWith("image/")) {
                return "that address gives \"" + type + "\", not a picture (a video stream address will not work)";
            }
            InputStream in = c.getInputStream();
            OutputStream os = new FileOutputStream(out);
            long total = 0;
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) return "the picture is too big";
                    os.write(buf, 0, n);
                }
            } finally {
                os.close();
                in.close();
            }
            return total == 0 ? "the camera sent an empty picture" : null;
        } catch (Exception e) {
            return "could not reach the camera: " + e.getMessage();
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
