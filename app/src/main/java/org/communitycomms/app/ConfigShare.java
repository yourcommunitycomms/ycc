package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.widget.Toast;

import org.communitycomms.app.dryad.DryadSheet;
import org.communitycomms.app.dryad.SheetStore;
import org.communitycomms.app.net.FileTransfer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hands a whole setup to other phones: every setting and all DRYAD sheets, but never the callsign,
 * PIN, private-message keys or things that belong to one phone (its PTT key, Bluetooth button,
 * network interface, audio levels). Sent as a file "settings.ucconfig" on the channel on air; the
 * receiver is asked "Clear all settings and accept the new ones?".
 *
 * File (UTF-8 JSON): {"format":"UCCONFIG1","from":callsign,
 *   "prefs":{fileName:{key:{"t":"s|i|l|b|f","v":value}}}, "sheets":"DRYAD text, all sheets"}
 */
public final class ConfigShare {
    public static final String FILE_NAME = "settings.ucconfig";
    public static final String MIME = "application/x-ucconfig";
    private static final String[] FILES = {"settings", "map", "quickProfiles", "dryad"};
    /** Kept on this phone, never sent and never overwritten. */
    private static final Set<String> LOCAL = new HashSet<String>(Arrays.asList(
            "callsign", "pinHash", "pinSalt", "pinFails", "wipeOnBadPin", "lastChannelHash", "pmPub", "pmPriv",
            "setupDone", "uiLocked", "btButton", "btButtonName", "pttKey", "netInterface", "radioSilent",
            "rxGainPct", "micGainPct", "onlineUntil",
            // this phone's own state and screen choices, and its private channel notes
            "status", "wasOnAir", "permissionsShown", "titleHidden", "commanderChannels", "commanderSideHidden",
            "contactsCompact", "broadcast", "noteA", "noteB", "noteC", "noteD", "noteE", "noteF", "noteG", "noteH", "notePublic",
            "noteOpenMANET",
            // this phone's audio devices, and its tunnel (each remote has its own key and port)
            "audioOut", "micType", "ringSpeaker", "tunnelServer", "tunnelKey", "tunnelRemotes", "tunnelRelayOn",
            "myIcon", "intercomRooms", "intercomCamera", "streams", "fakeGpsFor", "fakeGpsLat", "fakeGpsLon",
            // LAN sync is chosen per phone (e.g. auto update only on the test phone); relay switch per phone
            "lanAutoUpdate", "lanSyncEvery", "lanSyncLogs", "lanSyncData", "lanSyncDryad", "lanSyncConfig", "lanSyncLast",
            "lanSyncResult", "lanUpdateFile", "lanUpdateCode", "lan2Url", "lan2User", "lan2Pass", "swapLR", "tunnelClientOn", "relayPromptAt", "homeNetOn", "homeNetSsid", "atakOn", "atakUid"));

    /** LAN storage location: sent only if the user chose to share it; received ones are offered separately. */
    private static final Set<String> LAN_KEYS = new HashSet<String>(Arrays.asList("lanUrl", "lanUser", "lanPass", "lanShare"));
    /** Never in a backup file either. */
    private static final Set<String> SECRET = new HashSet<String>(Arrays.asList("pinHash", "pinSalt", "pinFails", "pmPub", "pmPriv"));

    /** Received and waiting for the user's answer (set by RadioService, shown by the home screen). */
    public static volatile File pending;
    public static volatile String pendingFrom = "";

    private ConfigShare() {
    }

    public static boolean isConfig(File f) {
        if (f == null) return false;
        String n = f.getName().toLowerCase(java.util.Locale.US);
        return n.endsWith(".ucconfig") || (n.startsWith("settings") && n.contains(".ucconfig"));
    }

    // ---- sending ----

