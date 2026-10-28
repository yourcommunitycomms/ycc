package org.communitycomms.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks everything the app needs and asks for it in one place: microphone, location, camera,
 * notifications, Bluetooth, and staying alive with the screen locked (battery optimisation and the
 * phone maker's own power saving, which Android cannot check, so it gets instructions instead).
 */
public final class PermissionsActivity extends Activity {
    private static final int REQ_ALL = 1;
    private LinearLayout box;

    private static final class Item {
        final String title, why;
        final String[] perms;

        Item(String title, String why, String... perms) {
            this.title = title;
            this.why = why;
            this.perms = perms;
        }
    }

    private static List<Item> runtimeItems() {
        List<Item> l = new ArrayList<Item>();
        l.add(new Item("Microphone", "To talk (push to talk, VOX) and for the gunshot alert*.", Manifest.permission.RECORD_AUDIO));
        l.add(new Item("Location", "To send your position, show distances in Contacts and on the map, "
                + "and to find Bluetooth PTT buttons on Android 11 and older.",
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION));
        l.add(new Item("Camera", "To take photos to send and to scan channel QR codes.", Manifest.permission.CAMERA));
        if (Build.VERSION.SDK_INT >= 33) {
            l.add(new Item("Notifications", "To show the radio is on, missed calls, messages and voice saved while away.",
                    "android.permission.POST_NOTIFICATIONS"));
        }
        if (Build.VERSION.SDK_INT >= 31) {
            l.add(new Item("Nearby devices (Bluetooth)", "For a Bluetooth headset or PTT button.",
                    "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"));
        }
        return l;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Permissions");
        ScrollView sv = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (12 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, p);
        sv.addView(box);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(); // back from a system settings screen: show what changed
    }

    private boolean granted(String perm) {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void render() {
        box.removeAllViews();
        final List<String> missing = new ArrayList<String>();
        for (Item it : runtimeItems()) for (String perm : it.perms) if (!granted(perm)) missing.add(perm);

        text(Build.VERSION.SDK_INT < 23
                ? "On this Android version every permission was given when the app was installed. "
                + "What is left is keeping the radio running with the screen locked (below)."
                : missing.isEmpty() ? "All permissions are allowed." : "Some permissions are still needed. "
                + "Tap Allow all, then choose Allow (or While using the app) for each one.", 15, 0xffffffff);
        if (!missing.isEmpty()) {
            button("Allow all", new Runnable() {
                @Override
                public void run() {
                    requestAll(missing);
                }
            });
        }
        for (Item it : runtimeItems()) {
            boolean ok = true;
            for (String perm : it.perms) ok &= granted(perm);
            row(it.title, it.why, ok);
        }

        heading("Keep working with the screen locked");
        if (Build.VERSION.SDK_INT >= 23) {
            boolean ignoring = ignoringBatteryOptimisation();
            row("Battery optimisation off for this app", "Otherwise Android may stop the radio, or stop it hearing "
                    + "the network, a while after the screen locks.", ignoring);
            if (!ignoring) {
                button("Turn battery optimisation off", new Runnable() {
                    @Override
                    public void run() {
                        askIgnoreBatteryOptimisation();
                    }
                });
            }
        }
        text(makerTips(), 14, 0xffcfe3d4);
        button("Open this app's Android settings", new Runnable() {
            @Override
            public void run() {
                openAppSettings();
            }
        });
        String wifi = wifiSleep();
        if (wifi != null) text(wifi, 14, 0xffcfe3d4);

        heading("\"Screen overlay detected\"?");
        text("Android 6 shows that when you allow a permission while another app is drawing over the screen "
                + "(a blue-light or dimmer app, chat bubbles, a screen recorder). It is about that other app: "
                + "this app never draws over others. Pause that app, allow the permission here, then turn it back on.",
                14, 0xffcfe3d4);

        heading("Setting up several phones?");
        text("With Developer options and USB debugging on, whoever installs the app over USB can allow all "
                + "of this from a computer in one go, instead of tapping through it on every phone.", 14, 0xffcfe3d4);
    }

    @TargetApi(23)
    private void requestAll(List<String> missing) {
        requestPermissions(missing.toArray(new String[0]), REQ_ALL);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        render();
        // Refused with "don't ask again": only the app's own settings page can change it now.
        if (Build.VERSION.SDK_INT >= 23) {
            for (int i = 0; i < p.length && i < r.length; i++) {
                if (r[i] != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(p[i])) {
                    Toast.makeText(this, "Some were refused for good: open this app's Android settings > Permissions",
                            Toast.LENGTH_LONG).show();
                    break;
                }
            }
        }
    }

    @TargetApi(23)
    private boolean ignoringBatteryOptimisation() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    @SuppressLint("BatteryLife") // sideloaded radio app: staying alive with the screen off is the point
    @TargetApi(23)
    private void askIgnoreBatteryOptimisation() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (ActivityNotFoundException e2) {
                openAppSettings();
            }
        }
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Open the phone's Settings > Apps > Your Community Communications", Toast.LENGTH_LONG).show();
        }
    }

    /** Phone makers add their own app killers that Android cannot report on. */
    private static String makerTips() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(java.util.Locale.US);
        String own;
        if (m.contains("samsung")) {
            own = "Samsung: Settings > Device maintenance (or Battery) > Battery > App power management / "
                    + "Unmonitored apps: add this app, and do not let it be put to sleep.";
        } else if (m.contains("zte") || m.contains("nubia")) {
            own = "ZTE: Settings > Power management (or Battery) > App power saving / Power-intensive prompt: "
                    + "set this app to Keep running (not Intelligent / Closed), and allow it to run after the screen locks.";
        } else if (m.contains("huawei") || m.contains("honor")) {
            own = "Huawei / Honor: Settings > Battery > App launch: this app to Manage manually, all three switches on.";
        } else if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) {
            own = "Xiaomi: Settings > Apps > this app > Battery saver: No restrictions, and Autostart on.";
        } else if (m.contains("oppo") || m.contains("realme") || m.contains("oneplus") || m.contains("vivo")) {
            own = "Settings > Battery > this app: allow background activity and auto-launch.";
        } else {
            own = "If the radio still goes quiet when the phone locks, look in the phone's Battery settings for a list "
                    + "of apps it closes or puts to sleep, and take this app off it.";
        }
        return own + "\nDo not swipe the app away from the recent apps list while you need the radio.";
    }

    /** Older Android: Wi-Fi can switch off with the screen ("Keep Wi-Fi on during sleep"). */
    @SuppressWarnings("deprecation")
    private String wifiSleep() {
        if (Build.VERSION.SDK_INT >= 26) return null; // the setting is gone; Wi-Fi stays on
        try {
            int v = Build.VERSION.SDK_INT >= 17
                    ? Settings.Global.getInt(getContentResolver(), Settings.Global.WIFI_SLEEP_POLICY, 2)
                    : Settings.System.getInt(getContentResolver(), Settings.System.WIFI_SLEEP_POLICY, 2);
            return v == 2 ? "Wi-Fi stays on while the screen is off. ✓"
                    : "Wi-Fi may switch off when the screen locks: Settings > Wi-Fi > Advanced > Keep Wi-Fi on during sleep: Always.";
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- small view helpers ----

    private void heading(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(13);
        v.setTextColor(0xff99aabb);
        v.setPadding(0, (int) (16 * getResources().getDisplayMetrics().density), 0, 0);
        box.addView(v);
    }

    private void text(String t, int sp, int colour) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(colour);
        v.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0, 0);
        box.addView(v);
    }

    private void row(String title, String why, boolean ok) {
        TextView v = new TextView(this);
        v.setText((ok ? "✓ " : "✗ ") + title + (ok ? "" : ": not allowed yet") + "\n" + why);
        v.setTextSize(14);
        v.setTextColor(ok ? 0xffa5d6a7 : 0xffff8a80);
        v.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);
        box.addView(v);
    }

    private void button(String t, final Runnable r) {
        Button bt = new Button(this);
        bt.setText(t);
        bt.setAllCaps(false);
        bt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        box.addView(bt);
    }
}
