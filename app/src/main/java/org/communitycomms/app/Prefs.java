package org.communitycomms.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

/** Saved settings. */
public final class Prefs {
    public static final int NET_BROADCAST = 0;
    public static final int NET_MULTICAST = 1;

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public String callsign() {
        String c = sp.getString("callsign", "");
        if (c.length() == 0) {
            c = randomCallsign(); // never blank: a new phone gets e.g. TANGO38 until renamed
            sp.edit().putString("callsign", c).commit();
        }
        return c;
    }

    public static final int SLOT_A = 0, SLOT_B = 1, SLOT_PUBLIC = 2;
    /** Experimental OpenMANET / ATAK Vx talk group (unencrypted, half duplex). */
    public static final int SLOT_OPENMANET = 3;

    public String openManetGroup() {
        return sp.getString("openmanetGroup", "");
    }

    public void setOpenManetGroup(String g) {
        sp.edit().putString("openmanetGroup", g).commit();
    }
    /**
     * Default, published key for the public channel: anyone running the app can hear it. It still
     * keeps out casual listeners with generic tools, but it is not private. Editable in Settings so
     * a group can run its own "public" channel.
     */
    public static final String PUBLIC_CHANNEL = "publicchannelkey";

    public String publicKey() {
        String k = sp.getString("publicKey", PUBLIC_CHANNEL).trim();
        return k.length() == 0 ? PUBLIC_CHANNEL : k;
    }

    public void setPublicKey(String k) {
        sp.edit().putString("publicKey", k.trim()).commit();
    }

    /** Show the Public / OpenMANET* channel choices on the home screen (both on by default). */
    public boolean showPublic() {
        return sp.getBoolean("showPublic", true);
    }

    public boolean showOpenManet() {
        return sp.getBoolean("showOpenManet", false); // OpenMANET support is opt-in (Settings)
    }

    public void setShowSlots(boolean pub, boolean openManet) {
        sp.edit().putBoolean("showPublic", pub).putBoolean("showOpenManet", openManet).commit();
    }

    /** First-start wizard done (name, public key, first DRYAD). */
    public boolean setupDone() {
        return sp.getBoolean("setupDone", false);
    }

    public void setSetupDone() {
        sp.edit().putBoolean("setupDone", true).commit();
    }

    private static final String[] WORDS = {"ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO", "FOXTROT", "GOLF",
            "HOTEL", "INDIA", "JULIET", "KILO", "LIMA", "MIKE", "NOVEMBER", "OSCAR", "PAPA", "QUEBEC", "ROMEO",
            "SIERRA", "TANGO", "UNIFORM", "VICTOR", "WHISKEY", "XRAY", "YANKEE", "ZULU"};

    /** A starting callsign such as TANGO38, so no two new phones look alike. */
    public static String randomCallsign() {
        java.util.Random r = new java.security.SecureRandom();
        return WORDS[r.nextInt(WORDS.length)] + (10 + r.nextInt(90));
    }

    /** Name used for a slot (A, B and C–H are user/DRYAD set, Public is the public key). */
    public String channel(int slot) {
        if (slot == SLOT_PUBLIC) return publicKey();
        if (slot == SLOT_OPENMANET) return openManetGroup();
        // "channel" was the single channel before A/B existed; it becomes A.
        if (slot == SLOT_A) return sp.getString("channelA", sp.getString("channel", ""));
        return sp.getString("channel" + letter(slot), "");
    }

    public void setChannel(int slot, String name) {
        if (slot == SLOT_PUBLIC) return;
        if (slot == SLOT_OPENMANET) {
            setOpenManetGroup(name);
            return;
        }
        sp.edit().putString("channel" + letter(slot), name).commit();
    }

    // ---- extra channels C–H, home-screen eyes, listening, channel notes ----

