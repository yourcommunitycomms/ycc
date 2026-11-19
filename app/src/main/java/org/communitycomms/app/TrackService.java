package org.communitycomms.app;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.communitycomms.app.geo.FakeGps;
import org.communitycomms.app.geo.Geo;
import org.communitycomms.app.geo.Track;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Path tracking (Map > Tools > Start tracking my path): records this phone's real GPS positions at the
 * chosen speed into a Track on the phone, counts laps and breaks, optionally counts steps, and can
 * send the path live to chosen people (a commander). Runs as its own foreground service, so it keeps
 * going on or off air and with the screen off. Never records while Fake GPS is on.
 */
public final class TrackService extends Service {
    private static final String TAG = "TrackService";
    static final String ACTION_START = "start", ACTION_PAUSE = "pause", ACTION_RESUME = "resume", ACTION_STOP = "stop";
    private static final int NOTIF_ID = 4711;
    private static final String CHANNEL = "track";

    private static volatile TrackService instance;
    private static volatile Track current;
    /** Callsigns the path goes to live (chosen at the start, or added by answering a request). */
    private static final Set<String> shareTo = Collections.synchronizedSet(new LinkedHashSet<String>());
    /** Set while Fake GPS is on: positions are ignored and the screen says why. */
    static volatile boolean fakeBlocked;

    private final Handler main = new Handler(Looper.getMainLooper());
    private LocationListener listener;
    private Track.LapDetector laps;
    private Track.Point lastStored;
    private int sentUpTo;
    private long lastSent, lastFixSeen;
    private SensorEventListener stepListener;
    private float stepBase = -1;

    /** The track being recorded, or null. */
    public static Track current() {
        return current;
    }

    public static boolean running() {
        return current != null && current.running();
    }

    public static Set<String> shareTo() {
        synchronized (shareTo) {
            return new LinkedHashSet<String>(shareTo);
        }
    }

    /** Starts a new track (the caller has checked Fake GPS and the location permission). */
    public static void start(Context c, int mode, Set<String> to) {
        shareTo.clear();
        if (to != null) shareTo.addAll(to);
        Intent i = new Intent(c, TrackService.class).setAction(ACTION_START).putExtra("mode", mode);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void command(Context c, String action) {
        if (current == null) return;
        c.startService(new Intent(c, TrackService.class).setAction(action));
    }

    /** Send the live path to this callsign as well (answering a request), starting with what is recorded so far. */
    public static void addShare(String name) {
        if (name == null || name.length() == 0) return;
        shareTo.add(name);
        TrackService s = instance;
        Track t = current;
        if (s != null && t != null) TrackShare.sendWhole(s, t, name);
    }

    public static void setShare(Set<String> to) {
        shareTo.clear();
        if (to != null) shareTo.addAll(to);
        TrackService s = instance;
        Track t = current;
        if (s != null && t != null) for (String n : to) TrackShare.sendWhole(s, t, n);
    }

    /** The data wipe: stop at once, without writing anything more. */
    public static void wipeStop(Context c) {
        Track t = current;
        current = null;
        shareTo.clear();
        if (t != null) t.close();
        TrackService s = instance;
        if (s != null) s.stopEverything();
        try {
            c.stopService(new Intent(c, TrackService.class));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        long now = System.currentTimeMillis();
        if (ACTION_START.equals(a)) {
            goForeground();
            startTrack(intent.getIntExtra("mode", Track.MODE_WALK), now);
        } else if (current == null) {
            // Restarted by Android with nothing to record (or a stale notification button).
            goForeground();
            stopSelf();
            return START_NOT_STICKY;
        } else if (ACTION_PAUSE.equals(a)) {
            current.pause(now);
            if (laps != null) laps.breakSegment();
            flushShare(true);
            notifiedAt = 0;
            updateNotification();
        } else if (ACTION_RESUME.equals(a)) {
            current.resume(now);
            lastStored = null;
            if (laps != null) laps.breakSegment();
            flushShare(true);
            notifiedAt = 0;
            updateNotification();
        } else if (ACTION_STOP.equals(a)) {
            Track t = current;
            long lapAt = laps == null ? 0 : laps.finish(); // stopped at the start, arriving the lap direction
            if (lapAt != 0) t.lap(lapAt);
            t.stop(now);
            flushShare(true);
            current = null;
            shareTo.clear();
            stopEverything();
            stopSelf();
        } else {
            goForeground();
        }
        return START_NOT_STICKY;
    }

    private void startTrack(int mode, long now) {
        if (current != null && current.running()) current.stop(now);
        try {
            current = Track.create(getFilesDir(), now, mode);
        } catch (java.io.IOException e) {
            Log.w(TAG, "track file", e);
            current = null;
            stopSelf();
            return;
        }
        laps = new Track.LapDetector(now);
        lastStored = null;
        sentUpTo = 0;
        lastSent = 0;
        stepBase = -1;
        fakeBlocked = FakeGps.enabled(this);
        startGps(mode);
        startSteps();
        updateNotification();
        Track t = current;
        for (String n : shareTo()) TrackShare.sendWhole(this, t, n); // tells them a track has started
    }

    @SuppressLint("MissingPermission")
    private void startGps(int mode) {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location l) {
                onFix(l);
            }

            @Override
            public void onStatusChanged(String p, int s, Bundle e) {
            }

            @Override
            public void onProviderEnabled(String p) {
            }

            @Override
            public void onProviderDisabled(String p) {
            }
        };
        try {
            if (lm.getAllProviders().contains(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, Track.intervalMs(mode), 0, listener, Looper.getMainLooper());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "gps", e); // no permission
        }
    }

