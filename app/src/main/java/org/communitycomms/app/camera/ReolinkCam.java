package org.communitycomms.app.camera;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One Reolink camera lens (a dual-lens camera or an NVR channel is saved as one entry per lens),
 * and the shared list used by both the Tactical videos and the Intercom screens. Kept only in
 * this phone's own "reolink" preferences file: never in the setup QR, settings share, LAN sync
 * or logs (ConfigShare only copies the settings/map/quickProfiles/dryad files).
 */
public final class ReolinkCam {
    public static final String PREFS = "reolink";

    public String host = "";      // address on the LAN (no port)
    public int httpPort = 80;
    public boolean https;
    public int rtspPort = 554;
    public String user = "admin", pass = "";
    public String name = "", model = "";
    /** 0-based, as the HTTP API counts. RTSP counts from 1 (h264Preview_01_...). */
    public int channel;
    public int channels = 1;
    /** Full-screen view: "sub" (plays on most phones), "main" (best, often H.265) or "snap" (pictures only). */
    public String fullStream = "sub";
    public boolean showOnTrigger = true, linkedFlood, hasFlood, enabled = true;
    /** On the phone or tablet that watches the cameras: tell the channel (chat), read it aloud there, add one picture. */
    public boolean sendAlert, announce, attachPicture;
    /** Also tell the channel when this camera stops answering for 2 minutes, and when it is back. */
    public boolean offlineAlert;
    /**
     * A camera that only answers on Reolink's own port 9000 (no HTTP / RTSP, e.g. the E1). Until the
     * port 9000 client gives pictures it is watched for presence only (online / offline).
     */
    public boolean bcOnly;
    public int bcPort = 9000;

    public String key() {
        return host + ":" + httpPort + "/" + channel;
    }

    /** The label on the video tiles. */
    public String feedName() {
        return name.length() > 0 ? name : (model.length() > 0 ? model : "Camera") + (channels > 1 ? " lens " + (channel + 1) : "");
    }

    public String baseUrl() {
        boolean std = https ? httpPort == 443 : httpPort == 80;
        return (https ? "https://" : "http://") + host + (std ? "" : ":" + httpPort);
    }

    /** rtsp://user:pass@host:554/h264Preview_01_sub (newer firmware also takes /Preview_01_sub). */
    public String rtspUrl(boolean main, boolean newStyle) {
        String ch = (channel + 1 < 10 ? "0" : "") + (channel + 1);
        return "rtsp://" + android.net.Uri.encode(user) + ":" + android.net.Uri.encode(pass) + "@" + host
                + (rtspPort == 554 ? "" : ":" + rtspPort) + "/" + (newStyle ? "Preview_" : "h264Preview_") + ch + (main ? "_main" : "_sub");
    }

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("host", host).put("port", httpPort).put("https", https).put("rtsp", rtspPort);
        o.put("user", user).put("pass", pass).put("name", name).put("model", model);
        o.put("ch", channel).put("chs", channels).put("full", fullStream);
        o.put("show", showOnTrigger).put("flood", linkedFlood).put("hasFlood", hasFlood).put("on", enabled);
        o.put("alert", sendAlert).put("say", announce).put("pic", attachPicture).put("offline", offlineAlert).put("bc", bcOnly).put("bcPort", bcPort);
        return o;
    }

    static ReolinkCam fromJson(JSONObject o) {
        ReolinkCam c = new ReolinkCam();
        c.host = o.optString("host", "");
        c.httpPort = o.optInt("port", 80);
        c.https = o.optBoolean("https", false);
        c.rtspPort = o.optInt("rtsp", 554);
        c.user = o.optString("user", "admin");
        c.pass = o.optString("pass", "");
        c.name = o.optString("name", "");
        c.model = o.optString("model", "");
        c.channel = o.optInt("ch", 0);
        c.channels = Math.max(1, o.optInt("chs", 1));
        c.fullStream = o.optString("full", "sub");
        c.showOnTrigger = o.optBoolean("show", true);
        c.linkedFlood = o.optBoolean("flood", false);
        c.hasFlood = o.optBoolean("hasFlood", false);
        c.enabled = o.optBoolean("on", true);
        c.sendAlert = o.optBoolean("alert", false);
        c.announce = o.optBoolean("say", false);
        c.attachPicture = o.optBoolean("pic", false);
        c.offlineAlert = o.optBoolean("offline", false);
        c.bcOnly = o.optBoolean("bc", false);
        c.bcPort = o.optInt("bcPort", 9000);
        return c;
    }

    public ReolinkCam copy() {
        try {
            return fromJson(toJson());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- the shared list and its settings ----

    public static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static volatile int version;

    /** Goes up on every save, so running screens and the watcher can pick up changes. */
    public static int version() {
        return version;
    }

    public static synchronized List<ReolinkCam> load(Context c) {
        List<ReolinkCam> out = new ArrayList<ReolinkCam>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("cams", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    public static synchronized void save(Context c, List<ReolinkCam> cams) {
        // Tile labels must be unique: the video walls find a feed by its name.
        List<String> seen = new ArrayList<String>();
        JSONArray a = new JSONArray();
        try {
            for (ReolinkCam cam : cams) {
                String n = cam.feedName(), base = n;
                for (int i = 2; seen.contains(n); i++) n = base + " " + i;
                if (!n.equals(cam.feedName())) cam.name = n;
                seen.add(n);
                a.put(cam.toJson());
            }
        } catch (Exception ignored) {
        }
        sp(c).edit().putString("cams", a.toString()).commit();
        version++;
    }

    public static boolean any(Context c) {
        return sp(c).getString("cams", "[]").length() > 2;
    }

    public static String defaultUser(Context c) {
        return sp(c).getString("defUser", "admin");
    }

    public static String defaultPass(Context c) {
        return sp(c).getString("defPass", "");
    }

    public static void setDefaults(Context c, String user, String pass) {
        sp(c).edit().putString("defUser", user == null || user.length() == 0 ? "admin" : user)
                .putString("defPass", pass == null ? "" : pass).commit();
    }

    /** Seconds the linked floodlights stay on after the last trigger. */
    public static int floodSeconds(Context c) {
        return sp(c).getInt("floodSecs", 60);
    }

    /**
     * Hold time: each triggered camera stays up this many seconds after its own movement ends. Also the
     * shortest gap between two channel alerts from one camera.
     */
    public static int quietSeconds(Context c) {
        return sp(c).getInt("holdSecs", 60);
    }

    /** Count people / vehicle / animal detection (GetAiState) as well as plain motion. */
    public static boolean useAi(Context c) {
        return sp(c).getBoolean("useAi", true);
    }

    /** Plain motion (GetMdState) also triggers; off = only people/vehicle/animal. */
    public static boolean useMotion(Context c) {
        return sp(c).getBoolean("useMotion", true);
    }

    /** Keep watching for triggers (linked floodlights, channel alerts) while the radio runs, not only on the video screens. */
    public static boolean backgroundWatch(Context c) {
        return sp(c).getBoolean("bgWatch", false);
    }

    public static void setInt(Context c, String k, int v) {
        sp(c).edit().putInt(k, v).commit();
        version++;
    }

    public static void setBool(Context c, String k, boolean v) {
        sp(c).edit().putBoolean(k, v).commit();
        version++;
    }
}
