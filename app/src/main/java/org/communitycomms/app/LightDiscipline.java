package org.communitycomms.app;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Light discipline for night use.
 * Red light mode: every screen is drawn through a filter that keeps only red (no white or blue
 * light) at reduced intensity, to protect night vision.
 * Blackout: a black cover with the backlight at minimum; the app stays open, focused and
 * unlocked (hardware PTT keeps working) until the screen is double-tapped or the app is left
 * (power button, lock screen, another screen).
 */
public final class LightDiscipline implements Application.ActivityLifecycleCallbacks {
    private static final String PREFS = "settings";

    public static boolean redMode(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("redMode", false);
    }

    public static void setRedMode(Activity a, boolean on) {
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("redMode", on).commit();
        apply(a);
    }

    /** Applies or removes the red filter on an activity's window. */
    public static void apply(Activity a) {
        View root = a.getWindow().getDecorView();
        if (redMode(a) && !(a instanceof QrShowActivity)) { // QR codes must stay black on white
            ColorMatrix m = new ColorMatrix(new float[]{
                    0.30f * 0.8f, 0.59f * 0.8f, 0.11f * 0.8f, 0, 0,
                    0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0,
                    0, 0, 0, 1, 0});
            Paint p = new Paint();
            p.setColorFilter(new ColorMatrixColorFilter(m));
            root.setLayerType(View.LAYER_TYPE_HARDWARE, p);
            // The status bar is drawn by the system (white icons), so hide it; blacken the nav bar.
            a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= 21) setBarColours(a, 0xff000000);
        } else {
            root.setLayerType(View.LAYER_TYPE_NONE, null);
            a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    @android.annotation.TargetApi(21)
    private static void setBarColours(Activity a, int c) {
        a.getWindow().setNavigationBarColor(c);
        a.getWindow().setStatusBarColor(c);
    }

    // ---- blackout ----

    /** Settings > Home screen: lowest backlight instead of none, for older devices where none stops the double tap. */
    public static boolean legacyBlackout(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("blackoutLegacy", false);
    }

    private static final int BLACKOUT_ID = 0x0b1ac0;
    private static final long DOUBLE_TAP_MS = 700;

    @SuppressWarnings("deprecation")
    public static void blackout(final Activity a) {
        final ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        if (decor.findViewById(BLACKOUT_ID) != null) return;
        final FrameLayout cover = new FrameLayout(a);
        cover.setId(BLACKOUT_ID);
        cover.setBackgroundColor(0xff000000);
        cover.setClickable(true);
        TextView hint = new TextView(a);
        hint.setText("Double-tap to wake");
        hint.setTextColor(0xff1a0000);
        hint.setTextSize(12);
        hint.setPadding(24, 24, 24, 24);
        if (legacyBlackout(a)) cover.addView(hint); // even dim red text is light on an OLED screen
        final WindowManager.LayoutParams lp = a.getWindow().getAttributes();
        final float oldBrightness = lp.screenBrightness;
        boolean legacy = legacyBlackout(a);
        // Exactly 0 is no light at all, to hide from night vision. It can stop touch on older Android, so Settings has a way round.
        lp.screenBrightness = legacy ? 0.01f : 0.0f;
        a.getWindow().setAttributes(lp);
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final int oldUi = decor.getSystemUiVisibility();
        // No immersive/hide-navigation flags: they make Android flash a bright "Viewing full
        // screen" banner the first time. Low profile + fullscreen hides the status bar quietly.
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE | View.SYSTEM_UI_FLAG_FULLSCREEN);
        // Power button or unlock: always comes back to the normal screen, whatever the double tap does.
        final BroadcastReceiver screen = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                endBlackout(a);
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        a.registerReceiver(screen, f);
        cover.setTag(new Runnable() {
            @Override
            public void run() {
                a.unregisterReceiver(screen);
                decor.removeView(cover);
                lp.screenBrightness = oldBrightness;
                a.getWindow().setAttributes(lp);
                decor.setSystemUiVisibility(oldUi);
            }
        });
        // Our own double tap, from the touch times: GestureDetector's timer misses taps when a slow
        // tablet is busy drawing the map behind the cover.
        final float slop = 100 * a.getResources().getDisplayMetrics().density;
        cover.setOnTouchListener(new View.OnTouchListener() {
            private long lastUp;
            private float lastX, lastY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    boolean second = e.getEventTime() - lastUp < DOUBLE_TAP_MS
                            && Math.abs(e.getX() - lastX) < slop && Math.abs(e.getY() - lastY) < slop;
                    lastUp = e.getEventTime();
                    lastX = e.getX();
                    lastY = e.getY();
                    if (second) endBlackout(a);
                }
                return true;
            }
        });
        decor.addView(cover, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Takes the black cover off, if there is one. */
    public static void endBlackout(Activity a) {
        View cover = a.getWindow().getDecorView().findViewById(BLACKOUT_ID);
        if (cover != null) ((Runnable) cover.getTag()).run();
    }

    // ---- lifecycle: apply red mode to every screen ----

    @Override
    public void onActivityCreated(Activity a, Bundle b) {
    }

    @Override
    public void onActivityStarted(Activity a) {
    }

    @Override
    public void onActivityResumed(Activity a) {
        apply(a);
    }

    @Override
    public void onActivityPaused(Activity a) {
        endBlackout(a); // power button, lock screen or another screen: never come back to a black screen
    }

    @Override
    public void onActivityStopped(Activity a) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity a, Bundle b) {
    }

    @Override
    public void onActivityDestroyed(Activity a) {
        endBlackout(a);
    }
}
