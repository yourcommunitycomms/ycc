package org.communitycomms.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.location.Location;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.communitycomms.app.geo.Geo;
import org.communitycomms.app.geo.MyPosition;

/**
 * Full-screen baseplate compass (opened by tapping the map's compass), drawn like a Silva:
 * the phone is the baseplate with a direction-of-travel arrow pointing up; the red/white needle
 * points to north; the bezel is turned by dragging it (or Set to my heading / Bearing to X) and
 * carries the orienting arrow. Turn yourself until the red needle sits in the orienting arrow
 * ("red in the shed") and the travel arrow points along the set bearing.
 */
public final class CompassActivity extends Activity {
    public static final String EXTRA_LAT = "lat", EXTRA_LON = "lon";

    private MyPosition pos;
    private CompassView compass;
    private TextView heading, setLine, info;
    private Button northMode, toX, pointsButton;
    /** 0 = no map points, 1 = around the edge, 2 = around the edge and as a list. */
    private int pointsMode;
    private LinearLayout pointList;
    private android.widget.ScrollView listScroll;
    /** The point picked from the list (red arrow), or null. */
    private Target target;

    /** A map point or a person with a position, for the compass. */
    static final class Target {
        String label;
        int icon;
        double lat, lon;
        boolean person;
    }

    private java.util.List<Target> targets() {
        java.util.List<Target> l = new java.util.ArrayList<Target>();
        long now = System.currentTimeMillis();
        for (org.communitycomms.app.geo.Poi p : org.communitycomms.app.geo.PoiStore.get(this).all()) {
            if (p.expiresAt != 0 && p.expiresAt < now) continue;
            Target t = new Target();
            t.label = org.communitycomms.app.geo.Poi.readable(p.note);
            if (t.label.length() == 0) t.label = org.communitycomms.app.geo.Poi.ICON_NAMES[p.icon];
            t.icon = p.icon;
            t.lat = p.lat;
            t.lon = p.lon;
            l.add(t);
        }
        for (Contacts.Contact c : Contacts.get(this).all()) {
            if (Double.isNaN(c.lat)) continue;
            Target t = new Target();
            t.label = c.name;
            t.icon = 0;
            t.lat = c.lat;
            t.lon = c.lon;
            t.person = true;
            l.add(t);
        }
        // Nearest first.
        if (me != null) {
            final double la = me.getLatitude(), lo = me.getLongitude();
            java.util.Collections.sort(l, new java.util.Comparator<Target>() {
                @Override
                public int compare(Target a, Target b) {
                    return Double.compare(Geo.distanceM(la, lo, a.lat, a.lon), Geo.distanceM(la, lo, b.lat, b.lon));
                }
            });
        }
        return l;
    }

    private long listBuiltMs;

