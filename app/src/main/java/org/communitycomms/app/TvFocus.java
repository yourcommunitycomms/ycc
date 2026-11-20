package org.communitycomms.app;

import android.app.Activity;
import android.app.Application;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.AbsListView;

/**
 * A gold frame round whatever has the focus when moving with a TV remote, D-pad or keyboard (never while touching),
 * so rows and buttons with their own background colour still show which one is picked. Lists draw the frame on
 * top of their selected row. Every screen gets it (the lifecycle callbacks); dialogs with coloured rows call watch().
 */
final class TvFocus implements Application.ActivityLifecycleCallbacks {
    private static final int TAG = 0x7f0f0c75;

    /** Frames the focused view in this window from now on. */
    static void watch(Window w) {
        if (w == null || Build.VERSION.SDK_INT < 18) return; // ViewOverlay
        final View decor = w.getDecorView();
        if (decor.getTag(TAG) != null) return;
        decor.setTag(TAG, Boolean.TRUE);
        decor.getViewTreeObserver().addOnGlobalFocusChangeListener(new ViewTreeObserver.OnGlobalFocusChangeListener() {
            @Override
            public void onGlobalFocusChanged(View old, View now) {
                if (old != null) unframe(old);
                if (now != null && !now.isInTouchMode()) frame(now);
            }
        });
        if (TvActivity.isTv(decor.getContext())) {
            // A remote can only reach what is focusable: rows that only have a click listener become focusable too.
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    focusClickables(decor);
                }
            });
        }
        decor.getViewTreeObserver().addOnTouchModeChangeListener(new ViewTreeObserver.OnTouchModeChangeListener() {
            @Override
            public void onTouchModeChanged(boolean touch) {
                View f = decor.findFocus();
                if (f == null) return;
                if (touch) unframe(f);
                else frame(f);
            }
        });
    }

    private static void focusClickables(View v) {
        if (v.isClickable() && !v.isFocusable() && v.isEnabled()) v.setFocusable(true);
        if (v instanceof android.view.ViewGroup && !(v instanceof AbsListView)) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) focusClickables(g.getChildAt(i));
        }
    }

    static GradientDrawable goldFrame(View v) {
        float d = v.getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x22f0c75e);
        g.setStroke((int) Math.max(2, 3 * d), 0xfff0c75e);
        return g;
    }

    private static void frame(final View v) {
        if (v instanceof AbsListView) { // the selected row is not focused itself: draw the list's selector on top
            AbsListView l = (AbsListView) v;
            if (!Boolean.TRUE.equals(l.getTag(TAG))) {
                l.setTag(TAG, Boolean.TRUE);
                l.setSelector(goldFrame(l));
                l.setDrawSelectorOnTop(true);
            }
            return;
        }
        if (v.getWidth() == 0) return;
        GradientDrawable g = goldFrame(v);
        g.setBounds(0, 0, v.getWidth(), v.getHeight());
        unframe(v);
        v.getOverlay().add(g);
        v.setTag(TAG, g);
    }

    private static void unframe(View v) {
        if (v instanceof AbsListView || Build.VERSION.SDK_INT < 18) return;
        Object g = v.getTag(TAG);
        if (g instanceof android.graphics.drawable.Drawable) {
            v.getOverlay().remove((android.graphics.drawable.Drawable) g);
            v.setTag(TAG, null);
        }
    }

    @Override
    public void onActivityCreated(Activity a, Bundle b) {
        watch(a.getWindow());
    }

    @Override
    public void onActivityStarted(Activity a) {
    }

    @Override
    public void onActivityResumed(Activity a) {
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
