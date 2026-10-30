package org.communitycomms.app.geo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * A small slippy map: draws z/x/y tiles from an offline folder (and, if switched on, from an
 * online URL, cached to disk), with markers on top. Drag to pan, pinch or double-tap to zoom.
 * Missing tiles fall back to a scaled-up parent tile, so zooming past the data still shows
 * something.
 */
public final class TileMapView extends View {
    public static final class Marker {
        public final String label;
        public final double lat, lon;
        public final int colour;
        public final Object tag;
        /** PoiIcons index, or -1 for a plain dot (people's positions). */
        public final int icon;
        /** Small picture drawn instead of the icon (a POI linked to a photo), or null. */
        public Bitmap thumb;
        /** Drawn as this shape instead of the icon, or null. */
        public Poi.Shape shape;
        /** A photo's view cone: {bearing, field of view} in degrees, or null. */
        public float[] aim;
        /** The icon turned this many degrees from north, drawn at this size (percent), with a direction arrow. */
        public int rotation, sizePct = 100;
        public boolean arrow;
        /** Shaded outlines drawn under every marker ({lat, lon, lat, lon, ...} each), or null: alert areas. */
        public List<double[]> area;

        public Marker(String label, double lat, double lon, int colour, Object tag) {
            this(label, lat, lon, colour, tag, -1);
        }

        public Marker(String label, double lat, double lon, int colour, Object tag, int icon) {
            this.label = label;
            this.lat = lat;
            this.lon = lon;
            this.colour = colour;
            this.tag = tag;
            this.icon = icon;
        }
    }

    public interface OnMarkerTap {
        void onMarkerTap(Marker m);
    }

    public interface OnLongPress {
        void onLongPress(double lat, double lon);
    }

    private OnLongPress onLongPress;

    public void setOnLongPress(OnLongPress l) {
        onLongPress = l;
    }

    /** Called (on the UI thread) when most of the view has no tile at all once loading has finished. */
    public interface OnBlankArea {
        void onBlankArea();
    }

    private OnBlankArea onBlankArea;

    public void setOnBlankArea(OnBlankArea l) {
        onBlankArea = l;
    }

    public boolean hasOnline() {
        return onlineUrl != null;
    }

    // ---- me: position arrow and compass ----

    private double meLat = Double.NaN, meLon;
    private float meAccuracy;
    private boolean meStale, meFake;
    private float heading = Float.NaN;

    /** My position (null = none yet) and the way the phone faces (degrees true, NaN = no compass). */
    public void setMe(android.location.Location loc, float headingDeg) {
        if (loc != null) {
            meLat = loc.getLatitude();
            meLon = loc.getLongitude();
            meAccuracy = loc.hasAccuracy() ? loc.getAccuracy() : 0;
            meStale = System.currentTimeMillis() - loc.getTime() > 5 * 60000;
            meFake = FakeGps.isFake(loc);
        }
        heading = headingDeg;
        invalidate();
    }

    private final Paint mePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path arrow = new android.graphics.Path();

    private String meProblem;

    /** Shown in the map corner while there is no "Me" (e.g. waiting for a GPS fix); null hides it. */
    public void setMeProblem(String p) {
        if (p == null ? meProblem == null : p.equals(meProblem)) return;
        meProblem = p;
        invalidate();
    }

    /** The map was dragged by hand, so the + is somewhere the user chose (not just following Me). */
    private boolean movedByHand;

    /** "+ 1.2 km NE 45° from me" in the bottom corner once the + has been moved by hand. */
    private void drawCrossBearing(Canvas c, float d, float y) {
        if (!movedByHand || Double.isNaN(meLat)) return;
        double lat = centerLat(), lon = centerLon();
        double brg = Geo.bearing(meLat, meLon, lat, lon);
        String t = "+ is " + Geo.distance(Geo.distanceM(meLat, meLon, lat, lon)) + " " + Geo.compass(brg) + " "
                + Math.round(brg) + "° from me";
        c.drawText(t, 8 * d, y, haloPaint);
        c.drawText(t, 8 * d, y, textPaint);
    }

