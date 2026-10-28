package org.communitycomms.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

/**
 * A small drawn icon with a short word under it, for the full map's buttons (they used to be text
 * buttons that ran off the bottom of the screen). Long press says what it does. Lit (gold) when on.
 */
final class MapIconButton extends View {
    static final int ME = 0, EVERYONE = 1, ADD = 2, SYNC = 3, FILTER = 4, PAD = 5, FAKE = 6, TILES = 7, SHARE = 8, ASK = 9;

    private final int kind;
    private String word;
    private final String help;
    private boolean lit, pressed;
    private final float d;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    MapIconButton(Context c, int kind, String word, String help, final Runnable action) {
        super(c);
        this.kind = kind;
        this.word = word;
        this.help = help;
        d = c.getResources().getDisplayMetrics().density;
        setContentDescription(help);
        setClickable(true);
        setFocusable(true);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(11 * d);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Toast.makeText(getContext(), MapIconButton.this.help, Toast.LENGTH_SHORT).show();
                return true;
            }
        });
    }

    void setWord(String w) {
        word = w;
        invalidate();
    }

    void setLit(boolean on) {
        lit = on;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(resolveSize((int) (48 * d), w), resolveSize((int) (54 * d), h));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) pressed = true;
        else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) pressed = false;
        invalidate();
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), m = 2 * d;
        r.set(m, m, w - m, h - m);
        bg.setColor(pressed || isFocused() ? 0xff6d6d6d : 0xff4a4a4a);
        c.drawRoundRect(r, 6 * d, 6 * d, bg);
        int col = lit ? 0xfff0c75e : 0xffffffff;
        line.setColor(col);
        fill.setColor(col);
        text.setColor(lit ? 0xfff0c75e : 0xffdddddd);
        line.setStrokeWidth(2.2f * d);
        float cx = w / 2, cy = (h - 13 * d) / 2 + 2 * d, s = Math.min(w, h - 14 * d) * 0.36f;
        draw(c, cx, cy, s);
        c.drawText(word, cx, h - 6 * d, text);
    }

    private void draw(Canvas c, float x, float y, float s) {
        switch (kind) {
            case ME: // a ring with a dot and cross hairs
                c.drawCircle(x, y, s * 0.62f, line);
                c.drawLine(x, y - s, x, y - s * 0.62f, line);
                c.drawLine(x, y + s * 0.62f, x, y + s, line);
                c.drawLine(x - s, y, x - s * 0.62f, y, line);
                c.drawLine(x + s * 0.62f, y, x + s, y, line);
                c.drawCircle(x, y, s * 0.2f, fill);
                break;
            case EVERYONE: // two heads in a circle
                c.drawCircle(x, y, s, line);
                for (int i = -1; i <= 1; i += 2) {
                    float hx = x + i * s * 0.33f;
                    c.drawCircle(hx, y - s * 0.22f, s * 0.2f, fill);
                    r.set(hx - s * 0.32f, y + s * 0.05f, hx + s * 0.32f, y + s * 0.7f);
                    c.drawArc(r, 180, 180, true, fill);
                }
                break;
            case ADD: // a map pin with a plus
                Path p = new Path();
                p.moveTo(x, y + s);
                p.cubicTo(x - s * 1.1f, y - s * 0.1f, x - s * 0.6f, y - s, x, y - s);
                p.cubicTo(x + s * 0.6f, y - s, x + s * 1.1f, y - s * 0.1f, x, y + s);
                c.drawPath(p, line);
                c.drawLine(x - s * 0.3f, y - s * 0.35f, x + s * 0.3f, y - s * 0.35f, line);
                c.drawLine(x, y - s * 0.65f, x, y - s * 0.05f, line);
                break;
            case SYNC: // circular arrows (refresh)
                r.set(x - s * 0.8f, y - s * 0.8f, x + s * 0.8f, y + s * 0.8f);
                c.drawArc(r, 200, 140, false, line);
                c.drawArc(r, 20, 140, false, line);
                arrowHead(c, x + s * 0.8f * (float) Math.cos(Math.toRadians(340)), y + s * 0.8f * (float) Math.sin(Math.toRadians(340)), 70, s);
                arrowHead(c, x + s * 0.8f * (float) Math.cos(Math.toRadians(160)), y + s * 0.8f * (float) Math.sin(Math.toRadians(160)), 250, s);
                break;
            case FILTER: // a funnel
                Path f = new Path();
                f.moveTo(x - s, y - s * 0.8f);
                f.lineTo(x + s, y - s * 0.8f);
                f.lineTo(x + s * 0.2f, y + s * 0.1f);
                f.lineTo(x + s * 0.2f, y + s * 0.85f);
                f.lineTo(x - s * 0.2f, y + s * 0.6f);
                f.lineTo(x - s * 0.2f, y + s * 0.1f);
                f.close();
                c.drawPath(f, line);
                break;
            case PAD: // four arrows round a dot
                c.drawCircle(x, y, s * 0.15f, fill);
                for (int a = 0; a < 360; a += 90) {
                    double rad = Math.toRadians(a);
                    float ux = (float) Math.sin(rad), uy = (float) -Math.cos(rad);
                    float tx = x + ux * s, ty = y + uy * s;
                    Path t = new Path();
                    t.moveTo(tx, ty);
                    t.lineTo(tx - ux * s * 0.45f + uy * s * 0.32f, ty - uy * s * 0.45f - ux * s * 0.32f);
                    t.lineTo(tx - ux * s * 0.45f - uy * s * 0.32f, ty - uy * s * 0.45f + ux * s * 0.32f);
                    t.close();
                    c.drawPath(t, fill);
                }
                break;
            case FAKE: // a position pin with a question mark
                c.drawCircle(x, y - s * 0.2f, s * 0.7f, line);
                c.drawLine(x - s * 0.45f, y + s * 0.35f, x, y + s, line);
                c.drawLine(x + s * 0.45f, y + s * 0.35f, x, y + s, line);
                Paint q = new Paint(text);
                q.setTextSize(s * 1.1f);
                q.setColor(line.getColor());
                q.setFakeBoldText(true);
                c.drawText("?", x, y + s * 0.18f, q);
                break;
            case SHARE: // a dot sending out waves
                c.drawCircle(x - s * 0.45f, y + s * 0.3f, s * 0.22f, fill);
                for (int i = 1; i <= 2; i++) {
                    float rr = s * 0.55f * i;
                    r.set(x - s * 0.45f - rr, y + s * 0.3f - rr, x - s * 0.45f + rr, y + s * 0.3f + rr);
                    c.drawArc(r, 280, 80, false, line);
                }
                break;
            case ASK: // a dot with waves coming in and a question mark
                c.drawCircle(x + s * 0.45f, y + s * 0.3f, s * 0.22f, fill);
                for (int i = 1; i <= 2; i++) {
                    float rr = s * 0.55f * i;
                    r.set(x + s * 0.45f - rr, y + s * 0.3f - rr, x + s * 0.45f + rr, y + s * 0.3f + rr);
                    c.drawArc(r, 180, 80, false, line);
                }
                Paint qq = new Paint(text);
                qq.setTextSize(s * 0.95f);
                qq.setColor(line.getColor());
                qq.setFakeBoldText(true);
                c.drawText("?", x + s * 0.55f, y - s * 0.25f, qq);
                break;
            default: // TILES: a grid square with a download arrow
                c.drawRect(x - s, y - s * 0.5f, x + s * 0.2f, y + s * 0.7f, line);
                c.drawLine(x - s * 0.4f, y - s * 0.5f, x - s * 0.4f, y + s * 0.7f, line);
                c.drawLine(x - s, y + s * 0.1f, x + s * 0.2f, y + s * 0.1f, line);
                c.drawLine(x + s * 0.65f, y - s, x + s * 0.65f, y + s * 0.3f, line);
                arrowHead(c, x + s * 0.65f, y + s * 0.45f, 180, s);
                break;
        }
    }

    /** A small filled arrowhead at (x, y) pointing along bearing deg (0 = up). */
    private void arrowHead(Canvas c, float x, float y, float deg, float s) {
        double a = Math.toRadians(deg);
        float ux = (float) Math.sin(a), uy = (float) -Math.cos(a), k = s * 0.32f;
        Path t = new Path();
        t.moveTo(x + ux * k, y + uy * k);
        t.lineTo(x - ux * k + uy * k, y - uy * k - ux * k);
        t.lineTo(x - ux * k - uy * k, y - uy * k + ux * k);
        t.close();
        c.drawPath(t, fill);
    }
}
