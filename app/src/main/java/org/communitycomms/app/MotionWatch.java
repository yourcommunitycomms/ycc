package org.communitycomms.app;

import android.annotation.TargetApi;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * Is the phone lying still? Uses the significant motion sensor where there is one (Android 4.3+: a
 * low-power hardware trigger that also wakes a sleeping phone), and otherwise a 2 second accelerometer
 * sample once a minute while the app is awake. No gyro (it costs more, and old phones lack one).
 * Still = no movement for 3 minutes.
 */
final class MotionWatch {
    interface Listener {
        /** Started moving (or picked up). Any thread. */
        void onMoved();

        /** A wake-up sensor fired that is not movement (proximity: a hand over the top, out of a pocket). */
        void onWake(String why);
    }

    static final long STILL_MS = 180000;
    private static final long SAMPLE_EVERY_MS = 60000, SAMPLE_MS = 2000;
    private static final float SHAKE = 0.6f; // m/s² swing in the accelerometer's size that counts as moving

    private final SensorManager sm;
    private final Listener listener;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile long lastMoveMs = System.currentTimeMillis();
    private boolean running;
    private Object trigger; // TriggerEventListener on 18+
    private SensorEventListener proximity;

    MotionWatch(Context c, Listener l) {
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        listener = l;
    }

    boolean still() {
        return System.currentTimeMillis() - lastMoveMs > STILL_MS;
    }

    void start() {
        if (running || sm == null) return;
        running = true;
        if (Build.VERSION.SDK_INT >= 18) armTrigger();
        if (Build.VERSION.SDK_INT >= 21) watchProximity();
        h.post(sample);
    }

    void stop() {
        running = false;
        h.removeCallbacks(sample);
        h.removeCallbacks(endSample);
        if (sm == null) return;
        sm.unregisterListener(accel);
        if (Build.VERSION.SDK_INT >= 18 && trigger != null) {
            Sensor s = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);
            if (s != null) sm.cancelTriggerSensor((TriggerEventListener) trigger, s);
        }
        if (proximity != null) sm.unregisterListener(proximity);
        proximity = null;
    }

    private void moved() {
        boolean was = still();
        lastMoveMs = System.currentTimeMillis();
        if (was) listener.onMoved();
    }

    @TargetApi(18)
    private void armTrigger() {
        final Sensor s = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);
        if (s == null) return;
        if (trigger == null) {
            trigger = new TriggerEventListener() {
                @Override
                public void onTrigger(TriggerEvent e) {
                    moved();
                    if (running) sm.requestTriggerSensor(this, s); // one shot: arm it again
                }
            };
        }
        sm.requestTriggerSensor((TriggerEventListener) trigger, s);
    }

    @TargetApi(21)
    private void watchProximity() {
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY, true); // only a wake-up proximity sensor
        if (s == null) return;
        proximity = new SensorEventListener() {
            private float last = -1;

            @Override
            public void onSensorChanged(SensorEvent e) {
                float v = e.values[0];
                if (last >= 0 && v != last) listener.onWake("proximity");
                last = v;
            }

            @Override
            public void onAccuracyChanged(Sensor s, int a) {
            }
        };
        sm.registerListener(proximity, s, SensorManager.SENSOR_DELAY_NORMAL);
    }

    // ---- accelerometer sample: once a minute, 2 seconds ----

    private float min = Float.MAX_VALUE, max = 0;

    private final SensorEventListener accel = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            float g = (float) Math.sqrt(x * x + y * y + z * z);
            if (g < min) min = g;
            if (g > max) max = g;
        }

        @Override
        public void onAccuracyChanged(Sensor s, int a) {
        }
    };

    private final Runnable sample = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (a == null) {
                lastMoveMs = System.currentTimeMillis(); // no sensor: never counts as still
                return;
            }
            min = Float.MAX_VALUE;
            max = 0;
            sm.registerListener(accel, a, SensorManager.SENSOR_DELAY_UI);
            h.postDelayed(endSample, SAMPLE_MS);
        }
    };

    private final Runnable endSample = new Runnable() {
        @Override
        public void run() {
            sm.unregisterListener(accel);
            if (max > 0 && max - min > SHAKE) moved();
            if (running) h.postDelayed(sample, SAMPLE_EVERY_MS - SAMPLE_MS);
        }
    };
}
