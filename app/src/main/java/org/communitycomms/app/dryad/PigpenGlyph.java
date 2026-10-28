package org.communitycomms.app.dryad;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** Draws one pigpen symbol into a box. */
public final class PigpenGlyph {
    private PigpenGlyph() {
    }

    public static void draw(Canvas c, int slot, RectF box, Paint line) {
        float inset = box.width() * 0.18f;
        float l = box.left + inset, t = box.top + inset, r = box.right - inset, b = box.bottom - inset;
        float cx = (l + r) / 2, cy = (t + b) / 2;
        int cell = Pigpen.cell(slot);
        if (Pigpen.isCentre(slot)) {
            // Plain X (classic space) or X with a dot in each triangle (classic end of line).
            c.drawLine(l, t, r, b, line);
            c.drawLine(r, t, l, b, line);
            if (slot == 27) {
                float off = (r - l) * 0.3f;
                Paint dot = dotPaint(line);
                float rad = Math.max(2f, line.getStrokeWidth() * 1.2f);
                c.drawCircle(cx, cy - off, rad, dot);
                c.drawCircle(cx + off, cy, rad, dot);
                c.drawCircle(cx, cy + off, rad, dot);
                c.drawCircle(cx - off, cy, rad, dot);
            }
            return;
        }
        if (Pigpen.isGrid(slot)) {
            int row = cell / 3, col = cell % 3;
            if (row > 0) c.drawLine(l, t, r, t, line);
            if (row < 2) c.drawLine(l, b, r, b, line);
            if (col > 0) c.drawLine(l, t, l, b, line);
            if (col < 2) c.drawLine(r, t, r, b, line);
        } else {
            switch (cell) {
                case 0: // top: V
                    c.drawLine(l, t, cx, b, line);
                    c.drawLine(cx, b, r, t, line);
                    break;
                case 1: // right: <
                    c.drawLine(r, t, l, cy, line);
                    c.drawLine(l, cy, r, b, line);
                    break;
                case 2: // bottom: ^
                    c.drawLine(l, b, cx, t, line);
                    c.drawLine(cx, t, r, b, line);
                    break;
                default: // left: >
                    c.drawLine(l, t, r, cy, line);
                    c.drawLine(r, cy, l, b, line);
                    break;
            }
        }
        if (Pigpen.hasDot(slot)) {
            float dx = cx, dy = cy;
            if (!Pigpen.isGrid(slot)) {
                // Put the dot inside the open side of the V so it reads clearly.
                float off = (r - l) * 0.22f;
                if (cell == 0) dy -= off;
                else if (cell == 1) dx += off;
                else if (cell == 2) dy += off;
                else dx -= off;
            }
            c.drawCircle(dx, dy, Math.max(2f, line.getStrokeWidth() * 1.2f), dotPaint(line));
        }
    }

    private static Paint dotPaint(Paint line) {
        Paint p = new Paint(line);
        p.setStyle(Paint.Style.FILL);
        return p;
    }
}
