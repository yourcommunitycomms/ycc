package org.communitycomms.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Cast screen to TV, auto off: while the phone's screen is being cast and the app has not been used
 * for an hour (Settings in the Cast screen box, on by default), casting is stopped. Android lets an app
 * end the cast by choosing the phone's own screen again (MediaRouter); where a phone refuses, a
 * notification asks the user to stop it.
 */
public final class CastWatch {
    private CastWatch() {
    }

    static final long IDLE_MS = 60 * 60000L;
    private static volatile long lastTouch = System.currentTimeMillis();
    private static volatile boolean asked;

    /** Any use of the app (a touch, a screen opened). */
    public static void touched() {
        lastTouch = System.currentTimeMillis();
        asked = false;
    }

    /** True while the screen is being cast to a TV (a presentation display is connected). */
    static boolean casting(Context c) {
        if (Build.VERSION.SDK_INT < 17) return false;
        android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        return dm != null && dm.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION).length > 0;
    }

    /** Called about once a minute (RadioService). */
    static void check(final Context c) {
        if (!new Prefs(c).menuOn("cast")) return;
        if (!c.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("castAutoOff", false)) return;
        if (!casting(c)) {
            asked = false;
            return;
        }
        if (System.currentTimeMillis() - lastTouch < IDLE_MS || asked) return;
        asked = true;
        stop(c);
        new android.os.Handler(c.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (casting(c)) notifyStillOn(c);
            }
        }, 8000);
    }

    @SuppressWarnings("deprecation")
    private static void stop(Context c) {
        try {
            android.media.MediaRouter mr = (android.media.MediaRouter) c.getSystemService(Context.MEDIA_ROUTER_SERVICE);
            if (mr == null) return;
            android.media.MediaRouter.RouteInfo phone = Build.VERSION.SDK_INT >= 18 ? mr.getDefaultRoute() : mr.getRouteAt(0);
            mr.selectRoute(android.media.MediaRouter.ROUTE_TYPE_LIVE_VIDEO, phone);
        } catch (RuntimeException ignored) {
            // Some phones only let the user end a cast: the notification asks them.
        }
    }

    @SuppressWarnings("deprecation")
    private static void notifyStillOn(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent open = new Intent("android.settings.CAST_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (open.resolveActivity(c.getPackageManager()) == null) open = new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("cast", "Casting", NotificationManager.IMPORTANCE_DEFAULT));
            b = new Notification.Builder(c, "cast");
        } else {
            b = new Notification.Builder(c);
        }
        nm.notify(14, b.setContentTitle("Still casting to the TV")
                .setContentText("Not used for an hour: tap to stop casting (it uses the WiFi and battery)")
                .setSmallIcon(R.drawable.ic_stat_radio)
                .setContentIntent(PendingIntent.getActivity(c, 14, open, fl))
                .setAutoCancel(true).build());
    }
}
