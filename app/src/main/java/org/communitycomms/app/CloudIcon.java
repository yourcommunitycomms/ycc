package org.communitycomms.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/** A small cloud drawn on a canvas (the Cloud group's short label), so it looks the same on Android 4.1. */
final class CloudIcon {
    private CloudIcon() {
    }

    static Drawable drawable(Context c, int heightPx, int colour) {
        int w = heightPx * 3 / 2, h = heightPx;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(colour);
        float base = h * 0.86f;
        cv.drawRoundRect(new RectF(w * 0.08f, h * 0.48f, w * 0.92f, base), h * 0.19f, h * 0.19f, p); // flat bottom
        cv.drawCircle(w * 0.32f, h * 0.56f, h * 0.22f, p); // left puff
        cv.drawCircle(w * 0.56f, h * 0.42f, h * 0.30f, p); // big top puff
        cv.drawCircle(w * 0.76f, h * 0.60f, h * 0.18f, p); // right puff
        BitmapDrawable d = new BitmapDrawable(c.getResources(), b);
        d.setBounds(0, 0, w, h);
        return d;
    }
}