    private void onFix(Location l) {
        Track t = current;
        if (t == null || l == null) return;
        long now = System.currentTimeMillis();
        lastFixSeen = now;
        // Fake GPS on: the real GPS still reports, but tracking stays off until Fake GPS is off again.
        fakeBlocked = FakeGps.enabled(this);
        if (fakeBlocked || FakeGps.isFake(l) || t.paused() || !t.running()) {
            updateNotification();
            return;
        }
        float acc = l.hasAccuracy() ? l.getAccuracy() : 25; // some old GPS chips (and emulators) give no accuracy
        if (acc > Track.maxAccuracyM(t.mode)) return;
        double lat = l.getLatitude(), lon = l.getLongitude();
        Track.Point last = lastStored;
        // Standing still: GPS wander is not walked distance. A point every minute keeps the path alive.
        if (last != null && Geo.distanceM(last.lat, last.lon, lat, lon) < Math.max(3, acc / 2) && now - last.t < 60000) return;
        t.addPoint(now, lat, lon, acc);
        lastStored = t.points.get(t.points.size() - 1);
        long lapAt = laps.fix(now, lat, lon, acc); // confirmed once you head off the same way: timed when you passed
        if (lapAt != 0) {
            t.lap(lapAt);
            buzz();
        }
        updateNotification();
        flushShare(false);
    }

