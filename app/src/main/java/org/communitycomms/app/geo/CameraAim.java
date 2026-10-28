package org.communitycomms.app.geo;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Which way the back camera points (degrees from magnetic north), sampled while a camera app is
 * open, so a photo's direction can be looked up by the time the picture file was written.
 * The camera looks out of the back of the phone, so this works however the phone is held.
 */
public final class CameraAim implements SensorEventListener {
    private static final int N = 200;
    private final SensorManager sm;
    private final long[] at = new long[N];
    private final float[] deg = new float[N];
    private int count, next;
    private final float[] grav = new float[3], mag = new float[3], rot = new float[9];
    private boolean hasGrav, hasMag;

    public CameraAim(Context c) {
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
    }

    public void start() {
        stop();
        count = 0;
        next = 0;
        if (sm == null) return;
        Sensor rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rv != null) {
            sm.registerListener(this, rv, SensorManager.SENSOR_DELAY_UI);
        } else {
            Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), m = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (a != null && m != null) {
                sm.registerListener(this, a, SensorManager.SENSOR_DELAY_UI);
                sm.registerListener(this, m, SensorManager.SENSOR_DELAY_UI);
            }
        }
    }

    public void stop() {
        if (sm != null) sm.unregisterListener(this);
    }

    /** The camera's direction nearest to timeMs (within 4 s), or NaN. */
    public synchronized float at(long timeMs) {
        float best = Float.NaN;
        long gap = 4000;
        for (int i = 0; i < count; i++) {
            long g = Math.abs(at[i] - timeMs);
            if (g < gap) {
                gap = g;
                best = deg[i];
            }
        }
        return best;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        int type = e.sensor.getType();
        if (type == Sensor.TYPE_ROTATION_VECTOR) {
            float[] v = e.values.length > 4 ? new float[]{e.values[0], e.values[1], e.values[2], e.values[3]} : e.values;
            SensorManager.getRotationMatrixFromVector(rot, v);
        } else {
            if (type == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(e.values, 0, grav, 0, 3);
                hasGrav = true;
            } else if (type == Sensor.TYPE_MAGNETIC_FIELD) {
                System.arraycopy(e.values, 0, mag, 0, 3);
                hasMag = true;
            }
            if (!hasGrav || !hasMag || !SensorManager.getRotationMatrix(rot, null, grav, mag)) return;
        }
        // The back camera looks along the phone's -Z axis: its east and north parts give the bearing.
        float east = -rot[2], north = -rot[5];
        if (east * east + north * north < 0.04f) return; // pointing at the ground or sky: no bearing
        float b = (float) ((Math.toDegrees(Math.atan2(east, north)) + 360) % 360);
        synchronized (this) {
            at[next] = System.currentTimeMillis();
            deg[next] = b;
            next = (next + 1) % N;
            if (count < N) count++;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) {
    }

    /**
     * The photo's width of view in degrees: from the picture's own 35 mm focal length when the camera
     * app wrote one, else from this phone's camera; portrait photos use the narrow side.
     */
    @SuppressWarnings("deprecation")
    public static float fieldOfView(String jpegPath) {
        boolean portrait = false;
        try {
            android.media.ExifInterface x = new android.media.ExifInterface(jpegPath);
            int w = x.getAttributeInt(android.media.ExifInterface.TAG_IMAGE_WIDTH, 0);
            int h = x.getAttributeInt(android.media.ExifInterface.TAG_IMAGE_LENGTH, 0);
            int o = x.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
            boolean turned = o == 6 || o == 8 || o == 5 || o == 7;
            portrait = turned ? w > h : h > w;
            String f35 = x.getAttribute("FocalLengthIn35mmFilm");
            if (f35 != null) {
                double f = Double.parseDouble(f35);
                if (f > 5) return (float) Math.toDegrees(2 * Math.atan((portrait ? 24 : 36) / (2 * f)));
            }
        } catch (Exception ignored) {
        }
        try {
            android.hardware.Camera cam = android.hardware.Camera.open();
            if (cam != null) {
                android.hardware.Camera.Parameters p = cam.getParameters();
                float a = portrait ? p.getVerticalViewAngle() : p.getHorizontalViewAngle();
                cam.release();
                if (a > 5 && a < 170) return a;
            }
        } catch (Exception ignored) {
        }
        return portrait ? 50 : 65;
    }

    /** The direction the camera app wrote into the picture (GPSImgDirection), or NaN. */
    public static float exifDirection(String jpegPath) {
        try {
            android.media.ExifInterface x = new android.media.ExifInterface(jpegPath);
            String d = x.getAttribute("GPSImgDirection");
            if (d == null) return Float.NaN;
            String[] r = d.split("/");
            double v = r.length == 2 ? Double.parseDouble(r[0]) / Double.parseDouble(r[1]) : Double.parseDouble(d);
            return (float) ((v % 360 + 360) % 360);
        } catch (Exception e) {
            return Float.NaN;
        }
    }
}