    /** Extra encrypted channels C–H, mostly for listening (commander use). */
    public static final int SLOT_C = 4, SLOT_H = 9, SLOT_COUNT = 10;
    /** Home screen room: at most this many channel buttons. */
    public static final int HOME_MAX = 4;

    /** "A", "B", "Public", "OpenMANET", "C" … "H". */
    public static String letter(int slot) {
        if (slot == SLOT_PUBLIC) return "Public";
        if (slot == SLOT_OPENMANET) return "OpenMANET";
        return slot <= SLOT_B ? (slot == SLOT_A ? "A" : "B") : String.valueOf((char) ('C' + slot - SLOT_C));
    }

    /** Order on the Channels page and the home screen: A, B, C … H, Public, OpenMANET*. */
    public static int[] order() {
        return new int[]{SLOT_A, SLOT_B, 4, 5, 6, 7, 8, 9, SLOT_PUBLIC, SLOT_OPENMANET};
    }

    public static boolean isEncrypted(int slot) {
        return slot != SLOT_PUBLIC && slot != SLOT_OPENMANET;
    }

    /** Channels page shows C–H (off by default). */
    public boolean extrasOn() {
        return sp.getBoolean("extrasOn", false);
    }

    public void setExtrasOn(boolean on) {
        sp.edit().putBoolean("extrasOn", on).commit();
    }

    /** The eye: channel button on the home screen. A, B, Public, OpenMANET* on by default; C–H off. */
    public boolean shown(int slot) {
        if (slot == SLOT_PUBLIC) return showPublic();
        if (slot == SLOT_OPENMANET) return showOpenManet();
        if (slot >= SLOT_C && !extrasOn()) return false;
        return sp.getBoolean("shown" + letter(slot), slot <= SLOT_B);
    }

    public void setShown(int slot, boolean on) {
        if (slot == SLOT_PUBLIC) sp.edit().putBoolean("showPublic", on).commit();
        else if (slot == SLOT_OPENMANET) sp.edit().putBoolean("showOpenManet", on).commit();
        else sp.edit().putBoolean("shown" + letter(slot), on).commit();
    }

    /** Channels on the home screen, in order, at most HOME_MAX. */
    public int[] homeSlots() {
        int[] out = new int[HOME_MAX];
        int n = 0;
        for (int s : order()) if (n < HOME_MAX && shown(s)) out[n++] = s;
        return java.util.Arrays.copyOf(out, n);
    }

    /** A private note on a channel (kept on this phone; moves with the channel on a swap). */
    public String channelNote(int slot) {
        return sp.getString("note" + letter(slot), "");
    }

    public void setChannelNote(int slot, String note) {
        sp.edit().putString("note" + letter(slot), note).commit();
    }

    /** Listened to as well as the one on air, while dual watch (multi-channel listening) is on. */
    public boolean listening(int slot) {
        return sp.getBoolean("listen" + letter(slot), false);
    }

    public void setListening(int slot, boolean on) {
        sp.edit().putBoolean("listen" + letter(slot), on).commit();
    }

    /** Broadcast: comma-separated slots that also get what is said on the channel on air ("" = off). */
    public String broadcast() {
        return sp.getString("broadcast", "");
    }

    public void setBroadcast(String slots) {
        sp.edit().putString("broadcast", slots).commit();
    }

    /** Listened to but not played out loud (still saved to Chat when saving is on). */
    public boolean channelMuted(int slot) {
        return sp.getBoolean("mute" + letter(slot), false);
    }

    public void setChannelMuted(int slot, boolean on) {
        sp.edit().putBoolean("mute" + letter(slot), on).commit();
    }

    /**
     * Channels page "Request catch-ups": this channel asks the others for what it missed (every few
     * minutes on air, and on coming back to the app), listen-only ones too. On by default, Public off.
     */
    public boolean channelCatchUp(int slot) {
        return sp.getBoolean("catchUp" + letter(slot), slot != SLOT_PUBLIC && slot != SLOT_OPENMANET);
    }

