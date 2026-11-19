package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.geo.MapPrefs;

/**
 * Data control: how the app uses internet / mobile data, set in one go.
 * <ul>
 * <li>Always online: online map tiles always on, automatic internet update check on, live internet feeds
 * (aircraft, ships, trains, alerts) allowed, the relay used over mobile data without asking.</li>
 * <li>User control: nothing forced; each setting as chosen below (and the way it worked before).</li>
 * <li>Offline: no online map tiles (and no asking), no automatic internet update check, no live internet feeds,
 * no tile downloads or online routing, the relay never used over mobile data. LAN servers still work.</li>
 * </ul>
 * Changing one of the settings by hand afterwards makes it User control. A setup code or ready-made APK can set
 * it and lock it ("Set by your group's server"); a later one can change or unlock it, and Clear all removes it.
 * Power modes are separate: Offline / Ultra low power still pause things as before.
 */
public final class DataControl {
    public static final int USER = 0, ONLINE = 1, OFFLINE = 2;
    public static final String[] NAMES = {"User control", "Always online", "Offline"};
    public static final String[] KEYS = {"user", "online", "offline"};
    /** Mobile data relay: 0 ask, 1 automatic, 2 never. */
    public static final String[] RELAY_NAMES = {"Ask first", "Use it without asking", "Never"};

    /** Quick flags for the network code (refreshed by {@link #load}). */
    public static volatile boolean feedsOff, offline;

    private DataControl() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static void load(Context c) {
        feedsOff = !sp(c).getBoolean("feedsInternet", true);
        offline = mode(c) == OFFLINE;
    }

    /** The preset in force; one changed by hand reads as User control. Phones from before this start on User control. */
    public static int mode(Context c) {
        int m = sp(c).getInt("dataMode", USER);
        if (m == USER) return USER;
        if (!matches(c, m)) {
            if (!locked(c)) sp(c).edit().putInt("dataMode", USER).commit();
            return locked(c) ? m : USER;
        }
        return m;
    }

    private static boolean matches(Context c, int m) {
        MapPrefs mp = new MapPrefs(c);
        SharedPreferences s = sp(c);
        boolean online = mp.onlineEnabled(), auto = s.getBoolean("autoUpdateInternet", true), feeds = s.getBoolean("feedsInternet", true);
        int relay = s.getInt("mobileRelay", 0);
        if (m == ONLINE) return online && auto && feeds && relay == 1;
        return !online && mp.onlineUntil() < System.currentTimeMillis() && !auto && !feeds && relay == 2;
    }

    public static boolean locked(Context c) {
        return sp(c).getBoolean("dataLock", false);
    }

    public static String label(Context c) {
        return NAMES[mode(c)] + (locked(c) ? " (set by your group's server)" : "");
    }

    /** Sets a preset (USER changes nothing underneath). lock: true / false, or null to keep the lock as it is. */
    public static void set(Context c, int m, Boolean lock) {
        SharedPreferences.Editor e = sp(c).edit().putInt("dataMode", m);
        if (lock != null) e.putBoolean("dataLock", lock);
        MapPrefs mp = new MapPrefs(c);
        if (m == ONLINE) {
            mp.setOnlineEnabled(true);
            e.putBoolean("autoUpdateInternet", true).putBoolean("feedsInternet", true).putInt("mobileRelay", 1);
        } else if (m == OFFLINE) {
            mp.setOnlineEnabled(false);
            mp.setOnlineFor(0);
            e.putBoolean("autoUpdateInternet", false).putBoolean("feedsInternet", false).putInt("mobileRelay", 2);
        }
        e.commit();
        load(c);
    }

    /** From a setup code or ready-made APK: "data" (online / user / offline) and "dataLock" (1 locks it). */
    public static void apply(Context c, org.json.JSONObject o) {
        if (o == null) return;
        int m = java.util.Arrays.asList(KEYS).indexOf(o.optString("data"));
        if (m >= 0) set(c, m, o.optInt("dataLock", 0) == 1);
        else if (o.has("dataLock")) sp(c).edit().putBoolean("dataLock", o.optInt("dataLock") == 1).commit();
    }

    public static boolean autoUpdateInternet(Context c) {
        return sp(c).getBoolean("autoUpdateInternet", true);
    }

    public static int mobileRelay(Context c) {
        return sp(c).getInt("mobileRelay", 0);
    }

    /** Online tile prompt and tile switches: may the person change online tiles here? */
    public static boolean tilesChangeable(Context c) {
        return !(locked(c) && mode(c) != USER);
    }

    /** Throws if this fetch is blocked by Data control (internet only: LAN addresses always pass). feed: a live feed. */
    public static void check(String url, boolean feed) throws java.io.IOException {
        if (!(offline || (feed && feedsOff))) return;
        if (isLocal(url)) return;
        throw new java.io.IOException(offline ? "Data control is Offline (no internet fetches)" : "Live internet feeds are off (Data control)");
    }