    public static void offerSend(final Activity a) {
        if (RadioService.files() == null) {
            Toast.makeText(a, "Join a channel first: the settings go to everyone on it", Toast.LENGTH_LONG).show();
            return;
        }
        if (RadioEngine.radioSilent) {
            Toast.makeText(a, "Radio silent: not sent", Toast.LENGTH_LONG).show();
            return;
        }
        int sheets = new SheetStore(a).loadAll(false).size();
        String msg = "Sends all your settings (channel names A and B, public key, network, options, map point "
                + "profiles) and " + sheets + " DRYAD sheet(s) to everyone on this channel. Your callsign, PIN and "
                + "phone-specific settings are not sent. Each phone is asked before anything changes.";
        if (RadioService.onPublic()) {
            msg = "You are on Public: ANYONE on it receives your channel names and DRYAD sheets, so they could "
                    + "join and read your private channels.\n\n" + msg;
        }
        new AlertDialog.Builder(a)
                .setTitle("Share settings")
                .setMessage(msg)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        send(a);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void send(Context c) {
        try {
            File dir = new File(c.getCacheDir(), "outgoing");
            dir.mkdirs();
            File f = new File(dir, FILE_NAME);
            FileOutputStream os = new FileOutputStream(f);
            os.write(export(c).toString().getBytes("UTF-8"));
            os.close();
            FileTransfer ft = RadioService.files();
            if (ft == null) throw new java.io.IOException("not on a channel");
            FileTransfer.Outgoing o = ft.send(f, FILE_NAME, MIME);
            RadioService.logFileSent(c, o, MIME, f);
            Toast.makeText(c, "Sending settings to the channel", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(c, "Could not send settings: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Sends the LAN storage location (address, user, password) to everyone on the channel. Receivers
     * on this version are only asked "Update LAN storage location?". The file is a whole settings
     * bundle marked "lanOnly", so older phones see an ordinary Share config (nothing wrong if accepted).
     */
    public static void sendLan(Context c) {
        if (RadioService.files() == null) {
            Toast.makeText(c, "Join a channel to send the LAN storage location to the group", Toast.LENGTH_LONG).show();
            return;
        }
        if (RadioEngine.radioSilent) {
            Toast.makeText(c, "Radio silent: not sent", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            JSONObject cfg = export(c);
            cfg.put("lanOnly", true);
            JSONObject s = cfg.getJSONObject("prefs").getJSONObject("settings");
            android.content.SharedPreferences sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
            for (String k : new String[]{"lanUrl", "lanUser", "lanPass"}) {
                s.put(k, new JSONObject().put("t", "s").put("v", sp.getString(k, "")));
            }
            File dir = new File(c.getCacheDir(), "outgoing");
            dir.mkdirs();
            File f = new File(dir, "lanstorage.ucconfig");
            FileOutputStream os = new FileOutputStream(f);
            os.write(cfg.toString().getBytes("UTF-8"));
            os.close();
            FileTransfer.Outgoing o = RadioService.files().send(f, f.getName(), MIME);
            RadioService.logFileSent(c, o, MIME, f);
            Toast.makeText(c, "LAN storage location sent to the channel", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(c, "Could not send it: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    static JSONObject export(Context c) throws Exception {
        return export(c, false);
    }

    /** backup = this phone's own copy (Save my config): keeps its own settings too, never the PIN. */
    static JSONObject export(Context c, boolean backup) throws Exception {
        boolean shareLan = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("lanShare", false);
        JSONObject root = new JSONObject();
        root.put("format", "UCCONFIG1");
        if (backup) root.put("backup", true);
        root.put("from", new Prefs(c).callsign());
        JSONObject prefs = new JSONObject();
        for (String name : FILES) {
            JSONObject one = new JSONObject();
            for (Map.Entry<String, ?> e : c.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().entrySet()) {
                if (e.getValue() == null || SECRET.contains(e.getKey())) continue;
                if (!backup && LOCAL.contains(e.getKey())) continue;
                if (!backup && !shareLan && LAN_KEYS.contains(e.getKey())) continue;
                Object v = e.getValue();
                String t = v instanceof String ? "s" : v instanceof Integer ? "i" : v instanceof Long ? "l"
                        : v instanceof Boolean ? "b" : v instanceof Float ? "f" : null;
                if (t == null) continue; // string sets are not used
                one.put(e.getKey(), new JSONObject().put("t", t).put("v", v));
            }
            prefs.put(name, one);
        }
        root.put("prefs", prefs);
        StringBuilder sheets = new StringBuilder();
        for (SheetStore.Entry e : new SheetStore(c).loadAll(false)) sheets.append(e.sheet.toText()).append('\n');
        root.put("sheets", sheets.toString());
        return root;
    }

    // ---- receiving ----

    /** Asks "clear all settings and accept?"; done = runs after accepting (e.g. recreate the screen). */
    public static void offerApply(final Activity a, final File f, String from, final Runnable done) {
        final JSONObject cfg;
        try {
            cfg = read(f);
        } catch (Exception e) {
            Toast.makeText(a, "Not a valid settings file", Toast.LENGTH_LONG).show();
            return;
        }
        if (cfg.optBoolean("lanOnly")) { // just the LAN storage location (Settings > LAN storage, Share)
            offerLan(a, cfg);
            return;
        }
        int sheets = 0;
        try {
            String s = cfg.optString("sheets");
            if (s.trim().length() > 0) sheets = DryadSheet.parseAll(s).size();
        } catch (RuntimeException ignored) {
        }
        String who = cfg.optString("from", from);
        new AlertDialog.Builder(a)
                .setTitle("Settings from " + who)
                .setMessage(who + " sent their settings and " + sheets + " DRYAD sheet(s).\n\nClear all settings and "
                        + "accept the new ones? Your DRYAD sheets are replaced too. Your callsign and PIN stay as they are.")
                .setPositiveButton("Clear and accept", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            apply(a, cfg);
                            Toast.makeText(a, "New settings in use", Toast.LENGTH_LONG).show();
                            offerLan(a, cfg);
                            if (done != null) done.run();
                        } catch (Exception e) {
                            Toast.makeText(a, "Could not apply: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("Keep mine", null)
                .show();
    }

    /** The sender shared their LAN storage location: "Update LAN storage location?" (asked on its own). */
    private static void offerLan(final Activity a, JSONObject cfg) {
        JSONObject s = cfg.optJSONObject("prefs") == null ? null : cfg.optJSONObject("prefs").optJSONObject("settings");
        if (s == null || s.optJSONObject("lanUrl") == null) return;
        final String url = s.optJSONObject("lanUrl").optString("v");
        final String user = s.optJSONObject("lanUser") == null ? "" : s.optJSONObject("lanUser").optString("v");
        final String pass = s.optJSONObject("lanPass") == null ? "" : s.optJSONObject("lanPass").optString("v");
        final org.communitycomms.app.geo.LanTiles lan = new org.communitycomms.app.geo.LanTiles(a);
        if (url.length() == 0 || url.equals(lan.base())) return;
        new AlertDialog.Builder(a)
                .setTitle("Update LAN storage location?")
                .setMessage("The settings include a LAN storage location for map tiles:\n" + url
                        + (user.length() > 0 ? "\nuser " + user : "") + "\n\nUse it on this phone?"
                        + (lan.configured() ? " (Replaces " + lan.base() + ")" : ""))
                .setPositiveButton("Update", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        lan.save(url, user, pass, true);
                        Toast.makeText(a, "LAN storage location updated", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Keep mine", null)
                .show();
    }

    // ---- this phone's own backups (Settings > Config backups) ----

    public static File backupFile(Context c) {
        File d = c.getExternalFilesDir("backups");
        if (d == null) d = new File(c.getFilesDir(), "backups");
        d.mkdirs();
        return new File(d, "myconfig.ucconfig");
    }

    /** Save my config: every setting and DRYAD sheet (never the PIN) to the app's backups folder. */
    public static String saveMine(Context c) {
        try {
            File f = backupFile(c);
            FileOutputStream os = new FileOutputStream(f);
            os.write(export(c, true).toString(2).getBytes("UTF-8"));
            os.close();
            return "Saved to " + f.getPath();
        } catch (Exception e) {
            return "Could not save: " + e.getMessage();
        }
    }

    /** Read my config: asks, then puts back everything from the saved file (the PIN stays). */
    public static void readMine(final Activity a, final Runnable done) {
        final File f = backupFile(a);
        if (!f.isFile()) {
            Toast.makeText(a, "No saved config yet (" + f.getPath() + ")", Toast.LENGTH_LONG).show();
            return;
        }
        final JSONObject cfg;
        try {
            cfg = read(f);
        } catch (Exception e) {
            Toast.makeText(a, "The saved config is damaged", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle("Read my config")
                .setMessage("Replace all settings and DRYAD sheets with the ones saved on "
                        + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(f.lastModified()))
                        + "? Your PIN stays as it is.")
                .setPositiveButton("Replace", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            apply(a, cfg, true);
                            Toast.makeText(a, "Saved config in use", Toast.LENGTH_LONG).show();
                            if (done != null) done.run();
                        } catch (Exception e) {
                            Toast.makeText(a, "Could not apply: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Load defaults: every option back to how the app ships, keeping who and where this phone is
     * (callsign, channel keys, Public key, PIN, DRYAD sheets, setup done).
     */
    public static void loadDefaults(final Activity a, final Runnable done) {
        final org.communitycomms.app.geo.LanTiles lan = new org.communitycomms.app.geo.LanTiles(a);
        if (!lan.configured()) {
            loadAppDefaults(a, done);
            return;
        }
        // The LAN storage's admin page can set network defaults: offer them when there are some.
        new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] b = lan.getPath(NETWORK_DEFAULTS);
                JSONObject cfg = null;
                try {
                    if (b != null) cfg = new JSONObject(new String(b, "UTF-8"));
                    if (cfg != null && !"UCCONFIG1".equals(cfg.optString("format"))) cfg = null;
                } catch (Exception e) {
                    cfg = null;
                }
                final JSONObject net = cfg;
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (a.isFinishing()) return;
                        if (net == null) {
                            loadAppDefaults(a, done);
                            return;
                        }
                        new AlertDialog.Builder(a)
                                .setTitle("Load defaults")
                                .setMessage("The LAN storage has network defaults (set on its admin page"
                                        + (net.optString("from").length() > 0 ? ", from " + net.optString("from") : "")
                                        + "). Use those, or the app's own defaults?\n\nNetwork defaults replace all settings "
                                        + "and DRYAD sheets; your callsign, PIN and this phone's own settings stay.")
                                .setPositiveButton("Network defaults", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        try {
                                            apply(a, net);
                                            Toast.makeText(a, "Network defaults loaded", Toast.LENGTH_LONG).show();
                                            if (done != null) done.run();
                                        } catch (Exception e) {
                                            Toast.makeText(a, "Could not apply: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                        }
                                    }
                                })
                                .setNeutralButton("App defaults", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        loadAppDefaults(a, done);
                                    }
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    }
                });
            }
        }).start();
    }

    /** Network defaults set on the LAN storage's admin page (a UCCONFIG1 file). */
    public static final String NETWORK_DEFAULTS = "Configs/_defaults.ucconfig";

    private static void loadAppDefaults(final Activity a, final Runnable done) {
        new AlertDialog.Builder(a)
                .setTitle("Load defaults")
                .setMessage("Put every option back to how the app ships? Your callsign, channel keys, Public key, PIN and "
                        + "DRYAD sheets are kept. Tip: Save my config first if you may want this setup back.")
                .setPositiveButton("Load defaults", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Set<String> keep = new HashSet<String>(Arrays.asList("callsign", "setupDone", "permissionsShown",
                                "publicKey", "channel", "channelA", "channelB", "channelC", "channelD", "channelE", "channelF",
                                "channelG", "channelH", "appMode"));
                        keep.addAll(SECRET);
                        for (String name : FILES) {
                            if (name.equals("dryad")) continue; // the sheets' own store settings
                            SharedPreferences sp = a.getSharedPreferences(name, Context.MODE_PRIVATE);
                            SharedPreferences.Editor ed = sp.edit();
                            for (String k : sp.getAll().keySet()) if (!keep.contains(k)) ed.remove(k);
                            ed.commit();
                        }
                        for (String other : new String[]{"adsb", "ais", "video"}) {
                            a.getSharedPreferences(other, Context.MODE_PRIVATE).edit().clear().commit();
                        }
                        RadioEngine.radioSilent = new Prefs(a).radioSilent();
                        Toast.makeText(a, "Defaults loaded", Toast.LENGTH_LONG).show();
                        if (done != null) done.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static JSONObject read(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            JSONObject o = new JSONObject(new String(bo.toByteArray(), "UTF-8"));
            if (!"UCCONFIG1".equals(o.optString("format"))) throw new IllegalArgumentException("unknown format");
            return o;
        } finally {
            in.close();
        }
    }

    static void apply(Context c, JSONObject cfg) throws Exception {
        apply(c, cfg, false);
    }

    /** restore = my own backup: its phone-specific settings come back too (the PIN never changes). */
    static void apply(Context c, JSONObject cfg, boolean restore) throws Exception {
        JSONObject prefs = cfg.getJSONObject("prefs");
        for (String name : FILES) {
            SharedPreferences sp = c.getSharedPreferences(name, Context.MODE_PRIVATE);
            SharedPreferences.Editor ed = sp.edit();
            for (String k : sp.getAll().keySet()) {
                if (SECRET.contains(k)) continue;
                if (!restore && (LOCAL.contains(k) || LAN_KEYS.contains(k))) continue;
                ed.remove(k); // clear, keeping local ones
            }
            JSONObject one = prefs.optJSONObject(name);
            if (one != null) {
                Iterator<String> it = one.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (SECRET.contains(k)) continue;
                    if (!restore && (LOCAL.contains(k) || LAN_KEYS.contains(k))) continue;
                    JSONObject e = one.getJSONObject(k);
                    String t = e.getString("t");
                    if (t.equals("s")) ed.putString(k, e.getString("v"));
                    else if (t.equals("i")) ed.putInt(k, e.getInt("v"));
                    else if (t.equals("l")) ed.putLong(k, e.getLong("v"));
                    else if (t.equals("b")) ed.putBoolean(k, e.getBoolean("v"));
                    else if (t.equals("f")) ed.putFloat(k, (float) e.getDouble("v"));
                }
            }
            ed.commit();
        }
        // DRYAD sheets: replace them all.
        SheetStore store = new SheetStore(c);
        for (SheetStore.Entry e : store.loadAll(false)) store.delete(e.id);
        String s = cfg.optString("sheets");
        if (s.trim().length() > 0) {
            List<DryadSheet> sheets = DryadSheet.parseAll(s);
            store.importAll(sheets);
        }
        Prefs p = new Prefs(c);
        p.setSetupDone();
        RadioEngine.radioSilent = p.radioSilent();
    }
}