    /** The list under the compass: tap one to point the red arrow at it and set the bearing. */
    private void renderList(java.util.List<Target> ts) {
        listScroll.setVisibility(pointsMode == 2 ? View.VISIBLE : View.GONE);
        if (pointsMode != 2) return;
        long now = System.currentTimeMillis();
        if (now - listBuiltMs < 2000 && pointList.getChildCount() > 0) return; // distances, not every compass tick
        listBuiltMs = now;
        pointList.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        if (ts.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("No map points or people with a position yet.");
            t.setPadding((int) (12 * d), 0, 0, 0);
            pointList.addView(t);
        }
        for (final Target t : ts) {
            TextView row = new TextView(this);
            row.setTextSize(16);
            row.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
            String where = me == null ? "(waiting for your GPS fix)"
                    : Math.round(show((float) Geo.bearing(me.getLatitude(), me.getLongitude(), t.lat, t.lon))) % 360 + "°  "
                    + Geo.distance(Geo.distanceM(me.getLatitude(), me.getLongitude(), t.lat, t.lon));
            boolean picked = target != null && target.lat == t.lat && target.lon == t.lon;
            row.setText((picked ? "> " : "") + t.label + "   " + where);
            row.setTextColor(picked ? 0xffff5252 : 0xffcfe3d4);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    target = t;
                    listBuiltMs = 0;
                    if (me != null) compass.setBearing(show((float) Geo.bearing(me.getLatitude(), me.getLongitude(), t.lat, t.lon)));
                    render();
                }
            });
            pointList.addView(row);
        }
    }
    private float lastHeading = Float.NaN;
    private Location me;
    private double xLat = Double.NaN, xLon;
    /** Show magnetic bearings (like a real compass) instead of true. */
    private boolean magnetic;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Compass");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        magnetic = getSharedPreferences("map", MODE_PRIVATE).getBoolean("compassMagnetic", false);
        if (getIntent().hasExtra(EXTRA_LAT)) {
            xLat = getIntent().getDoubleExtra(EXTRA_LAT, Double.NaN);
            xLon = getIntent().getDoubleExtra(EXTRA_LON, 0);
        }
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff0f1f16);
        heading = new TextView(this);
        heading.setTextSize(30);
        heading.setTextColor(0xffffffff);
        heading.setGravity(android.view.Gravity.CENTER);
        heading.setPadding(0, (int) (8 * d), 0, 0);
        root.addView(heading);
        setLine = new TextView(this);
        setLine.setTextSize(17);
        setLine.setTextColor(0xfff0c75e);
        setLine.setGravity(android.view.Gravity.CENTER);
        root.addView(setLine);
        compass = new CompassView(this);
        root.addView(compass, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        info = new TextView(this);
        info.setTextSize(13);
        info.setTextColor(0xffcfe3d4);
        info.setPadding((int) (12 * d), 0, (int) (12 * d), (int) (4 * d));
        root.addView(info);

        Button mine = button("Set to my heading", new Runnable() {
            @Override
            public void run() {
                if (!Float.isNaN(lastHeading)) compass.setBearing(show(lastHeading));
            }
        });
        toX = button("Bearing to X", new Runnable() {
            @Override
            public void run() {
                double b = bearingToX();
                if (!Double.isNaN(b)) compass.setBearing(show((float) b));
            }
        });
        northMode = button("", new Runnable() {
            @Override
            public void run() {
                // The set bearing keeps pointing the same way on the ground when switching.
                float trueSet = toTrue(compass.bearing);
                magnetic = !magnetic;
                getSharedPreferences("map", MODE_PRIVATE).edit().putBoolean("compassMagnetic", magnetic).commit();
                compass.setBearing(show(trueSet));
                render();
            }
        });
        root.addView(FilesActivity.row(mine, toX, northMode));
        pointsButton = button("", new Runnable() {
            @Override
            public void run() {
                pointsMode = (pointsMode + 1) % 3;
                getSharedPreferences("map", MODE_PRIVATE).edit().putInt("compassPoints", pointsMode).commit();
                render();
            }
        });
        root.addView(pointsButton);
        pointList = new LinearLayout(this);
        pointList.setOrientation(LinearLayout.VERTICAL);
        listScroll = new android.widget.ScrollView(this);
        listScroll.addView(pointList);
        root.addView(listScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (150 * d)));
        pointsMode = getSharedPreferences("map", MODE_PRIVATE).getInt("compassPoints", 0);
        setContentView(root);
        pos = new MyPosition(this, new MyPosition.Listener() {
            @Override
            public void onMyPosition(Location loc, float h) {
                if (loc != null) me = loc;
                lastHeading = h;
                render();
            }
        });
        render();
    }

    private Button button(String t, final Runnable r) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        pos.start();
    }

    @Override
    protected void onPause() {
        pos.stop();
        super.onPause();
    }

    /** A true bearing as shown (magnetic subtracts the declination). */
    private float show(float trueDeg) {
        return norm(magnetic ? trueDeg - pos.declination() : trueDeg);
    }

    private float toTrue(float shown) {
        return norm(magnetic ? shown + pos.declination() : shown);
    }

    private static float norm(float a) {
        return (a % 360 + 360) % 360;
    }

    private double bearingToX() {
        if (Double.isNaN(xLat) || me == null) return Double.NaN;
        return Geo.bearing(me.getLatitude(), me.getLongitude(), xLat, xLon);
    }

    private void render() {
        String ref = magnetic ? "magnetic" : "true";
        northMode.setText(magnetic ? "Magnetic north" : "True north");
        toX.setEnabled(!Double.isNaN(xLat) && me != null);
        if (Float.isNaN(lastHeading)) {
            heading.setText("No compass reading");
            setLine.setText("Hold the phone flat, away from metal");
        } else {
            float h = show(lastHeading);
            heading.setText(Math.round(h) % 360 + "° " + Geo.compass(h));
            float off = ((compass.bearing - h + 540) % 360) - 180;
            String turn = Math.abs(off) <= 3 ? "on the bearing"
                    : "turn " + (off > 0 ? "right " : "left ") + Math.round(Math.abs(off)) + "°";
            setLine.setText("Set " + Math.round(compass.bearing) % 360 + "° " + Geo.compass(compass.bearing) + " · " + turn);
        }
        StringBuilder sb = new StringBuilder("Bearings are " + ref + " north");
        if (me != null) sb.append(String.format(java.util.Locale.US, " · declination %+.1f°", pos.declination()));
        double bx = bearingToX();
        if (!Double.isNaN(bx)) {
            sb.append("\nX: ").append(Math.round(show((float) bx)) % 360).append("° ").append(Geo.compass(show((float) bx)))
                    .append(", ").append(Geo.distance(Geo.distanceM(me.getLatitude(), me.getLongitude(), xLat, xLon)));
        } else if (!Double.isNaN(xLat)) {
            sb.append("\nX: waiting for your GPS fix to work out the bearing");
        }
        if (target != null && me != null) {
            sb.append("\nTarget ").append(target.label).append(": ")
                    .append(Math.round(show((float) Geo.bearing(me.getLatitude(), me.getLongitude(), target.lat, target.lon))) % 360)
                    .append("°, ").append(Geo.distance(Geo.distanceM(me.getLatitude(), me.getLongitude(), target.lat, target.lon)));
        }
        sb.append("\nDrag the bezel to set a bearing. Turn until the red needle sits in the orienting arrow.");
        info.setText(sb);
        pointsButton.setText(pointsMode == 0 ? "Map points: hidden (tap to show)" : pointsMode == 1
                ? "Map points: around the edge (tap for a list too)" : "Map points: edge and list (tap to hide)");
        java.util.List<Target> ts = pointsMode == 0 ? new java.util.ArrayList<Target>() : targets();
        compass.points = ts;
        renderList(ts);
        compass.needle = Float.isNaN(lastHeading) ? Float.NaN : show(lastHeading);
        compass.invalidate();
    }

    /** The baseplate, bezel and needle. Bearings here are as shown (true or magnetic). */
    final class CompassView extends View {
        float bearing;
        float needle = Float.NaN;
        java.util.List<Target> points = new java.util.ArrayList<Target>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float dragStart, bearingStart;

        CompassView(Context c) {
            super(c);
        }

        void setBearing(float b) {
            bearing = norm(b);
            render();
        }

        @Override
        protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight();
            // The baseplate is 2.63 dial radii tall: size it to fit the space, dial below the arrow.
            float r = radius(), cx = w / 2, cy = centreY();

            // Baseplate: clear plastic with a ruler edge, and the direction-of-travel arrow at the top.
            RectF plate = new RectF(cx - r * 1.12f, cy - r * 1.45f, cx + r * 1.12f, cy + r * 1.18f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x33cfe3d4);
            c.drawRoundRect(plate, 18 * d, 18 * d, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f * d);
            p.setColor(0x88cfe3d4);
            c.drawRoundRect(plate, 18 * d, 18 * d, p);
            for (int i = 0; i <= 20; i++) { // ruler ticks along the left edge
                float y = plate.top + 14 * d + i * (plate.height() - 28 * d) / 20;
                c.drawLine(plate.left, y, plate.left + (i % 5 == 0 ? 14 : 7) * d, y, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xfff0c75e);
            path.reset();
            path.moveTo(cx, plate.top + 6 * d);
            path.lineTo(cx + 16 * d, plate.top + 34 * d);
            path.lineTo(cx + 5 * d, plate.top + 34 * d);
            path.lineTo(cx + 5 * d, cy - r - 6 * d);
            path.lineTo(cx - 5 * d, cy - r - 6 * d);
            path.lineTo(cx - 5 * d, plate.top + 34 * d);
            path.lineTo(cx - 16 * d, plate.top + 34 * d);
            path.close();
            c.drawPath(path, p);

            // Bezel, turned so the set bearing sits at the index line at the top.
            c.save();
            c.translate(cx, cy);
            c.rotate(-bearing);
            p.setColor(0xff1f3d2b);
            c.drawCircle(0, 0, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(0xffcfe3d4);
            c.drawCircle(0, 0, r, p);
            p.setTextSize(13 * d);
            for (int deg = 0; deg < 360; deg += 2) {
                float len = deg % 10 == 0 ? 12 * d : 6 * d;
                p.setStrokeWidth(deg % 10 == 0 ? 2 * d : 1 * d);
                c.save();
                c.rotate(deg);
                c.drawLine(0, -r, 0, -r + len, p);
                if (deg % 30 == 0) {
                    p.setStyle(Paint.Style.FILL);
                    String t = deg == 0 ? "N" : deg == 90 ? "E" : deg == 180 ? "S" : deg == 270 ? "W" : String.valueOf(deg);
                    p.setTextSize((deg % 90 == 0 ? 18 : 12) * d);
                    p.setColor(deg == 0 ? 0xffff5252 : 0xffcfe3d4);
                    c.drawText(t, -p.measureText(t) / 2, -r + 34 * d, p);
                    p.setColor(0xffcfe3d4);
                    p.setStyle(Paint.Style.STROKE);
                }
                c.restore();
            }
            // Meridian lines and the orienting arrow (the "shed"), fixed to the bezel's N.
            p.setStrokeWidth(1 * d);
            p.setColor(0x66ff5252);
            for (int k = -2; k <= 2; k++) c.drawLine(k * r * 0.22f, -r * 0.62f, k * r * 0.22f, r * 0.62f, p);
            p.setStrokeWidth(3 * d);
            p.setColor(0xffff5252);
            path.reset();
            path.moveTo(0, -r * 0.62f);
            path.lineTo(r * 0.16f, -r * 0.34f);
            path.lineTo(r * 0.08f, -r * 0.34f);
            path.lineTo(r * 0.08f, r * 0.42f);
            path.lineTo(-r * 0.08f, r * 0.42f);
            path.lineTo(-r * 0.08f, -r * 0.34f);
            path.lineTo(-r * 0.16f, -r * 0.34f);
            path.close();
            c.drawPath(path, p);
            c.restore();

            // Index line where the bearing is read, on the baseplate.
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xfff0c75e);
            c.drawRect(cx - 2 * d, cy - r - 10 * d, cx + 2 * d, cy - r + 14 * d, p);

            // Map points around the edge of the dial, where they lie from here (screen up = the way I face).
            if (!Float.isNaN(needle) && me != null && !points.isEmpty()) {
                float ring = r + 10 * d;
                Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
                tp.setTextSize(11 * d);
                tp.setColor(0xffffffff);
                Paint halo = new Paint(tp);
                halo.setColor(0xff000000);
                halo.setStyle(Paint.Style.STROKE);
                halo.setStrokeWidth(3 * d);
                for (Target t : points) {
                    double brg = Geo.bearing(me.getLatitude(), me.getLongitude(), t.lat, t.lon);
                    double rel = Math.toRadians(show((float) brg) - needle);
                    float px = cx + (float) (Math.sin(rel) * ring), py = cy - (float) (Math.cos(rel) * ring);
                    org.communitycomms.app.geo.PoiIcons.draw(c, t.icon, px, py, 22 * d);
                    String dist = Geo.distance(Geo.distanceM(me.getLatitude(), me.getLongitude(), t.lat, t.lon));
                    float tw = tp.measureText(dist);
                    c.drawText(dist, px - tw / 2, py + 22 * d, halo);
                    c.drawText(dist, px - tw / 2, py + 22 * d, tp);
                }
            }
            // The picked point: a big red arrow pointing straight at it.
            if (!Float.isNaN(needle) && me != null && target != null) {
                double brg = Geo.bearing(me.getLatitude(), me.getLongitude(), target.lat, target.lon);
                c.save();
                c.translate(cx, cy);
                c.rotate(show((float) brg) - needle);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xccff1744);
                path.reset();
                path.moveTo(0, -r * 0.95f);
                path.lineTo(r * 0.14f, -r * 0.7f);
                path.lineTo(r * 0.05f, -r * 0.7f);
                path.lineTo(r * 0.05f, -r * 0.15f);
                path.lineTo(-r * 0.05f, -r * 0.15f);
                path.lineTo(-r * 0.05f, -r * 0.7f);
                path.lineTo(-r * 0.14f, -r * 0.7f);
                path.close();
                c.drawPath(path, p);
                c.restore();
            }
            // Needle: points to north (as shown), red end north.
            if (!Float.isNaN(needle)) {
                c.save();
                c.translate(cx, cy);
                c.rotate(-needle);
                float nl = r * 0.78f, nw = r * 0.09f;
                path.reset();
                path.moveTo(0, -nl);
                path.lineTo(nw, 0);
                path.lineTo(-nw, 0);
                path.close();
                p.setColor(0xffe53935);
                c.drawPath(path, p);
                path.reset();
                path.moveTo(0, nl);
                path.lineTo(nw, 0);
                path.lineTo(-nw, 0);
                path.close();
                p.setColor(0xffeeeeee);
                c.drawPath(path, p);
                p.setColor(0xff212121);
                c.drawCircle(0, 0, nw * 0.7f, p);
                c.restore();
            }
        }

        private float radius() {
            float d = getResources().getDisplayMetrics().density;
            return Math.min(getWidth() * 0.42f, (getHeight() - 12 * d) / 2.63f);
        }

        private float centreY() {
            float d = getResources().getDisplayMetrics().density;
            float r = radius();
            return (getHeight() - 2.63f * r) / 2 + 1.45f * r + 2 * d;
        }

        private float angle(MotionEvent e) {
            float cx = getWidth() / 2f, cy = centreY();
            return (float) Math.toDegrees(Math.atan2(e.getX() - cx, cy - e.getY()));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            // Turning the bezel: drag round it (clockwise drag turns the dial clockwise, so the
            // bearing at the index goes down, as on a real compass).
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                dragStart = angle(e);
                bearingStart = bearing;
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                float delta = angle(e) - dragStart;
                setBearing(Math.round(bearingStart - delta));
                return true;
            }
            return super.onTouchEvent(e);
        }
    }
}