    static boolean isLocal(String url) {
        try {
            String h = new java.net.URL(url).getHost().toLowerCase(java.util.Locale.US);
            if (h.equals("localhost") || h.endsWith(".local") || h.endsWith(".lan") || h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("127.")) return true;
            if (h.startsWith("172.")) {
                int b = Integer.parseInt(h.split("\\.")[1]);
                return b >= 16 && b <= 31;
            }
            return h.indexOf('.') < 0; // a bare LAN name
        } catch (Exception e) {
            return false;
        }
    }

    // ---- screens ----

    /** Shortcuts / Settings: pick a preset (or see the lock). done runs after a change (may be null). */
    public static void choose(final Activity a, final Runnable done) {
        if (locked(a) && mode(a) != USER) {
            new AlertDialog.Builder(a).setTitle("Data control")
                    .setMessage("Data: " + NAMES[mode(a)] + ".\n\nThis was set by your group's server (a ready-made app or setup code), "
                            + "so it can't be changed on this phone. A new setup from the server can change it.")
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        final float d = a.getResources().getDisplayMetrics().density;
        final RadioGroup g = new RadioGroup(a);
        String[] desc = {"User control: you choose each setting (online maps, update check, live feeds, relay over mobile data)",
                "Always online: online maps, update checks and live feeds, and the relay over mobile data without asking. "
                        + "For phones on unlimited data.",
                "Offline: nothing from the internet (no online maps, update checks or live feeds). Your own WiFi / MANET and LAN server still work."};
        int[] order = {ONLINE, USER, OFFLINE};
        for (int m : order) {
            RadioButton b = new RadioButton(a);
            b.setId(100 + m);
            b.setText(desc[m]);
            b.setPadding(b.getPaddingLeft(), (int) (6 * d), 0, (int) (6 * d));
            g.addView(b);
        }
        g.check(100 + mode(a));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        box.addView(g);
        TextView note = new TextView(a);
        note.setText("Power modes still apply: Offline or Ultra low power pause things as before.");
        note.setTextSize(12);
        note.setTextColor(0xff99aabb);
        note.setPadding(0, (int) (8 * d), 0, 0);
        box.addView(note);
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a).setTitle("How should the app use mobile data?")
                .setView(sv)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        int m = g.getCheckedRadioButtonId() - 100;
                        set(a, m, null);
                        if (m == USER) individual(a, done);
                        else if (done != null) done.run();
                    }
                })
                .setNeutralButton("Each setting…", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        individual(a, done);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    /** The individual data settings (User control). Saving one that differs from the preset makes it User control. */
    public static void individual(final Activity a, final Runnable done) {
        final float d = a.getResources().getDisplayMetrics().density;
        final MapPrefs mp = new MapPrefs(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        final boolean ro = locked(a) && mode(a) != USER;
        final CheckBox maps = new CheckBox(a);
        maps.setText("Always fetch online map tiles when there is internet (off: ask when the map has no offline tiles)");
        maps.setChecked(mp.onlineEnabled());
        final CheckBox upd = new CheckBox(a);
        upd.setText("Check the internet for app updates by itself (every 6 hours; the LAN server is always checked)");
        upd.setChecked(autoUpdateInternet(a));
        final CheckBox feeds = new CheckBox(a);
        feeds.setText("Live internet feeds (aircraft, ships, trains, alerts) where they are switched on");
        feeds.setChecked(sp(a).getBoolean("feedsInternet", true));
        TextView rl = new TextView(a);
        rl.setText("No WiFi but a relay server is set up: use it over mobile data?");
        rl.setPadding(0, (int) (10 * d), 0, 0);
        final Spinner relay = new Spinner(a);
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<String>(a, android.R.layout.simple_spinner_item, RELAY_NAMES);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        relay.setAdapter(ad);
        relay.setSelection(Math.max(0, Math.min(2, mobileRelay(a))));
        for (android.view.View v : new android.view.View[]{maps, upd, feeds, rl, relay}) {
            v.setEnabled(!ro);
            box.addView(v);
        }
        if (ro) {
            TextView n = new TextView(a);
            n.setText("Set by your group's server: these can't be changed on this phone.");
            n.setTextColor(0xffffb74d);
            n.setPadding(0, (int) (10 * d), 0, 0);
            box.addView(n);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle("Data settings (" + NAMES[mode(a)] + ")").setView(sv);
        if (ro) {
            b.setPositiveButton(android.R.string.ok, null);
        } else {
            b.setPositiveButton("Save", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface di, int w) {
                    mp.setOnlineEnabled(maps.isChecked());
                    sp(a).edit().putBoolean("autoUpdateInternet", upd.isChecked()).putBoolean("feedsInternet", feeds.isChecked())
                            .putInt("mobileRelay", relay.getSelectedItemPosition()).commit();
                    mode(a); // a preset that no longer matches becomes User control
                    load(a);
                    Toast.makeText(a, "Data: " + NAMES[mode(a)], Toast.LENGTH_SHORT).show();
                    if (done != null) done.run();
                }
            }).setNegativeButton(android.R.string.cancel, null);
        }
        b.show();
    }
}
