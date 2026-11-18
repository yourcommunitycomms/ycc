package org.communitycomms.app;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * A small notice at the top of whatever screen is open: "X has started their video feed", for 30 seconds.
 * Tap it to open Video feed on that person. No sound and no buzz; it does not block the screen, is not shown
 * over a black screen or on the Video feed screen itself, and works on Android 4.1 (a plain view on the window).
 */
final class VideoNotice implements Application.ActivityLifecycleCallbacks {
    static final long SHOW_MS = 30000;
    /** A feed counts as started again after this long without pictures. */
    static final long GAP_MS = 60000;
    private static final int VIEW_ID = 0x0b1d30;
    private static WeakReference<Activity> resumed = new WeakReference<Activity>(null);
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final Map<String, Long> shownAt = new HashMap<String, Long>();

    /** From VideoHub when the first picture of a feed arrives (or the first after a gap). Any thread. */
    static void started(final String name) {
        if (name == null || name.length() == 0) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                Long last = shownAt.get(name);
                long now = System.currentTimeMillis();
                if (last != null && now - last < SHOW_MS) return; // already showing for this person
                Activity a = resumed.get();
                if (a == null || a.isFinishing() || a instanceof TacticalVideoActivity || LightDiscipline.blackedOut(a)) return;
                shownAt.put(name, now);
                show(a, name);
            }
        });
    }

    private static void show(final Activity a, final String name) {
        // Inside the content area (below the title bar), so it never covers the bar or its buttons.
        View content = a.findViewById(android.R.id.content);
        final ViewGroup decor = content instanceof ViewGroup ? (ViewGroup) content : (ViewGroup) a.getWindow().getDecorView();
        View old = decor.findViewById(VIEW_ID);
        if (old != null) decor.removeView(old);
        float d = a.getResources().getDisplayMetrics().density;
        final TextView t = new TextView(a);
        t.setId(VIEW_ID);
        t.setText(name + " has started their video feed\nTap to watch");
        t.setTextColor(0xff0f1f16);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        int p = (int) (12 * d);
        t.setPadding(p, p / 2, p, p / 2);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xf0f0c75e);
        bg.setCornerRadius(14 * d);
        t.setBackgroundDrawable(bg);
        t.setContentDescription(name + " has started their video feed. Tap to watch.");
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                decor.removeView(t);
                a.startActivity(new Intent(a, TacticalVideoActivity.class).putExtra(TacticalVideoActivity.EXTRA_FOCUS, name));
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = (int) (8 * d);
        decor.addView(t, lp);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (t.getParent() != null) decor.removeView(t);
            }
        }, SHOW_MS);
    }

    @Override
    public void onActivityResumed(Activity a) {
        resumed = new WeakReference<Activity>(a);
    }

    @Override
    public void onActivityPaused(Activity a) {
        if (resumed.get() == a) resumed = new WeakReference<Activity>(null);
        View v = a.getWindow().getDecorView().findViewById(VIEW_ID);
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    @Override
    public void onActivityCreated(Activity a, Bundle b) {
    }

    @Override
    public void onActivityStarted(Activity a) {
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
