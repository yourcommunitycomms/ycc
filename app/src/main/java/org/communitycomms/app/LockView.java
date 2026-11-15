package org.communitycomms.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

/** A small padlock drawn on a canvas (closed or open), so it looks the same on Android 4.1 as on new phones. */
public final class LockView extends View {
    private boolean open;

    public LockView(Context c) {
        super(c);
        setClickable(true);
        setFocusable(true);
        setContentDescription("Protected entries: locked");
    }

    public void setOpen(boolean o) {
        open = o;
        setContentDescription(o ? "Protected entries: unlocked (tap to lock)" : "Protected entries: locked (tap to unlock)");
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int s = Math.min(getWidth(), getHeight());
        canvas.save();
        canvas.translate((getWidth() - s) / 2f, (getHeight() - s) / 2f);
        draw(canvas, s, open, open ? 0xff81c784 : 0xfff0c75e);
        canvas.restore();
    }

    static void draw(Canvas c, int s, boolean open, int colour) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(colour);
        float body = s * 0.62f, left = (s - body) / 2f, top = s * 0.45f;
        c.drawRoundRect(new RectF(left, top, left + body, s * 0.92f), s * 0.06f, s * 0.06f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.09f);
        float sw = body * 0.62f, sl = (s - sw) / 2f, lift = open ? s * 0.14f : 0;
        RectF arc = new RectF(sl, s * 0.12f - lift, sl + sw, s * 0.12f + sw - lift);
        c.drawArc(arc, 180, 180, false, p);
        float mid = s * 0.12f + sw / 2f - lift;
        c.drawLine(sl + sw, mid, sl + sw, top, p); // right leg always down
        if (!open) c.drawLine(sl, mid, sl, top, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xff0f1f16);
        c.drawCircle(s / 2f, top + (s * 0.92f - top) * 0.45f, s * 0.07f, p); // keyhole
    }

    /** A little closed padlock to put beside protected items in a list. */
    static Drawable badge(Context ctx, int sizePx) {
        Bitmap b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        draw(new Canvas(b), sizePx, false, 0xff90caf9);
        BitmapDrawable d = new BitmapDrawable(ctx.getResources(), b);
        d.setBounds(0, 0, sizePx, sizePx);
        return d;
    }
}
