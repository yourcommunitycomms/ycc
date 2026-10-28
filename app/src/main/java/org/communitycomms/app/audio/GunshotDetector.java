package org.communitycomms.app.audio;

/**
 * UNTESTED (*): flags "possible gunshots" from the microphone. A phone mic cannot really tell a
 * gunshot from a slammed door, so this only looks for a pattern: a sudden bang loud enough to clip
 * the mic (from near-quiet to full scale within one 40 ms frame), three or more times within
 * 10 seconds. After an alarm it stays quiet for a minute. Off by default.
 */
public final class GunshotDetector {
    public interface Alarm {
        /** Called from the capture thread. */
        void onPossibleGunshots(int bangs);
    }

    /** A sample at or beyond this counts as clipped. */
    private static final int CLIP = 32000;
    /** Clipped samples in one frame needed to call it a bang (one stray sample is not enough). */
    private static final int MIN_CLIPPED = 3;
    /** The frame before must be this many times quieter (a bang starts suddenly; shouting builds). */
    private static final double RISE = 4.0;
    private static final long WINDOW_MS = 10000, GAP_MS = 150, COOLDOWN_MS = 60000;
    private static final int BANGS = 3;

    private final Alarm alarm;
    private final long[] bangs = new long[BANGS];
    private int count;
    private long lastBangMs, quietUntil;
    private double prevRms;

    public GunshotDetector(Alarm alarm) {
        this.alarm = alarm;
    }

    public void frame(short[] pcm, int n) {
        int clipped = 0;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            int v = pcm[i];
            if (v >= CLIP || v <= -CLIP) clipped++;
            sum += (double) v * v;
        }
        double rms = Math.sqrt(sum / Math.max(1, n));
        boolean sudden = prevRms * RISE < rms;
        prevRms = rms;
        long now = System.currentTimeMillis();
        if (clipped < MIN_CLIPPED || !sudden || now < quietUntil || now - lastBangMs < GAP_MS) return;
        lastBangMs = now;
        bangs[count % BANGS] = now;
        count++;
        if (count >= BANGS && now - bangs[(count - BANGS) % BANGS] <= WINDOW_MS) {
            int heard = count;
            count = 0;
            quietUntil = now + COOLDOWN_MS;
            alarm.onPossibleGunshots(Math.min(heard, 99));
        }
    }
}