    public void setChannelCatchUp(int slot, boolean on) {
        sp.edit().putBoolean("catchUp" + letter(slot), on).commit();
    }

    /** Swaps two channels' names, notes and listening (the home-screen eyes stay where they are). */
    public void swap(int a, int b) {
        String na = channel(a), nb = channel(b), ta = channelNote(a), tb = channelNote(b);
        boolean la = listening(a), lb = listening(b);
        setChannel(a, nb);
        setChannel(b, na);
        setChannelNote(a, tb);
        setChannelNote(b, ta);
        setListening(a, lb);
        setListening(b, la);
        boolean ma = channelMuted(a), mb = channelMuted(b);
        setChannelMuted(a, mb);
        setChannelMuted(b, ma);
    }

    public int activeSlot() {
        return sp.getInt("activeSlot", SLOT_A);
    }

    public void setActiveSlot(int slot) {
        sp.edit().putInt("activeSlot", slot).commit();
    }

    public int netMode() {
        return sp.getInt("netMode", NET_BROADCAST);
    }

    public int port() {
        return sp.getInt("port", org.communitycomms.app.net.UdpLink.DEFAULT_PORT);
    }

    /** Comma separated host:port list sent to in addition to broadcast/multicast. */
    public String extraPeers() {
        return sp.getString("extraPeers", "");
    }

    /** Hardware key that acts as PTT (USB PTT, headset button, keyboard key). */
    public int pttKey() {
        return sp.getInt("pttKey", KeyEvent.KEYCODE_UNKNOWN);
    }

    /** Network interface to use (e.g. wlan0, tun0, eth0, rndis0); "" = automatic (all). */
    public String netInterface() {
        return sp.getString("netInterface", "");
    }

    public void setNetInterface(String name) {
        sp.edit().putString("netInterface", name).commit();
    }

    public boolean vox() {
        return sp.getBoolean("vox", false);
    }

    /** VOX trigger level in dBFS (-90 … 0). */
    public int voxThresholdDb() {
        return sp.getInt("voxDb", -35);
    }

    public void setVox(boolean on, int db) {
        sp.edit().putBoolean("vox", on).putInt("voxDb", db).commit();
    }

    public boolean latch() {
        return sp.getBoolean("latch", false);
    }

    public void save(String callsign, int netMode, int port, String extraPeers) {
        sp.edit().putString("callsign", callsign).putInt("netMode", netMode)
                .putInt("port", port).putString("extraPeers", extraPeers).commit();
    }

    public void setPttKey(int key) {
        sp.edit().putInt("pttKey", key).commit();
    }

    public void setLatch(boolean on) {
        sp.edit().putBoolean("latch", on).commit();
    }

    public void setCallsign(String c) {
        sp.edit().putString("callsign", c).commit();
    }

    public void setNetwork(int netMode, int port, String extraPeers) {
        sp.edit().putInt("netMode", netMode).putInt("port", port).putString("extraPeers", extraPeers).commit();
    }

    /** Send my position when I start transmitting, and every 30 s while I keep talking (on by default). */
    public boolean locationOnTransmit() {
        return sp.getBoolean("locOnTx", true);
    }

    public void setLocationOnTransmit(boolean on) {
        sp.edit().putBoolean("locOnTx", on).commit();
    }

    /** Acknowledgement mode: reply "ACK ..." to every received message (range testing / debug). Off by default. */
    public boolean autoAck() {
        return sp.getBoolean("autoAck", false);
    }

    public void setAutoAck(boolean on) {
        sp.edit().putBoolean("autoAck", on).commit();
    }

    /** Dual watch: while on A also listen to B (and the other way round). Listen only, never transmits. */
    public boolean dualWatch() {
        return sp.getBoolean("dualWatch", false);
    }

    public boolean dualWatchPlay() {
        return sp.getBoolean("dualWatchPlay", true);
    }

    public boolean dualWatchSave() {
        return sp.getBoolean("dualWatchSave", true);
    }

