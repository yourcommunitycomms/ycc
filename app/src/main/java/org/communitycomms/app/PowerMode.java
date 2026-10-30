package org.communitycomms.app;

import android.content.Context;

/**
 * Settings > Power and sleep. Normal; Power saver (slower check-ins and GPS, no background data); Ultra low
 * (with the screen off the radio sleeps and wakes every 4 minutes to check in); Offline (no network expected:
 * no remote prompts or sync, and GPS and check-ins rest while nobody is around and the screen is off).
 * After PTT, Power saver and Ultra low work as normal for a few minutes so a reply is heard.
 */
public final class PowerMode {
    private PowerMode() {
    }

    public static final int NORMAL = 0, SAVER = 1, ULTRA = 2, OFFLINE = 3;
    public static final long ULTRA_CHECK_IN_MS = 240000;
    private static final long SAVER_MS = 30000, OFFLINE_IDLE_MS = 60000, NORMAL_PRESENCE_MS = 5000, AFTER_PTT_MS = 180000;

    private static volatile int mode = NORMAL;
    private static volatile long awakeUntil;
    /** Kept up to date by RadioService. */
    static volatile boolean screenOn = true, alone, charging;

    static void load(Context c) {
        mode = new Prefs(c).powerMode();
    }

    public static int mode() {
        return mode;
    }

    public static void set(Context c, int m) {
        mode = m;
        new Prefs(c).setPowerMode(m);
        RadioService s = RadioService.instance();
        if (s != null) s.powerChanged();
    }

    /** PTT was pressed: normal for a few minutes, so any reply is heard. */
    static void pttUsed() {
        awakeUntil = System.currentTimeMillis() + AFTER_PTT_MS;
        RadioService s = RadioService.instance();
        if (s != null) s.powerChanged();
    }

    /** The mode in force now. */
    private static int now() {
        int m = mode;
        return (m == SAVER || m == ULTRA) && System.currentTimeMillis() < awakeUntil ? NORMAL : m;
    }

    /** Offline, nobody around and the screen off: nothing to keep up to date. */
    static boolean idle() {
        return now() == OFFLINE && alone && !screenOn;
    }

    /** Ultra low with the screen off: the radio rests between check-ins. */
    static boolean sleeping() {
        return now() == ULTRA && !screenOn;
    }

    /** The screen may sleep by itself in these modes, instead of being kept on. */
    static boolean letScreenSleep() {
        return mode == SAVER || mode == ULTRA;
    }

    static boolean offline() {
        return mode == OFFLINE;
    }

    static long presenceMs() {
        int m = now();
        return m == ULTRA ? ULTRA_CHECK_IN_MS : m == SAVER ? SAVER_MS : idle() ? OFFLINE_IDLE_MS : NORMAL_PRESENCE_MS;
    }

    /** With every presence, so others do not count this phone as lost between check-ins: tens of seconds, 0 = normal. */
    static int checkInByte() {
        long ms = presenceMs();
        return ms <= NORMAL_PRESENCE_MS ? 0 : (int) Math.min(255, ms / 10000);
    }

    static long locationMs(long normal) {
        int m = now();
        return m == ULTRA ? ULTRA_CHECK_IN_MS : m == SAVER ? Math.max(normal, SAVER_MS) : normal;
    }

    /** Aircraft, ships and alerts: with the screen on, except in Ultra low. */
    public static boolean feedsOn() {
        int m = now();
        return m == NORMAL || (m != ULTRA && screenOn);
    }

    /** How long nobody may be heard before warning: this phone hears less in a saving mode. */
    static long quietMs() {
        return mode == ULTRA ? 600000 : mode == SAVER ? 180000 : 60000;
    }

    static boolean lanSyncOn() {
        return now() == NORMAL;
    }
}
