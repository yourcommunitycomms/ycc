package org.communitycomms.app.geo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import android.view.WindowManager;

/**
 * Live "where am I and which way am I facing" for the maps: GPS/network position updates plus a
 * compass heading (true north, corrected for screen rotation). Start in onResume, stop in onPause.
 */
public final class MyPosition implements SensorEventListener, LocationListener {
    public interface Listener {
        /** Main thread. loc may be null (no fix yet); heading is degrees from true north, or NaN. */
        void onMyPosition(Location loc, float heading);
    }

    private final Context context;
    private final Listener listener;
    private final SensorManager sm;
    private final LocationManager lm;
    private Location loc;
    private float heading = Float.NaN;
    private float declination;
    private final float[] grav = new float[3], mag = new float[3], rot = new float[9], orient = new float[3];
    private boolean hasGrav, hasMag, hasCompass;
    private long lastSent;

    public MyPosition(Context c, Listener l) {
        context = c;
        listener = l;
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
    }

    public Location location() {
        return loc;
    }

    /** Magnetic declination at the last fix (degrees, east positive); 0 before the first fix. */
    public float declination() {
        return declination;
    }

    public boolean hasFix() {
        return loc != null;
    }

    /** Why there is no "Me" on the map yet, or null when there is a fix. */
    public String problem() {
        if (loc != null) return null;
        if (android.os.Build.VERSION.SDK_INT >= 23 && context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return "Me: location permission not given";
        boolean on = false;
        try {
            on = lm != null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
        } catch (RuntimeException ignored) {
        }
        return on ? "Me: waiting for a GPS fix (needs open sky)" : "Me: Location is off in phone settings";
    }

    @SuppressLint("MissingPermission")
    public void start() {
        if (lm != null) {
            setLoc(LocationHelper.bestKnown(lm));
            try {
                for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                    if (lm.getAllProviders().contains(p)) lm.requestLocationUpdates(p, 2000, 0, this, Looper.getMainLooper());
                }
            } catch (RuntimeException ignored) {
                // no permission yet: the compass still works
            }
        }
        if (sm != null) {
            Sensor rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (rv != null) {
                hasCompass = sm.registerListener(this, rv, SensorManager.SENSOR_DELAY_UI);
            } else {
                Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), m = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
                if (a != null && m != null) {
                    sm.registerListener(this, a, SensorManager.SENSOR_DELAY_UI);
                    hasCompass = sm.registerListener(this, m, SensorManager.SENSOR_DELAY_UI);
                }
            }
        }
        send(true);
    }

    public void stop() {
        if (sm != null) sm.unregisterListener(this);
        if (lm != null) {
            try {
                lm.removeUpdates(this);
            } catch (SecurityException ignored) {
            }
        }
    }

    private void setLoc(Location l) {
        Location fake = FakeGps.location(context);
        if (fake != null) l = fake; // fake GPS: real fixes are ignored
        if (l == null) return;
        if (loc != null && l.getTime() < loc.getTime() - 60000) return; // keep the newer fix
        loc = l;
        declination = new GeomagneticField((float) l.getLatitude(), (float) l.getLongitude(),
                (float) l.getAltitude(), System.currentTimeMillis()).getDeclination();
    }

    private float sentHeading = Float.NaN;

    private void send(boolean now) {
        long t = SystemClock.uptimeMillis();
        if (!now && t - lastSent < 200) return;
        // Compass jitter of a degree or so is not worth redrawing a whole map for (it kept tablets busy).
        // A small drift that has settled is still shown, at most every 15 s (one redraw: no real load).
        if (!now && !Float.isNaN(sentHeading) && !Float.isNaN(heading)
                && Math.abs(((heading - sentHeading + 540) % 360) - 180) < 2f && t - lastSent < 15000) return;
        lastSent = t;
        sentHeading = heading;
        listener.onMyPosition(loc, heading);
    }

    // ---- compass ----

    @Override
    public void onSensorChanged(SensorEvent e) {
        int type = e.sensor.getType();
        if (type == Sensor.TYPE_ROTATION_VECTOR) {
            float[] v = e.values.length > 4 ? new float[]{e.values[0], e.values[1], e.values[2], e.values[3]} : e.values;
            SensorManager.getRotationMatrixFromVector(rot, v);
        } else {
            if (type == Sensor.TYPE_ACCELEROMETER) {
                lowPass(e.values, grav);
                hasGrav = true;
            } else if (type == Sensor.TYPE_MAGNETIC_FIELD) {
                lowPass(e.values, mag);
                hasMag = true;
            }
            if (!hasGrav || !hasMag || !SensorManager.getRotationMatrix(rot, null, grav, mag)) return;
        }
        float[] r = rot;
        int disp = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        if (disp != Surface.ROTATION_0) {
            float[] out = new float[9];
            int ax = disp == Surface.ROTATION_90 ? SensorManager.AXIS_Y
                    : disp == Surface.ROTATION_180 ? SensorManager.AXIS_MINUS_X : SensorManager.AXIS_MINUS_Y;
            int ay = disp == Surface.ROTATION_90 ? SensorManager.AXIS_MINUS_X
                    : disp == Surface.ROTATION_180 ? SensorManager.AXIS_MINUS_Y : SensorManager.AXIS_X;
            SensorManager.remapCoordinateSystem(rot, ax, ay, out);
            r = out;
        }
        SensorManager.getOrientation(r, orient);
        float h = (float) Math.toDegrees(orient[0]) + declination;
        h = (h % 360 + 360) % 360;
        if (Float.isNaN(heading)) {
            heading = h;
        } else {
            float d = ((h - heading + 540) % 360) - 180; // smooth the shortest way round
            heading = ((heading + d * 0.25f) % 360 + 360) % 360;
        }
        send(false);
    }

    private static void lowPass(float[] in, float[] out) {
        for (int i = 0; i < 3; i++) out[i] += 0.2f * (in[i] - out[i]);
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) {
    }

    // ---- location ----

    @Override
    public void onLocationChanged(Location l) {
        if (LocationManager.NETWORK_PROVIDER.equals(l.getProvider()) && loc != null
                && LocationManager.GPS_PROVIDER.equals(loc.getProvider()) && l.getTime() - loc.getTime() < 30000) {
            return; // a fresh GPS fix beats a network one
        }
        setLoc(l);
        // No compass on this phone (the ZTE Blade A462 has none): point the arrow along the GPS course
        // while moving.
        if (!hasCompass && l.hasBearing() && l.hasSpeed() && l.getSpeed() > 0.8f) heading = l.getBearing();
        send(true);
    }

    @Override
    public void onStatusChanged(String p, int s, Bundle b) {
    }

    @Override
    public void onProviderEnabled(String p) {
    }

    @Override
    public void onProviderDisabled(String p) {
    }
}
