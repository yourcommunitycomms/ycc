package org.communitycomms.app;

import java.security.MessageDigest;

/**
 * Shared timing for the two windowed sleeps (Power and sleep).
 * <p>
 * Radio silence check-in windows: worked out from the channel name and the clock, so every phone on the
 * channel knows when the next window is, but a listener without the key cannot predict it. Windows come
 * about every period (for example 60 minutes, landing anywhere from 50 to 70), each open for the window
 * length. Outside them the app sends nothing at all.
 * <p>
 * Burst sleep windows: when every phone heard on the channel is in burst mode, nobody needs live audio,
 * so the phones wake together for a few seconds every 30 seconds (lined up to the clock) and sleep in
 * between; bursts recorded meanwhile go out at the next window.
 */
final class CheckInWindows {
    private CheckInWindows() {
    }

    // ---- radio silence windows ----

    /** Start of the window for period number k: k * period, moved by up to a sixth of the period either way. */
    static long start(String channel, long periodMs, long k) {
        long sixth = periodMs / 6;
        long jitter = sixth <= 0 ? 0 : floorMod(hash(channel + "|win|" + periodMs + "|" + k), 2 * sixth) - sixth;
        return k * periodMs + jitter;
    }

    /** The window open now (its start), or -1. */
    static long openNow(String channel, long periodMs, long lenMs, long now) {
        long k = now / periodMs;
        for (long j = k - 1; j <= k + 1; j++) {
            long s = start(channel, periodMs, j);
            if (now >= s && now < s + lenMs) return s;
        }
        return -1;
    }

    /** Start of the next window after now. */
    static long next(String channel, long periodMs, long now) {
        long k = now / periodMs;
        for (long j = k - 1; j <= k + 2; j++) {
            long s = start(channel, periodMs, j);
            if (s > now) return s;
        }
        return now + periodMs;
    }

    /** Start of the window before the one starting at s (for "missed two windows"). */
    static long previous(String channel, long periodMs, long s) {
        long k = Math.round((double) s / periodMs);
        for (long j = k; j >= k - 2; j--) {
            long p = start(channel, periodMs, j);
            if (p < s) return p;
        }
        return s - periodMs;
    }

    // ---- burst sleep windows ----

    static final long BURST_CYCLE_MS = 30000, BURST_AWAKE_MS = 5000;

    static boolean burstAwake(long now) {
        return floorMod(now, BURST_CYCLE_MS) < BURST_AWAKE_MS;
    }

    static long burstNext(long now) {
        return now - floorMod(now, BURST_CYCLE_MS) + BURST_CYCLE_MS;
    }

    /** Math.floorMod is Android 7+ only. */
    private static long floorMod(long a, long b) {
        long m = a % b;
        return m < 0 ? m + b : m;
    }

    private static long hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (d[i] & 0xff);
            return v;
        } catch (Exception e) {
            return s.hashCode();
        }
    }
}
