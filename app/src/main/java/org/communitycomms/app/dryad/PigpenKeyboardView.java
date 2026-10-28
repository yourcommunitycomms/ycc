package org.communitycomms.app.dryad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Tap pad laid out the way pigpen is drawn: the plain # grid and the dotted # grid side by side,
 * then the plain X and the dotted X, each with its four arms around a centre key (plain X and
 * X-with-four-dots). Keys show symbols only; which letter they mean depends on the DRYAD row.
 */
public final class PigpenKeyboardView extends View {
    public interface OnSymbol {
        void onSymbol(int slot);
    }

    /** Pad is 7 key-widths across: 3 keys, a gap, 3 keys. Two 3-high blocks with a gap. */
    private static final int COLS = 7;
    private static final float ROWS = 6.5f;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keyBg = new Paint();
    private final Paint pressedBg = new Paint();
    private final RectF box = new RectF();
    private OnSymbol listener;
    private int pressed = -1;

    public PigpenKeyboardView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        line.setColor(Color.WHITE);
        line.setStrokeWidth(2.5f * d);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStyle(Paint.Style.STROKE);
        guide.setColor(0xff607d8b);
        guide.setStrokeWidth(2 * d);
        guide.setStyle(Paint.Style.STROKE);
        keyBg.setColor(0xff37474f);
        pressedBg.setColor(0xff00897b);
    }

    public void setOnSymbol(OnSymbol l) {
        listener = l;
    }

    private float k() {
        return getWidth() / (float) COLS;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, (int) (w / (float) COLS * ROWS));
    }

    /** Slot at grid position (col 0..6, row 0..5), or -1 for an empty spot. */
    private static int slotAt(int col, int row) {
        boolean right = col >= 4;
        int c = right ? col - 4 : col;
        if (col == 3 || c > 2) return -1;
        if (row < 3) return (right ? 9 : 0) + row * 3 + c; // # grids
        int r = row - 3;
        int base = right ? 22 : 18;                          // X arms: top, right, bottom, left
        if (r == 0 && c == 1) return base;
        if (r == 1 && c == 2) return base + 1;
        if (r == 2 && c == 1) return base + 2;
        if (r == 1 && c == 0) return base + 3;
        if (r == 1 && c == 1) return right ? 27 : 26;        // centres
        return -1;
    }

    private float rowTop(int row) {
        return (row < 3 ? row : row + 0.5f) * k();
    }

    private void keyBox(int col, int row) {
        float k = k();
        float gap = k * 0.06f;
        float top = rowTop(row);
        box.set(col * k + gap, top + gap, (col + 1) * k - gap, top + k - gap);
    }

    @Override
    protected void onDraw(Canvas c) {
        float k = k();
        // Faint figure outlines behind the keys so the pad reads as # # and X X.
        for (int side = 0; side < 2; side++) {
            float x0 = side * 4 * k;
            for (int i = 1; i < 3; i++) {
                c.drawLine(x0 + i * k, 0, x0 + i * k, 3 * k, guide);
                c.drawLine(x0, i * k, x0 + 3 * k, i * k, guide);
            }
            float y0 = rowTop(3);
            c.drawLine(x0, y0, x0 + 3 * k, y0 + 3 * k, guide);
            c.drawLine(x0 + 3 * k, y0, x0, y0 + 3 * k, guide);
        }
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < COLS; col++) {
                int slot = slotAt(col, row);
                if (slot < 0) continue;
                keyBox(col, row);
                c.drawRoundRect(box, 8, 8, slot == pressed ? pressedBg : keyBg);
                PigpenGlyph.draw(c, slot, box, line);
            }
        }
    }

    private int slotAtPoint(float x, float y) {
        float k = k();
        int col = (int) (x / k);
        int row;
        if (y < 3 * k) row = (int) (y / k);
        else if (y < 3.5f * k) return -1;
        else row = 3 + (int) ((y - 3.5f * k) / k);
        if (col < 0 || col >= COLS || row < 0 || row > 5) return -1;
        return slotAt(col, row);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int slot = slotAtPoint(e.getX(), e.getY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = slot;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (slot >= 0 && slot == pressed && listener != null) listener.onSymbol(slot);
                pressed = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                invalidate();
                return true;
        }
        return true;
    }
}
