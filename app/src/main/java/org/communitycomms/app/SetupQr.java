package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.Toast;

import org.communitycomms.app.geo.LanTiles;

import org.json.JSONObject;

/**
 * Phone setup QR code from the LAN storage's admin page (Phone setup tab):
 * "YCCSETUP1|" + JSON {"name","url","user","pass","tunnel","tkey","pub","chA","omg","omh","home","defaults"}.
 * A code for a remote person (the bridge tab) may hold only the tunnel, with no "url".
 * Asks, checks the storage login works, saves it, loads the network defaults and DRYAD sheets from it
 * (when "defaults" is set), then sets the tunnel server, Public key and channel A.
 */
public final class SetupQr {
    public static final String PREFIX = "YCCSETUP1|";

    private SetupQr() {
    }

    public static boolean is(String text) {
        return text != null && text.startsWith(PREFIX);
    }

    /** The same code as the admin page makes, from this phone's own settings (null without LAN storage). */
    public static String make(android.content.Context c, boolean withChannelA) {
        android.content.SharedPreferences sp = c.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE);
        LanTiles lan = new LanTiles(c);
        if (!lan.configured()) return null;
        Prefs p = new Prefs(c);
        JSONObject o = new JSONObject();
        try {
            o.put("name", "from " + sp.getString("callsign", "a phone"));
            o.put("url", lan.base());
            put(o, "user", lan.user());
            put(o, "pass", sp.getString("lanPass", ""));
            put(o, "tunnel", p.tunnelServer());
            put(o, "tkey", p.tunnelKey());
            if (!Prefs.PUBLIC_CHANNEL.equals(p.publicKey())) put(o, "pub", p.publicKey());
            if (withChannelA) put(o, "chA", p.channel(Prefs.SLOT_A));
            if (p.showOpenManet()) {
                put(o, "omg", p.openManetGroup());
                put(o, "omh", sp.getString("openmanetHost", ""));
            }
            if (sp.getBoolean("homeNetOn", false)) put(o, "home", sp.getString("homeNetSsid", ""));
            o.put("defaults", 1);
        } catch (org.json.JSONException e) {
            return null;
        }
        return PREFIX + o.toString();
    }

    private static void put(JSONObject o, String k, String v) throws org.json.JSONException {
        if (v != null && v.trim().length() > 0) o.put(k, v.trim());
    }

    /** Settings button: asks whether to include channel A, then shows the code full screen. */
    public static void show(final Activity a) {
        if (make(a, false) == null) {
            Toast.makeText(a, "Set the LAN storage location first (Settings > LAN storage location)", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle("Phone setup QR code")
                .setMessage("New phones scan this in the setup wizard (or Settings > Scan QR) to get this phone's "
                        + "LAN storage and its login, tunnel server, Public key, OpenMANET and home WiFi, then the "
                        + "network defaults. It holds the LAN storage password: only show it to your own group.\n\n"
                        + "Include channel A (" + new Prefs(a).channel(Prefs.SLOT_A) + ")?")
                .setPositiveButton("With channel A", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        showCode(a, true);
                    }
                })
                .setNeutralButton("Without", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        showCode(a, false);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showCode(Activity a, boolean withA) {
        java.util.ArrayList<String> payloads = new java.util.ArrayList<String>();
        java.util.ArrayList<String> titles = new java.util.ArrayList<String>();
        payloads.add(make(a, withA));
        titles.add("Phone setup");
        QrShowActivity.start(a, payloads, titles, null);
    }

    /** Shows what the code sets; onCancel runs if the user says no (the scanner carries on). */
    public static void offer(final Activity a, String text, final Runnable onCancel, final Runnable onDone) {
        final JSONObject o;
        try {
            o = new JSONObject(text.substring(PREFIX.length()));
        } catch (Exception e) {
            Toast.makeText(a, "That setup QR code is damaged", Toast.LENGTH_LONG).show();
            if (onCancel != null) onCancel.run();
            return;
        }
        final String url = o.optString("url").trim();
        final boolean hasLan = url.startsWith("http://") || url.startsWith("https://");
        if (!hasLan && o.optString("tunnel").length() == 0) { // a code for a remote person may hold only the tunnel
            Toast.makeText(a, "That setup QR code has no storage address", Toast.LENGTH_LONG).show();
            if (onCancel != null) onCancel.run();
            return;
        }
        StringBuilder sb = new StringBuilder("This sets up the phone for ");
        sb.append(o.optString("name").length() > 0 ? o.optString("name") : "this network").append(":\n\n");
        if (hasLan) sb.append("LAN storage: ").append(url);
        if (hasLan && o.optString("user").length() > 0) sb.append(" (user ").append(o.optString("user")).append(")");
        if (o.optString("tunnel").length() > 0) sb.append("\nTunnel server: ").append(o.optString("tunnel"));
        if (o.optString("pub").length() > 0) sb.append("\nPublic channel key");
        if (o.optString("chA").length() > 0) sb.append("\nChannel A: ").append(o.optString("chA"));
        if (o.optString("omg").length() > 0) {
            sb.append("\nOpenMANET on, group ").append(o.optString("omg"));
            if (o.optString("omh").length() > 0) sb.append(", node ").append(o.optString("omh"));
        }
        if (o.optString("home").length() > 0) sb.append("\nHome WiFi: ").append(o.optString("home"));
        if (o.optInt("defaults") == 1) {
            sb.append("\n\nThen it loads the network defaults and DRYAD sheets from the storage, replacing these "
                    + "settings and sheets. Your callsign, PIN and this phone's own settings stay.");
        }
        new AlertDialog.Builder(a)
                .setTitle("Phone setup QR code")
                .setMessage(sb.toString())
                .setPositiveButton("Set up", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (hasLan) check(a, o, url, onCancel, onDone);
                        else apply(a, o, "", onDone);
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (onCancel != null) onCancel.run();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private static void check(final Activity a, final JSONObject o, final String url, final Runnable onCancel,
                              final Runnable onDone) {
        Toast.makeText(a, "Checking the LAN storage...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = new LanTiles(a, url, o.optString("user"), o.optString("pass")).test();
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (a.isFinishing()) return;
                        if (result.startsWith("OK") || result.startsWith("Reached")) {
                            apply(a, o, url, onDone);
                            return;
                        }
                        new AlertDialog.Builder(a)
                                .setTitle("Could not use the LAN storage")
                                .setMessage(result + "\n\nCheck the phone is on the right WiFi or MANET, then scan again.")
                                .setPositiveButton("Set up anyway", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        apply(a, o, url, onDone);
                                    }
                                })
                                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        if (onCancel != null) onCancel.run();
                                    }
                                })
                                .show();
                    }
                });
            }
        }).start();
    }

    private static void apply(final Activity a, final JSONObject o, String url, final Runnable onDone) {
        final LanTiles lan = new LanTiles(a);
        // Without a user or password in the code, keep the ones this phone already has.
        if (url.length() > 0) {
            lan.save(url, o.has("user") ? o.optString("user") : lan.user(), o.has("pass") ? o.optString("pass") : null,
                    lan.shareWithManet());
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg = "Phone set up";
                if (o.optInt("defaults") == 1) {
                    byte[] b = lan.getPath(ConfigShare.NETWORK_DEFAULTS);
                    try {
                        JSONObject net = b == null ? null : new JSONObject(new String(b, "UTF-8"));
                        if (net != null && "UCCONFIG1".equals(net.optString("format"))) {
                            ConfigShare.apply(a, net);
                            msg = "Phone set up with the network defaults";
                        } else {
                            msg = "Phone set up (the storage has no network defaults yet)";
                        }
                    } catch (Exception e) {
                        msg = "Phone set up, but the network defaults could not be loaded";
                    }
                }
                final String done = msg;
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // After the defaults, which would otherwise clear the channel and Public key.
                        Prefs p = new Prefs(a);
                        if (o.optString("tunnel").length() > 0) p.setTunnel(o.optString("tunnel"), o.optString("tkey"));
                        if (o.optString("pub").length() > 0) p.setPublicKey(o.optString("pub"));
                        if (o.optString("chA").length() > 0) p.setChannel(Prefs.SLOT_A, o.optString("chA"));
                        android.content.SharedPreferences.Editor ed =
                                a.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit();
                        if (o.optString("omg").length() > 0) {
                            p.setOpenManetGroup(o.optString("omg"));
                            ed.putBoolean("showOpenManet", true);
                            if (o.optString("omh").length() > 0) ed.putString("openmanetHost", o.optString("omh"));
                        }
                        if (o.optString("home").length() > 0) {
                            ed.putBoolean("homeNetOn", true).putString("homeNetSsid", o.optString("home"));
                        }
                        ed.commit();
                        p.setSetupDone();
                        Toast.makeText(a, done + (o.optString("tunnel").length() > 0
                                ? ". Turn the tunnel on in Settings when away from the MANET." : ""), Toast.LENGTH_LONG).show();
                        if (onDone != null) onDone.run();
                    }
                });
            }
        }).start();
    }
}
