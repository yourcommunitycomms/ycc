package org.communitycomms.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.List;
import java.util.Locale;

/**
 * Mapping walk live graph: the last 30 seconds, one slot per second (right = now). Each probe is a bar in the second it
 * was sent: green answered fast, amber answered slowly (over 500 ms; taller = slower, up to 2 s), red lost (full height),
 * grey still waiting. A line of counts on top. Redraws itself every half second while it is on screen.
 */
public final class WalkGraph extends View {
    static final int SECONDS = 30;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    public WalkGraph(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        txt.setColor(0xffffffff);
        txt.setTextSize(12 * d);
        setBackgroundColor(0xe0102027);
    }

    private final Runnable again = new Runnable() {
        @Override
        public void run() {
            invalidate();
            if (getWindowToken() != null && getVisibility() == VISIBLE) postDelayed(this, 500);
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(again);
        post(again);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(again);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis);
        removeCallbacks(again);
        if (vis == VISIBLE) post(again);
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (78 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.currentTimeMillis();
        List<long[]> r = MeshWalk.recent(now - SECONDS * 1000L);
        int ok = 0, lost = 0, wait = 0, slow = 0;
        long sum = 0, max = 0;
        float w = getWidth(), h = getHeight(), top = 32 * d, base = h - 4 * d, slot = w / SECONDS;
        // grid: a faint tick every 5 s
        p.setColor(0x33ffffff);
        for (int s = 0; s <= SECONDS; s += 5) c.drawRect(w - s * slot - 0.5f * d, top, w - s * slot + 0.5f * d, base, p);
        for (long[] x : r) {
            float age = (now - x[0]) / 1000f;
            float right = w - age * slot, left = right - slot + 1.5f * d;
            float fh;
            if (x[1] == MeshWalk.R_LOST) {
                lost++;
                p.setColor(0xffe53935);
                fh = 1f;
            } else if (x[1] == MeshWalk.R_WAIT) {
                wait++;
                p.setColor(0xff78909c);
                fh = 0.15f;
            } else {
                ok++;
                sum += x[1];
                max = Math.max(max, x[1]);
                boolean s = x[1] > 500;
                if (s) slow++;
                p.setColor(s ? 0xffffb300 : 0xff43a047);
                fh = s ? 0.5f + 0.5f * Math.min(1f, (x[1] - 500) / 1500f) : 0.45f;
            }
            c.drawRect(Math.max(0, left), base - fh * (base - top), Math.max(0, right), base, p);
        }
        int done = ok + lost;
        String line = String.format(Locale.US, "Last %d s: %d answered, %d lost%s", SECONDS, ok, lost,
                done == 0 ? "" : " (" + Math.round(100.0 * lost / done) + "%)");
        String line2 = ok > 0 ? "Round trip " + (sum / ok) + " ms avg, " + max + " ms max" + (slow > 0 ? " · " + slow + " slow" : "") : "";
        if (r.isEmpty()) line = "Last " + SECONDS + " s: no probes yet";
        c.drawText(line, 6 * d, 13 * d, txt);
        if (line2.length() > 0) c.drawText(line2, 6 * d, 27 * d, txt);
    }
}
