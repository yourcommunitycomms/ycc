package org.communitycomms.app.dryad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows a message as pigpen symbols, wrapping onto new lines. Tokens are slots (0–25) or
 * literal characters (stored as -(char) - 1), so spaces and digits pass through.
 */
public final class PigpenStripView extends View {
    private final List<Integer> tokens = new ArrayList<Integer>();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float cell;
    private final RectF box = new RectF();

    public PigpenStripView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        cell = 36 * d;
        line.setColor(Color.WHITE);
        line.setStrokeWidth(2.5f * d);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStyle(Paint.Style.STROKE);
        text.setColor(Color.WHITE);
        text.setTextSize(20 * d);
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void setTokens(List<Integer> t) {
        tokens.clear();
        tokens.addAll(t);
        requestLayout();
        invalidate();
    }

    public static int literal(char ch) {
        return -ch - 1;
    }

    private int perLine(int width) {
        return Math.max(1, (int) (width / cell));
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int lines = Math.max(1, (tokens.size() + perLine(w) - 1) / perLine(w));
        setMeasuredDimension(w, (int) (lines * cell) + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = perLine(getWidth());
        for (int i = 0; i < tokens.size(); i++) {
            float x = (i % n) * cell;
            float y = (i / n) * cell + getPaddingTop();
            box.set(x, y, x + cell, y + cell);
            int t = tokens.get(i);
            if (t >= 0) {
                PigpenGlyph.draw(c, t, box, line);
            } else {
                char ch = (char) (-t - 1);
                c.drawText(String.valueOf(ch), box.centerX(), box.centerY() + text.getTextSize() / 3, text);
            }
        }
    }
}
