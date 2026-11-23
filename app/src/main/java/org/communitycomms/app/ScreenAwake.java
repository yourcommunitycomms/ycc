package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Never let the screen sleep (Anchored menu and Black screen menu). Android's own screen timeout
 * turns the screen off and locks the phone, and some phones then stop the radio. Two ways to stay awake:
 * <ul>
 * <li>BLACK: kept awake, YCC's black screen after the Black screen time (30 s if it was "never"),
 * double-tap to wake. The screen is held dim, so another app or a YCC page without the black screen
 * is dim, not off.</li>
 * <li>LIT: kept awake and lit, the black screen does not come by itself (its button still works).</li>
 * </ul>
 * Held with FLAG_KEEP_SCREEN_ON on every YCC page plus a screen wake lock for other apps, while YCC runs.
 * The power button still turns the screen off.
 */
final class ScreenAwake implements Application.ActivityLifecycleCallbacks {
    static final int OFF = 0, BLACK = 1, LIT = 2;
    private static volatile int mode;
    private static PowerManager.WakeLock lock;

    static int mode() {
        return mode;
    }

    static boolean on() {
        return mode != OFF;
    }

    static void load(Context c) {
        mode = new Prefs(c).screenAwake();
        apply(c);
    }

    static void set(Context c, int m) {
        mode = m;
        Prefs p = new Prefs(c);
        p.setScreenAwake(m);
        if (m == BLACK && p.blackAfterSec() == 0) p.setBlackAfterSec(30);
        if (m == LIT) p.setBlackAfterSec(0);
        apply(c);
    }

    @SuppressWarnings("deprecation") // the screen wake locks still work, and are the only way to keep it on over other apps
    private static synchronized void apply(Context c) {
        if (lock != null && lock.isHeld()) lock.release();
        lock = null;
        if (mode == OFF) return;
        PowerManager pm = (PowerManager) c.getApplicationContext().getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        int level = mode == LIT ? PowerManager.SCREEN_BRIGHT_WAKE_LOCK : PowerManager.SCREEN_DIM_WAKE_LOCK;
        lock = pm.newWakeLock(level | PowerManager.ON_AFTER_RELEASE, "communitycomms:screen");
        lock.setReferenceCounted(false);
        try {
            lock.acquire();
        } catch (RuntimeException ignored) {
        }
    }

    static String label(int m) {
        return m == BLACK ? "Never sleep, black screen" : m == LIT ? "Never sleep, screen stays lit" : "Normal (Android may sleep the screen)";
    }

    /** The choice, from the Anchored menu or the Black screen menu. */
    static void choose(final Activity a, final Runnable after) {
        final int[] modes = {OFF, BLACK, LIT};
        String[] names = {
                "Normal: Android's screen timeout may turn the screen off and lock the phone",
                "Never sleep, with the black screen: goes black after the Black screen time (double-tap to wake)",
                "Never sleep, screen stays lit (uses the most battery)"};
        final int[] pick = {mode};
        new AlertDialog.Builder(a).setTitle("Keep the screen awake")
                .setSingleChoiceItems(names, mode, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        pick[0] = modes[which];
                    }
                })
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        set(a, pick[0]);
                        keep(a);
                        Toast.makeText(a, label(pick[0]) + (pick[0] != OFF ? ". The power button still turns it off." : ""), Toast.LENGTH_LONG).show();
                        if (after != null) after.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    static void keep(Activity a) {
        if (mode != OFF) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onActivityResumed(Activity a) {
        keep(a);
    }

    @Override
    public void onActivityCreated(Activity a, Bundle b) {
    }

    @Override
    public void onActivityStarted(Activity a) {
    }

    @Override
    public void onActivityPaused(Activity a) {
    }

    @Override
    public void onActivityStopped(Activity a) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity a, Bundle b) {
    }

    @Override
    public void onActivityDestroyed(Activity a) {
    }
}
