package org.communitycomms.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.communitycomms.app.geo.TilePrefetch;

/**
 * Keeps a tile prefetch going with the app in the background or the screen off: a notification shows
 * progress with Pause / Resume and Stop, and tapping it opens the map.
 */
public final class TilePrefetchService extends Service {
    private static final String CHANNEL = "prefetch";
    private static final int NOTE = 7741;
    public static final String ACTION_PAUSE = "pause", ACTION_STOP = "stop";
    private static final String EXTRA_LAT = "lat", EXTRA_LON = "lon", EXTRA_KM = "km", EXTRA_SYNC = "sync";

    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    /** Starts a prefetch around a point (or carries on one already running). */
    public static void start(Context c, double lat, double lon, int km) {
        Intent i = new Intent(c, TilePrefetchService.class).putExtra(EXTRA_LAT, lat).putExtra(EXTRA_LON, lon).putExtra(EXTRA_KM, km);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    /** Starts copying map data between the two map servers (TilePrefetch.PRIMARY_TO_SECONDARY etc). */
    public static void startSync(Context c, int mode) {
        Intent i = new Intent(c, TilePrefetchService.class).putExtra(EXTRA_SYNC, mode);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ycc:prefetch");
        wake.setReferenceCounted(false);
        wake.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "ycc:prefetch");
            wifi.setReferenceCounted(false);
            wifi.acquire();
        }
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        goForeground(note("Starting…", false));
        TilePrefetch.Job j = TilePrefetch.job;
        String action = i == null ? null : i.getAction();
        if (ACTION_PAUSE.equals(action)) {
            if (j != null) j.paused = !j.paused;
        } else if (ACTION_STOP.equals(action)) {
            if (j != null) j.stop();
        } else if (i != null && i.hasExtra(EXTRA_SYNC)) {
            TilePrefetch.beginSync(this, i.getIntExtra(EXTRA_SYNC, 0));
        } else if (i != null && i.hasExtra(EXTRA_KM)) {
            TilePrefetch.begin(this, i.getDoubleExtra(EXTRA_LAT, 0), i.getDoubleExtra(EXTRA_LON, 0), i.getIntExtra(EXTRA_KM, 5));
        } else if (j == null || j.finished) {
            // Restarted by the system after the app was killed: carry on the saved job, if any.
            if (!TilePrefetch.resume(this)) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        main.removeCallbacks(tick);
        main.post(tick);
        return START_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            TilePrefetch.Job j = TilePrefetch.job;
            if (j == null) {
                stopSelf();
                return;
            }
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (j.finished) {
                stopForeground(true);
                if (nm != null) nm.notify(NOTE, note((j.stopped() ? "Stopped: " : j.error != null ? "Stopped early: " : "Done: ") + j.line(), true));
                stopSelf();
                return;
            }
            if (nm != null) nm.notify(NOTE, note(j.line(), false));
            main.postDelayed(this, 2000);
        }
    };

    private void goForeground(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTE, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTE, n);
        }
    }

    private PendingIntent action(String a, int code) {
        int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getService(this, code, new Intent(this, TilePrefetchService.class).setAction(a), fl);
    }

    @SuppressWarnings("deprecation")
    private Notification note(String text, boolean finished) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Map tile prefetch", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 9, new Intent(this, MainActivity.class), fl);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        TilePrefetch.Job cur = TilePrefetch.job;
        b.setContentTitle(cur != null ? cur.title : "Map tiles").setContentText(text).setSmallIcon(R.drawable.ic_stat_radio).setContentIntent(open)
                .setOngoing(!finished).setOnlyAlertOnce(true);
        TilePrefetch.Job j = TilePrefetch.job;
        if (!finished && j != null) {
            b.setProgress(Math.max(1, j.total), j.done, j.total == 0);
            b.addAction(R.drawable.ic_stat_radio, j.paused ? "Resume" : "Pause", action(ACTION_PAUSE, 1));
            b.addAction(R.drawable.ic_stat_radio, "Stop", action(ACTION_STOP, 2));
        }
        return b.build();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(tick);
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        super.onDestroy();
    }
}
