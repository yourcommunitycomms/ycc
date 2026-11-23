package org.communitycomms.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

/**
 * Floating PTT button: a small round button over other apps while YCC is on air (like TAK Vx's channel circle).
 * Setting (Settings > Voice and PTT, and the Permissions page): Off (default), When not in YCC, Always.
 * Needs Android's "Display over other apps" (Android 6 and later ask for it; older phones grant it at install).
 *
 * Hold to talk (a short tap opens YCC); with Latch on, a tap starts or stops talking and a double tap opens YCC.
 * Drag to move it (the place is remembered), or onto the X at the bottom to hide it until the next time on air.
 * Red while transmitting, green with the talker's name while someone talks, dim when quiet. Hidden while radio
 * silent and on TVs. PTT goes through RadioService.hardwarePtt, so latch, radio silent and burst all apply.
 */
public final class FloatingPtt implements Application.ActivityLifecycleCallbacks {
    public static final int OFF = 0, OUTSIDE = 1, ALWAYS = 2;
    public static final String[] MODES = {"Off", "When not in YCC", "Always"};

    private static FloatingPtt instance;

    public static void install(Application app) {
        instance = new FloatingPtt(app);
        app.registerActivityLifecycleCallbacks(instance);
    }

    public static int mode(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("floatPtt", OFF);
    }

    public static void setMode(Context c, int m) {
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("floatPtt", m).commit();
        if (instance != null) instance.tick();
    }