    private void drawMe(Canvas c, float d) {
        if (Double.isNaN(meLat)) {
            if (meProblem != null) {
                c.drawText(meProblem, 8 * d, getHeight() - 10 * d, haloPaint);
                c.drawText(meProblem, 8 * d, getHeight() - 10 * d, textPaint);
            }
            return;
        }
        drawCrossBearing(c, d, getHeight() - 10 * d);
        if (meFake && !movedByHand) {
            c.drawText("Me: (Fake) GPS position", 8 * d, getHeight() - 10 * d, haloPaint);
            c.drawText("Me: (Fake) GPS position", 8 * d, getHeight() - 10 * d, textPaint);
        }
        float mx = sx(Geo.lonToX(meLon, z)), my = sy(Geo.latToY(meLat, z));
        int blue = meStale ? 0xff90a4ae : 0xff2196f3;
        // Accuracy circle: metres per pixel at this latitude and zoom.
        double mPerPx = 156543.03 * Math.cos(Math.toRadians(meLat)) / (1 << z) * 256 / tilePx;
        float acc = (float) (meAccuracy / mPerPx);
        if (acc > 12 * d) {
            mePaint.setStyle(Paint.Style.FILL);
            mePaint.setColor((blue & 0x00ffffff) | 0x30000000);
            c.drawCircle(mx, my, acc, mePaint);
        }
        float s = 14 * d;
        mePaint.setStyle(Paint.Style.FILL);
        mePaint.setColor(blue);
        if (Float.isNaN(heading)) {
            c.drawCircle(mx, my, s * 0.6f, mePaint);
            c.drawCircle(mx, my, s * 0.6f, ringPaint);
        } else {
            arrow.reset();
            arrow.moveTo(0, -s);
            arrow.lineTo(s * 0.75f, s * 0.8f);
            arrow.lineTo(0, s * 0.35f);
            arrow.lineTo(-s * 0.75f, s * 0.8f);
            arrow.close();
            c.save();
            c.translate(mx, my);
            c.rotate(heading);
            c.drawPath(arrow, mePaint);
            c.drawPath(arrow, ringPaint);
            c.restore();
        }
        String me = meFake ? "Me (Fake)" : "Me";
        c.drawText(me, mx + s * 1.1f, my + s * 0.35f, haloPaint);
        c.drawText(me, mx + s * 1.1f, my + s * 0.35f, textPaint);
    }

    /** Small compass in the top right corner. The map is north up, so N stays at the top and the
     *  blue pointer shows the way I face, matching the Me arrow; the label is my heading. */
    private void drawCompass(Canvas c, float d) {
        if (Float.isNaN(heading)) return;
        float r = 22 * d, cx = getWidth() - r - 8 * d, cy = r + 8 * d;
        mePaint.setStyle(Paint.Style.FILL);
        mePaint.setColor(0xb0000000);
        c.drawCircle(cx, cy, r, mePaint);
        c.drawCircle(cx, cy, r, ringPaint);
        float nw = textPaint.measureText("N");
        mePaint.setColor(0xffff5252);
        mePaint.setTextSize(textPaint.getTextSize() * 0.85f);
        c.drawText("N", cx - nw * 0.42f, cy - r + mePaint.getTextSize() * 0.95f, mePaint);
        c.save();
        c.translate(cx, cy);
        c.rotate(heading);
        arrow.reset();
        arrow.moveTo(0, -r * 0.75f);
        arrow.lineTo(r * 0.3f, r * 0.45f);
        arrow.lineTo(0, r * 0.25f);
        arrow.lineTo(-r * 0.3f, r * 0.45f);
        arrow.close();
        mePaint.setColor(0xff2196f3);
        c.drawPath(arrow, mePaint);
        c.restore();
        String t = Math.round(heading) % 360 + "° " + Geo.compass(heading);
        float tw = textPaint.measureText(t);
        c.drawText(t, cx - tw / 2, cy + r + 16 * d, haloPaint);
        c.drawText(t, cx - tw / 2, cy + r + 16 * d, textPaint);
    }

    private static final int MIN_Z = 2, MAX_Z = 19;
    private final float tilePx;
    private double centerX, centerY; // in tile units at zoom z
    private int z = 12;
    private File offline, onlineCache;
    private String onlineUrl;
    private final List<Marker> markers = new ArrayList<Marker>();
    private OnMarkerTap onMarkerTap;

    private final LruCache<String, Bitmap> cache;
    private final Set<String> missing = new HashSet<String>();
    private final Set<String> queued = new HashSet<String>();
    private final LinkedBlockingDeque<int[]> queue = new LinkedBlockingDeque<int[]>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint gridPaint = new Paint();
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private float pinch = 1f;