    /** Stereo dual watch: A in the left ear, B in the right (needs headphones); off = quieter mode. */
    public boolean dualWatchStereo() {
        return sp.getBoolean("dualWatchStereo", false);
    }

    public void setDualWatch(boolean on, boolean play, boolean save) {
        sp.edit().putBoolean("dualWatch", on).putBoolean("dualWatchPlay", play).putBoolean("dualWatchSave", save).commit();
    }

    /**
     * Channels listened to alongside the one on air (dual watch on): every channel ticked Listen
     * (A–H, Public), never the one on air or one without a name. None ticked: the other of A/B, as
     * dual watch always did. Empty when dual watch is off or the channel on air is OpenMANET*.
     */
    public java.util.List<Integer> watchSlots(int main) {
        java.util.List<Integer> out = new java.util.ArrayList<Integer>();
        if (!dualWatch() || main == SLOT_OPENMANET) return out;
        boolean any = false;
        for (int s : order()) {
            if (s == SLOT_OPENMANET || !listening(s) || (s >= SLOT_C && !extrasOn())) continue;
            any = true;
            if (s != main && channel(s).length() > 0 && !channel(s).equals(channel(main))) out.add(s);
        }
        if (!any) {
            int d = main == SLOT_A ? SLOT_B : SLOT_A;
            if (d != main && channel(d).length() > 0 && !channel(d).equals(channel(main))) out.add(d);
        }
        return out;
    }

    public void setDualWatchStereo(boolean on) {
        sp.edit().putBoolean("dualWatchStereo", on).commit();
    }

    /** Radio audio and mic through a connected Bluetooth headset (SCO), on by default. */
    public boolean btHeadset() {
        return sp.getBoolean("btHeadset", true);
    }

    public void setBtHeadset(boolean on) {
        sp.edit().putBoolean("btHeadset", on).commit();
    }

    /** Address of the Bluetooth (BLE) PTT button, or "". */
    public String btButton() {
        return sp.getString("btButton", "");
    }

    public String btButtonName() {
        return sp.getString("btButtonName", "");
    }

    public void setBtButton(String address, String name) {
        sp.edit().putString("btButton", address).putString("btButtonName", name).commit();
    }

    /** Audio screen: boost on what is heard and mic level, in percent (100 = unchanged). */
    public int rxGainPct() {
        return sp.getInt("rxGainPct", 100);
    }

    public int micGainPct() {
        return sp.getInt("micGainPct", 100);
    }

    public void setGains(int rxPct, int micPct) {
        sp.edit().putInt("rxGainPct", rxPct).putInt("micGainPct", micPct).commit();
        org.communitycomms.app.audio.AudioLevels.rxGain = rxPct / 100f;
        org.communitycomms.app.audio.AudioLevels.micGain = micPct / 100f;
    }

    /** Call button on the home screen, and ringing when someone calls. On by default. */
    public boolean callEnabled() {
        return sp.getBoolean("callEnabled", true);
    }

    public void setCallEnabled(boolean on) {
        sp.edit().putBoolean("callEnabled", on).commit();
    }

    /** Radio silent: receive only, nothing is sent. Survives restarts so a silent phone stays silent. */
    public boolean radioSilent() {
        return sp.getBoolean("radioSilent", false);
    }

    public void setRadioSilent(boolean on) {
        sp.edit().putBoolean("radioSilent", on).commit();
        RadioEngine.radioSilent = on;
    }

    /** Ring (loudly) when someone presses Call on the channel. On by default. */
    public boolean ringOnCall() {
        return sp.getBoolean("ringOnCall", true);
    }

    public void setRingOnCall(boolean on) {
        sp.edit().putBoolean("ringOnCall", on).commit();
    }

    /** Home screen style: false = radio (big PTT), true = map with a smaller PTT. */
    public boolean homeMap() {
        return sp.getBoolean("homeMap", false);
    }

