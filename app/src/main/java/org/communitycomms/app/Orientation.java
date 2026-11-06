package org.communitycomms.app;

import android.app.Activity;
import android.app.Application;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

/**
 * Settings > Home screen and menus > Screen orientation: Auto (the phone decides), Portrait or
 * Landscape, for every screen (either way up), and Invert screen (upside down; on Auto, the way the
 * screen is held when it opens). Screens that fix their own orientation keep it.
 */
final class Orientation implements Application.ActivityLifecycleCallbacks {
    static void apply(Activity a) {
        try {
            ActivityInfo info = a.getPackageManager().getActivityInfo(a.getComponentName(), 0);
            int own = info.screenOrientation;
            if (own != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    && own != ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    && own != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    && own != ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                    && own != ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) return; // its own fixed orientation
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        Prefs p = new Prefs(a);
        int o = p.orientation();
        int want = o == 1 ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT : o == 2 ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        if (p.invertScreen()) {
            boolean land = o == 2 || (o == 0 && a.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
            want = land ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
        }
        if (a.getRequestedOrientation() != want) a.setRequestedOrientation(want);
    }

    @Override
    public void onActivityCreated(Activity a, Bundle b) {
        apply(a);
    }

    @Override
    public void onActivityResumed(Activity a) {
        apply(a);
        // Casting to a TV: the screen stays on (a sleeping screen would end the cast).
        if (CastWatch.casting(a)) a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