    public TileMapView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        tilePx = 256 * Math.max(1f, d / 1.5f);
        int mem = (int) Math.min(Runtime.getRuntime().maxMemory() / 6, 48L * 1024 * 1024);
        cache = new LruCache<String, Bitmap>(mem) {
            @Override
            protected int sizeOf(String k, Bitmap b) {
                return b.getRowBytes() * b.getHeight();
            }
        };
        gridPaint.setColor(0xff263238);
        markerPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2 * d);
        ringPaint.setColor(Color.WHITE);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(14 * d);
        haloPaint.setColor(Color.BLACK);
        haloPaint.setTextSize(14 * d);
        haloPaint.setStyle(Paint.Style.STROKE);
        haloPaint.setStrokeWidth(3 * d);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector det) {
                pinch *= det.getScaleFactor();
                if (pinch > 1.6f) {
                    zoomBy(1, det.getFocusX(), det.getFocusY());
                    pinch = 1f;
                } else if (pinch < 0.62f) {
                    zoomBy(-1, det.getFocusX(), det.getFocusY());
                    pinch = 1f;
                }
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                centerX += dx / tilePx;
                centerY += dy / tilePx;
                movedByHand = true;
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                zoomBy(1, e.getX(), e.getY());
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (onLongPress == null) return;
                double tx = centerX + (e.getX() - getWidth() / 2f) / tilePx;
                double ty = centerY + (e.getY() - getHeight() / 2f) / tilePx;
                onLongPress.onLongPress(Geo.yToLat(ty, z), Geo.xToLon(tx, z));
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (cornerTap(e.getX(), e.getY())) return true;
                Marker m = markerAt(e.getX(), e.getY());
                if (m != null && onMarkerTap != null) onMarkerTap.onMarkerTap(m);
                return m != null;
            }
        });
        Thread loader = new Thread(new Runnable() {
            @Override
            public void run() {
                loadLoop();
            }
        }, "tiles");
        loader.setDaemon(true);
        loader.start();
    }

    // ---- setup ----

    public void setSources(File offlineFolder, String onlineUrlOrNull, File onlineCacheDir) {
        offline = offlineFolder;
        onlineUrl = onlineUrlOrNull == null || onlineUrlOrNull.length() == 0 ? null : onlineUrlOrNull;
        onlineCache = onlineCacheDir;
        cache.evictAll();
        synchronized (missing) {
            missing.clear();
        }
        invalidate();
    }

    public void setMarkers(List<Marker> m) {
        markers.clear();
        markers.addAll(m);
        invalidate();
    }

    public void setOnMarkerTap(OnMarkerTap l) {
        onMarkerTap = l;
    }

    public void center(double lat, double lon, int zoom) {
        z = Math.max(MIN_Z, Math.min(MAX_Z, zoom));
        centerX = Geo.lonToX(lon, z);
        centerY = Geo.latToY(lat, z);
        movedByHand = false; // centred by a button (Me, Everyone, a contact): no "+ from me" line
        invalidate();
    }

    public double centerLat() {
        return Geo.yToLat(centerY, z);
    }

    public double centerLon() {
        return Geo.xToLon(centerX, z);
    }

    public int zoom() {
        return z;
    }

    /** What is on screen: {north, west, south, east} in degrees. */
    public double[] bounds() {
        double hw = getWidth() / 2.0 / tilePx, hh = getHeight() / 2.0 / tilePx;
        return new double[]{Geo.yToLat(centerY - hh, z), Geo.xToLon(centerX - hw, z),
                Geo.yToLat(centerY + hh, z), Geo.xToLon(centerX + hw, z)};
    }

    /** Forget which tiles were missing (after a sync brought new ones) and draw again. */
    public void retryMissing() {
        synchronized (missing) {
            missing.clear();
        }
        invalidate();
    }

    /** Moves the view by screen pixels, like a drag (the map controls' arrows). */
    public void pan(float dxPx, float dyPx) {
        centerX += dxPx / tilePx;
        centerY += dyPx / tilePx;
        movedByHand = true;
        invalidate();
    }

    public void zoomBy(int dz, float fx, float fy) {
        int nz = Math.max(MIN_Z, Math.min(MAX_Z, z + dz));
        if (nz == z) return;
        // Keep the point under the finger still.
        double px = centerX + (fx - getWidth() / 2f) / tilePx;
        double py = centerY + (fy - getHeight() / 2f) / tilePx;
        double f = Math.pow(2, nz - z);
        px *= f;
        py *= f;
        centerX = px - (fx - getWidth() / 2f) / tilePx;
        centerY = py - (fy - getHeight() / 2f) / tilePx;
        z = nz;
        invalidate();
    }

    // ---- drawing ----

    private float sx(double tx) {
        return (float) ((tx - centerX) * tilePx + getWidth() / 2f);
    }

    private float sy(double ty) {
        return (float) ((ty - centerY) * tilePx + getHeight() / 2f);
    }

    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xff11181c);
        int n = 1 << z;
        int x0 = (int) Math.floor(centerX - getWidth() / 2f / tilePx), x1 = (int) Math.floor(centerX + getWidth() / 2f / tilePx);
        int y0 = (int) Math.floor(centerY - getHeight() / 2f / tilePx), y1 = (int) Math.floor(centerY + getHeight() / 2f / tilePx);
        int tiles = 0, blank = 0;
        for (int ty = Math.max(0, y0); ty <= Math.min(n - 1, y1); ty++) {
            for (int tx = x0; tx <= x1; tx++) {
                int wx = ((tx % n) + n) % n;
                dst.set(sx(tx), sy(ty), sx(tx + 1), sy(ty + 1));
                tiles++;
                if (!drawTile(c, 0, z, wx, ty)) {
                    blank++;
                    c.drawRect(dst.left, dst.top, dst.right, dst.top + 1, gridPaint);
                    c.drawRect(dst.left, dst.top, dst.left + 1, dst.bottom, gridPaint);
                }
                if (ovOn) drawTile(c, 1, z, wx, ty);
            }
        }
        boolean idle;
        synchronized (missing) {
            idle = queued.isEmpty();
        }
        if (onBlankArea != null && idle && tiles > 0 && blank * 2 >= tiles) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (onBlankArea != null) onBlankArea.onBlankArea();
                }
            });
        }
        float r = 8 * getResources().getDisplayMetrics().density;
        // Centre crosshair: "Add POI" drops a point here.
        float cxs = getWidth() / 2f, cys = getHeight() / 2f;
        c.drawLine(cxs - r, cys, cxs + r, cys, ringPaint);
        c.drawLine(cxs, cys - r, cxs, cys + r, ringPaint);
        for (Marker m : markers) {
            if (m.area != null) drawArea(c, m);
        }
        for (Marker m : markers) {
            float mx = sx(Geo.lonToX(m.lon, z)), my = sy(Geo.latToY(m.lat, z));
            if (m.shape != null) {
                drawShape(c, m, mx, my, r);
                continue;
            }
            if (m.aim != null) drawCone(c, m.aim, mx, my);
            if (m.thumb != null) {
                float h = r * 2.4f;
                float w = h * m.thumb.getWidth() / Math.max(1, m.thumb.getHeight());
                dst.set(mx - w, my - h, mx + w, my + h);
                c.drawBitmap(m.thumb, null, dst, tilePaint);
                c.drawRect(dst, ringPaint);
                c.drawText(m.label, mx + w + r * 0.6f, my + r * 0.5f, haloPaint);
                c.drawText(m.label, mx + w + r * 0.6f, my + r * 0.5f, textPaint);
                continue;
            }
            if (m.icon >= 0) {
                float s = m.sizePct / 100f;
                if (m.arrow) drawHeading(c, mx, my, r * 1.8f * s, m.rotation);
                if (m.rotation != 0) {
                    c.save();
                    c.rotate(m.rotation, mx, my);
                }
                PoiIcons.draw(c, m.icon, mx, my, r * 3.6f * s);
                if (m.rotation != 0) c.restore();
                c.drawText(m.label, mx + r * 2f * s, my + r * 0.5f, haloPaint);
                c.drawText(m.label, mx + r * 2f * s, my + r * 0.5f, textPaint);
                continue;
            }
            markerPaint.setColor(m.colour);
            c.drawCircle(mx, my, r, markerPaint);
            c.drawCircle(mx, my, r, ringPaint);
            c.drawText(m.label, mx + r * 1.4f, my + r * 0.5f, haloPaint);
            c.drawText(m.label, mx + r * 1.4f, my + r * 0.5f, textPaint);
        }
        float d = getResources().getDisplayMetrics().density;
        drawMe(c, d);
        drawCompass(c, d);
        drawCentreMe(c, d);
    }

    private final Paint headingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** A direction arrow from the icon's edge, the way the unit faces or moves. */
    private void drawHeading(Canvas c, float x, float y, float half, int deg) {
        float d = getResources().getDisplayMetrics().density;
        double a = Math.toRadians(deg);
        float ux = (float) Math.sin(a), uy = (float) -Math.cos(a);
        float x0 = x + ux * half, y0 = y + uy * half, len = half + 26 * d;
        float x1 = x + ux * len, y1 = y + uy * len;
        for (int pass = 0; pass < 2; pass++) {
            headingPaint.setStyle(Paint.Style.STROKE);
            headingPaint.setStrokeCap(Paint.Cap.ROUND);
            headingPaint.setStrokeWidth((pass == 0 ? 6 : 3) * d);
            headingPaint.setColor(pass == 0 ? 0xc0000000 : 0xffffeb3b);
            c.drawLine(x0, y0, x1, y1, headingPaint);
            float hx = 9 * d;
            c.drawLine(x1, y1, x1 - ux * hx + uy * hx * 0.7f, y1 - uy * hx - ux * hx * 0.7f, headingPaint);
            c.drawLine(x1, y1, x1 - ux * hx - uy * hx * 0.7f, y1 - uy * hx + ux * hx * 0.7f, headingPaint);
        }
    }

    private final Paint areaFill = new Paint(Paint.ANTI_ALIAS_FLAG), areaLine = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void drawArea(Canvas c, Marker m) {
        float d = getResources().getDisplayMetrics().density;
        areaFill.setStyle(Paint.Style.FILL);
        areaFill.setColor((m.colour & 0x00ffffff) | 0x38000000);
        areaLine.setStyle(Paint.Style.STROKE);
        areaLine.setStrokeWidth(2 * d);
        areaLine.setColor((m.colour & 0x00ffffff) | 0xd0000000);
        for (double[] r : m.area) {
            android.graphics.Path p = new android.graphics.Path();
            for (int i = 0; i + 1 < r.length; i += 2) {
                float x = sx(Geo.lonToX(r[i + 1], z)), y = sy(Geo.latToY(r[i], z));
                if (i == 0) p.moveTo(x, y);
                else p.lineTo(x, y);
            }
            p.close();
            c.drawPath(p, areaFill);
            c.drawPath(p, areaLine);
        }
    }

    // ---- top-right buttons: centre on me (the usual "my location" target) and the compass ----

    private float compassCx() {
        float d = getResources().getDisplayMetrics().density;
        return getWidth() - 22 * d - 8 * d;
    }

    /** Left of the compass (or in its place when there is no compass). */
    private float centreMeCx() {
        float d = getResources().getDisplayMetrics().density;
        return Float.isNaN(heading) ? compassCx() : compassCx() - 22 * d * 2 - 10 * d;
    }

    private void drawCentreMe(Canvas c, float d) {
        float r = 22 * d, cx = centreMeCx(), cy = r + 8 * d;
        mePaint.setStyle(Paint.Style.FILL);
        mePaint.setColor(0xb0000000);
        c.drawCircle(cx, cy, r, mePaint);
        c.drawCircle(cx, cy, r, ringPaint);
        // Target: a ring with four ticks and a dot, blue when there is a fix to go to.
        int col = Double.isNaN(meLat) ? 0xff9e9e9e : 0xff64b5f6;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f * d);
        p.setColor(col);
        c.drawCircle(cx, cy, r * 0.42f, p);
        c.drawLine(cx, cy - r * 0.72f, cx, cy - r * 0.42f, p);
        c.drawLine(cx, cy + r * 0.42f, cx, cy + r * 0.72f, p);
        c.drawLine(cx - r * 0.72f, cy, cx - r * 0.42f, cy, p);
        c.drawLine(cx + r * 0.42f, cy, cx + r * 0.72f, cy, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, r * 0.16f, p);
    }

    /** Taps on the two corner buttons; true if one was hit. */
    private boolean cornerTap(float x, float y) {
        float d = getResources().getDisplayMetrics().density;
        float r = 26 * d, cy = 22 * d + 8 * d;
        if (Math.hypot(x - centreMeCx(), y - cy) <= r) {
            if (Double.isNaN(meLat)) {
                android.widget.Toast.makeText(getContext(), meProblem != null ? meProblem : "No GPS fix yet",
                        android.widget.Toast.LENGTH_SHORT).show();
            } else {
                center(meLat, meLon, Math.max(z, 15));
            }
            return true;
        }
        if (!Float.isNaN(heading) && Math.hypot(x - compassCx(), y - cy) <= r) {
            android.content.Intent i = new android.content.Intent(getContext(), org.communitycomms.app.CompassActivity.class);
            if (movedByHand) { // the + / X was put somewhere: offer the bearing to it
                i.putExtra(org.communitycomms.app.CompassActivity.EXTRA_LAT, centerLat());
                i.putExtra(org.communitycomms.app.CompassActivity.EXTRA_LON, centerLon());
            }
            getContext().startActivity(i);
            return true;
        }
        return false;
    }

    /** Draws the tile, or a zoomed-in piece of an ancestor. Returns false if nothing at all. */
    private boolean drawTile(Canvas c, int layer, int tz, int tx, int ty) {
        for (int up = 0; up <= 5 && tz - up >= 0; up++) {
            int pz = tz - up, px = tx >> up, py = ty >> up;
            Bitmap b = cache.get(key(layer, pz, px, py));
            if (b == null) {
                if (up <= 1) request(layer, pz, px, py);
                continue;
            }
            int part = 1 << up;
            int w = b.getWidth() / part, h = b.getHeight() / part;
            int ox = (tx - (px << up)) * w, oy = (ty - (py << up)) * h;
            src.set(ox, oy, ox + w, oy + h);
            c.drawBitmap(b, src, dst, layer == 0 ? tilePaint : overlayPaint);
            return true;
        }
        return false;
    }

    private static String key(int layer, int z, int x, int y) {
        return (layer == 0 ? "" : "o") + z + "/" + x + "/" + y;
    }

    // ---- overlay layer (e.g. topographic over satellite), drawn see-through on top ----

    private boolean ovOn;
    private File ovOffline, ovCache;
    private String ovUrl;
    private final Paint overlayPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    /** Overlay sources (any may be null; all null = no overlay) and how see-through, 0-100 %. */
    public void setOverlay(File offlineFolder, String onlineUrlOrNull, File onlineCacheDir, int opacityPct) {
        ovOffline = offlineFolder;
        ovUrl = onlineUrlOrNull == null || onlineUrlOrNull.length() == 0 ? null : onlineUrlOrNull;
        ovCache = onlineCacheDir;
        ovOn = ovOffline != null || ovUrl != null || ovCache != null;
        overlayPaint.setAlpha(Math.max(0, Math.min(255, opacityPct * 255 / 100)));
        cache.evictAll();
        synchronized (missing) {
            missing.clear();
        }
        invalidate();
    }

    private void request(int layer, int tz, int tx, int ty) {
        String k = key(layer, tz, tx, ty);
        synchronized (missing) {
            if (missing.contains(k) || queued.contains(k)) return;
            queued.add(k);
        }
        queue.addFirst(new int[]{tz, tx, ty, layer}); // newest first: what is on screen now
    }

    // ---- loading ----

    private void loadLoop() {
        while (true) {
            int[] t;
            try {
                t = queue.takeFirst();
            } catch (InterruptedException e) {
                return;
            }
            String k = key(t[3], t[0], t[1], t[2]);
            Bitmap b = null;
            if (Math.abs(t[0] - z) <= 5) {
                boolean base = t[3] == 0;
                String url = base ? onlineUrl : ovUrl;
                File cacheDir = base ? onlineCache : ovCache;
                b = fromFolder(base ? offline : ovOffline, t);
                if (b == null) b = fromFolder(cacheDir, t);
                // The LAN storage location (shared maps cache) before going online.
                String lu = base ? lanBaseUrl : lanOvUrl;
                String src = lu == null || lu.length() == 0 ? null : LanTiles.sourceId(lu);
                if (b == null && lan != null && src != null) b = fromLan(src, cacheDir, t);
                // Then any named set in the LAN maps cache (e.g. "ATAK Home") before going online.
                if (b == null && base && lan != null) {
                    for (String set : namedSets) {
                        b = fromLan(set, cacheDir, t);
                        if (b != null) break;
                    }
                }
                if (b == null && url != null) b = fromNet(url, cacheDir, t);
            }
            boolean idle;
            synchronized (missing) {
                queued.remove(k);
                if (b == null && Math.abs(t[0] - z) <= 5) missing.add(k);
                idle = queued.isEmpty();
            }
            if (b != null) cache.put(k, b);
            if (b != null || idle) { // idle: redraw once so a blank area is noticed
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        invalidate();
                    }
                });
            }
        }
    }

    private static Bitmap fromFolder(File root, int[] t) {
        if (root == null) return null;
        String base = t[0] + File.separator + t[1] + File.separator + t[2];
        for (String ext : new String[]{".jpg", ".png", ".jpeg", ".webp"}) {
            File f = new File(root, base + ext);
            if (f.isFile()) return BitmapFactory.decodeFile(f.getPath());
        }
        return null;
    }

    private volatile LanTiles lan;
    /** LAN storage use since the app started: tiles read from it, given to it, and tries that failed (map label). */
    public static final java.util.concurrent.atomic.AtomicInteger lanGot = new java.util.concurrent.atomic.AtomicInteger(),
            lanSent = new java.util.concurrent.atomic.AtomicInteger(), lanFailed = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.ExecutorService lanUploads = java.util.concurrent.Executors.newSingleThreadExecutor();

    private volatile String lanBaseUrl, lanOvUrl;

    /**
     * The LAN storage location (null = none): read before going online (and while offline), and given
     * every online tile. The URLs name the tile sources even while online tiles are off.
     */
    /** Named tile sets found in the LAN maps cache (looked up when the LAN storage is set). */
    private volatile java.util.List<String> namedSets = new java.util.ArrayList<String>();

    public void setLan(LanTiles l, String baseTileUrl, String overlayTileUrl) {
        lan = l != null && l.configured() ? l : null;
        final LanTiles found = lan;
        if (found != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    java.util.List<String> sets = found.namedSets();
                    android.util.Log.i("TileMapView", "named LAN tile sets: " + sets);
                    namedSets = sets;
                    if (!sets.isEmpty()) {
                        synchronized (missing) {
                            missing.clear(); // they may hold tiles that were missing
                        }
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                invalidate();
                            }
                        });
                    }
                }
            }, "lan-sets").start();
        } else {
            namedSets = new java.util.ArrayList<String>();
        }
        lanBaseUrl = baseTileUrl;
        lanOvUrl = overlayTileUrl;
        synchronized (missing) {
            missing.clear(); // tiles missing before may be on the LAN
        }
        invalidate();
    }

    private Bitmap fromLan(String source, File cacheDir, int[] t) {
        byte[] data = lan.get(source, t[0], t[1], t[2]);
        if (data == null) return null;
        lanGot.incrementAndGet();
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (b != null && cacheDir != null) {
            try {
                File out = new File(cacheDir, t[0] + "/" + t[1] + "/" + t[2] + ".jpg");
                out.getParentFile().mkdirs();
                FileOutputStream os = new FileOutputStream(out);
                os.write(data);
                os.close();
            } catch (java.io.IOException ignored) {
            }
        }
        return b;
    }

    private Bitmap fromNet(String onlineUrl, File onlineCache, int[] t) {
        String u = onlineUrl.replace("{z}", String.valueOf(t[0])).replace("{x}", String.valueOf(t[1]))
                .replace("{y}", String.valueOf(t[2]));
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            Tls12SocketFactory.apply(c);
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            // OSM blocks some agent strings (an "offline" one got its "Access blocked" tile), so keep this plain.
            c.setRequestProperty("User-Agent", "YourCommunityCommunications/0.2 (Android; sideloaded field radio app)");
            if (c.getResponseCode() != 200) {
                android.util.Log.w("TileMapView", "tile HTTP " + c.getResponseCode() + " " + u);
                return null;
            }
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            byte[] data = bo.toByteArray();
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (b != null && onlineCache != null) {
                // Saved with .jpg so fromFolder finds it; PNG data in a .jpg name still decodes.
                File out = new File(onlineCache, t[0] + "/" + t[1] + "/" + t[2] + ".jpg");
                out.getParentFile().mkdirs();
                FileOutputStream os = new FileOutputStream(out);
                os.write(data);
                os.close();
            }
            final LanTiles l = lan;
            if (b != null && l != null && !onlineUrl.startsWith(l.base() + "/")) {
                // Copy it to the LAN storage location too (not when it came from there, e.g. a map
                // set kept on the LAN store and used as a custom URL), so other phones need not fetch it online.
                // Filed under the source it really came from: on Android 4.x that is Google's road
                // map, which must not land in the OpenStreetMap folder the phone reads from.
                final String src = LanTiles.sourceId(onlineUrl);
                final int[] tt = t.clone();
                final byte[] dd = data;
                lanUploads.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (l.put(src, tt[0], tt[1], tt[2], dd)) lanSent.incrementAndGet();
                        else lanFailed.incrementAndGet();
                    }
                });
            }
            return b;
        } catch (Exception e) {
            android.util.Log.w("TileMapView", "tile fetch failed: " + u + ": " + e);
            return null;
        }
    }

    // ---- touch ----

    private final Paint shapeFill = new Paint(Paint.ANTI_ALIAS_FLAG), shapeLine = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint coneFill = new Paint(Paint.ANTI_ALIAS_FLAG), coneLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF coneOval = new RectF();

    /** Which way a photo was taken: a see-through wedge as wide as the camera's view. */
    private void drawCone(Canvas c, float[] aim, float mx, float my) {
        float len = 90 * getResources().getDisplayMetrics().density;
        coneFill.setStyle(Paint.Style.FILL);
        coneFill.setColor(0x4400b0ff);
        coneLine.setStyle(Paint.Style.STROKE);
        coneLine.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);
        coneLine.setColor(0xcc00b0ff);
        coneOval.set(mx - len, my - len, mx + len, my + len);
        // Canvas angles start east and run clockwise; map bearings start north.
        float start = aim[0] - 90 - aim[1] / 2;
        c.drawArc(coneOval, start, aim[1], true, coneFill);
        c.drawArc(coneOval, start, aim[1], true, coneLine);
    }

    /** A map point drawn as a shape: rectangle, circle, oval, line, arrow or text box. */
    private void drawShape(Canvas c, Marker m, float mx, float my, float r) {
        Poi.Shape s = m.shape;
        float d = getResources().getDisplayMetrics().density;
        float x2 = sx(Geo.lonToX(s.lon2, z)), y2 = sy(Geo.latToY(s.lat2, z));
        shapeFill.setStyle(Paint.Style.FILL);
        shapeFill.setColor(s.bg);
        shapeLine.setStyle(Paint.Style.STROKE);
        shapeLine.setStrokeWidth(3 * d);
        shapeLine.setColor(s.fg);
        shapeLine.setStrokeCap(Paint.Cap.ROUND);
        float lx = mx, ly = my;
        switch (s.type) {
            case Poi.RECT:
            case Poi.OVAL: {
                float hw = Math.abs(x2 - mx), hh = Math.abs(y2 - my);
                android.graphics.RectF rf = new android.graphics.RectF(mx - hw, my - hh, mx + hw, my + hh);
                if (s.type == Poi.RECT) {
                    if ((s.bg >>> 24) != 0) c.drawRect(rf, shapeFill);
                    c.drawRect(rf, shapeLine);
                } else {
                    if ((s.bg >>> 24) != 0) c.drawOval(rf, shapeFill);
                    c.drawOval(rf, shapeLine);
                }
                lx = mx - hw;
                ly = my - hh - 4 * d;
                break;
            }
            case Poi.CIRCLE: {
                double mPerPx = 156543.03 * Math.cos(Math.toRadians(m.lat)) / (1 << z) * 256 / tilePx;
                float rad = (float) (s.radiusM / mPerPx);
                if ((s.bg >>> 24) != 0) c.drawCircle(mx, my, rad, shapeFill);
                c.drawCircle(mx, my, rad, shapeLine);
                lx = mx + rad * 0.72f;
                ly = my - rad * 0.72f;
                break;
            }
            case Poi.LINE:
            case Poi.ARROW: {
                c.drawLine(mx, my, x2, y2, shapeLine);
                if (s.type == Poi.ARROW) {
                    double a = Math.atan2(y2 - my, x2 - mx);
                    float hl = 14 * d;
                    c.drawLine(x2, y2, x2 - (float) (hl * Math.cos(a - 0.5)), y2 - (float) (hl * Math.sin(a - 0.5)), shapeLine);
                    c.drawLine(x2, y2, x2 - (float) (hl * Math.cos(a + 0.5)), y2 - (float) (hl * Math.sin(a + 0.5)), shapeLine);
                }
                lx = (mx + x2) / 2;
                ly = (my + y2) / 2 - 6 * d;
                break;
            }
            default: { // text box
                textPaint.setColor(s.fg);
                String[] lines = m.label.length() == 0 ? new String[]{"…"} : m.label.split(" / ");
                float w = 0;
                for (String l : lines) w = Math.max(w, textPaint.measureText(l));
                float lh = textPaint.getTextSize() * 1.25f, pad = 6 * d;
                android.graphics.RectF box = new android.graphics.RectF(mx - w / 2 - pad, my - lines.length * lh / 2 - pad,
                        mx + w / 2 + pad, my + lines.length * lh / 2 + pad);
                shapeFill.setColor((s.bg >>> 24) == 0 ? 0xcc101418 : s.bg | 0xc0000000);
                c.drawRoundRect(box, 4 * d, 4 * d, shapeFill);
                shapeLine.setStrokeWidth(1.5f * d);
                c.drawRoundRect(box, 4 * d, 4 * d, shapeLine);
                for (int i = 0; i < lines.length; i++) {
                    c.drawText(lines[i], mx - w / 2, box.top + pad + (i + 0.85f) * lh, textPaint);
                }
                textPaint.setColor(Color.WHITE);
                return;
            }
        }
        if (m.label.length() > 0) {
            c.drawText(m.label, lx, ly, haloPaint);
            c.drawText(m.label, lx, ly, textPaint);
        }
    }

    private Marker markerAt(float x, float y) {
        float best = 40 * getResources().getDisplayMetrics().density;
        Marker hit = null;
        for (Marker m : markers) {
            float dx = sx(Geo.lonToX(m.lon, z)) - x, dy = sy(Geo.latToY(m.lat, z)) - y;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < best) {
                best = d;
                hit = m;
            }
        }
        return hit;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        if (!scaler.isInProgress()) gestures.onTouchEvent(e);
        return true;
    }
}