    public void setHomeMap(boolean on) {
        sp.edit().putBoolean("homeMap", on).commit();
    }

    /** Speak a short announcement when someone else's POI arrives (on by default). */
    public boolean announcePoi() {
        return sp.getBoolean("announcePoi", true);
    }

    public void setAnnouncePoi(boolean on) {
        sp.edit().putBoolean("announcePoi", on).commit();
    }

    /** Main-screen lock: channel select, join/leave and settings are blocked until a long press. */
    public boolean locked() {
        return sp.getBoolean("uiLocked", false);
    }

    public void setLocked(boolean on) {
        sp.edit().putBoolean("uiLocked", on).commit();
    }

    /** Catch-up sync: exchange "who sent what, when" every Z minutes, covering the last Y minutes. */
    public boolean catchUp() {
        return sp.getBoolean("catchUp", true);
    }

    public int catchUpEveryMin() {
        return sp.getInt("catchUpEvery", 5);
    }

    public int catchUpWindowMin() {
        return sp.getInt("catchUpWindow", 30);
    }

    public void setCatchUp(boolean on, int everyMin, int windowMin) {
        sp.edit().putBoolean("catchUp", on).putInt("catchUpEvery", Math.max(1, everyMin))
                .putInt("catchUpWindow", Math.max(1, windowMin)).commit();
    }

    /** Lost-device protection: two wrong PIN entries erase all of this app's data. Off by default. */
    // ---- start-up rejoin, status (away / stealth), gunshot alert*, permissions ----

    /** On app start, go back on the channel the phone was on (A/B stays main with dual watch). On by default. */
    public boolean rejoinOnStart() {
        return sp.getBoolean("rejoinOnStart", true);
    }

    public void setRejoinOnStart(boolean on) {
        sp.edit().putBoolean("rejoinOnStart", on).commit();
    }

    /** Save every transmission heard and sent to Chat as audio, even when Available. Off by default. */
    /** Coming back to the app after being away asks the others at once for what was missed (on by default). */
    public boolean requestMissedWhenAway() {
        return sp.getBoolean("requestMissed", true);
    }

    /** Commander view: offer to resend to someone back after being offline (10 s pop-up; on by default). */
    public boolean offerMissed() {
        return sp.getBoolean("offerMissed", true);
    }

    public void setOfferMissed(boolean on) {
        sp.edit().putBoolean("offerMissed", on).commit();
    }

    public void setRequestMissedWhenAway(boolean on) {
        sp.edit().putBoolean("requestMissed", on).commit();
    }

    /** Received bursts wait this long (s) for a clear channel; 0 = play at once. */
    public int burstWaitSec() {
        return sp.getInt("burstWait", 60);
    }

    public void setBurstWaitSec(int s) {
        sp.edit().putInt("burstWait", Math.max(0, s)).commit();
    }

    /** Audio page: clean up received bursts (less background noise, quiet ones turned up). Off by default. */
    public boolean burstCleanup() {
        return sp.getBoolean("burstCleanup", false);
    }

    public void setBurstCleanup(boolean on) {
        sp.edit().putBoolean("burstCleanup", on).commit();
    }

    public boolean burstWaitTone() {
        return sp.getBoolean("burstWaitTone", true);
    }

    public void setBurstWaitTone(boolean on) {
        sp.edit().putBoolean("burstWaitTone", on).commit();
    }

