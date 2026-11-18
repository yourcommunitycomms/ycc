package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.os.BatteryManager;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

/**
 * The Anchored button in the title bar (radio home, map home, full map). It shows while the device is plugged in
 * (grey crossed anchor = not anchored, gold anchor = anchored), and also while anchored on battery (gold anchor with
 * a red dot: power lost). One tap asks, then sets or clears Anchored (the same setting as Settings > Power and batteries).
 * It stays a manual setting: plugging in never anchors a device by itself (a power bank is not stable power).
 */
final class AnchorButton {
    static final int MENU_ID = 77;

    private AnchorButton() {
    }

    static boolean pluggedIn(Context c) {
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return b != null && b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    /** Adds the button (call from onCreateOptionsMenu); hidden when on battery and not anchored. */
    static void add(Activity a, Menu menu) {
        MenuItem it = menu.add(0, MENU_ID, 0, "Anchored");
        it.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        update(a, menu);
    }

    /** Refreshes the icon and whether it shows (call from onPrepareOptionsMenu too). */
    static void update(Activity a, Menu menu) {
        MenuItem it = menu.findItem(MENU_ID);
        if (it == null) return;
        boolean anchored = new Prefs(a).anchor(), plugged = pluggedIn(a);
        it.setVisible(plugged || anchored);
        it.setTitle(anchored ? (plugged ? "Anchored (tap to stop)" : "Anchored, but power lost (tap to stop)")
                : "Not anchored (tap to anchor)");
        it.setIcon(icon(a, anchored, plugged));
    }

    /** Handles a tap; true when it was the anchor button. */
    static boolean onSelected(final Activity a, MenuItem item) {
        if (item.getItemId() != MENU_ID) return false;
        ask(a, null);
        return true;
    }

    /** Asks, then sets or clears Anchored (the title bar button and the Shortcuts page). */
    static void ask(final Activity a, final Runnable after) {
        final boolean anchored = new Prefs(a).anchor();
        new AlertDialog.Builder(a)
                .setTitle(anchored ? "Stop being anchored?" : "Anchor this device?")
                .setMessage(anchored
                        ? "It will no longer stay awake to hold messages for sleeping phones, and the channel is no longer told if its power is lost."
                        : "Anchored means on stable power (mains, not a power bank). It stays awake, holds what sleeping phones miss, and tells the channel if its power is lost for 10 minutes.")
                .setPositiveButton(anchored ? "Stop" : "Anchor", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        PowerMode.setAnchor(a, !anchored);
                        Toast.makeText(a, anchored ? "Not anchored" : "Anchored", Toast.LENGTH_SHORT).show();
                        a.invalidateOptionsMenu();
                        if (after != null) after.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    /** Redraws the title bar when the power cable goes in or out (register in onResume, unregister in onPause). */
    static BroadcastReceiver watch(final Activity a) {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                a.invalidateOptionsMenu();
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        a.registerReceiver(r, f);
        return r;
    }

    static void unwatch(Activity a, BroadcastReceiver r) {
        if (r == null) return;
        try {
            a.unregisterReceiver(r);
        } catch (IllegalArgumentException ignored) {
        }
    }

    /** An anchor drawn on a canvas (works on Android 4.1): gold when anchored, grey and crossed out when not. */
    static BitmapDrawable icon(Context c, boolean anchored, boolean plugged) {
        float d = c.getResources().getDisplayMetrics().density;
        int s = Math.round(28 * d);
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(s * 0.09f);
        p.setColor(anchored ? 0xfff0c75e : 0xff9e9e9e);
        float cx = s / 2f;
        cv.drawCircle(cx, s * 0.17f, s * 0.08f, p); // ring
        cv.drawLine(cx, s * 0.25f, cx, s * 0.84f, p); // shank
        cv.drawLine(cx - s * 0.2f, s * 0.36f, cx + s * 0.2f, s * 0.36f, p); // stock
        cv.drawArc(new RectF(cx - s * 0.34f, s * 0.46f, cx + s * 0.34f, s * 0.86f), 20, 140, false, p); // arms
        p.setStyle(Paint.Style.FILL);
        cv.drawCircle(cx - s * 0.31f, s * 0.62f, s * 0.06f, p); // flukes
        cv.drawCircle(cx + s * 0.31f, s * 0.62f, s * 0.06f, p);
        if (!anchored) { // crossed out
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(s * 0.08f);
            p.setColor(0xffe57373);
            cv.drawLine(s * 0.15f, s * 0.85f, s * 0.85f, s * 0.15f, p);
        } else if (!plugged) { // anchored but on battery: power lost
            p.setColor(0xffe53935);
            cv.drawCircle(s * 0.82f, s * 0.18f, s * 0.13f, p);
        }
        BitmapDrawable bd = new BitmapDrawable(c.getResources(), b);
        bd.setBounds(0, 0, s, s);
        return bd;
    }
}