    /** Laps: a short buzz, never a sound. */
    private void buzz() {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(new long[]{0, 120, 120, 120}, -1);
        } catch (RuntimeException ignored) {
        }
    }

    /** Live share: new points every 15 s at most (each point in low power), and straight away on pause or stop. */
    private void flushShare(boolean now) {
        Track t = current;
        Set<String> to = shareTo();
        if (t == null || to.isEmpty()) return;
        long ms = System.currentTimeMillis();
        if (!now && ms - lastSent < Math.max(15000, Track.intervalMs(t.mode) - 1000)) return;
        lastSent = ms;
        int n;
        synchronized (t) {
            n = t.points.size();
        }
        TrackShare.sendPoints(this, t, Math.max(0, Math.min(sentUpTo, n) - 1), to); // one point of overlap joins the line
        sentUpTo = n;
    }

    // ---- steps (optional, off by default; Android 10+ asks for Physical activity) ----

    static boolean stepsWanted(Context c) {
        return c.getSharedPreferences("settings", MODE_PRIVATE).getBoolean("trackSteps", false);
    }

    static boolean stepsAllowed(Context c) {
        return Build.VERSION.SDK_INT < 29
                || c.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Step counting needs the step counter chip (Android 4.4+); most older phones have none. */
    static boolean hasStepCounter(Context c) {
        if (Build.VERSION.SDK_INT < 19) return false;
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        return sm != null && sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null;
    }

    @TargetApi(19)
    private void startSteps() {
        if (stepListener != null || !stepsWanted(this) || !stepsAllowed(this) || !hasStepCounter(this)) return;
        SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        stepListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                Track t = current;
                if (t == null || e.values.length == 0) return;
                float v = e.values[0];
                if (stepBase < 0) stepBase = v;
                t.steps(System.currentTimeMillis(), (int) (v - stepBase));
            }

            @Override
            public void onAccuracyChanged(Sensor s, int a) {
            }
        };
        // Batched by the chip (up to 10 s late): cheap on the battery.
        sm.registerListener(stepListener, sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER), SensorManager.SENSOR_DELAY_NORMAL);
        if (current != null && current.steps < 0) current.steps(System.currentTimeMillis(), 0);
    }

    /** The Physical activity permission was just granted (after the track started). */
    static void stepsPermissionGranted() {
        final TrackService s = instance;
        if (s != null) s.main.post(new Runnable() {
            @Override
            public void run() {
                s.startSteps();
            }
        });
    }

    private void stopEverything() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm != null && listener != null) {
            try {
                lm.removeUpdates(listener);
            } catch (RuntimeException ignored) {
            }
        }
        listener = null;
        if (stepListener != null) {
            SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sm != null) sm.unregisterListener(stepListener);
            stepListener = null;
        }
        fakeBlocked = false;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    @Override
    public void onDestroy() {
        Track t = current;
        if (t != null) { // killed: what is recorded is already on disk; the track reads as stopped
            t.stop(System.currentTimeMillis());
            current = null;
        }
        stopEverything();
        instance = null;
        super.onDestroy();
    }

    // ---- notification ----

    static String summary(Track t) {
        if (t == null) return "";
        Track.Stats s = t.stats(System.currentTimeMillis());
        StringBuilder b = new StringBuilder();
        b.append(Track.hms(s.moving)).append(" · ").append(Geo.distance(s.distanceM));
        if (s.lapCount > 0) b.append(" · lap ").append(s.lapCount + 1).append(' ').append(Track.hms(s.currentLap));
        if (s.steps >= 0) b.append(" · ").append(s.steps).append(" steps");
        return b.toString();
    }

    private Notification.Builder builder() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Path tracking", NotificationManager.IMPORTANCE_LOW));
            return new Notification.Builder(this, CHANNEL);
        }
        return new Notification.Builder(this);
    }

    private Notification build() {
        Track t = current;
        int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 71, new Intent(this, MapActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), fl);
        boolean paused = t != null && t.paused();
        String title = t == null ? "Path tracking"
                : fakeBlocked ? "Path tracking held: Fake GPS is on"
                : paused ? "Path tracking paused" : "Tracking your path (" + Track.MODE_WORDS[t.mode] + ")";
        Notification.Builder b = builder().setContentTitle(title).setContentText(summary(t))
                .setSmallIcon(R.drawable.ic_stat_radio).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true);
        if (t != null) {
            b.addAction(0, paused ? "Resume" : "Pause", PendingIntent.getService(this, paused ? 73 : 72,
                    new Intent(this, TrackService.class).setAction(paused ? ACTION_RESUME : ACTION_PAUSE), fl));
            b.addAction(0, "Stop", PendingIntent.getService(this, 74, new Intent(this, TrackService.class).setAction(ACTION_STOP), fl));
        }
        if (Build.VERSION.SDK_INT >= 21) b.setColor(0xff1f3d2b);
        return Build.VERSION.SDK_INT >= 16 ? b.build() : b.getNotification();
    }

    private void goForeground() {
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } catch (RuntimeException e) {
                Log.w(TAG, "foreground", e);
            }
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private long notifiedAt;

    private void updateNotification() {
        long now = System.currentTimeMillis();
        if (now - notifiedAt < 1500) return;
        notifiedAt = now;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && current != null) nm.notify(NOTIF_ID, build());
    }
}
