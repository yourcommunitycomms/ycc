package org.communitycomms.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Share my camera > Telemetry overlay: position, which way the camera points (compass), its tilt and roll
 * (from the rotation or accelerometer and magnet sensors, the gyro where the phone fuses it in), speed
 * when the GPS knows it, and the time, drawn onto each picture before it is sent, so every viewer
 * (old app versions too) sees it with no change to what is sent.
 */
final class VideoTelemetry implements SensorEventListener, android.location.LocationListener {
    private volatile Location fix;
    private final Context c;
    private final SensorManager sm;
    private final float[] rot = new float[9], cam = new float[9], ori = new float[3], grav = new float[3], mag = new float[3];
    private boolean haveGrav, haveMag, haveRot;
    private volatile float azimuth = Float.NaN, pitch, roll;

    VideoTelemetry(Context c) {
        this.c = c.getApplicationContext();
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
    }

    @SuppressWarnings("MissingPermission")
    void start() {
        // Its own GPS while sharing, once a second (the radio may not be asking for positions).
        android.location.LocationManager lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        try {
            if (lm != null && lm.getAllProviders().contains(android.location.LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000, 0, this, android.os.Looper.getMainLooper());
            }
        } catch (RuntimeException ignored) {
            // no location permission: the last known position is used
        }
        if (sm == null) return;
        Sensor r = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR); // fuses the gyro where there is one
        if (r != null) {
            sm.registerListener(this, r, SensorManager.SENSOR_DELAY_UI);
        } else {
            Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), m = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (a != null) sm.registerListener(this, a, SensorManager.SENSOR_DELAY_UI);
            if (m != null) sm.registerListener(this, m, SensorManager.SENSOR_DELAY_UI);
        }
    }

    void stop() {
        if (sm != null) sm.unregisterListener(this);
        android.location.LocationManager lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm != null) lm.removeUpdates(this);
    }

    @Override
    public void onLocationChanged(Location l) {
        fix = l;
    }

    @Override
    public void onStatusChanged(String p, int s, android.os.Bundle b) {
    }

    @Override
    public void onProviderEnabled(String p) {
    }

    @Override
    public void onProviderDisabled(String p) {
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        int t = e.sensor.getType();
        if (t == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rot, e.values);
            haveRot = true;
        } else if (t == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(e.values, 0, grav, 0, 3);
            haveGrav = true;
        } else if (t == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(e.values, 0, mag, 0, 3);
            haveMag = true;
        }
        if (!haveRot) {
            if (!haveGrav || !haveMag || !SensorManager.getRotationMatrix(rot, null, grav, mag)) return;
        }
        // The phone held up like a camera: the direction the back camera looks, its tilt and its roll.
        SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, cam);
        SensorManager.getOrientation(cam, ori);
        azimuth = (float) ((Math.toDegrees(ori[0]) + 360) % 360);
        pitch = (float) -Math.toDegrees(ori[1]);
        roll = (float) Math.toDegrees(ori[2]);
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) {
    }

    private static final String[] POINTS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    /** Draws the overlay onto the picture (a mutable copy when needed) and returns it. */
    Bitmap draw(Bitmap b) {
        Bitmap out = b.isMutable() ? b : b.copy(Bitmap.Config.ARGB_8888, true);
        if (out == null) return b;
        int w = out.getWidth(), h = out.getHeight();
        float ts = Math.max(8f, w / 17f);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTextSize(ts);
        text.setColor(0xffffffff);
        text.setFakeBoldText(true);
        Paint band = new Paint();
        band.setColor(0x99000000);
        RadioService s = RadioService.instance();
        Location l = fix;
        if (l == null && s != null) l = s.lastFix();
        if (l != null && System.currentTimeMillis() - l.getTime() > 10 * 60000L) l = null;
        String l1 = l == null ? "No GPS fix" : String.format(Locale.US, "%.5f, %.5f", l.getLatitude(), l.getLongitude())
                + (l.hasAccuracy() ? " ±" + Math.round(l.getAccuracy()) + "m" : "");
        float az = azimuth;
        String l2 = (Float.isNaN(az) ? "Heading ?" : Math.round(az) % 360 + "° " + POINTS[(int) (((az + 22.5f) % 360) / 45)])
                + (l != null && l.hasSpeed() ? "  " + speed(l.getSpeed()) : "");
        String l3 = (Float.isNaN(az) ? "" : String.format(Locale.US, "tilt %+d° roll %+d°  ", Math.round(pitch), Math.round(roll)))
                + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String[] lines = {l1, l2, l3};
        float lh = ts * 1.15f, top = h - lh * lines.length - ts * 0.3f;
        Canvas cv = new Canvas(out);
        cv.drawRect(0, top, w, h, band);
        for (int i = 0; i < lines.length; i++) cv.drawText(lines[i], ts * 0.3f, top + lh * (i + 1) - ts * 0.15f, text);
        if (!Float.isNaN(az)) { // a small north arrow, top right: points where north is in the picture's frame of reference
            float r = ts * 1.1f, cx = w - r - ts * 0.4f, cy = r + ts * 0.4f;
            cv.drawCircle(cx, cy, r, band);
            cv.save();
            cv.rotate(-az, cx, cy);
            Path p = new Path();
            p.moveTo(cx, cy - r * 0.8f);
            p.lineTo(cx - r * 0.4f, cy + r * 0.5f);
            p.lineTo(cx + r * 0.4f, cy + r * 0.5f);
            p.close();
            Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
            red.setColor(0xffff5252);
            cv.drawPath(p, red);
            cv.restore();
            Paint n = new Paint(text);
            n.setTextSize(ts * 0.75f);
            cv.drawText("N", cx - n.measureText("N") / 2, cy + r + ts * 0.75f, n);
        }
        return out;
    }

    private static String speed(float mps) {
        return org.communitycomms.app.geo.Geo.imperial ? Math.round(mps * 2.23694f) + " mph" : Math.round(mps * 3.6f) + " km/h";
    }
}