    /** A "Radio silent" tick for any screen (Channels page, Data / files): the same switch as Settings. */
    public static android.widget.CheckBox radioSilentBox(final android.content.Context c) {
        final Prefs p = new Prefs(c);
        final android.widget.CheckBox b = new android.widget.CheckBox(c);
        b.setText("Radio silent (receive only): nothing is sent, not voice, messages, positions or acks");
        b.setChecked(p.radioSilent());
        b.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton bt, boolean on) {
                p.setRadioSilent(on);
                RadioEngine.radioSilent = on;
                android.widget.Toast.makeText(c, on ? "Radio silent: receive only" : "Radio silent off: transmitting again",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        return b;
    }

    public boolean swapLeftRight() {
        return sp.getBoolean("swapLR", false);
    }

    public void setSwapLeftRight(boolean on) {
        sp.edit().putBoolean("swapLR", on).commit();
    }

    public boolean recordAllVoice() {
        return sp.getBoolean("recordAllVoice", false);
    }

    public void setRecordAllVoice(boolean on) {
        sp.edit().putBoolean("recordAllVoice", on).commit();
    }

    /** Black screen button on the home screen. Off by default: people must read how to get out of it. */
    public boolean blackScreenButton() {
        return sp.getBoolean("blackScreenButton", false);
    }

    public void setBlackScreenButton(boolean on) {
        sp.edit().putBoolean("blackScreenButton", on).commit();
    }

    // ---- automatic key rotation: the channel key changes every hour or day by itself ----

    public static final int ROTATE_OFF = 0, ROTATE_HOURLY = 1, ROTATE_DAILY = 2;

    public int keyRotation() {
        return sp.getInt("keyRotation", ROTATE_OFF);
    }

    public void setKeyRotation(int r) {
        sp.edit().putInt("keyRotation", r).commit();
    }

    /** The current period number (UTC hours or days since 1970), or -1 when rotation is off. */
    public static long rotationPeriod(int mode, long now) {
        if (mode == ROTATE_HOURLY) return now / 3600000L;
        if (mode == ROTATE_DAILY) return now / 86400000L;
        return -1;
    }

    /** Milliseconds until the key next changes (or -1). */
    public static long msToNextRotation(int mode, long now) {
        long len = mode == ROTATE_HOURLY ? 3600000L : mode == ROTATE_DAILY ? 86400000L : 0;
        return len == 0 ? -1 : len - now % len;
    }

    /**
     * The channel name actually used: with rotation on, the name plus the period, so the key changes
     * every hour / day (UTC) for everyone with the same setting. The Public key never rotates.
     */
    public String effectiveChannel(String name) {
        int m = keyRotation();
        if (m == ROTATE_OFF || name.length() == 0 || name.trim().equals(publicKey().trim())) return name;
        return name + "|" + (m == ROTATE_HOURLY ? "h" : "d") + rotationPeriod(m, System.currentTimeMillis());
    }

    /** The map icon others see this phone as (e.g. an ambulance), -1 = a plain dot. */
    public int myIcon() {
        return sp.getInt("myIcon", -1);
    }

    public void setMyIcon(int icon) {
        sp.edit().putInt("myIcon", icon).commit();
        RadioEngine.myIcon = icon;
    }

    /** A short beep at the start of each PTT transmission, so listeners know a call is coming. Off by default. */
    public boolean startTone() {
        return sp.getBoolean("startTone", false);
    }

    public void setStartTone(boolean on) {
        sp.edit().putBoolean("startTone", on).commit();
        RadioEngine.startTone = on;
    }

    /** Home screen goes to black screen after this many seconds without a touch; 0 = never. */
    public int blackAfterSec() {
        return sp.getInt("blackAfterSec", 0);
    }

    public void setBlackAfterSec(int s) {
        sp.edit().putInt("blackAfterSec", Math.max(0, s)).commit();
    }

    // ---- app mode: the full radio, a simple activity mode, or the wall intercom ----

    public static final int MODE_ADVANCED = 0, MODE_INTERCOM = 1, MODE_SCOUTS = 2, MODE_STAFF = 3, MODE_4WD = 4,
            MODE_CONVOY = 5, MODE_CAMPING = 6;
    public static final String[] MODE_NAMES = {"Advanced (full radio, DRYAD, maps)", "Intercom (wall-mounted, rooms)",
            "Scouts", "Staff", "4WDing", "Friends convoy", "Caravan / camping"};

    public int appMode() {
        return sp.getInt("appMode", MODE_ADVANCED);
    }

    public void setAppMode(int m) {
        sp.edit().putInt("appMode", m).commit();
    }

    /** Simple modes: no DRYAD, Pigpen or OpenMANET; a private key (channel A) and the public key. */
    public boolean simple() {
        return appMode() >= MODE_SCOUTS;
    }

    // ---- intercom ----

    /** Rooms (callsigns) on the intercom screen, one per line. */
    public String intercomRooms() {
        return sp.getString("intercomRooms", "");
    }

    public void setIntercomRooms(String s) {
        sp.edit().putString("intercomRooms", s == null ? "" : s).commit();
    }

    /** Intercom screen goes black after 60 s without a touch (on by default; long-press Blackout to switch). */
    public boolean intercomAutoBlack() {
        return sp.getBoolean("intercomAutoBlack", true);
    }

    public void setIntercomAutoBlack(boolean on) {
        sp.edit().putBoolean("intercomAutoBlack", on).commit();
    }

    /** Gate / door camera for the intercom: an MJPEG or snapshot URL, "" = none. */
    public String intercomCamera() {
        return sp.getString("intercomCamera", "");
    }

    public void setIntercomCamera(String s) {
        sp.edit().putString("intercomCamera", s == null ? "" : s.trim()).commit();
    }

    // ---- 4WD ----

    public boolean crashDetect() {
        return sp.getBoolean("crashDetect", appMode() == MODE_4WD);
    }

    public void setCrashDetect(boolean on) {
        sp.edit().putBoolean("crashDetect", on).commit();
    }

    /** Alert when someone on the channel drops off (not heard for 2 minutes). */
    public boolean dropAlert() {
        return sp.getBoolean("dropAlert", appMode() == MODE_4WD || appMode() == MODE_CONVOY);
    }

    public void setDropAlert(boolean on) {
        sp.edit().putBoolean("dropAlert", on).commit();
    }

    // ---- internet audio streams ----

    /** Radio streams over the internet in the menu. Off by default: the app is offline unless asked. */
    public boolean streamsEnabled() {
        return sp.getBoolean("streamsEnabled", false);
    }

    public void setStreamsEnabled(boolean on) {
        sp.edit().putBoolean("streamsEnabled", on).commit();
    }

    /** One per line: "name|url". */
    public String streams() {
        return sp.getString("streams", "");
    }

    public void setStreams(String s) {
        sp.edit().putString("streams", s == null ? "" : s).commit();
    }

    /**
     * Radio silent support: others on the channel may be radio silent (they never acknowledge or
     * show up as online), so no "transmission failed" warnings and no red 0-contacts button.
     */
    public boolean silentListeners() {
        return sp.getBoolean("silentListeners", false);
    }

    public void setSilentListeners(boolean on) {
        sp.edit().putBoolean("silentListeners", on).commit();
    }

    // ---- burst voice (record, then send as a file) ----

    public boolean burstVoice() {
        return sp.getBoolean("burstVoice", false);
    }

    public void setBurstVoice(boolean on) {
        sp.edit().putBoolean("burstVoice", on).commit();
    }

    /** 0 smallest … 3 clearest (BurstVoice.QUALITY_NAMES). */
    public int burstQuality() {
        return sp.getInt("burstQuality", 1);
    }

    public void setBurstQuality(int q) {
        sp.edit().putInt("burstQuality", q).commit();
    }

    // ---- tunnel comms (internet link to a relay phone or tablet) ----

    /** Remote side: "host:port" of the relay to send everything through, "" = off. */
    public String tunnelServer() {
        return sp.getString("tunnelServer", "");
    }

    /** Remote side: the DRYAD row text that keys the tunnel. */
    public String tunnelKey() {
        return sp.getString("tunnelKey", "");
    }

    public void setTunnel(String server, String key) {
        sp.edit().putString("tunnelServer", server == null ? "" : server.trim())
                .putString("tunnelKey", key == null ? "" : key).commit();
    }

    /** Relay side: one line per remote, "port|label|DRYAD row". Empty = not a relay. */
    public String tunnelRemotes() {
        return sp.getString("tunnelRemotes", "");
    }

    public void setTunnelRemotes(String s) {
        sp.edit().putString("tunnelRemotes", s == null ? "" : s).commit();
    }

    /**
     * Remote side: actually use the relay set in tunnelServer. Off by default, so a relay can be set
     * up (or arrive in a config) without this phone switching to it.
     */
    public boolean tunnelClientOn() {
        return sp.getBoolean("tunnelClientOn", false);
    }

    /** Tunnel for this one channel only (used when the tunnel is not on for every channel). */
    public boolean tunnelSlot(int slot) {
        return sp.getBoolean("tunnelSlot" + slot, false);
    }

    public void setTunnelSlot(int slot, boolean on) {
        sp.edit().putBoolean("tunnelSlot" + slot, on).commit();
    }

    /** A relay server and key are set up (Settings > Tunnel comms). */
    public boolean tunnelSetUp() {
        return tunnelServer().length() > 0 && tunnelKey().length() > 0;
    }

    public void setTunnelClientOn(boolean on) {
        sp.edit().putBoolean("tunnelClientOn", on).commit();
    }

    public boolean tunnelRelayOn() {
        return sp.getBoolean("tunnelRelayOn", false);
    }

    public void setTunnelRelayOn(boolean on) {
        sp.edit().putBoolean("tunnelRelayOn", on).commit();
    }

    /** Was on air when the app last stopped (false after tapping Leave). */
    public boolean wasOnAir() {
        return sp.getBoolean("wasOnAir", false);
    }

    public void setWasOnAir(boolean on) {
        sp.edit().putBoolean("wasOnAir", on).commit();
    }

    /** Status chosen on the home screen: RadioEngine.STATUS_AVAILABLE, _AWAY or _STEALTH. */
    public int status() {
        return sp.getInt("status", RadioEngine.STATUS_AVAILABLE);
    }

    public void setStatus(int s) {
        sp.edit().putInt("status", s).commit();
    }

    /** Volume turned all the way down = stealth (on by default). */
    public boolean autoStealth() {
        return sp.getBoolean("autoStealth", true);
    }

    public void setAutoStealth(boolean on) {
        sp.edit().putBoolean("autoStealth", on).commit();
    }

    /** In stealth or with the volume at zero, vibrate for what arrives (on by default). */
    public boolean stealthVibrate() {
        return sp.getBoolean("stealthVibrate", true);
    }

    public void setStealthVibrate(boolean on) {
        sp.edit().putBoolean("stealthVibrate", on).commit();
    }

    /** Volume turned all the way down: a received Call turns the speaker and ringer back up (on by default). */
    public boolean callWakesVolume() {
        return sp.getBoolean("callWakesVolume", true);
    }

    public void setCallWakesVolume(boolean on) {
        sp.edit().putBoolean("callWakesVolume", on).commit();
    }

    /** Possible-gunshot alert* (untested). Off by default. */
    public boolean gunshotAlert() {
        return sp.getBoolean("gunshotAlert", false);
    }

    public void setGunshotAlert(boolean on) {
        sp.edit().putBoolean("gunshotAlert", on).commit();
    }

    /** The permissions screen has been shown once (first start). */
    public boolean permissionsShown() {
        return sp.getBoolean("permissionsShown", false);
    }

    public void setPermissionsShown() {
        sp.edit().putBoolean("permissionsShown", true).commit();
    }

    public boolean wipeOnBadPin() {
        return sp.getBoolean("wipeOnBadPin", false);
    }

    public void setWipeOnBadPin(boolean on) {
        sp.edit().putBoolean("wipeOnBadPin", on).commit();
    }
}