    /** "Display over other apps" allowed (always true before Android 6). */
    public static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    /** Opens Android's "Display over other apps" page for this app. */
    public static void askDraw(Activity a) {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + a.getPackageName())));
        } catch (RuntimeException e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + a.getPackageName())));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private final Context app;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final WindowManager wm;
    private final SharedPreferences sp;
    private final float d;
    private int started;
    private Bubble bubble;
    private View cross;
    private WindowManager.LayoutParams lp, crossLp;
    private boolean hiddenThisTime, wasOnAir;

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            tick();
            h.postDelayed(this, bubble != null ? 300 : 1500);
        }
    };

    private FloatingPtt(Application a) {
        app = a;
        wm = (WindowManager) a.getSystemService(Context.WINDOW_SERVICE);
        sp = a.getSharedPreferences("settings", Context.MODE_PRIVATE);
        d = a.getResources().getDisplayMetrics().density;
        h.postDelayed(loop, 1500);
    }

    // ---- when to show ----

    private void tick() {
        RadioService rs = RadioService.instance();
        boolean onAir = rs != null && (rs.engine() != null || rs.compat() != null);
        if (!onAir && wasOnAir) hiddenThisTime = false; // dragged to the X: back the next time on air
        wasOnAir = onAir;
        int m = mode(app);
        boolean show = onAir && m != OFF && !hiddenThisTime && canDraw(app) && !TvActivity.isTv(app) && !RadioEngine.radioSilent
                && (m == ALWAYS || started == 0 || transmitting(rs));
        if (show) {
            if (bubble == null) add();
            if (bubble != null) bubble.update(rs);
        } else if (bubble != null && !bubble.down) {
            remove();
        }
    }

    private static boolean transmitting(RadioService rs) {
        if (rs == null) return false;
        CompatVoice c = rs.compat();
        if (c != null) return c.isTransmitting();
        RadioEngine e = rs.engine();
        return e != null && e.isTransmitting();
    }

    @SuppressWarnings("deprecation")
    private static int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void add() {
        try {
            bubble = new Bubble(app);
            int size = (int) (64 * d);
            lp = new WindowManager.LayoutParams(size, size, overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            android.util.DisplayMetrics dm = app.getResources().getDisplayMetrics();
            lp.x = sp.getInt("floatX", dm.widthPixels - size - (int) (12 * d));
            lp.y = sp.getInt("floatY", dm.heightPixels / 3);
            clamp();
            wm.addView(bubble, lp);
        } catch (RuntimeException e) {
            bubble = null; // permission taken away, or the window refused
        }
    }

    private void remove() {
        hideCross();
        try {
            if (bubble != null) wm.removeView(bubble);
        } catch (RuntimeException ignored) {
        }
        bubble = null;
    }

    private void clamp() {
        android.util.DisplayMetrics dm = app.getResources().getDisplayMetrics();
        lp.x = Math.max(0, Math.min(lp.x, dm.widthPixels - lp.width));
        lp.y = Math.max(0, Math.min(lp.y, dm.heightPixels - lp.height));
    }

    // ---- the X to drop it on ----

    private void showCross() {
        if (cross != null) return;
        try {
            cross = new View(app) {
                final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

                @Override
                protected void onDraw(Canvas c) {
                    float r = getWidth() / 2f;
                    p.setColor(0xcc000000);
                    c.drawCircle(r, r, r, p);
                    p.setColor(Color.WHITE);
                    p.setStrokeWidth(5 * d);
                    float k = r * 0.4f;
                    c.drawLine(r - k, r - k, r + k, r + k, p);
                    c.drawLine(r + k, r - k, r - k, r + k, p);
                }
            };
            int s = (int) (56 * d);
            crossLp = new WindowManager.LayoutParams(s, s, overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
            crossLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            crossLp.y = (int) (48 * d);
            wm.addView(cross, crossLp);
        } catch (RuntimeException e) {
            cross = null;
        }
    }

    private void hideCross() {
        try {
            if (cross != null) wm.removeView(cross);
        } catch (RuntimeException ignored) {
        }
        cross = null;
    }

    private boolean overCross() {
        android.util.DisplayMetrics dm = app.getResources().getDisplayMetrics();
        float cx = dm.widthPixels / 2f, cy = dm.heightPixels - 48 * d - 28 * d;
        float bx = lp.x + lp.width / 2f, by = lp.y + lp.height / 2f;
        return Math.hypot(bx - cx, by - cy) < 80 * d;
    }

    private void openApp() {
        try {
            app.startActivity(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (RuntimeException ignored) {
        }
    }

    // ---- the button ----

    @SuppressLint("ViewConstructor")
    private final class Bubble extends View {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        String label = "", talker;
        boolean tx, down, dragging, talkSent;
        float downX, downY;
        int startX, startY;
        long downAt, lastTapAt;
        final int slop;
        final Runnable startTalk = new Runnable() {
            @Override
            public void run() {
                if (down && !dragging) {
                    talkSent = true;
                    RadioService rs = RadioService.instance();
                    if (rs != null) rs.hardwarePtt(true);
                }
            }
        };

        Bubble(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(3 * d);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
        }

        void update(RadioService rs) {
            String l = channelLabel(rs);
            boolean t = transmitting(rs);
            String who = talker(rs);
            if (!l.equals(label) || t != tx || (who == null ? talker != null : !who.equals(talker))) {
                label = l;
                tx = t;
                talker = who;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = getWidth() / 2f;
            int col = tx ? 0xffc62828 : talker != null ? 0xff2e7d32 : 0xb0263238;
            fill.setColor(col);
            c.drawCircle(r, r, r - 2 * d, fill);
            ring.setColor(tx ? 0xffff8a80 : talker != null ? 0xffa5d6a7 : 0xc0f0c75e);
            c.drawCircle(r, r, r - 3 * d, ring);
            text.setTextSize((label.length() > 2 ? 16 : 22) * d);
            c.drawText(label, r, r + (talker != null ? 0 : 8 * d), text);
            if (talker != null) {
                text.setTextSize(9 * d);
                String s = talker.length() > 10 ? talker.substring(0, 10) : talker;
                c.drawText(s, r, r + 16 * d, text);
            }
        }

        @Override
        @SuppressLint("ClickableViewAccessibility")
        public boolean onTouchEvent(MotionEvent e) {
            Prefs p = new Prefs(app);
            boolean latch = p.latch();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down = true;
                    dragging = false;
                    talkSent = false;
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    downAt = System.currentTimeMillis();
                    if (!latch) h.postDelayed(startTalk, 200); // a short tap opens YCC instead
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragging && !talkSent && Math.hypot(dx, dy) > slop * 2) {
                        dragging = true;
                        h.removeCallbacks(startTalk);
                        showCross();
                    }
                    if (dragging) {
                        lp.x = startX + (int) dx;
                        lp.y = startY + (int) dy;
                        clamp();
                        try {
                            wm.updateViewLayout(this, lp);
                        } catch (RuntimeException ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    down = false;
                    h.removeCallbacks(startTalk);
                    RadioService rs = RadioService.instance();
                    if (dragging) {
                        boolean drop = overCross();
                        hideCross();
                        sp.edit().putInt("floatX", lp.x).putInt("floatY", lp.y).apply();
                        if (drop) {
                            hiddenThisTime = true;
                            remove();
                            android.widget.Toast.makeText(app, "Floating PTT hidden until next time on air", android.widget.Toast.LENGTH_SHORT).show();
                        }
                        return true;
                    }
                    if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        if (talkSent && rs != null && !latch) rs.hardwarePtt(false);
                        return true;
                    }
                    long now = System.currentTimeMillis();
                    if (latch) {
                        if (now - lastTapAt < 350) { // double tap: open YCC (and undo the first tap's toggle)
                            lastTapAt = 0;
                            if (rs != null) rs.hardwarePtt(true);
                            openApp();
                        } else {
                            lastTapAt = now;
                            if (rs != null) rs.hardwarePtt(true); // latch: each tap starts or stops talking
                        }
                    } else if (talkSent) {
                        if (rs != null) rs.hardwarePtt(false);
                    } else {
                        openApp(); // short tap
                    }
                    return true;
            }
            return false;
        }
    }

    private String channelLabel(RadioService rs) {
        Prefs p = new Prefs(app);
        int slot = p.activeSlot();
        if (rs != null && rs.compat() != null) {
            String l = CompatVoice.channelLabel(rs.compat().describe());
            if (l.startsWith("channel ")) {
                int sp2 = l.indexOf(' ', 8);
                return "OM" + (sp2 > 0 ? l.substring(8, sp2) : "");
            }
            return "OM";
        }
        if (slot == Prefs.SLOT_PUBLIC) return "P";
        if (slot == Prefs.SLOT_B) return "B";
        return "A";
    }

    private static String talker(RadioService rs) {
        if (rs == null) return null;
        long now = System.currentTimeMillis();
        CompatVoice c = rs.compat();
        if (c != null) {
            for (CompatVoice.Talker t : c.talkers()) {
                if (t.talking(now)) {
                    String l = t.label();
                    return l.startsWith("OpenMANET ") ? l.substring(10) : l;
                }
            }
            return null;
        }
        RadioEngine e = rs.engine();
        if (e == null) return null;
        for (RadioEngine.Station s : e.stations()) if (s.isTalking(now)) return s.name;
        return null;
    }

    // ---- is YCC on screen? ----

    @Override
    public void onActivityStarted(Activity a) {
        started++;
        h.post(new Runnable() {
            @Override
            public void run() {
                tick();
            }
        });
    }

    @Override
    public void onActivityStopped(Activity a) {
        started = Math.max(0, started - 1);
        h.post(new Runnable() {
            @Override
            public void run() {
                tick();
            }
        });
    }

    @Override
    public void onActivityCreated(Activity a, Bundle b) {
    }

    @Override
    public void onActivityResumed(Activity a) {
    }

    @Override
    public void onActivityPaused(Activity a) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity a, Bundle b) {
    }

    @Override
    public void onActivityDestroyed(Activity a) {
    }
}
