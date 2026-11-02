package org.communitycomms.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Small white icons for the buttons over the map: stacked layers, and an eye (crossed out when hiding). */
final class MapGlyph extends Drawable {
    static final int LAYERS = 0, EYE = 1, EYE_OFF = 2;
    private final int kind, size;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    MapGlyph(Context c, int kind) {
        this.kind = kind;
        float d = c.getResources().getDisplayMetrics().density;
        size = (int) (20 * d);
        paint.setColor(0xffffffff);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f * d);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public void draw(Canvas c) {
        float s = size, x = getBounds().left, y = getBounds().top;
        if (kind == LAYERS) {
            for (int i = 2; i >= 0; i--) { // a flat diamond for each layer, the top one furthest up
                float top = y + s * (0.08f + 0.2f * i);
                Path p = new Path();
                p.moveTo(x + s * 0.5f, top);
                p.lineTo(x + s * 0.95f, top + s * 0.24f);
                p.lineTo(x + s * 0.5f, top + s * 0.48f);
                p.lineTo(x + s * 0.05f, top + s * 0.24f);
                p.close();
                c.drawPath(p, paint);
            }
            return;
        }
        Path p = new Path();
        p.moveTo(x + s * 0.03f, y + s * 0.5f);
        p.quadTo(x + s * 0.5f, y - s * 0.02f, x + s * 0.97f, y + s * 0.5f);
        p.quadTo(x + s * 0.5f, y + s * 1.02f, x + s * 0.03f, y + s * 0.5f);
        p.close();
        c.drawPath(p, paint);
        c.drawCircle(x + s * 0.5f, y + s * 0.5f, s * 0.14f, paint);
        if (kind == EYE_OFF) c.drawLine(x + s * 0.12f, y + s * 0.9f, x + s * 0.88f, y + s * 0.1f, paint);
    }

    @Override
    public void setAlpha(int a) {
        paint.setAlpha(a);
    }

    @Override
    public void setColorFilter(ColorFilter f) {
        paint.setColorFilter(f);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
