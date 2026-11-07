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
    private static final long SAVER_MS = 30000, OFFLINE_IDLE_MS = 60000, NORMAL_PRESENCE_MS = 5000, AFTER_PTT_MS = 180000;

    private static volatile int mode = NORMAL;
    /** Last voice heard or sent on any channel (RadioEngine), for adaptive presence and the Wi-Fi lock. */
    public static volatile long lastVoiceMs = System.currentTimeMillis();
    /** Settings > Power and sleep > Deep sleep (experimental): the CPU sleeps between packets right now. */
    static volatile boolean deepSleeping;
    static final long BUSY_MS = 120000, QUIET_MS = 600000, QUIET_PRESENCE_MS = 60000;

    /** Busy: voice in the last 2 minutes, the screen on, talking or a mission. Wi-Fi then keeps low latency. */
    static boolean busy() {
        return screenOn || missionMine() || anchor || System.currentTimeMillis() - lastVoiceMs < BUSY_MS;
    }
    private static volatile long awakeUntil;
    /** Kept up to date by RadioService. */
    static volatile boolean screenOn = true, alone, charging, fullOnCharge;

    private static volatile long maxSleepMs = 300000, missionUntil;
    /** Mission mode heard from another phone on the channel: until when, by whom, and when last heard. */
    static volatile long othersMissionUntil, othersMissionHeard;
    static volatile String othersMissionBy = "";
    /** A mission phone not heard for this long no longer keeps the others awake (lost or flat). */
    static final long MISSION_LOST_MS = 600000;

    static void load(Context c) {
        Prefs p = new Prefs(c);
        mode = p.powerMode();
        fullOnCharge = p.gpsFullOnCharge();
        maxSleepMs = p.maxSleepMin() * 60000L;
        missionUntil = p.missionUntil();
        anchor = p.anchor();
    }

    /** Settings > Power and sleep > Max sleep time: the Ultra low check-in interval (and later sleeps). */
    public static long ultraCheckInMs() {
        return sleepMs();
    }

    /** The sleep actually used: the max sleep time, but at most 10 minutes when no anchor is around to hold what is missed. */
    static long sleepMs() {
        return anchor || anchorHeard ? maxSleepMs : Math.min(maxSleepMs, NO_ANCHOR_MAX_MS);
    }

    // ---- "I'm on mains power" anchor: stays awake and holds a copy of everything for sleepers ----

    /** This phone is an anchor (Settings > Power and sleep). */
    static volatile boolean anchor;
    /** An anchor was heard on the channel in the last 10 minutes (RadioService keeps it up to date). */
    static volatile boolean anchorHeard;
    static final long NO_ANCHOR_MAX_MS = 600000;

    public static void setAnchor(Context c, boolean on) {
        anchor = on;
        new Prefs(c).setAnchor(on);
        powerNudge();
        RadioService s = RadioService.instance();
        if (s != null) s.missionChanged(); // presence carries the anchor flag: send it now
    }

    public static void setMaxSleep(Context c, int min) {
        new Prefs(c).setMaxSleepMin(min);
        maxSleepMs = new Prefs(c).maxSleepMin() * 60000L;
        powerNudge();
    }

    // ---- Mission mode (life or death): flat out on this phone, and the others on the channel stay awake ----

    public static boolean missionMine() {
        return missionUntil > System.currentTimeMillis();
    }

    public static long missionUntil() {
        return missionMine() ? missionUntil : 0;
    }

    /** Turns Mission mode on until a time (0 = off). */
    public static void setMission(Context c, long until) {
        missionUntil = until;
        new Prefs(c).setMissionUntil(until);
        RadioService s = RadioService.instance();
        if (s != null) {
            s.powerChanged();
            s.missionChanged();
        }
    }

    /** Another phone on the channel is in Mission mode, and was heard in the last 10 minutes. */
    public static boolean missionOthers() {
        long now = System.currentTimeMillis();
        return othersMissionUntil > now && now - othersMissionHeard < MISSION_LOST_MS;
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
        if (missionMine() || anchor) return NORMAL;
        // Away: the phone sleeps like Ultra low and checks in by itself (others are told when).
        if (m != OFFLINE && RadioEngine.myStatus == RadioEngine.STATUS_AWAY) m = ULTRA;
        if ((m == SAVER || m == ULTRA) && System.currentTimeMillis() < awakeUntil) return NORMAL;
        if (m == ULTRA && missionOthers()) return SAVER; // someone's mission: awake, checking in every 30 s
        return m;
    }

    /** Offline, nobody around and the screen off: nothing to keep up to date. */
    static boolean idle() {
        return now() == OFFLINE && alone && !screenOn;
    }

    /** Ultra low with the screen off: the radio rests between check-ins. */
    static boolean sleeping() {
        return (now() == ULTRA || outsideWindow || burstNapping) && !screenOn;
    }

    /** Radio silence check-in windows: between windows (nothing is sent; the phone sleeps). */
    public static volatile boolean outsideWindow;
    /** Radio silence windows on: about how often this phone is heard (period + window), else 0. */
    static volatile long windowPeriodMs;
    /** Burst sleep windows: asleep between the 5 second windows every 30 seconds. */
    public static volatile boolean burstNapping;

    /** The screen may sleep by itself in these modes, instead of being kept on. */
    static boolean letScreenSleep() {
        return mode == SAVER || mode == ULTRA;
    }

    /** Power and sleep: GPS at its fastest while on charge. Off by default. */
    public static void setFullOnCharge(Context c, boolean on) {
        fullOnCharge = on;
        new Prefs(c).setGpsFullOnCharge(on);
        powerNudge();
    }

    private static void powerNudge() {
        RadioService s = RadioService.instance();
        if (s != null) s.powerChanged();
    }

    /** A GPS interval: the saving one, or the full one on charge when that is ticked. */
    public static long gpsMs(long full, long saving) {
        return (fullOnCharge && charging) || missionMine() ? full : saving;
    }

    static boolean offline() {
        return mode == OFFLINE;
    }

    static long presenceMs() {
        int m = now();
        if (windowPeriodMs > 0) return windowPeriodMs; // radio silence windows: heard once a window
        if (m == ULTRA) return sleepMs();
        if (m == SAVER) return SAVER_MS;
        if (idle()) return OFFLINE_IDLE_MS;
        // Normal: every 5 s while the channel is busy or someone is looking, slower as it stays quiet.
        long quiet = System.currentTimeMillis() - lastVoiceMs;
        if (missionMine() || screenOn || quiet < BUSY_MS) return NORMAL_PRESENCE_MS;
        if (missionOthers()) return SAVER_MS;
        return deepSleeping || quiet > QUIET_MS ? QUIET_PRESENCE_MS : SAVER_MS;
    }

    /**
     * With every presence, so others do not count this phone as lost between check-ins. 0 = normal;
     * 1 to 180 = tens of seconds (up to 30 minutes); 181 to 210 = 31 to 60 minutes (older apps read
     * these as 30 minutes or more, which is still "long").
     */
    static int checkInByte() {
        return encodeCheckIn(presenceMs());
    }

    static int encodeCheckIn(long ms) {
        if (ms <= NORMAL_PRESENCE_MS) return 0;
        if (ms <= 1800000) return (int) Math.max(1, ms / 10000);
        return (int) Math.min(210, 150 + (ms + 59999) / 60000);
    }

    static long decodeCheckIn(int b) {
        return b <= 180 ? b * 10000L : (b - 150) * 60000L;
    }

    static long locationMs(long normal) {
        int m = now();
        return m == ULTRA ? sleepMs() : m == SAVER ? Math.max(normal, SAVER_MS) : normal;
    }

    /** Aircraft, ships and alerts: with the screen on, except in Ultra low. */
    public static boolean feedsOn() {
        int m = now();
        return m == NORMAL || (m != ULTRA && screenOn);
    }

    /** How long nobody may be heard before warning: this phone hears less in a saving mode. */
    static long quietMs() {
        return mode == ULTRA ? Math.max(600000, maxSleepMs * 2) : mode == SAVER ? 180000 : 60000;
    }

    static boolean lanSyncOn() {
        return now() == NORMAL;
    }
}
