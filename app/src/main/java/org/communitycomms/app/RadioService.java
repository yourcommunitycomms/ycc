package org.communitycomms.app;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import org.communitycomms.app.net.FileTransfer;
import org.communitycomms.app.net.UdpLink;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Keeps the radio alive while the screen is off. The activity binds to it for control. */
public final class RadioService extends Service implements RadioEngine.Listener {
    public interface Ui {
        void onRosterChanged();

        void onTransmitChanged(boolean on);

        void onJoinState(String status, boolean joined);

        /** Another station sent DRYAD sheets; the UI asks before importing. */
        void onDryadOffer(String fromName, String text);

        /** Result of the last transmission. Empty heardBy = nobody acknowledged it. */
        void onTransmitResult(List<String> heardBy, boolean reminder);
    }

    public static final String ACTION_RESEND = "org.communitycomms.app.RESEND";
    private static final String ACTION_CHECK_IN = "org.communitycomms.app.CHECK_IN";
    private static final long RESEND_REMINDER_MS = 5 * 60000;
    private static final int NOTIF_FAILED = 2;
    private android.speech.tts.TextToSpeech tts;
    private boolean ttsReady;
    private List<String> lastHeard;
    private final Runnable resendReminder = new Runnable() {
        @Override
        public void run() {
            RadioEngine e = engine;
            if (e == null || !e.canResend()) return;
            beep(android.media.ToneGenerator.TONE_PROP_PROMPT);
            say("Your last message was not acknowledged. Resend it?");
            notifyFailed(true);
            Ui u = ui;
            if (u != null) u.onTransmitResult(new ArrayList<String>(), true);
        }
    };

    @Override
    public void onTransmitResult(final List<String> heardBy) {
        main.post(new Runnable() {
            @Override
            public void run() {
                lastHeard = heardBy;
                main.removeCallbacks(resendReminder);
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (heardBy.isEmpty() && new Prefs(RadioService.this).silentListeners()) {
                    // Radio silent support: listeners never acknowledge, so silence is not a failure.
                    nm.cancel(NOTIF_FAILED);
                    Ui u = ui;
                    if (u != null) u.onTransmitResult(heardBy, false);
                    return;
                }
                if (heardBy.isEmpty()) {
                    beep(android.media.ToneGenerator.TONE_PROP_NACK);
                    say("Transmission failed.");
                    if (retryLeft > 0) {
                        // A scheduled retry series is running: queue the next attempt.
                        nextRetryAt = System.currentTimeMillis() + retryInterval;
                        main.postDelayed(retryTick, retryInterval);
                    } else {
                        notifyFailed(false);
                        main.postDelayed(resendReminder, RESEND_REMINDER_MS);
                    }
                } else {
                    nm.cancel(NOTIF_FAILED);
                    cancelRetries();
                }
                Ui u = ui;
                if (u != null) u.onTransmitResult(heardBy, false);
            }
        });
    }

    // ---- map POIs ----

    /** Map screen refresh hook. */
    public static volatile Runnable poiUi;

    @Override
    public void onPoiMessage(String fromName, byte[] payload, int off) {
        byte op = payload[off];
        if (op == org.communitycomms.app.geo.Poi.OP_UPSERT) {
            org.communitycomms.app.geo.Poi p = org.communitycomms.app.geo.Poi.decode(payload, off + 1, payload.length - off - 1);
            if (p == null) return;
            messages.notePoiOnAir(p.id);
            // A POI we removed (tombstone) must not come back from someone's old copy.
            org.communitycomms.app.msg.MessageLog.Entry known = org.communitycomms.app.msg.MessageLog.get(this).find(p.id);
            if (known != null && known.removed && known.version >= p.version) return;
            boolean wasNew = !hasPoi(p.id);
            if (org.communitycomms.app.geo.PoiStore.get(this).apply(p)) {
                logPoi(p, false);
                announcePoi(p, wasNew);
                Runnable r = poiUi;
                if (r != null) main.post(r);
            }
        } else if (op == org.communitycomms.app.geo.Poi.OP_SYNC_REQUEST) {
            // Answer after a random pause so a whole channel does not reply at the same instant.
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sendAllPois(false);
                }
            }, 300 + (long) (Math.random() * 2500));
        }
    }

    /** Sends every live POI; with ask=true also asks everyone else to send theirs. */
    public boolean sendAllPois(final boolean ask) {
        final RadioEngine e = engine;
        if (e == null) return false;
        final List<org.communitycomms.app.geo.Poi> all = org.communitycomms.app.geo.PoiStore.get(this).all();
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (ask) e.sendPoiBody(new byte[]{org.communitycomms.app.geo.Poi.OP_SYNC_REQUEST});
                for (org.communitycomms.app.geo.Poi p : all) {
                    if (org.communitycomms.app.geo.Poi.isPersonal(p.note)) continue; // personal: this phone only
                    e.sendPoiBody(p.encode());
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException ex) {
                        return;
                    }
                }
            }
        }, "poi-sync").start();
        return true;
    }

    // ---- call alert ----

    /** Rings everyone on the channel on air (target null) or one callsign. False when not on air or radio silent. */
    public boolean sendCall(String target) {
        RadioEngine e = engine;
        return e != null && e.sendCall(target);
    }

    @Override
    public void onCall(String fromName) {
        ringFor(fromName, "");
    }

    // ---- a call nobody acknowledged: a Missed call line (under Voice in Chat) and a reminder every 5 minutes ----

    private static final long CALL_MISSED_MS = 30000, CALL_REMIND_MS = 300000;
    private static final int CALL_REMINDERS = 12;
    private String unanswered, unansweredWhere = "";
    private boolean unansweredLogged;
    private long nextReminder;
    private int reminders;

    private void callWaiting(String from, String where) {
        unanswered = from;
        unansweredWhere = where;
        unansweredLogged = false;
        reminders = 0;
        nextReminder = System.currentTimeMillis() + CALL_REMIND_MS;
        main.removeCallbacks(markMissed);
        main.postDelayed(markMissed, CALL_MISSED_MS);
    }

    /** The call screen was answered. */
    public void callSeen() {
        unanswered = null;
        main.removeCallbacks(markMissed);
    }

    private final Runnable markMissed = new Runnable() {
        @Override
        public void run() {
            if (unanswered == null || unansweredLogged) return;
            unansweredLogged = true;
            org.communitycomms.app.msg.MessageLog.Entry m = new org.communitycomms.app.msg.MessageLog.Entry();
            m.id = new java.security.SecureRandom().nextLong();
            m.type = org.communitycomms.app.msg.MessageLog.TYPE_TEXT;
            m.time = System.currentTimeMillis();
            m.creator = unanswered;
            m.channelTag = tagNow();
            m.body = "MISSED CALL from " + unanswered + unansweredWhere;
            m.local = true;
            org.communitycomms.app.msg.MessageLog.get(RadioService.this).put(m);
            notifyMessage(m);
        }
    };

    private void checkCallReminder() {
        if (unanswered == null || System.currentTimeMillis() < nextReminder) return;
        Prefs pr = new Prefs(this);
        if (!pr.callReminder() || ++reminders > CALL_REMINDERS) {
            callSeen();
            return;
        }
        nextReminder += CALL_REMIND_MS;
        notifyCall("Missed call from " + unanswered + ": tap to acknowledge");
        if (curStatus == RadioEngine.STATUS_STEALTH) {
            if (pr.stealthVibrate()) buzz(true);
            return;
        }
        missedTone();
        buzz(true);
        showCallScreen(unanswered, "");
    }

    private void ringFor(final String fromName, final String where) {
        final Prefs pr = new Prefs(this);
        if (!pr.callEnabled()) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                String text = "CALL from " + fromName + where;
                callWaiting(fromName, where);
                if (curStatus == RadioEngine.STATUS_STEALTH) {
                    if (volumeZero && pr.status() != RadioEngine.STATUS_STEALTH && pr.callWakesVolume()) {
                        // Volume was only turned all the way down: a Call brings the sound back for the
                        // ring, then it all goes back (status stays as it was).
                        raiseVolumes();
                    } else {
                        // Stealth chosen on purpose: no ring, no sound, just a buzz and a note.
                        if (pr.stealthVibrate()) buzz(true);
                        notifyCall(text + " (stealth: not rung)");
                        unansweredWhere = where + " while you were in stealth";
                        main.removeCallbacks(markMissed);
                        markMissed.run();
                        return;
                    }
                }
                org.communitycomms.app.audio.CallRinger.ring(RadioService.this);
                showCallScreen(fromName, where);
                notifyCall(text);
            }
        });
    }

    // ---- status: available / away / stealth, battery, contacts ----

    /** Status in force now (the chosen one, or stealth while the volume is all the way down). */
    private int curStatus = RadioEngine.STATUS_AVAILABLE;
    private int pendingStatus = -1;
    private long pendingSince;
    /** The stream the radio plays on is at its lowest setting. */
    private boolean volumeZero;

    /** A call or voice brings the black screen back: the setting, never in stealth. */
    public boolean blackWakes() {
        return new Prefs(this).blackWake() && curStatus != RadioEngine.STATUS_STEALTH;
    }

    public int radioStatus() {
        return curStatus;
    }

    /** True while stealth comes only from the volume being all the way down. */
    public boolean stealthFromVolume() {
        return curStatus == RadioEngine.STATUS_STEALTH && volumeZero && new Prefs(this).status() != RadioEngine.STATUS_STEALTH;
    }

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            main.postDelayed(this, 2000);
            readBattery();
            checkStatus();
            mergeContacts();
            if (++statusTicks % 30 == 0 && PowerMode.lanSyncOn()) LanSync.maybeRun(RadioService.this); // once a minute: due LAN sync / update check
            PowerMode.alone = Contacts.get(RadioService.this).onlineCount() == 0;
            applyPower();
            checkCallReminder();
            if (statusTicks % 5 == 0 && !PowerMode.alone && !RadioEngine.radioSilent) org.communitycomms.app.audio.BurstVoice.sendWaiting(RadioService.this);
            AtakBridge.apply(RadioService.this); // Settings: ATAK compatible on / off
            checkMission();
            windowTick();
            if (statusTicks % 30 == 15) checkOverdue();
            checkStill();
            tuneLocks();
        }
    };
    private int statusTicks;

    /** The home screen's status button: takes effect straight away. */
    public void chooseStatus(int s) {
        new Prefs(this).setStatus(s);
        pendingStatus = -1;
        checkStatusNow();
        if (s == RadioEngine.STATUS_AWAY && !PowerMode.offline()) {
            Toast.makeText(this, "Away: with the screen off this phone sleeps and checks in by itself about every "
                    + PowerMode.ultraCheckInMs() / 60000 + " min. Others see \"auto check-in in about N min\".", Toast.LENGTH_LONG).show();
        }
        powerChanged();
    }

    /** Enigma page > CW mode changed: stealth (voice muted, nothing spoken) and VOX off while it is on. */
    public void cwModeChanged() {
        RadioEngine e = engine;
        if (e != null && RadioEngine.cwMode) {
            e.setTransmit(false);
            e.setVox(false);
        } else if (e != null) {
            e.setVox(new Prefs(this).vox());
        }
        checkStatusNow();
    }

    private void checkStatusNow() {
        Prefs pr = new Prefs(this);
        volumeZero = engine != null && volumeAtLowest();
        int want = RadioEngine.cwMode || pr.status() == RadioEngine.STATUS_STEALTH || (volumeZero && pr.autoStealth())
                ? RadioEngine.STATUS_STEALTH : pr.status();
        if (want != curStatus) applyStatus(want);
    }

    private void checkStatus() {
        if (System.currentTimeMillis() < wakeUntil) return; // a Call is ringing through: volumes are put back after
        Prefs pr = new Prefs(this);
        volumeZero = engine != null && volumeAtLowest();
        int want = RadioEngine.cwMode || pr.status() == RadioEngine.STATUS_STEALTH || (volumeZero && pr.autoStealth())
                ? RadioEngine.STATUS_STEALTH : pr.status();
        if (want == curStatus) {
            pendingStatus = -1;
            return;
        }
        // A volume change must hold for a few seconds, so stepping through zero does not flip the status.
        long now = System.currentTimeMillis();
        if (pendingStatus != want) {
            pendingStatus = want;
            pendingSince = now;
        } else if (now - pendingSince >= 3000) {
            pendingStatus = -1;
            applyStatus(want);
        }
    }

    private void applyStatus(int s) {
        boolean changed = s != curStatus;
        curStatus = s;
        RadioEngine.myStatus = s;
        org.communitycomms.app.audio.AudioLevels.muted = s == RadioEngine.STATUS_STEALTH;
        RadioEngine e = engine;
        if (e != null) {
            boolean all = new Prefs(this).recordAllVoice();
            e.setRecorder(s == RadioEngine.STATUS_AVAILABLE && !all ? null : awayRecorder(e));
            e.setSentSaver(all ? sentSaver(e) : null);
            if (changed) e.sendPresenceNow();
        }
        if (changed) {
            String why = s == RadioEngine.STATUS_STEALTH && volumeZero && new Prefs(this).status() != RadioEngine.STATUS_STEALTH
                    ? " (volume all the way down)" : "";
            logLocal(new Prefs(this).callsign(), "You are now " + Contacts.statusName(s) + why, tagNow());
            Ui u = ui;
            if (u != null) u.onRosterChanged();
        }
    }

    /** Away / stealth: each transmission heard on the channel on air is saved to Chat and notified. */
    private VoiceRecorder awayRecorder(final RadioEngine e) {
        VoiceRecorder old = e.recorder();
        if (old != null) return old;
        final String letter = slotName(new Prefs(this).activeSlot());
        return new VoiceRecorder(receivedDir(this), letter, new VoiceRecorder.Listener() {
            @Override
            public void onRecording(java.io.File f, final String from, final int seconds, long voiceId) {
                final org.communitycomms.app.msg.MessageLog.Entry en = new org.communitycomms.app.msg.MessageLog.Entry();
                en.id = voiceId; // the same on every phone that recorded it (voice catch-up)
                en.type = org.communitycomms.app.msg.MessageLog.TYPE_FILE;
                en.time = System.currentTimeMillis() - seconds * 1000L;
                en.creator = from;
                en.channelTag = e.channelTag();
                final boolean missed = curStatus != RadioEngine.STATUS_AVAILABLE;
                en.body = "Voice on " + letter + " from " + from + " (" + Math.max(1, seconds) + " s)" + (!missed ? ""
                        : " while you were " + (curStatus == RadioEngine.STATUS_STEALTH ? "in stealth" : "away"));
                en.path = f.getPath();
                en.mime = "audio/wav";
                en.local = true;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        org.communitycomms.app.msg.MessageLog.get(RadioService.this).put(en);
                        if (missed) notifyMessage(en); // heard live when Available: just logged
                    }
                });
            }
        });
    }

    /** After the Record all voice setting changed: start or stop saving on the channel on air. */
    public void applyRecording() {
        main.post(new Runnable() {
            @Override
            public void run() {
                applyStatus(curStatus);
            }
        });
    }

    /** Record all voice: each of this phone's own transmissions saved to Chat as a WAV. */
    private RadioEngine.SentSaver sentSaver(final RadioEngine e) {
        final String letter = slotName(new Prefs(this).activeSlot());
        return new RadioEngine.SentSaver() {
            @Override
            public void onSent(List<byte[]> frames, long voiceId) {
                String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
                java.io.File dir = receivedDir(RadioService.this);
                dir.mkdirs();
                java.io.File f = new java.io.File(dir, "rec-" + letter + "-sent-" + stamp + ".wav");
                int seconds = VoiceRecorder.writeAdpcm(f, frames);
                if (seconds < 0) return;
                final org.communitycomms.app.msg.MessageLog.Entry en = new org.communitycomms.app.msg.MessageLog.Entry();
                en.id = voiceId;
                en.type = org.communitycomms.app.msg.MessageLog.TYPE_FILE;
                en.time = System.currentTimeMillis() - seconds * 1000L;
                en.creator = new Prefs(RadioService.this).callsign();
                en.channelTag = e.channelTag();
                en.body = "Voice sent on " + letter + " (" + Math.max(1, seconds) + " s)";
                en.path = f.getPath();
                en.mime = "audio/wav";
                en.local = true;
                en.mine = true;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        org.communitycomms.app.msg.MessageLog.get(RadioService.this).put(en);
                    }
                });
            }
        };
    }

    /** Which channel (A, Public, D…) a listen-only engine is on, from its name. */
    /** Team code or private contact, by key, for channels outside the list. */
    private static final java.util.Map<String, String> watchLabels = new java.util.concurrent.ConcurrentHashMap<String, String>();

    private static String letterOf(Prefs pr, RadioEngine m) {
        String named = watchLabels.get(m.channelName().trim());
        if (named != null) return named;
        for (int s : Prefs.order()) {
            if (pr.channel(s).trim().length() > 0 && pr.effectiveChannel(pr.channel(s)).trim().equals(m.channelName().trim())) return Prefs.letter(s);
        }
        return "?";
    }

    private static String slotName(int slot) {
        return Prefs.letter(slot);
    }

    private int tagNow() {
        RadioEngine e = engine;
        return e == null ? 0 : e.channelTag();
    }

    /** A line in Chat kept on this phone only (status changes, missed calls). */
    private void logLocal(String creator, String text, int tag) {
        org.communitycomms.app.msg.MessageLog.Entry m = new org.communitycomms.app.msg.MessageLog.Entry();
        m.id = new java.security.SecureRandom().nextLong();
        m.type = org.communitycomms.app.msg.MessageLog.TYPE_TEXT;
        m.time = System.currentTimeMillis();
        m.creator = creator;
        m.channelTag = tag;
        m.body = text;
        m.local = true;
        m.mine = true; // a note, not an unread message
        org.communitycomms.app.msg.MessageLog.get(this).put(m);
    }

    /** True when the stream the radio plays on is at its lowest step (the in-call stream cannot reach 0). */
    private boolean volumeAtLowest() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        // A phone switched to silent or vibrate counts as "all the way down" too.
        if (am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return true;
        int stream = mainPan != 0 ? AudioManager.STREAM_MUSIC : AudioManager.STREAM_VOICE_CALL;
        int min = Build.VERSION.SDK_INT >= 28 ? am.getStreamMinVolume(stream) : stream == AudioManager.STREAM_VOICE_CALL ? 1 : 0;
        try {
            return am.getStreamVolume(stream) <= min;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static final int[] WAKE_STREAMS = {AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_RING, AudioManager.STREAM_MUSIC};
    /** While a Call rings through a turned-down phone: the status and volumes are left alone until then. */
    private long wakeUntil;
    private int savedRinger = -1;
    private final int[] savedVolumes = new int[WAKE_STREAMS.length];

    /**
     * A Call while the volume was all the way down (or the phone on silent / vibrate): ringer, radio
     * and media up to at least 60 % for the ring, then everything is put back as it was, so the
     * phone returns to stealth (or away) by itself.
     */
    private void raiseVolumes() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        if (savedRinger < 0) { // a second Call during the ring keeps the first saved state
            savedRinger = am.getRingerMode();
            for (int i = 0; i < WAKE_STREAMS.length; i++) savedVolumes[i] = am.getStreamVolume(WAKE_STREAMS[i]);
        }
        try {
            am.setRingerMode(AudioManager.RINGER_MODE_NORMAL); // out of silent / vibrate
        } catch (RuntimeException ignored) {
            // Do Not Disturb access needed on newer Android
        }
        for (int s : WAKE_STREAMS) {
            try {
                int want = am.getStreamMaxVolume(s) * 6 / 10;
                if (am.getStreamVolume(s) < want) am.setStreamVolume(s, want, 0);
            } catch (RuntimeException ignored) {
                // Do Not Disturb can refuse ringer changes
            }
        }
        wakeUntil = System.currentTimeMillis() + org.communitycomms.app.audio.CallRinger.RING_MS + 1500;
        main.removeCallbacks(restoreVolumes);
        main.postDelayed(restoreVolumes, org.communitycomms.app.audio.CallRinger.RING_MS + 1500);
    }

    private final Runnable restoreVolumes = new Runnable() {
        @Override
        public void run() {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null || savedRinger < 0) return;
            for (int i = 0; i < WAKE_STREAMS.length; i++) {
                try {
                    am.setStreamVolume(WAKE_STREAMS[i], savedVolumes[i], 0);
                } catch (RuntimeException ignored) {
                }
            }
            try {
                am.setRingerMode(savedRinger); // after the volumes: setting RING to 0 can change the mode
            } catch (RuntimeException ignored) {
            }
            savedRinger = -1;
            wakeUntil = 0;
        }
    };

    /** Short buzz for a message, longer for a call (stealth / volume at zero). */
    @SuppressWarnings("deprecation")
    private void buzz(boolean call) {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(call ? new long[]{0, 500, 250, 500, 250, 500} : new long[]{0, 150, 120, 150}, -1);
        } catch (RuntimeException ignored) {
        }
    }

    /** Stealth or volume at zero, with vibrate on: what arrives buzzes instead of sounding. */
    private boolean silentButBuzz() {
        Prefs pr = new Prefs(this);
        return pr.stealthVibrate() && (curStatus == RadioEngine.STATUS_STEALTH || volumeZero);
    }

    private long batteryReadAt;

    private void readBattery() {
        long now = System.currentTimeMillis();
        if (now - batteryReadAt < 30000) return;
        batteryReadAt = now;
        try {
            Intent i = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return;
            int level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) RadioEngine.myBattery = level * 100 / scale;
            PowerMode.charging = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
        } catch (RuntimeException ignored) {
        }
    }

    private void mergeContacts() {
        Contacts cs = Contacts.get(this);
        RadioEngine e = engine;
        if (e != null) {
            Prefs pr = new Prefs(this);
            int slot = pr.activeSlot();
            for (Contacts.Change c : cs.merge(e.stations(), slotName(slot))) statusLine(c, e.channelTag());
            for (RadioEngine m : e.monitors()) {
                for (Contacts.Change c : cs.merge(m.stations(), letterOf(pr, m))) statusLine(c, m.channelTag());
            }
            checkDrops(e, pr);
            checkBack(e, pr);
            newcomers(e);
        }
        CompatVoice cv = compat;
        if (cv != null) for (CompatVoice.Talker t : cv.talkers()) cs.heard(t.label(), t.lastMs, "OpenMANET", t.address);
    }

    // ---- commander: someone back after being offline, who missed messages ----

    /** The commander view, while on screen: offered "X missed N messages, send?". */
    public interface MissedListener {
        void onMissedWhileOffline(String name, int minutes, int count, String channel, long since);
    }

    public static volatile MissedListener missedListener;
    private static final long OFFLINE_MS = 120000;
    /** Callsign -> last heard, on the channel on air, for the "back after being offline" check. */
    private final java.util.Map<String, Long> backWatch = new java.util.HashMap<String, Long>();

    private void checkBack(RadioEngine e, Prefs pr) {
        for (RadioEngine.Station s : e.stations()) {
            if (s.name == null || s.name.length() == 0 || "?".equals(s.name)) continue;
            Long had = backWatch.get(s.name);
            backWatch.put(s.name, s.lastSeenMs);
            if (had == null || s.lastSeenMs - had < OFFLINE_MS) continue;
            MissedListener l = missedListener;
            if (l == null || !pr.offerMissed()) continue;
            int n = messages.countSince(had, s.lastSeenMs, s.name);
            if (n > 0) l.onMissedWhileOffline(s.name, (int) ((s.lastSeenMs - had) / 60000), n, letterOf(pr, e), had);
        }
    }

    /** "Send" on that offer: everything since then goes out again (others drop what they already have). */
    public void sendMissedSince(long since) {
        if (engine == null || RadioEngine.radioSilent) return;
        messages.resendSince(since);
    }

    // ---- 4WD / convoy: someone drops out, crash detection ----

    private static final long DROP_MS = 120000;
    /** Callsign -> last heard, for everyone heard on the channel on air since joining. */
    private final java.util.Map<String, Long> dropWatch = new java.util.HashMap<String, Long>();

    /** Someone not heard for 2 minutes: a buzz, a notification and a line in Chat, once each time. */
    private void checkDrops(RadioEngine e, Prefs pr) {
        if (!pr.dropAlert() || PowerMode.windowPeriodMs > 0) { // check-in windows have their own overdue notice
            dropWatch.clear();
            return;
        }
        long now = System.currentTimeMillis();
        for (RadioEngine.Station s : e.stations()) {
            if (s.name == null || s.name.length() == 0 || "?".equals(s.name)) continue;
            Long had = dropWatch.get(s.name);
            if (had == null || s.lastSeenMs > had) dropWatch.put(s.name, s.lastSeenMs);
        }
        java.util.Iterator<java.util.Map.Entry<String, Long>> it = dropWatch.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, Long> en = it.next();
            long grace = 0;
            for (RadioEngine.Station s : e.stations()) if (en.getKey().equals(s.name)) grace = s.checkInMs * 2;
            if (now - en.getValue() < Math.max(Math.max(DROP_MS, PowerMode.quietMs()), grace)) continue;
            it.remove();
            if (PlannedOffline.planned(this, en.getKey(), en.getValue())) continue; // said they were going offline
            org.communitycomms.app.msg.MessageLog.Entry m = new org.communitycomms.app.msg.MessageLog.Entry();
            m.id = new java.security.SecureRandom().nextLong();
            m.type = org.communitycomms.app.msg.MessageLog.TYPE_TEXT;
            m.time = now;
            m.creator = en.getKey();
            m.channelTag = e.channelTag();
            m.body = "Lost contact with " + en.getKey() + " (not heard for " + Math.max(DROP_MS, PowerMode.quietMs()) / 60000 + " minutes)";
            m.local = true;
            org.communitycomms.app.msg.MessageLog.get(this).put(m);
            buzz(true);
            notifyMessage(m);
        }
    }

    // ---- automatic key rotation: re-join with the next hour's / day's key at the boundary ----

    private volatile Object[] lastJoin;
    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            Object[] j = lastJoin;
            if (j == null || engine == null || new Prefs(RadioService.this).keyRotation() == Prefs.ROTATE_OFF) return;
            Log.i(TAG, "key rotation: re-joining with the new period's key");
            join((String) j[0], (String) j[1], (Integer) j[2], (Integer) j[3], (String) j[4]);
        }
    };

    private void scheduleRotation() {
        main.removeCallbacks(rotate);
        long ms = Prefs.msToNextRotation(new Prefs(this).keyRotation(), System.currentTimeMillis());
        if (ms > 0) main.postDelayed(rotate, ms + 1500); // just after the boundary
    }

    /** Callsigns heard on the channel on air since joining; someone new gets our pinned announcements. */
    private final java.util.Set<String> heardHere = new java.util.HashSet<String>();
    private int heardTag;

    private void newcomers(RadioEngine e) {
        if (heardTag != e.channelTag()) {
            heardTag = e.channelTag();
            heardHere.clear();
        }
        boolean someoneNew = false;
        for (RadioEngine.Station s : e.stations()) {
            if (s.name == null || "?".equals(s.name)) continue;
            if (heardHere.add(s.name)) someoneNew = true;
        }
        if (someoneNew && messages != null) messages.resendPins();
    }

    private android.hardware.SensorEventListener crashListener;
    private long crashAt;

    /** Crash detection while on air (4WD by default): a jolt over about 4 g opens the "are you OK" screen. */
    public void applyCrashDetect() {
        android.hardware.SensorManager sm = (android.hardware.SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return;
        boolean want = engine != null && new Prefs(this).crashDetect();
        if (want && crashListener == null) {
            android.hardware.Sensor a = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
            if (a == null) return;
            crashListener = new android.hardware.SensorEventListener() {
                @Override
                public void onSensorChanged(android.hardware.SensorEvent ev) {
                    float x = ev.values[0], y = ev.values[1], z = ev.values[2];
                    double g = Math.sqrt(x * x + y * y + z * z) / 9.81;
                    long now = System.currentTimeMillis();
                    if (g > 4.0 && now - crashAt > 60000) {
                        crashAt = now;
                        startActivity(new Intent(RadioService.this, CrashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                }

                @Override
                public void onAccuracyChanged(android.hardware.Sensor s, int acc) {
                }
            };
            sm.registerListener(crashListener, a, android.hardware.SensorManager.SENSOR_DELAY_GAME);
        } else if (!want && crashListener != null) {
            sm.unregisterListener(crashListener);
            crashListener = null;
        }
    }

    /** No answer to "are you OK": rescue map point, message, Call to everyone, and location shared until stopped. */
    @SuppressLint("MissingPermission")
    public void sendCrashAlert() {
        Prefs pr = new Prefs(this);
        String me = pr.callsign().length() == 0 ? "?" : pr.callsign();
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
        android.location.Location l = lm == null ? null : org.communitycomms.app.geo.LocationHelper.bestKnown(lm);
        if (l != null) {
            sendPoi(org.communitycomms.app.geo.Poi.create(l.getLatitude(), l.getLongitude(), 25,
                    "CRASH DETECTED: " + me + " may need help", 12 * 3600000L, me));
        }
        if (messages != null) messages.sendText("CRASH DETECTED: " + me + " did not answer \"are you OK\" and may need help"
                + (l == null ? "" : String.format(java.util.Locale.US, " at %.5f, %.5f", l.getLatitude(), l.getLongitude())));
        RadioEngine e = engine;
        if (e != null) e.sendCall(null);
        if (hasLocationPermission()) setRapidShareUntilStopped();
    }

    /** "TANGO is now Away" in Chat on this phone (status shows yellow / red in Contacts too). */
    private void statusLine(Contacts.Change c, int tag) {
        logLocal(c.name, c.name + " is now " + Contacts.statusName(c.status), tag);
    }

    // ---- possible gunshots* (untested, off by default) ----

    /** Starts or stops the listener to match the setting (called on join and from Settings). */
    public void applyGunshot() {
        RadioEngine e = engine;
        if (e == null) return;
        e.setGunshotDetector(!new Prefs(this).gunshotAlert() ? null
                : new org.communitycomms.app.audio.GunshotDetector(new org.communitycomms.app.audio.GunshotDetector.Alarm() {
            @Override
            public void onPossibleGunshots(final int bangs) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        sendGunshotAlert(bangs);
                    }
                });
            }
        }));
    }

    private void sendGunshotAlert(int bangs) {
        RadioEngine e = engine;
        if (e == null) return;
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
        android.location.Location l = lastFix;
        android.location.Location known = lm == null || !hasLocationPermission() ? null
                : org.communitycomms.app.geo.LocationHelper.bestKnown(lm);
        if (l == null || (known != null && known.getTime() > l.getTime())) l = known;
        if (org.communitycomms.app.geo.FakeGps.isFake(known)) l = known; // fake GPS wins over real fixes
        String me = new Prefs(this).callsign();
        String where = l == null ? ", no GPS fix"
                : " at " + org.communitycomms.app.geo.Geo.latLon(l.getLatitude(), l.getLongitude())
                + " (fix " + org.communitycomms.app.geo.Geo.age(System.currentTimeMillis() - l.getTime()) + ")";
        messages.sendText("POSSIBLE GUNSHOTS detected near " + me + where + ": " + bangs
                + " very loud bangs within 10 s. Automatic alert*, untested: check before acting.");
        if (l != null) e.sendLocation(l.getLatitude(), l.getLongitude(), l.getAccuracy(), l.getTime(), "");
        Log.i(TAG, "gunshot alert sent");
    }

    private String lastCallFrom = "", lastCallWhere = "";

    private Intent callScreen(String from, String where) {
        return new Intent(this, CallAlertActivity.class).putExtra(CallAlertActivity.EXTRA_FROM, from)
                .putExtra(CallAlertActivity.EXTRA_WHERE, where)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    /** The big call screen, over the lock screen. Newer Android only allows it via the notification. */
    private void showCallScreen(String from, String where) {
        lastCallFrom = from;
        lastCallWhere = where;
        if (LightDiscipline.covered && !blackWakes()) return; // the black screen stays black
        try {
            startActivity(callScreen(from, where));
        } catch (RuntimeException e) {
            Log.w(TAG, "call screen", e);
        }
    }

    /** Acknowledge on the call screen: the caller (and the channel) see it was heard. */
    public void acknowledgeCall(String from) {
        callSeen();
        RadioEngine e = engine;
        if (e == null || RadioEngine.radioSilent) return;
        messages.sendText(new Prefs(this).callsign() + " heard the call from " + from);
    }

    @SuppressWarnings("deprecation")
    private void notifyCall(String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 7, open, piFlags);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            // Calls get their own urgent channel so the full-screen call alert is allowed; the ring is our own sound.
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CALL_CHANNEL_ID, "Calls", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CALL_CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        if (!text.contains("not rung")) {
            b.setFullScreenIntent(PendingIntent.getActivity(this, 9, callScreen(lastCallFrom, lastCallWhere),
                    piFlags | PendingIntent.FLAG_UPDATE_CURRENT), true);
        }
        Notification n = b.setContentTitle(text)
                .setContentText(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()))
                .setSmallIcon(R.drawable.ic_stat_radio)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_CALL, n);
    }

    private static final int NOTIF_CALL = 8, NOTIF_FENCE = 13;
    private static final String CALL_CHANNEL_ID = "calls";

    public boolean sendPoi(org.communitycomms.app.geo.Poi p) {
        RadioEngine e = engine;
        org.communitycomms.app.geo.PoiStore.get(this).apply(p);
        if (org.communitycomms.app.geo.Poi.isPersonal(p.note)) return true; // this phone only: never on air
        logPoi(p, true);
        if (e == null) return false;
        messages.notePoiOnAir(p.id);
        e.sendPoiBody(p.encode());
        if (org.communitycomms.app.geo.Poi.isPublic(p.note)) sendPoiOnPublic(e, p);
        return true;
    }

    private volatile org.communitycomms.app.crypto.ChannelCrypto publicCrypto;
    private volatile String publicCryptoKey = "";

    private static final java.util.Map<String, org.communitycomms.app.crypto.ListenKey> listenKeys =
            new java.util.HashMap<String, org.communitycomms.app.crypto.ListenKey>();

    /**
     * For the Public key only, when it was changed from the default (or is a shared listen key): the
     * signing / listen key (slow to work out, so kept). Null for every other channel.
     */
    public org.communitycomms.app.crypto.ListenKey listenKeyIfPublic(String channel) {
        String key = new Prefs(this).publicKey().trim();
        if (!channel.trim().equals(key) || !org.communitycomms.app.crypto.ListenKey.signedPublic(key)) return null;
        synchronized (listenKeys) {
            org.communitycomms.app.crypto.ListenKey lk = listenKeys.get(key);
            if (lk != null) return lk;
        }
        try {
            org.communitycomms.app.crypto.ListenKey lk = org.communitycomms.app.crypto.ListenKey.isListenKey(key)
                    ? org.communitycomms.app.crypto.ListenKey.parse(key) : org.communitycomms.app.crypto.ListenKey.fromFull(key);
            synchronized (listenKeys) {
                listenKeys.put(key, lk);
            }
            return lk;
        } catch (Exception e) {
            Log.w(TAG, "listen key", e);
            return null;
        }
    }

    /** "Also share on Public": the point (and later edits of it) also goes out on the Public channel. */
    private void sendPoiOnPublic(final RadioEngine e, final org.communitycomms.app.geo.Poi p) {
        final String key = new Prefs(this).publicKey();
        if (key.trim().equals(e.channelName().trim())) return; // already on Public
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    org.communitycomms.app.crypto.ListenKey lk = listenKeyIfPublic(key);
                    org.communitycomms.app.crypto.ChannelCrypto c = publicCrypto;
                    if (c == null || !key.equals(publicCryptoKey)) {
                        // A few seconds on old phones, once. A changed Public key is a signed channel.
                        c = new org.communitycomms.app.crypto.ChannelCrypto(lk == null ? key : lk.channelName());
                        publicCrypto = c;
                        publicCryptoKey = key;
                    }
                    e.sendPoiBodyFor(c, lk, p.encode());
                } catch (Exception ex) {
                    Log.w(TAG, "share on Public", ex);
                }
            }
        }, "poi-public").start();
    }

    private boolean hasPoi(long id) {
        for (org.communitycomms.app.geo.Poi q : org.communitycomms.app.geo.PoiStore.get(this).all()) if (q.id == id) return true;
        return false;
    }

    /** POIs also show in the chat view and take part in catch-up sync. */
    private void logPoi(org.communitycomms.app.geo.Poi p, boolean mine) {
        org.communitycomms.app.msg.MessageLog.Entry m = new org.communitycomms.app.msg.MessageLog.Entry();
        m.id = p.id;
        m.type = org.communitycomms.app.msg.MessageLog.TYPE_POI;
        m.version = p.version;
        m.time = System.currentTimeMillis();
        m.creator = mine ? p.editor : p.editor.length() > 0 ? p.editor : p.creator;
        RadioEngine e = engine;
        m.channelTag = e == null ? 0 : e.channelTag();
        String readable = org.communitycomms.app.geo.Poi.readable(p.note);
        m.body = (readable.length() == 0 ? org.communitycomms.app.geo.Poi.ICON_NAMES[p.icon] : readable)
                + "  (" + org.communitycomms.app.geo.Geo.latLon(p.lat, p.lon) + ")";
        m.mine = mine;
        m.removed = p.expired(System.currentTimeMillis());
        org.communitycomms.app.msg.MessageLog.get(this).put(m);
    }

    // ---- spoken POI announcements ----

    private final List<String> poiNews = new ArrayList<String>();
    private final Runnable poiSpeak = new Runnable() {
        @Override
        public void run() {
            List<String> n;
            synchronized (poiNews) {
                n = new ArrayList<String>(poiNews);
                poiNews.clear();
            }
            if (n.isEmpty()) return;
            // A burst (e.g. a POI sync) becomes one short sentence instead of a long list.
            say(n.size() == 1 ? n.get(0) : n.size() + " map points received.");
        }
    };

    private void announcePoi(org.communitycomms.app.geo.Poi p, boolean wasNew) {
        if (!new Prefs(this).announcePoi()) return;
        if (org.communitycomms.app.geo.Poi.quiet(p.note)) return; // the editor chose not to announce it
        String who = p.editor.length() > 0 ? p.editor : p.creator;
        String note = org.communitycomms.app.geo.Poi.readable(p.note);
        String what = note.length() > 0 ? note : org.communitycomms.app.geo.Poi.ICON_NAMES[p.icon].replace("(", "").replace(")", "");
        String text;
        if (p.expired(System.currentTimeMillis())) text = who + " removed map point " + what + ".";
        else text = (wasNew ? "New map point from " : "Map point updated by ") + who + ": " + what + spokenWhere(p) + ".";
        synchronized (poiNews) {
            poiNews.add(text);
        }
        main.removeCallbacks(poiSpeak);
        main.postDelayed(poiSpeak, 1500);
    }

    private static final String[] DIRECTION_WORDS = {"north", "north east", "east", "south east", "south",
            "south west", "west", "north west"};

    /** ", 2.3 kilometres north east" from the last known fix, or "" without one. */
    private String spokenWhere(org.communitycomms.app.geo.Poi p) {
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
        android.location.Location me = lm == null ? null : org.communitycomms.app.geo.LocationHelper.bestKnown(lm);
        if (me == null || System.currentTimeMillis() - me.getTime() > 30 * 60000) return "";
        double m = org.communitycomms.app.geo.Geo.distanceM(me.getLatitude(), me.getLongitude(), p.lat, p.lon);
        double brg = org.communitycomms.app.geo.Geo.bearing(me.getLatitude(), me.getLongitude(), p.lat, p.lon);
        String d = org.communitycomms.app.geo.Geo.spoken(m);
        return ", " + d + " " + DIRECTION_WORDS[(int) Math.round(brg / 45) % 8];
    }

    // ---- chat, reports and catch-up sync ----

    private org.communitycomms.app.msg.MessageCenter messages;
    /** Screen showing the chat; while set, new messages do not raise a notification. */
    public static volatile boolean chatVisible;
    private long lastDigestAt;
    private int keyTicks;
    private final Runnable messageTick = new Runnable() {
        @Override
        public void run() {
            main.postDelayed(this, 10000);
            if (engine == null) return;
            messages.sendDue();
            if (++keyTicks % 6 == 0) messages.sendKey(); // private-message key, once a minute
            Prefs pr = new Prefs(RadioService.this);
            long now = System.currentTimeMillis();
            if (pr.catchUp() && pr.channelCatchUp(pr.activeSlot()) && now - lastDigestAt >= pr.catchUpEveryMin() * 60000L) {
                lastDigestAt = now;
                messages.sendDigest(pr.catchUpWindowMin(), true);
            }
        }
    };

    /**
     * The app came back to the front after `awayMs`: ask everyone now for what was sent meanwhile.
     * Returns the minutes asked for, or 0 when not on air, radio silent or catch-up is off.
     */
    public int catchUpAfterAway(long awayMs) {
        Prefs pr = new Prefs(this);
        if (engine == null || messages == null || RadioEngine.radioSilent || !pr.catchUp()) return 0;
        boolean any = pr.channelCatchUp(pr.activeSlot());
        for (org.communitycomms.app.msg.MessageCenter mc : watchMessages) any |= mc.slot >= 0 && pr.channelCatchUp(mc.slot);
        if (!any) return 0; // no channel set to Request catch-ups (Channels page)
        // The whole catch-up window: others answer with what they hold from it that is not in our list,
        // so listing less would only bring back things this phone already has.
        // Back to the newest message heard from anyone (at least the usual window, at most a day), and at
        // least as long as the app was away.
        int min = Math.max(pr.catchUpWindowMin(), Math.max(messages.minutesSinceLastReceived(), (int) (awayMs / 60000) + 1));
        min = Math.min(org.communitycomms.app.msg.MessageCenter.MAX_WINDOW_MIN, min);
        if (pr.channelCatchUp(pr.activeSlot())) {
            lastDigestAt = System.currentTimeMillis();
            messages.sendDigest(min, true);
            messages.sendVoiceDigest(min, true); // missed live voice, from phones that record all voice
        }
        // Listen-only channels set to Request catch-ups: messages and reports (they take no files).
        for (org.communitycomms.app.msg.MessageCenter mc : watchMessages) {
            if (mc.slot >= 0 && pr.channelCatchUp(mc.slot)) mc.sendDigest(min, true);
        }
        return min;
    }

    public org.communitycomms.app.msg.MessageCenter messages() {
        return messages;
    }

    @Override
    public void onChatMessage(String fromName, byte[] payload, int off) {
        final byte[] copy = payload;
        final int o = off;
        final String n = fromName;
        main.post(new Runnable() {
            @Override
            public void run() {
                messages.onMessage(n, copy, o);
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void notifyMessage(org.communitycomms.app.msg.MessageLog.Entry m) {
        if (silentButBuzz()) buzz(false);
        if (chatVisible) return;
        String text = m.type == org.communitycomms.app.msg.MessageLog.TYPE_REPORT ? "Report: " + reportTitle(m) : m.body;
        Intent open = new Intent(this, ChatActivity.class);
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 5, open, piFlags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Notification n = b.setContentTitle("Message from " + m.creator)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_radio)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_MESSAGE, n);
    }

    private static String reportTitle(org.communitycomms.app.msg.MessageLog.Entry m) {
        org.communitycomms.app.msg.Report r = org.communitycomms.app.msg.Report.parse(m.body);
        return r == null ? "report" : r.title;
    }

    private static final int NOTIF_MESSAGE = 6;

    /** Same key on sender and receivers for a file transfer, so catch-up sync can match them. */
    public static long fileKey(long sender, int transferId) {
        return sender * 1000003L + transferId;
    }

    /** Records a file we just queued for sending (shown in the chat, listed in digests). */
    public static void logFileSent(android.content.Context c, FileTransfer.Outgoing o, String mime, java.io.File f) {
        RadioService s = instance;
        RadioEngine e = s == null ? null : s.engine;
        if (e == null) return;
        org.communitycomms.app.msg.MessageLog.Entry m = new org.communitycomms.app.msg.MessageLog.Entry();
        m.id = fileKey(e.myId(), o.id);
        m.type = org.communitycomms.app.msg.MessageLog.TYPE_FILE;
        m.time = System.currentTimeMillis();
        m.creator = new Prefs(c).callsign();
        m.channelTag = e.channelTag();
        m.body = o.name;
        m.path = f.getPath();
        m.mime = mime == null ? "" : mime;
        m.mine = true;
        org.communitycomms.app.msg.MessageLog.get(c).put(m);
    }

    // ---- scheduled retries of an unacknowledged message ----

    private int retryLeft, retryTotal;
    private long retryInterval, nextRetryAt;
    private final Runnable retryTick = new Runnable() {
        @Override
        public void run() {
            if (retryLeft <= 0) return;
            retryLeft--;
            nextRetryAt = 0;
            if (!resendLastKeepingRetries()) cancelRetries();
        }
    };

    /** Retry the last message after firstDelayMs, then every intervalMs, up to count times. */
    public void scheduleRetries(long firstDelayMs, long intervalMs, int count) {
        cancelRetries();
        retryLeft = retryTotal = Math.max(1, Math.min(99, count));
        retryInterval = intervalMs;
        nextRetryAt = System.currentTimeMillis() + firstDelayMs;
        main.postDelayed(retryTick, firstDelayMs);
    }

    public void cancelRetries() {
        main.removeCallbacks(retryTick);
        retryLeft = 0;
        nextRetryAt = 0;
    }

    /** e.g. "Retry 2 of 5 at 14:30", or null when nothing is scheduled. */
    public String retryStatus() {
        if (retryLeft <= 0) return null;
        String when = nextRetryAt == 0 ? "now"
                : "at " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date(nextRetryAt));
        return "Retry " + (retryTotal - retryLeft + 1) + " of " + retryTotal + " " + when;
    }

    private boolean resendLastKeepingRetries() {
        RadioEngine e = engine;
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_FAILED);
        return e != null && e.resendLast();
    }

    /** Replays the last transmission; true if there was one. */
    public boolean resendLast() {
        RadioEngine e = engine;
        cancelRetries();
        main.removeCallbacks(resendReminder);
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_FAILED);
        return e != null && e.resendLast();
    }

    public void dismissResend() {
        cancelRetries();
        main.removeCallbacks(resendReminder);
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_FAILED);
    }

    public List<String> lastHeard() {
        return lastHeard;
    }

    private void beep(int tone) {
        if (curStatus == RadioEngine.STATUS_STEALTH) {
            if (silentButBuzz()) buzz(false);
            return;
        }
        try {
            final android.media.ToneGenerator tg = new android.media.ToneGenerator(AudioManager.STREAM_VOICE_CALL, 90);
            tg.startTone(tone, 600);
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    tg.release();
                }
            }, 1000);
        } catch (RuntimeException ignored) {
            // No tone hardware: the spoken message and notification still go out.
        }
    }

    /** Text as voice: a message that every phone reads aloud (not in stealth). */
    public static final String SAY = "[say] ";

    public void sayAloud(String from, String text) {
        say(from + " says: " + text);
    }

    /** Spoken on this phone only (offline route directions); nothing in stealth. */
    public void speakLocal(String text) {
        say(text);
    }

    // ---- geofences: the phone that made one alerts when a contact leaves it ----

    /** Inside or not, at the last check, for each fence and contact ("fenceId|name"). */
    private final java.util.Map<String, Boolean> fenceInside = new java.util.HashMap<String, Boolean>();
    private final Runnable fenceTick = new Runnable() {
        @Override
        public void run() {
            try {
                checkFences();
            } catch (RuntimeException e) {
                Log.w(TAG, "geofence", e);
            }
            main.postDelayed(this, 15000);
        }
    };

    /** Cast screen to TV: stops a cast left on for an hour without the app being used (CastWatch). */
    private final Runnable castTick = new Runnable() {
        @Override
        public void run() {
            try {
                CastWatch.check(RadioService.this);
            } catch (RuntimeException e) {
                Log.w(TAG, "cast watch", e);
            }
            main.postDelayed(this, 60000);
        }
    };

    private void checkFences() {
        String me = new Prefs(this).callsign();
        long now = System.currentTimeMillis();
        List<Contacts.Contact> people = null;
        for (org.communitycomms.app.geo.Poi p : org.communitycomms.app.geo.PoiStore.get(this).all()) {
            int mode = org.communitycomms.app.geo.Poi.fence(p.note);
            if (mode == 0 || p.expired(now) || !p.creator.equalsIgnoreCase(me)) continue; // one phone alerts, not all
            org.communitycomms.app.geo.Poi.Shape s = org.communitycomms.app.geo.Poi.shape(p.note);
            if (s == null) continue;
            if (people == null) people = Contacts.get(this).all();
            String fenceName = org.communitycomms.app.geo.Poi.readable(p.note).split("\n")[0].trim();
            if (fenceName.length() == 0) fenceName = "the geofence";
            for (Contacts.Contact c : people) {
                if (Double.isNaN(c.lat) || now - c.fixTimeMs > 15 * 60000L || c.name.equalsIgnoreCase(me)) continue;
                boolean in = org.communitycomms.app.geo.Poi.inside(p.lat, p.lon, s, c.lat, c.lon);
                Boolean was = fenceInside.put(p.id + "|" + c.name, in);
                if (was != null && was && !in) fenceAlert(c.name, fenceName, mode, c.lat, c.lon);
            }
        }
    }

    private void fenceAlert(String who, String fence, int mode, double lat, double lon) {
        String text = who + " has left " + fence;
        alertLocal(text + ".");
        Intent open = new Intent(this, MapActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CALL_CHANNEL_ID, "Calls", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CALL_CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        Notification n = b.setContentTitle("Geofence: " + text)
                .setContentText(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()))
                .setSmallIcon(R.drawable.ic_stat_radio)
                .setContentIntent(PendingIntent.getActivity(this, 12, open, piFlags))
                .setAutoCancel(true)
                .build();
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_FENCE, n);
        if (mode == org.communitycomms.app.geo.Poi.FENCE_TELL_CHANNEL) {
            sendAlertText("Geofence: " + text + " (at " + org.communitycomms.app.geo.Geo.latLon(lat, lon) + ")");
        }
    }

    /** An alert: a warning tone and spoken words on this phone (stealth: a buzz only). */
    public void alertLocal(String text) {
        beep(android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK);
        if (curStatus == RadioEngine.STATUS_STEALTH) return;
        say(text);
    }

    /** A plain chat message to everyone on the channel on air (alerts). False when not on air. */
    public boolean sendAlertText(String text) {
        if (engine == null || RadioEngine.radioSilent) return false;
        return messages.sendText(text);
    }

    @SuppressWarnings("deprecation")
    private void say(String text) {
        Log.i(TAG, "say: " + text);
        if (!ttsReady || curStatus == RadioEngine.STATUS_STEALTH) return; // stealth: nothing spoken
        java.util.HashMap<String, String> p = new java.util.HashMap<String, String>();
        p.put(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM, String.valueOf(AudioManager.STREAM_VOICE_CALL));
        tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, p);
    }

    @SuppressWarnings("deprecation")
    private void notifyFailed(boolean reminder) {
        Intent open = new Intent(this, MainActivity.class);
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 3, open, piFlags);
        PendingIntent resend = PendingIntent.getService(this, 4,
                new Intent(this, RadioService.class).setAction(ACTION_RESEND), piFlags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Notification n = b.setContentTitle(reminder ? "Resend your last message?" : "Transmission failed")
                .setContentText("Nobody acknowledged it.")
                .setSmallIcon(R.drawable.ic_stat_radio)
                .setContentIntent(pi)
                .addAction(R.drawable.ic_stat_radio, "Resend", resend)
                .setAutoCancel(true)
                .build();
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_FAILED, n);
    }

    private static volatile RadioService instance;
    private final List<String[]> pendingOffers = new ArrayList<String[]>();

    public static final int SEND_OK = 0, SEND_NOT_ON_AIR = 1, SEND_PUBLIC = 2;

    /**
     * Sends sheet text on the channel currently on air. On Public everyone could read it, so that
     * needs allowPublic (the caller has warned and the user said send anyway).
     */
    public static int sendDryad(String text, boolean allowPublic) {
        RadioService s = instance;
        RadioEngine e = s == null ? null : s.engine;
        if (e == null) return SEND_NOT_ON_AIR;
        if (!allowPublic && onPublic()) return SEND_PUBLIC;
        e.sendDryad(text);
        return SEND_OK;
    }

    // ---- file sharing ----

    /** Screen that wants to hear about transfer progress (the Files screen). */
    public static volatile Runnable filesUi;

    public static RadioService instance() {
        return instance;
    }

    public static FileTransfer files() {
        RadioService s = instance;
        RadioEngine e = s == null ? null : s.engine;
        return e == null ? null : e.files();
    }

    public static boolean onPublic() {
        RadioService s = instance;
        RadioEngine e = s == null ? null : s.engine;
        return e != null && new Prefs(s).activeSlot() == Prefs.SLOT_PUBLIC
                && new Prefs(s).publicKey().equals(e.channelName());
    }

    /** App-specific external storage, so received files can be opened by other apps via our provider. */
    public static java.io.File receivedDir(android.content.Context c) {
        java.io.File d = c.getExternalFilesDir("Received");
        if (d == null) d = new java.io.File(c.getFilesDir(), "Received");
        d.mkdirs();
        return d;
    }

    private final FileTransfer.Listener fileListener = new FileTransfer.Listener() {
        @Override
        public void onTransfersChanged() {
            final Runnable r = filesUi;
            if (r != null) main.post(r);
        }

        @Override
        public void onFileReceived(final FileTransfer.Incoming in) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    RadioEngine e = engine;
                    if (voiceCatchUp(in)) return;
                    org.communitycomms.app.msg.MessageLog.Entry m = new org.communitycomms.app.msg.MessageLog.Entry();
                    m.id = fileKey(in.sender, in.id);
                    m.type = org.communitycomms.app.msg.MessageLog.TYPE_FILE;
                    m.time = System.currentTimeMillis();
                    m.creator = in.from;
                    m.channelTag = e == null ? 0 : e.channelTag();
                    m.body = in.result.getName();
                    m.path = in.result.getPath();
                    m.mime = in.mime == null ? "" : in.mime;
                    org.communitycomms.app.msg.MessageLog.get(RadioService.this).put(m);
                    if (org.communitycomms.app.audio.BurstVoice.isBurst(m.body, m.mime)) {
                        // Burst voice: plays by itself like a radio call, no file notification.
                        org.communitycomms.app.audio.BurstVoice.play(in.result);
                        return;
                    }
                    notifyReceived(in);
                    // A settings bundle: the home screen asks whether to accept it.
                    if (ConfigShare.isConfig(in.result)) {
                        ConfigShare.pending = in.result;
                        ConfigShare.pendingFrom = in.from;
                    }
                }
            });
        }
    };

    /**
     * A missed live-voice recording sent by another phone (voice catch-up): it fills the "fetching"
     * line in Chat (unread), under the recording's shared id. True if it was one.
     */
    private boolean voiceCatchUp(FileTransfer.Incoming in) {
        String n = in.result.getName();
        java.util.regex.Matcher mt = java.util.regex.Pattern.compile(
                org.communitycomms.app.msg.MessageCenter.VOICE_FILE_PREFIX + "([0-9a-f]{16})\\.wav").matcher(n);
        if (!mt.lookingAt()) return false;
        long id = new java.math.BigInteger(mt.group(1), 16).longValue();
        org.communitycomms.app.msg.MessageLog log = org.communitycomms.app.msg.MessageLog.get(this);
        org.communitycomms.app.msg.MessageLog.Entry old = log.find(id);
        if (old != null && old.state == org.communitycomms.app.msg.MessageLog.STATE_DONE) {
            android.util.Log.i("VoiceCatchUp", "duplicate " + Long.toHexString(id) + " from " + in.from);
            in.result.delete(); // already had it (another phone sent it too)
            return true;
        }
        RadioEngine e = engine;
        org.communitycomms.app.msg.MessageLog.Entry m = new org.communitycomms.app.msg.MessageLog.Entry();
        m.id = id;
        m.type = org.communitycomms.app.msg.MessageLog.TYPE_FILE;
        m.time = old != null ? old.time : System.currentTimeMillis();
        m.creator = old != null ? old.creator : in.from;
        m.channelTag = old != null ? old.channelTag : e == null ? 0 : e.channelTag();
        int seconds = (int) Math.max(1, (in.result.length() - 44) / (2L * RadioEngine.SAMPLE_RATE));
        m.body = "Missed voice from " + m.creator + " (" + seconds + " s), caught up from " + in.from;
        m.path = in.result.getPath();
        m.mime = "audio/wav";
        m.local = true; // shareable in a later voice catch-up, never in the normal digest
        android.util.Log.i("VoiceCatchUp", "got " + Long.toHexString(id) + " from " + in.from);
        log.put(m);
        return true;
    }

    @SuppressWarnings("deprecation")
    private void notifyReceived(FileTransfer.Incoming in) {
        Intent open = new Intent(this, FilesActivity.class);
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, piFlags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Notification n = b.setContentTitle("File from " + in.from)
                .setContentText(in.result.getName())
                .setSmallIcon(R.drawable.ic_stat_radio)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(1000 + (in.id & 0xffff), n);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        watchScreen();
        instance = this;
        restoreRapidShare(); // still sharing after a crash: carries on once back on a channel
        messages =new org.communitycomms.app.msg.MessageCenter(this, new org.communitycomms.app.msg.MessageCenter.Air() {
            @Override
            public RadioEngine engine() {
                return engine;
            }

            @Override
            public void onNewMessage(org.communitycomms.app.msg.MessageLog.Entry m) {
                if (CalStore.is(m.body)) {
                    CalStore.onMessage(RadioService.this, m); // a team calendar entry: no chat notification, its reminder is set
                    return;
                }
                if (PlannedOffline.is(m.body)) PlannedOffline.onMessage(RadioService.this, m);
                if (m.body != null && m.body.contains(OVERDUE_TAG)) onOverdueMessage(m);
                notifyMessage(m);
                onLocationRequest(m);
                Cw.onText(RadioService.this, m);
            }
        });
        main.postDelayed(messageTick, 10000);
        main.postDelayed(fenceTick, 20000);
        main.postDelayed(castTick, 60000);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                CalStore.rescheduleAll(RadioService.this); // reminders do not survive the phone restarting
            }
        }, 15000);
        Prefs startPrefs = new Prefs(this);
        curStatus = startPrefs.status();
        RadioEngine.myStatus = curStatus;
        org.communitycomms.app.audio.AudioLevels.muted = curStatus == RadioEngine.STATUS_STEALTH;
        main.postDelayed(statusTick, 2000);
        new Thread(new Runnable() {
            @Override
            public void run() {
                org.communitycomms.app.msg.PrivateKeys.get(RadioService.this); // make the key pair early (slow once)
            }
        }, "pm-keygen").start();
        // Spoken alerts use whatever offline voice the phone has; beeps still work without one.
        tts = new android.speech.tts.TextToSpeech(this, new android.speech.tts.TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS;
            }
        });
    }

    @Override
    public void onDryadReceived(final String fromName, final String text) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Ui u = ui;
                if (u != null) u.onDryadOffer(fromName, text);
                else {
                    pendingOffers.add(new String[]{fromName, text});
                    android.widget.Toast.makeText(RadioService.this, fromName
                            + " sent DRYAD sheets. Open the radio screen to import them.",
                            android.widget.Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private static final String TAG = "RadioService";
    private static final String CHANNEL_ID = "radio";
    private static final int NOTIF_ID = 1;

    public final class LocalBinder extends Binder {
        public RadioService service() {
            return RadioService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile RadioEngine engine;
    private volatile Ui ui;
    private String status = "Not on a channel";
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CHECK_IN.equals(intent.getAction())) {
            if (PowerMode.deepSleeping && !asleep) deepCheckIn();
            else checkIn();
            return START_STICKY;
        }
        if (intent != null && ACTION_RESEND.equals(intent.getAction())) {
            if (engine != null) {
                resendLast();
                return START_STICKY;
            }
        }
        goForeground("Starting");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(screenWatch);
        try {
            unregisterReceiver(powerWatch);
        } catch (IllegalArgumentException ignored) {
        }
        if (motion != null) motion.stop();
        motion = null;
        cancelCheckIn();
        main.removeCallbacks(endCheckIn);
        if (instance == this) instance = null;
        rapidShareUntil = 0; // a deliberate stop (Exit) ends sharing; a crash never gets here
        saveRapidShare();
        main.removeCallbacks(resendReminder);
        main.removeCallbacks(messageTick);
        main.removeCallbacks(statusTick);
        main.removeCallbacks(poiSpeak);
        if (tts != null) tts.shutdown();
        leave();
        super.onDestroy();
    }

    public void clearUi(Ui old) {
        if (ui == old) ui = null;
    }

    public void setUi(Ui ui) {
        this.ui = ui;
        if (ui != null) {
            ui.onJoinState(airWords(status), engine != null);
            for (String[] o : pendingOffers) ui.onDryadOffer(o[0], o[1]);
            pendingOffers.clear();
        }
    }

    public RadioEngine engine() {
        return engine;
    }

    // ---- channel ----

    public void join(final String channel, final String callsign, final int netMode, final int port,
                     final String extraPeers) {
        // Re-joining (another channel, or a setting changed): the old radio stays on air while the
        // slow key derivation runs, and is only swapped out at the end, so there is next to no gap.
        final int gen;
        synchronized (joinGen) {
            gen = joinGen.incrementAndGet();
        }
        final boolean wasOnAir = engine != null || compat != null;
        publishState(wasOnAir ? status + " · switching…" : "Deriving channel key...", wasOnAir);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Key rotation (Settings): the name plus this hour's / day's number. A changed Public
                    // key makes Public a signed channel (shareable read-only listen key).
                    org.communitycomms.app.crypto.ListenKey lk = listenKeyIfPublic(channel);
                    RadioEngine e = lk != null ? new RadioEngine(channel, lk, callsign, RadioService.this)
                            : new RadioEngine(new Prefs(RadioService.this).effectiveChannel(channel), callsign, RadioService.this);
                    lastJoin = new Object[]{channel, callsign, netMode, port, extraPeers};
                    // Key derivation takes seconds on old phones. If Leave or another Join came
                    // meanwhile, this join is stale: going on air now would leave a second, hidden
                    // radio running that hears this phone's own sends as if from someone else.
                    if (gen != joinGen.get()) return;
                    stopRadio(); // the old one (same port): only now, with the new one ready to start
                    String ifName = new Prefs(RadioService.this).netInterface();
                    NetworkInterface iface = ifName.length() == 0 ? null : NetworkInterface.getByName(ifName);
                    if (ifName.length() > 0 && (iface == null || !iface.isUp())) {
                        throw new java.io.IOException("network \"" + ifName + "\" is not connected");
                    }
                    List<InetSocketAddress> dests = destinations(netMode, port, extraPeers, iface);
                    InetAddress group = InetAddress.getByName(UdpLink.DEFAULT_GROUP);
                    acquireLocks();
                    setCommunicationAudio(true);
                    mainPan = stereoPan();
                    e.setPan(mainPan);
                    e.start(port, dests, group, iface);
                    e.startFiles(receivedDir(RadioService.this), new java.io.File(getCacheDir(), "parts"), fileListener);
                    String watching = startDualWatch(e, callsign);
                    synchronized (joinGen) {
                        if (gen != joinGen.get()) {
                            e.stop(true); // superseded while starting: never stay on air
                            if (engine == null && compat == null) {
                                releaseLocks();
                                setCommunicationAudio(false);
                            }
                            return;
                        }
                        engine = e;
                    }
                    applyTunnel(e);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            updateLocationLoop(); // carries on a rapid share across an A/B switch
                            applyStatus(curStatus); // away / stealth recording on the new channel
                            checkStatusNow();
                            applyGunshot();
                            applyBroadcast();
                            applyAdsbShare();
                            applyAisShare();
                            applyCrashDetect();
                            scheduleRotation();
                            new Prefs(RadioService.this).setWasOnAir(true);
                        }
                    });
                    // Catch up on what was sent while we were away, and send anything now due.
                    lastDigestAt = System.currentTimeMillis();
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (engine == null) return;
                            Prefs pr = new Prefs(RadioService.this);
                            if (pr.catchUp() && pr.channelCatchUp(pr.activeSlot())) messages.sendDigest(pr.catchUpWindowMin(), true);
                            messages.sendDue();
                            messages.sendKey();
                        }
                    }, 3000);
                    // The name is the passcode, so it is never shown in the status or notification.
                    String s = "On air, channel tag " + Integer.toHexString(e.channelTag()) + watching
                            + (org.communitycomms.app.net.Tunnel.client != null ? (org.communitycomms.app.net.Tunnel.onlyTags
                            ? " · internet tunnel on for some channels" : " · internet tunnel on (and the WiFi here)")
                            : org.communitycomms.app.net.Tunnel.relay != null ? " · tunnel relay on" : "")
                            + (org.communitycomms.app.net.Tunnel.vps == null ? "" : org.communitycomms.app.net.Tunnel.vps.gateway
                            ? " · internet relay gateway" : " · internet relay");
                    publishState(s, true);
                    goForeground(s);
                } catch (Exception ex) {
                    Log.e(TAG, "join failed", ex);
                    releaseLocks();
                    publishState("Join failed: " + ex.getMessage(), false);
                }
            }
        }, "join").start();
    }

    // ---- dual watch: listening to more channels than the one on air ----

    private int mainPan;

    /**
     * Stereo dual watch, only with exactly one extra channel: the earlier of the pair (A, B, C…,
     * Public) in the left ear, the other in the right. 0 otherwise.
     */
    private int stereoPan() {
        Prefs pr = new Prefs(this);
        int slot = pr.activeSlot();
        if (!pr.dualWatch() || !pr.dualWatchPlay() || !pr.dualWatchStereo()) return 0;
        List<Integer> w = pr.watchSlots(slot);
        if (w.size() != 1) return 0;
        return orderIndex(slot) < orderIndex(w.get(0)) ? -1 : 1;
    }

    private static int orderIndex(int slot) {
        int[] o = Prefs.order();
        for (int i = 0; i < o.length; i++) if (o[i] == slot) return i;
        return 0;
    }

    /** Messages and reports on each channel listened to. */
    private final List<org.communitycomms.app.msg.MessageCenter> watchMessages =
            new java.util.concurrent.CopyOnWriteArrayList<org.communitycomms.app.msg.MessageCenter>();

    /** Channel tags of the channels being listened to (not talked on); empty when none. */
    public int[] watchTags() {
        RadioEngine e = engine;
        if (e == null) return new int[0];
        List<RadioEngine> ms = e.monitors();
        int[] out = new int[ms.size()];
        for (int i = 0; i < out.length; i++) out[i] = ms.get(i).channelTag();
        return out;
    }

    /** The channels on air now, main first, as slots ("0,1"). */
    public String slotsOnAir() {
        Prefs pr = new Prefs(this);
        StringBuilder out = new StringBuilder().append(pr.activeSlot());
        RadioEngine e = engine;
        if (e != null) {
            for (RadioEngine m : e.monitors()) {
                int s = slotNamed(pr, m.channelName());
                if (s >= 0) out.append(',').append(s);
            }
        }
        return out.toString();
    }

    // ---- teams (a channel is a team): message, call and broadcast to channels only listened to ----

    /** The engine for a channel: the one on air, or a listen-only one; null when not on it. */
    public RadioEngine engineFor(int slot) {
        RadioEngine e = engine;
        if (e == null) return null;
        Prefs pr = new Prefs(this);
        if (pr.channel(slot).trim().length() == 0) return null;
        String name = pr.effectiveChannel(pr.channel(slot)).trim(); // with key rotation, this period's name
        if (name.equals(e.channelName().trim())) return e;
        for (RadioEngine m : e.monitors()) if (name.equals(m.channelName().trim())) return m;
        return null;
    }

    /** A text to everyone on that channel (team). False when not on it or radio silent. */
    public boolean sendTextTo(int slot, String text) {
        RadioEngine target = engineFor(slot);
        if (target == null || RadioEngine.radioSilent) return false;
        if (target == engine) return messages.sendText(text);
        for (org.communitycomms.app.msg.MessageCenter mc : watchMessages) {
            if (mc.onAir() == target) return mc.sendText(text);
        }
        return false;
    }

    /** Rings one person (or everyone, name null) on a channel talked or listened on. */
    public boolean callOn(int slot, String name) {
        RadioEngine target = engineFor(slot);
        return target != null && target.sendCall(name);
    }

    /** Channels (slots) a broadcast goes to as well as the one on air; empty = no broadcast. */
    public java.util.Set<Integer> broadcastSlots() {
        java.util.Set<Integer> out = new java.util.TreeSet<Integer>();
        for (String s : new Prefs(this).broadcast().split(",")) {
            try {
                if (s.length() > 0) out.add(Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** Broadcast on (talking also goes to these listened channels) or off (empty). */
    public void setBroadcast(java.util.Set<Integer> slots) {
        StringBuilder sb = new StringBuilder();
        for (int s : slots) sb.append(sb.length() == 0 ? "" : ",").append(s);
        new Prefs(this).setBroadcast(sb.toString());
        applyBroadcast();
    }

    /** Points the engine on air at the listen-only engines of the broadcast channels (after every join). */
    private void applyBroadcast() {
        RadioEngine e = engine;
        if (e == null) return;
        List<RadioEngine> targets = new ArrayList<RadioEngine>();
        for (int s : broadcastSlots()) {
            RadioEngine m = engineFor(s);
            if (m != null && m != e) targets.add(m);
        }
        e.setBroadcast(targets);
    }

    /** Slots being listened to right now (listen-only engines that started), for the screens. */
    public List<Integer> watchingSlots() {
        List<Integer> out = new ArrayList<Integer>();
        RadioEngine e = engine;
        if (e == null) return out;
        Prefs pr = new Prefs(this);
        java.util.Set<String> names = new java.util.HashSet<String>();
        for (RadioEngine m : e.monitors()) names.add(m.channelName().trim());
        for (int s : pr.watchSlots(pr.activeSlot())) if (names.contains(pr.effectiveChannel(pr.channel(s)).trim())) out.add(s);
        return out;
    }

    /**
     * Also listens to other channels (the other of A/B by default, or any ticked Listen: A–H,
     * Public): one listen-only engine each, fed by the main engine's socket, only ever sending acks.
     * Their voice can be played (unless that channel is muted) and/or saved, and their messages and
     * reports go to the chat. Returns text for the status line. Runs on the join thread (key
     * derivation is slow, a second or two per channel on old phones).
     */
    private String startDualWatch(RadioEngine on, String callsign) {
        Prefs pr = new Prefs(this);
        watchMessages.clear();
        List<Integer> slots = pr.watchSlots(pr.activeSlot());
        StringBuilder ok = new StringBuilder(), failed = new StringBuilder();
        for (int other : slots) {
            String letter = Prefs.letter(other);
            try {
                startWatch(on, callsign, pr, other);
                ok.append(ok.length() == 0 ? "" : ", ").append(letter);
            } catch (Exception ex) {
                Log.e(TAG, "dual watch failed on " + letter, ex);
                failed.append(failed.length() == 0 ? "" : ", ").append(letter);
            }
        }
        String on2 = pr.channel(pr.activeSlot()).trim();
        int extra = 0;
        for (ChannelArray.Row r : ChannelArray.load(this)) {
            if (r.listen && !r.priv.equals(on2)) extra += watchExtra(on, callsign, pr, r.priv, r.code, r.hear);
        }
        for (PrivateContacts.Person p : PrivateContacts.load(this)) {
            if (!p.key.equals(on2)) extra += watchExtra(on, callsign, pr, p.key, p.name, true);
        }
        String letters = ok.toString();
        if (slots.isEmpty()) return extra == 0 ? "" : " · also listening to " + extra + " teams and contacts";
        return (letters.length() == 0 ? "" : " · also listening to " + letters
                + (mainPan != 0 ? " (stereo: " + (mainPan < 0 ? "this channel left, " + letters + " right)"
                : letters + " left, this channel right)") : "")
                + (pr.dualWatchSave() ? " (saving voice)" : ""))
                + (extra == 0 ? "" : " · and " + extra + " teams and contacts")
                + (failed.length() == 0 ? "" : " · could not listen to " + failed);
    }

    private int watchExtra(RadioEngine on, String callsign, Prefs pr, String key, String label, boolean play) {
        try {
            watchLabels.put(key.trim(), label);
            startWatch(on, callsign, pr, key, label, -1, play, true);
            return 1;
        } catch (Exception ex) {
            Log.e(TAG, "could not listen to " + label, ex);
            return 0;
        }
    }

    private void startWatch(RadioEngine on, String callsign, Prefs pr, int other) throws Exception {
        startWatch(on, callsign, pr, pr.channel(other), Prefs.letter(other), other, pr.dualWatchPlay() && !pr.channelMuted(other), pr.dualWatchSave() || pr.recordAllVoice());
    }

    private void startWatch(RadioEngine on, String callsign, Prefs pr, String key, final String letter, final int other, boolean play, boolean save) throws Exception {
        final org.communitycomms.app.msg.MessageCenter[] mc = new org.communitycomms.app.msg.MessageCenter[1];
        final org.communitycomms.app.crypto.ListenKey watchLk = listenKeyIfPublic(key);
        final RadioEngine m = new RadioEngine(watchLk != null ? key : pr.effectiveChannel(key), watchLk,
                callsign, new RadioEngine.Listener() {
            @Override
            public void onRosterChanged() {
            }

            @Override
            public void onTransmitChanged(boolean transmitting) {
            }

            @Override
            public void onDryadReceived(String fromName, String text) {
            }

            @Override
            public void onTransmitResult(List<String> heardBy) {
            }

            @Override
            public void onPoiMessage(String fromName, byte[] payload, int off) {
                // Not taken in: POIs from a watched channel would be re-shared on the one on air.
            }

            @Override
            public void onCall(String fromName) {
                ringFor(fromName, " on " + letter);
            }

            @Override
            public void onChatMessage(final String fromName, final byte[] payload, final int off) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mc[0] != null && watchMessages.contains(mc[0])) mc[0].onMessage(fromName, payload, off);
                    }
                });
            }
        });
        VoiceRecorder rec = !save ? null : new VoiceRecorder(receivedDir(this), letter,
                new VoiceRecorder.Listener() {
                    @Override
                    public void onRecording(java.io.File f, String from, int seconds, long voiceId) {
                        org.communitycomms.app.msg.MessageLog.Entry en = new org.communitycomms.app.msg.MessageLog.Entry();
                        en.id = voiceId;
                        en.type = org.communitycomms.app.msg.MessageLog.TYPE_FILE;
                        en.time = System.currentTimeMillis() - seconds * 1000L;
                        en.creator = from;
                        en.channelTag = m.channelTag();
                        en.body = "Voice on " + letter + " from " + from + " (" + Math.max(1, seconds) + " s)";
                        en.path = f.getPath();
                        en.mime = "audio/wav";
                        en.local = true;
                        org.communitycomms.app.msg.MessageLog.get(RadioService.this).put(en);
                    }
                });
        m.setPan(-mainPan);
        m.startMonitor(play, rec);
        mc[0] = new org.communitycomms.app.msg.MessageCenter(this, new org.communitycomms.app.msg.MessageCenter.Air() {
            @Override
            public RadioEngine engine() {
                return m;
            }

            @Override
            public void onNewMessage(org.communitycomms.app.msg.MessageLog.Entry en) {
                notifyMessage(en);
            }
        });
        mc[0].slot = other;
        watchMessages.add(mc[0]);
        on.addMonitor(m);
    }

    // ---- experimental OpenMANET / ATAK Vx voice ----

    private volatile CompatVoice compat;

    public CompatVoice compat() {
        return compat;
    }

    /** Joins an OpenMANET talk group ("address:port"). Unencrypted, half duplex. */
    public void joinCompat(final String groupAndPort) {
        leave();
        final int gen = joinGen.get();
        publishState("Joining talk group…", false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String ifName = new Prefs(RadioService.this).netInterface();
                    NetworkInterface iface = ifName.length() == 0 ? null : NetworkInterface.getByName(ifName);
                    acquireLocks();
                    setCommunicationAudio(true);
                    CompatVoice c = new CompatVoice(groupAndPort, iface, new CompatVoice.Listener() {
                        @Override
                        public void onTalkersChanged() {
                            onRosterChanged();
                        }

                        @Override
                        public void onTransmitChanged(boolean on) {
                            RadioService.this.onTransmitChanged(on);
                        }
                    });
                    synchronized (joinGen) {
                        if (gen != joinGen.get()) {
                            c.stop(); // superseded while starting
                            return;
                        }
                        compat = c;
                    }
                    String s = "EXPERIMENTAL OpenMANET/ATAK · " + c.describe() + " · NOT encrypted · half duplex";
                    publishState(s, true);
                    goForeground("OpenMANET talk group (experimental)");
                } catch (Exception ex) {
                    Log.e(TAG, "compat join failed", ex);
                    releaseLocks();
                    setCommunicationAudio(false);
                    publishState("Join failed: " + ex.getMessage(), false);
                }
            }
        }, "join-compat").start();
    }

    /** Bumped by every leave (and so every join): a join still starting up checks it is current. */
    private final java.util.concurrent.atomic.AtomicInteger joinGen = new java.util.concurrent.atomic.AtomicInteger();

    public void leave() {
        synchronized (joinGen) {
            joinGen.incrementAndGet();
        }
        txLocationOn = false;
        main.post(new Runnable() {
            @Override
            public void run() {
                updateLocationLoop();
            }
        });
        if (stopRadio()) setCommunicationAudio(false);
        applyCrashDetect(); // off air: stops watching for jolts
        main.removeCallbacks(rotate);
        dropWatch.clear();
        org.communitycomms.app.net.Tunnel.Client.stopClient();
        org.communitycomms.app.net.Tunnel.Client.stopVps();
        org.communitycomms.app.net.Tunnel.Relay.stopRelay();
        main.removeCallbacks(vpsWatch);
        cancelLinkNotice();
        releaseLocks();
        publishState("Not on a channel", false);
    }

    /**
     * Tunnel comms, started with each join: a remote phone connects to its relay, sending the tags
     * of every channel it is on; a relay opens its remotes' ports. Off when neither is set.
     */
    private void applyTunnel(RadioEngine e) {
        applyTunnel(e, true);
    }

    /** The slot whose channel has this name (A–H, Public), or -1. */
    private static int slotNamed(Prefs pr, String name) {
        String n = name == null ? "" : name.trim();
        for (int s : Prefs.order()) {
            String c = s == Prefs.SLOT_PUBLIC ? pr.publicKey() : pr.effectiveChannel(pr.channel(s));
            if (c != null && c.trim().equals(n) && n.length() > 0) return s;
        }
        return -1;
    }

    /** Channels page: a tunnel icon or the all-channels switch changed (no rejoin needed). */
    public void applyTunnelNow() {
        RadioEngine e = engine;
        if (e != null) applyTunnel(e, false);
    }

    /**
     * Which channels go through the tunnel: all of them (Tunnel comms on), or only those with their
     * tunnel icon on (Channels page). restart = on joining (a fresh connection); otherwise only the
     * channel list changes, sent in the next HELLO.
     */
    private void applyTunnel(RadioEngine e, boolean restart) {
        Prefs pr = new Prefs(this);
        boolean all = pr.tunnelClientOn();
        List<Integer> tl = new ArrayList<Integer>();
        if (all || pr.tunnelSlot(pr.activeSlot())) tl.add(e.channelTag());
        for (RadioEngine m : e.monitors()) {
            int s = slotNamed(pr, m.channelName());
            if (all || (s >= 0 && pr.tunnelSlot(s))) tl.add(m.channelTag());
        }
        int[] t = new int[tl.size()];
        for (int i = 0; i < t.length; i++) t[i] = tl.get(i);
        org.communitycomms.app.net.Tunnel.tags = t;
        org.communitycomms.app.net.Tunnel.onlyTags = !all;
        // Relay mode "vps" leaves the direct tunnel client out (a direct relay on this phone still runs).
        boolean direct = !"vps".equals(pr.relayMode());
        if (direct && pr.tunnelSetUp() && (all || t.length > 0) && !PowerMode.offline()) {
            org.communitycomms.app.net.Tunnel.Relay.stopRelay();
            if (restart || org.communitycomms.app.net.Tunnel.client == null) {
                org.communitycomms.app.net.Tunnel.Client.start(pr.tunnelServer(), pr.tunnelKey());
            }
        } else {
            org.communitycomms.app.net.Tunnel.Client.stopClient();
            if (pr.tunnelRelayOn() && pr.tunnelRemotes().trim().length() > 0) {
                org.communitycomms.app.net.Tunnel.Relay.start(pr.tunnelRemotes());
            } else {
                org.communitycomms.app.net.Tunnel.Relay.stopRelay();
            }
        }
        applyVps(e, restart);
    }

    // ---- internet relay (VPS): gateway on the MANET, or a remote off it ----

    private volatile boolean vpsOnManet;
    private String vpsConfig = "";

    /** Settings > Internet relay changed: reconnect with the new settings (no re-join). */
    public void applyVpsNow() {
        RadioEngine e = engine;
        if (e != null) applyVps(e, true);
    }

    /**
     * On the MANET (gateway): bridges the channels marked "share to internet". Off it (remote):
     * every channel this phone is on goes through the relay. restart = connect afresh.
     */
    private void applyVps(RadioEngine e, boolean restart) {
        Prefs pr = new Prefs(this);
        main.removeCallbacks(vpsWatch);
        if (!pr.vpsSetUp() || PowerMode.offline()) {
            org.communitycomms.app.net.Tunnel.Client.stopVps();
            vpsConfig = "";
            return;
        }
        org.communitycomms.app.net.Tunnel.listener = vpsListener;
        boolean onManet = onManet(pr);
        vpsOnManet = onManet;
        List<Integer> tl = new ArrayList<Integer>();
        if (!onManet || pr.shareSlot(pr.activeSlot())) tl.add(e.channelTag());
        for (RadioEngine m : e.monitors()) {
            int s = slotNamed(pr, m.channelName());
            if (!onManet || (s >= 0 && pr.shareSlot(s))) tl.add(m.channelTag());
        }
        int[] t = new int[tl.size()];
        for (int i = 0; i < t.length; i++) t[i] = tl.get(i);
        String cfg = pr.vpsServer() + "|" + pr.vpsKey().hashCode() + "|" + onManet;
        org.communitycomms.app.net.Tunnel.Client v = org.communitycomms.app.net.Tunnel.vps;
        if (onManet && t.length == 0) {
            // A gateway with nothing shared has nothing to bridge.
            org.communitycomms.app.net.Tunnel.Client.stopVps();
            vpsConfig = "";
        } else if (restart || v == null || !cfg.equals(vpsConfig)) {
            vpsConfig = cfg;
            vpsDownNotified = false;
            org.communitycomms.app.net.Tunnel.Client.startVps(pr.vpsServer(), pr.vpsKey(), onManet, t, pr.airName());
        } else {
            v.setTags(t);
        }
        if ("auto".equals(pr.manetRole())) main.postDelayed(vpsWatch, 15000);
    }

    /** Auto-detect: re-check the WiFi address every 15 s; a change of network switches gateway / remote. */
    private final Runnable vpsWatch = new Runnable() {
        @Override
        public void run() {
            RadioEngine e = engine;
            if (e == null) return;
            Prefs pr = new Prefs(RadioService.this);
            if (pr.vpsSetUp() && onManet(pr) != vpsOnManet) {
                Log.i(TAG, "internet relay: network changed, now " + (vpsOnManet ? "off" : "on") + " the MANET");
                applyVps(e, true);
                publishState(status, true);
            } else {
                main.postDelayed(this, 15000);
            }
        }
    };

    /** "I am on the MANET", "off", or auto: a WiFi (not mobile data) IPv4 address inside the MANET range. */
    static boolean onManet(Prefs pr) {
        String role = pr.manetRole();
        if ("on".equals(role)) return true;
        if ("off".equals(role)) return false;
        return inRange(localIpv4(), pr.manetRange());
    }

    /** IPv4 addresses of the interfaces that are up, leaving out mobile data and loopback. */
    static List<java.net.Inet4Address> localIpv4() {
        List<java.net.Inet4Address> out = new ArrayList<java.net.Inet4Address>();
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                String n = ni.getName().toLowerCase(java.util.Locale.US);
                if (!ni.isUp() || ni.isLoopback() || n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp")
                        || n.startsWith("v4-rmnet") || n.startsWith("clat")) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address) out.add((java.net.Inet4Address) a);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** True if any address is inside "a.b.c.d/bits" (or a bare address = /32). */
    static boolean inRange(List<java.net.Inet4Address> addrs, String cidr) {
        try {
            String[] p = cidr.trim().split("/");
            int bits = p.length > 1 ? Integer.parseInt(p[1].trim()) : 32;
            byte[] net = java.net.InetAddress.getByName(p[0].trim()).getAddress();
            if (net.length != 4 || bits < 0 || bits > 32) return false;
            int n = ((net[0] & 0xff) << 24) | ((net[1] & 0xff) << 16) | ((net[2] & 0xff) << 8) | (net[3] & 0xff);
            int mask = bits == 0 ? 0 : -1 << (32 - bits);
            for (java.net.Inet4Address a : addrs) {
                byte[] b = a.getAddress();
                int x = ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
                if ((x & mask) == (n & mask)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static final int NOTIF_LINK = 4721;
    private volatile boolean vpsDownNotified;

    private final org.communitycomms.app.net.Tunnel.Listener vpsListener = new org.communitycomms.app.net.Tunnel.Listener() {
        @Override
        public void onLink(final org.communitycomms.app.net.Tunnel.Client c, final boolean up, final long outageMs) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    linkChanged(c, up, outageMs);
                }
            });
        }

        @Override
        public void onSites(org.communitycomms.app.net.Tunnel.Client c, final List<String> wentDown, final List<String> cameUp) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    for (String s : wentDown) if (siteKind(s) == 1) systemLine("Site " + s + " offline (internet relay)");
                    for (String s : cameUp) if (siteKind(s) == 1) systemLine("Site " + s + " back online (internet relay)");
                }
            });
        }

        @Override
        public void onStandby(org.communitycomms.app.net.Tunnel.Client c, boolean standby) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    publishState(status, engine != null);
                }
            });
        }
    };

    private static int siteKind(String name) {
        org.communitycomms.app.net.Tunnel.Client v = org.communitycomms.app.net.Tunnel.vps;
        if (v == null) return 0;
        for (org.communitycomms.app.net.Tunnel.Site s : v.sites) if (s.name.equals(name)) return s.kind;
        return 0;
    }

    private void linkChanged(org.communitycomms.app.net.Tunnel.Client c, boolean up, long outageMs) {
        if (c != org.communitycomms.app.net.Tunnel.vps || engine == null) return;
        Prefs pr = new Prefs(this);
        String secs = org.communitycomms.app.net.Tunnel.DOWN_MS / 1000 + " s";
        if (!up && c.revoked) { // the relay's admin removed this key (BYE): it will not come back by itself
            vpsDownNotified = false;
            notifyLink("Removed from the internet relay", "The relay's admin removed this " + (c.gateway ? "site" : "phone")
                    + ". Ask them for a new setup QR code.", false);
            systemLine("Internet relay: removed by the relay's admin. Ask for a new setup QR code.");
        } else if (!up) {
            vpsDownNotified = true;
            if (c.gateway) {
                notifyLink("Internet link to relay down", "No answer from the internet relay for " + secs
                        + ". Remote users cannot hear this MANET until it is back.", pr.linkTone());
                // Everyone on this MANET: a line in the channel chat (on the channel on air if it is shared).
                if (pr.shareSlot(pr.activeSlot())) sendAlertText("[relay] Internet link down: remote users cannot hear us for now (gateway " + pr.airName() + ")");
                else systemLine("Internet link to relay down");
                if (pr.linkSpeak()) say("Link down");
            } else {
                systemLine("Internet relay link down: no answer for " + secs);
            }
        } else if (outageMs > 0 && vpsDownNotified) {
            vpsDownNotified = false;
            String len = outageWords(outageMs);
            if (c.gateway) {
                cancelLinkNotice();
                notifyLink("Internet link to relay restored", "Back after " + len + ".", false);
                if (pr.shareSlot(pr.activeSlot())) sendAlertText("[relay] Internet link restored after " + len);
                else systemLine("Internet link to relay restored after " + len);
                if (pr.linkSpeak()) say("Link restored");
            } else {
                systemLine("Internet relay link restored after " + len);
            }
        }
        publishState(status, true);
    }

    static String outageWords(long ms) {
        long s = ms / 1000;
        return s < 120 ? s + " s" : s < 7200 ? (s / 60) + " min" : (s / 3600) + " h " + (s % 3600 / 60) + " min";
    }

    /** A chat line on this phone only (not sent), on the channel on air. */
    private void systemLine(String text) {
        RadioEngine e = engine;
        if (e == null) return;
        org.communitycomms.app.msg.MessageLog.Entry m = new org.communitycomms.app.msg.MessageLog.Entry();
        m.id = new java.util.Random().nextLong();
        m.type = org.communitycomms.app.msg.MessageLog.TYPE_TEXT;
        m.time = System.currentTimeMillis();
        m.creator = "Relay";
        m.channelTag = e.channelTag();
        m.body = text;
        m.local = true;
        org.communitycomms.app.msg.MessageLog.get(this).put(m);
    }

    private void cancelLinkNotice() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_LINK);
    }

    @SuppressWarnings("deprecation")
    private void notifyLink(String title, String text, boolean tone) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            // Two channels, so the silent default and the optional tone can each be changed by the user.
            String id = tone ? "relaylinktone" : "relaylink";
            NotificationChannel ch = new NotificationChannel(id, tone ? "Internet relay link (tone)" : "Internet relay link",
                    tone ? NotificationManager.IMPORTANCE_DEFAULT : NotificationManager.IMPORTANCE_LOW);
            if (!tone) ch.setSound(null, null);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, id);
        } else {
            b = new Notification.Builder(this);
            if (tone) b.setDefaults(Notification.DEFAULT_SOUND);
        }
        int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 17, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), fl);
        nm.notify(NOTIF_LINK, b.setContentTitle(title).setContentText(text).setSmallIcon(R.drawable.ic_stat_radio)
                .setContentIntent(open).setAutoCancel(true).build());
    }

    /** Status line prefix: the internet relay link down, or gateway standby. */
    static String vpsWords() {
        org.communitycomms.app.net.Tunnel.Client v = org.communitycomms.app.net.Tunnel.vps;
        if (v == null) return "";
        if (v.standby) return v.otherGateway.length() > 0 ? "Relay gateway standby (" + v.otherGateway + " is gateway) · " : "";
        if (v.everUp() && !v.linkUp()) return RELAY_DOWN + " · ";
        return "";
    }

    public static final String RELAY_DOWN = "● RELAY LINK DOWN";

    /** Stops whatever radio is on air (keeps the audio mode and locks). True if one was. */
    private synchronized boolean stopRadio() {
        watchMessages.clear();
        CompatVoice c = compat;
        compat = null;
        if (c != null) c.stop();
        RadioEngine e = engine;
        engine = null;
        if (e != null) e.stop();
        return c != null || e != null;
    }

    /** For the app-data wipe: drops off air without sending anything at all, then stops. */
    public void stopSilently() {
        CompatVoice c = compat;
        compat = null;
        if (c != null) c.stop();
        RadioEngine e = engine;
        engine = null;
        if (e != null) e.stop(true);
        org.communitycomms.app.net.Tunnel.Client.stopClient();
        org.communitycomms.app.net.Tunnel.Client.stopVps();
        org.communitycomms.app.net.Tunnel.Relay.stopRelay();
        setCommunicationAudio(false);
        releaseLocks();
        main.removeCallbacksAndMessages(null);
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancelAll();
        stopSelf();
    }

    public void stopEverything() {
        leave();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    /** Offline: never says on air. */
    private static String airWords(String s) {
        String w = PowerMode.offline() ? s.replace("On air", "Offline") : s;
        if (s.startsWith("On air")) w = vpsWords() + w;
        long mu = PowerMode.missionUntil();
        java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
        if (mu > 0 && !missionKey().equals(missionAck)) w = "MISSION MODE until " + hm.format(new java.util.Date(mu)) + " · " + w;
        else if (PowerMode.outsideWindow) w = "RADIO SILENCE, next check-in window about " + hm.format(new java.util.Date(nextWindowAt)) + " · " + w;
        else if (PowerMode.missionOthers() && !missionKey().equals(missionAck)) w = "Mission mode on by " + PowerMode.othersMissionBy + " until "
                + hm.format(new java.util.Date(PowerMode.othersMissionUntil)) + " · " + w;
        return w;
    }

    // ---- radio silence check-in windows and burst sleep windows ----

    static final long OVERDUE_AFTER_MS = 240000, OVERDUE_EXTEND_MS = 300000;
    public static final String OVERDUE_TAG = "\n[overdue:";
    private long windowStart = -1, windowExtendUntil;
    private static volatile long nextWindowAt;
    private boolean windowOverdueDone;
    private java.util.Set<String> heardLastWindow = new java.util.HashSet<String>(), heardThisWindow = new java.util.HashSet<String>();
    private final java.util.Set<String> overdueSaid = new java.util.HashSet<String>();
    private final java.util.Map<String, Integer> windowsMissed = new java.util.HashMap<String, Integer>();

    /** Every 2 s and at each wake-up: inside or outside a silence window, and burst naps. */
    private void windowTick() {
        Prefs p = new Prefs(this);
        RadioEngine e = engine;
        long now = System.currentTimeMillis();
        boolean mission = PowerMode.missionMine() || PowerMode.missionOthers();
        boolean outside = false;
        int per = p.silencePeriodMin();
        if (per > 0 && e != null && compat == null && !mission) {
            long period = per * 60000L, len = p.silenceLenMin() * 60000L;
            long s = CheckInWindows.openNow(e.channelName(), period, len, now);
            if (s >= 0 && s != windowStart) { // a new window opens
                heardLastWindow = heardThisWindow;
                heardThisWindow = new java.util.HashSet<String>();
                overdueSaid.clear();
                windowStart = s;
                windowOverdueDone = false;
                Log.i(TAG, "power: check-in window open");
            }
            boolean open = s >= 0 || now < windowExtendUntil;
            if (open && windowStart >= 0) {
                for (RadioEngine.Station st : e.stations()) {
                    if (st.name != null && st.lastSeenMs >= windowStart) {
                        heardThisWindow.add(st.name);
                        windowsMissed.remove(st.name);
                    }
                }
                if (!windowOverdueDone && now >= windowStart + Math.min(OVERDUE_AFTER_MS, len * 4 / 5)) {
                    windowOverdueDone = true;
                    windowOverdue();
                }
            }
            outside = !open;
            PowerMode.windowPeriodMs = period + len;
        } else {
            PowerMode.windowPeriodMs = 0;
        }
        boolean flip = outside != PowerMode.outsideWindow;
        if (flip) Log.i(TAG, "power: radio silence " + (outside ? "until the next window" : "lifted (window)"));
        PowerMode.outsideWindow = outside;
        if (outside && e != null) nextWindowAt = CheckInWindows.next(e.channelName(), per * 60000L, now);
        if (flip) publishState(status, engine != null);
        RadioEngine.radioSilent = p.radioSilent() || outside;
        // Burst sleep: everyone heard is in burst mode, so wake together 5 s in every 30 s.
        boolean napping = false;
        if (!outside && p.burstSleep() && e != null && compat == null && p.burstNow() && !mission && !PowerMode.anchor
                && !PowerMode.busy() && allBurst(e, now)) {
            napping = !CheckInWindows.burstAwake(now);
            if (!napping && org.communitycomms.app.audio.BurstVoice.waiting(this) > 0 && !RadioEngine.radioSilent) {
                org.communitycomms.app.audio.BurstVoice.sendWaiting(this); // bursts kept during the nap go out now
            }
        }
        if (napping != PowerMode.burstNapping) Log.i(TAG, "power: burst " + (napping ? "nap" : "window"));
        PowerMode.burstNapping = napping;
    }

    /** Everyone heard recently on the channel records voice as bursts (and someone is there). */
    private static boolean allBurst(RadioEngine e, long now) {
        int n = 0;
        for (RadioEngine.Station s : e.stations()) {
            if (now - s.lastSeenMs > Math.max(90000, s.checkInMs + 30000)) continue;
            if (s.voiceMode != 1) return false;
            n++;
        }
        return n > 0;
    }

    /** At the overdue mark: anyone heard in the last window but not this one is named overdue, by one phone. */
    private void windowOverdue() {
        final java.util.List<String> late = new java.util.ArrayList<String>();
        for (String n : heardLastWindow) if (!heardThisWindow.contains(n)) late.add(n);
        if (late.isEmpty()) return;
        // A random short wait, so usually only one phone says it (the others hear it and stay quiet).
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                for (String n : late) {
                    if (heardThisWindow.contains(n) || overdueSaid.contains(n)) continue;
                    org.communitycomms.app.msg.MessageCenter mc = messages;
                    if (mc != null && engine != null && !RadioEngine.radioSilent) {
                        mc.sendText(n + " is overdue for the check-in window." + OVERDUE_TAG + n + "]");
                    }
                    overdueHeard(n);
                }
            }
        }, 2000 + new java.util.Random().nextInt(18000));
    }

    /** "X is overdue" heard (or said): everyone stays awake longer to catch X; two missed windows alert. */
    private void overdueHeard(String name) {
        if (!overdueSaid.add(name)) return;
        windowExtendUntil = Math.max(windowExtendUntil, System.currentTimeMillis() + OVERDUE_EXTEND_MS);
        int missed = (windowsMissed.containsKey(name) ? windowsMissed.get(name) : 0) + 1;
        windowsMissed.put(name, missed);
        if (missed >= 2) {
            Contacts.Contact c = Contacts.get(this).find(name);
            String where = c != null && c.hasFix() ? " Last known position " + org.communitycomms.app.geo.Geo.latLon(c.lat, c.lon) + "." : "";
            notifyMission(name + " missed two check-in windows", "Not heard in the last two windows." + where);
            buzz(true);
        }
    }

    /** A chat line "X is overdue..." arrived. */
    private void onOverdueMessage(org.communitycomms.app.msg.MessageLog.Entry m) {
        int i = m.body.indexOf(OVERDUE_TAG);
        if (i < 0) return;
        String n = m.body.substring(i + OVERDUE_TAG.length());
        if (n.endsWith("]")) n = n.substring(0, n.length() - 1);
        overdueHeard(n);
    }

    /** When the next wake-up is due while sleeping: the next silence window, the next burst window, or the check-in. */
    private long nextWakeMs() {
        long now = System.currentTimeMillis();
        RadioEngine e = engine;
        Prefs p = new Prefs(this);
        if (PowerMode.outsideWindow && e != null && p.silencePeriodMin() > 0) {
            return Math.max(1000, CheckInWindows.next(e.channelName(), p.silencePeriodMin() * 60000L, now) - now);
        }
        if (PowerMode.burstNapping) return Math.max(1000, CheckInWindows.burstNext(now) - now);
        return PowerMode.ultraCheckInMs();
    }

    // ---- mains power anchor ----

    private long anchorUnpluggedAt;
    private boolean anchorLossSent;

    /** An anchor unplugged for 10 minutes: tells the channel (generator or base power may have failed) and asks its owner. */
    private void checkAnchor() {
        if (!PowerMode.anchor) {
            anchorUnpluggedAt = 0;
            anchorLossSent = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (PowerMode.charging) {
            if (anchorLossSent && messages != null && engine != null && !RadioEngine.radioSilent) {
                messages.sendText("Anchor " + new Prefs(this).callsign() + " is back on mains power.");
            }
            anchorUnpluggedAt = 0;
            anchorLossSent = false;
            return;
        }
        if (anchorUnpluggedAt == 0) anchorUnpluggedAt = now;
        if (anchorLossSent || now - anchorUnpluggedAt < 600000) return;
        anchorLossSent = true;
        if (messages != null && engine != null && !RadioEngine.radioSilent) {
            messages.sendText("Anchor " + new Prefs(this).callsign() + " lost mains power (unplugged for 10 minutes).");
        }
        notifyMission("This phone lost mains power", "It has been unplugged for 10 minutes. Still an anchor? Turn it off in "
                + "Settings > Power and sleep if it is now on battery.");
    }

    // ---- going offline as planned ----

    /** Someone who said when they would be back, and was not heard by then plus the grace: say so once. */
    private void checkOverdue() {
        RadioEngine e = engine;
        for (String name : PlannedOffline.check(this)) {
            org.communitycomms.app.msg.MessageLog.Entry m = new org.communitycomms.app.msg.MessageLog.Entry();
            m.id = new java.security.SecureRandom().nextLong();
            m.type = org.communitycomms.app.msg.MessageLog.TYPE_TEXT;
            m.time = System.currentTimeMillis();
            m.creator = name;
            m.channelTag = e == null ? 0 : e.channelTag();
            m.body = name + " is overdue: they went offline as planned and are not back yet";
            m.local = true;
            org.communitycomms.app.msg.MessageLog.get(this).put(m);
            buzz(true);
            notifyMessage(m);
        }
    }

    /** Exit prompt: tells the channel this phone is going offline as planned (backAt 0 = no time given). */
    public boolean sayGoingOffline(long backAt) {
        org.communitycomms.app.msg.MessageCenter mc = messages;
        RadioEngine e = engine;
        if (mc == null || e == null || RadioEngine.radioSilent) return false;
        return mc.sendText(PlannedOffline.body(new Prefs(this).callsign(), backAt));
    }

    // ---- GPS rest: off while the phone is still, back on movement or the screen ----

    private MotionWatch motion;
    private boolean gpsResting;

    /** Every 2 s: whether the GPS rests (still, sharing, the setting on, screen off, no mission). */
    private void checkStill() {
        Prefs p = new Prefs(this);
        boolean want = p.gpsRest() && engine != null;
        if (want && motion == null) {
            motion = new MotionWatch(this, new MotionWatch.Listener() {
                @Override
                public void onMoved() {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            moved();
                        }
                    });
                }

                @Override
                public void onWake(final String why) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            wakeCheckIn(why);
                        }
                    });
                }
            });
            motion.start();
        } else if (!want && motion != null) {
            motion.stop();
            motion = null;
        }
        boolean rest = motion != null && motion.still() && !PowerMode.screenOn && !PowerMode.missionMine()
                && lastFix != null;
        if (rest != gpsResting) {
            gpsResting = rest;
            Log.i(TAG, "power: GPS " + (rest ? "resting (still)" : "back on"));
            locationWas = ""; // applyPower re-runs the location loop
        }
    }

    /** Moved (or picked up): GPS back on, and from a sleep, a check-in straight away (at most once a minute). */
    private long lastMoveCheckIn;

    private void moved() {
        if (gpsResting) {
            gpsResting = false;
            locationWas = "";
            applyPower();
        }
        long now = System.currentTimeMillis();
        if (asleep && now - lastMoveCheckIn > 60000) {
            lastMoveCheckIn = now;
            Log.i(TAG, "power: moved, checking in");
            main.removeCallbacks(endCheckIn);
            checkIn();
        }
    }

    /** Plugged in or unplugged, or a wake-up sensor: a sleeping phone checks in straight away. */
    void wakeCheckIn(String why) {
        long now = System.currentTimeMillis();
        if (!asleep || now - lastMoveCheckIn < 60000) return;
        lastMoveCheckIn = now;
        Log.i(TAG, "power: " + why + ", checking in");
        main.removeCallbacks(endCheckIn);
        checkIn();
    }

    // ---- Mission mode ----

    /** The mission banner the user acknowledged and hid (a later or extended mission shows again). */
    private static volatile String missionAck = "";

    /** Which mission the banner is about now ("" for none). */
    public static String missionKey() {
        long mu = PowerMode.missionUntil();
        if (mu > 0) return "me:" + mu / 60000;
        if (PowerMode.missionOthers()) return PowerMode.othersMissionBy + ":" + PowerMode.othersMissionUntil / 60000;
        return "";
    }

    /** Home screen: the user acknowledged the Mission mode banner, so hide it (the mission itself carries on). */
    public void ackMission() {
        missionAck = missionKey();
        publishState(status, engine != null);
    }

    private boolean missionWasMine;

    /** Mission mode was turned on, extended or off on this phone: tell the channel and update the notification. */
    public void missionChanged() {
        main.post(new Runnable() {
            @Override
            public void run() {
                missionWasMine = PowerMode.missionMine();
                RadioEngine.myBurst = new Prefs(RadioService.this).burstNow();
                RadioEngine e = engine;
                if (e != null) e.sendPresenceNow();
                if (engine != null || compat != null) {
                    goForeground(status);
                    publishState(status, engine != null);
                }
            }
        });
    }

    /** Every 2 s: my mission ending, and another phone's mission heard (or ended, or lost). */
    private void checkMission() {
        RadioEngine.myBurst = new Prefs(this).burstNow();
        boolean mine = PowerMode.missionMine();
        if (missionWasMine && !mine) {
            missionWasMine = false;
            new Prefs(this).setMissionUntil(0);
            notifyMission("Mission mode ended", "This phone is back to its own power setting.");
            missionChanged();
        } else if (mine && !missionWasMine) {
            missionWasMine = true; // still on after a restart
        }
        checkAnchor();
        RadioEngine e = engine;
        if (e == null) return;
        long now = System.currentTimeMillis(), until = 0, heard = 0;
        String by = "";
        boolean byEnded = false, anchorNear = false;
        for (RadioEngine.Station s : e.stations()) if (s.anchor && now - s.lastSeenMs < 600000) anchorNear = true;
        PowerMode.anchorHeard = anchorNear;
        for (RadioEngine.Station s : e.stations()) {
            if (s.missionUntilMs > now && s.missionUntilMs > until) {
                until = s.missionUntilMs;
                heard = s.lastSeenMs;
                by = s.name;
            } else if (s.name != null && s.name.equals(PowerMode.othersMissionBy) && s.lastSeenMs > PowerMode.othersMissionHeard) {
                byEnded = true; // heard again without the flag: their mission ended
            }
        }
        boolean was = PowerMode.missionOthers();
        if (until > 0) {
            PowerMode.othersMissionUntil = until;
            PowerMode.othersMissionHeard = heard;
            PowerMode.othersMissionBy = by;
        } else if (byEnded) {
            PowerMode.othersMissionUntil = 0;
        }
        boolean is = PowerMode.missionOthers();
        if (is != was) {
            if (is) notifyMission("Mission mode on by " + PowerMode.othersMissionBy,
                    "Until " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(PowerMode.othersMissionUntil))
                            + ". This phone stays awake (no deep sleep) until then.");
            else notifyMission("Mission mode over", "Back to this phone's own power setting.");
            applyPower();
            publishState(status, engine != null);
        }
    }

    private void notifyMission(String title, String text) {
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new android.app.NotificationChannel("mission", "Mission mode", android.app.NotificationManager.IMPORTANCE_HIGH));
            b = new Notification.Builder(this, "mission");
        } else {
            b = new Notification.Builder(this);
        }
        int fl = Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0;
        android.app.PendingIntent open = android.app.PendingIntent.getActivity(this, 16, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), fl);
        nm.notify(4711, b.setContentTitle(title).setContentText(text).setSmallIcon(R.drawable.ic_stat_radio)
                .setContentIntent(open).setAutoCancel(true).build());
    }

    private void publishState(final String s, final boolean joined) {
        status = s;
        main.post(new Runnable() {
            @Override
            public void run() {
                Ui u = ui;
                if (u != null) u.onJoinState(airWords(s), joined);
            }
        });
    }

    /**
     * Where packets go. With an interface chosen (Advanced network), only that interface's
     * broadcast address is used; a VPN tunnel usually has none, so it falls back to multicast.
     */
    static List<InetSocketAddress> destinations(int netMode, int port, String extraPeers, NetworkInterface only)
            throws Exception {
        List<InetSocketAddress> out = new ArrayList<InetSocketAddress>();
        InetSocketAddress mcast = new InetSocketAddress(InetAddress.getByName(UdpLink.DEFAULT_GROUP), port);
        if (netMode == Prefs.NET_MULTICAST) {
            out.add(mcast);
        } else {
            List<NetworkInterface> nis = only != null ? Collections.singletonList(only)
                    : Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : nis) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address && ia.getBroadcast() != null) {
                        InetSocketAddress d = new InetSocketAddress(ia.getBroadcast(), port);
                        if (!out.contains(d)) out.add(d);
                    }
                }
            }
            if (out.isEmpty()) {
                out.add(only != null ? mcast : new InetSocketAddress(InetAddress.getByName("255.255.255.255"), port));
            }
        }
        for (String peer : extraPeers.split("[,\\s]+")) {
            peer = peer.trim();
            if (peer.length() == 0) continue;
            int colon = peer.lastIndexOf(':');
            String host = colon > 0 ? peer.substring(0, colon) : peer;
            int p = colon > 0 ? Integer.parseInt(peer.substring(colon + 1)) : port;
            out.add(new InetSocketAddress(InetAddress.getByName(host), p));
        }
        return out;
    }

    // ---- engine callbacks ----

    @Override
    public void onRosterChanged() {
        main.post(new Runnable() {
            @Override
            public void run() {
                Ui u = ui;
                if (u != null) u.onRosterChanged();
            }
        });
    }

    @Override
    public void onTransmitChanged(final boolean transmitting) {
        main.post(new Runnable() {
            @Override
            public void run() {
                txLocation(transmitting);
                Ui u = ui;
                if (u != null) u.onTransmitChanged(transmitting);
            }
        });
    }

    /**
     * VoIP-style audio routing: in-communication mode turns on the platform echo canceller so mic and
     * speaker can both be live (duplex). Loudspeaker unless a wired headset is plugged in.
     */
    @SuppressWarnings("deprecation")
    private void setCommunicationAudio(boolean on) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            if (on) {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                am.setSpeakerphoneOn(org.communitycomms.app.audio.AudioRoute.wantSpeaker(this, am, am.isWiredHeadsetOn()));
            } else {
                am.setSpeakerphoneOn(false);
                am.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "audio mode change failed", ex);
        }
        // A wired headset plugged in or pulled out while on air: move the audio with it.
        if (on && !plugWatched) {
            plugWatched = true;
            registerReceiver(plugReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
        } else if (!on && plugWatched) {
            plugWatched = false;
            try {
                unregisterReceiver(plugReceiver);
            } catch (RuntimeException ignored) {
            }
        }
        // Bluetooth headset audio and the Bluetooth PTT button follow being on air.
        if (on) bt.start();
        else bt.stop();
        if (on) mediaButtons.start(this);
        else mediaButtons.stop(this);
    }

    private final MediaButtons mediaButtons = new MediaButtons();

    private boolean plugWatched;

    /** Audio page: the sound output setting changed, apply it now if on air. */
    @SuppressWarnings("deprecation")
    public void applyAudioRoute() {
        if (engine == null && compat == null) return;
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null || am.isBluetoothScoOn()) return;
        try {
            am.setSpeakerphoneOn(org.communitycomms.app.audio.AudioRoute.wantSpeaker(this, am, am.isWiredHeadsetOn()));
        } catch (RuntimeException ignored) {
        }
    }

    private final BroadcastReceiver plugReceiver = new BroadcastReceiver() {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context c, Intent i) {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            boolean wired = i.getIntExtra("state", 0) == 1;
            Log.i(TAG, "wired headset " + (wired ? "in" : "out") + " (mic " + i.getIntExtra("microphone", 0) + ")");
            try {
                am.setSpeakerphoneOn(org.communitycomms.app.audio.AudioRoute.wantSpeaker(RadioService.this, am, wired));
            } catch (RuntimeException ex) {
                Log.w(TAG, "speaker change failed", ex);
            }
        }
    };

    // ---- Bluetooth headset and PTT button (e.g. FalconClaw) ----

    private final BtPtt bt = new BtPtt(CommsApp.context(), new BtPtt.Ptt() {
        @Override
        public void onPtt(boolean down) {
            hardwarePtt(down);
        }
    });

    public BtPtt bt() {
        return bt;
    }

    /** Re-reads the Bluetooth settings (button chosen, headset on/off) while on air. */
    public void restartBt() {
        if (engine == null && compat == null) return;
        bt.stop();
        bt.start();
        mediaButtons.start(this);
    }

    /**
     * PTT from a hardware button with no screen involved (Bluetooth button, screen off): same rules
     * as the on-screen button (toggle mode, radio silent).
     */
    public void hardwarePtt(boolean down) {
        Prefs pr = new Prefs(this);
        if (down && pr.radioSilent()) {
            Toast.makeText(this, "Radio silent: not transmitting", Toast.LENGTH_SHORT).show();
            return;
        }
        if (PowerMode.offline() || PowerMode.outsideWindow || PowerMode.burstNapping) { // no live voice: the button records a message
            boolean recording = org.communitycomms.app.audio.BurstVoice.recording();
            if (down && !recording) {
                org.communitycomms.app.audio.BurstVoice.start(this, pr.burstQuality());
            } else if (recording && (down || !pr.latch())) {
                String r = org.communitycomms.app.audio.BurstVoice.stopAndKeep(this);
                if (r != null) Toast.makeText(this, r, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (compat == null && pr.burstNow()) { // burst voice: record while held, send on release
            boolean recording = org.communitycomms.app.audio.BurstVoice.recording();
            if (down && !recording) {
                org.communitycomms.app.audio.BurstVoice.start(this, pr.burstQuality());
            } else if (recording && (down || !pr.latch())) {
                String r = org.communitycomms.app.audio.BurstVoice.stopAndSend(this);
                if (r != null) Toast.makeText(this, r, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        boolean on;
        CompatVoice c = compat;
        RadioEngine e = engine;
        boolean now = c != null ? c.isTransmitting() : e != null && e.isTransmitting();
        if (pr.latch()) {
            if (!down) return;
            on = !now;
        } else {
            on = down;
        }
        if (c != null) c.setTransmit(on);
        else if (e != null) e.setTransmit(on);
    }

    // ---- locks and notification ----

    @SuppressLint("WakelockTimeout")
    private synchronized void acquireLocks() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null && multicastLock == null) {
            multicastLock = wm.createMulticastLock("communitycomms");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            wifiFast = PowerMode.busy();
            wifiLock = wm.createWifiLock(wifiMode(wifiFast), "communitycomms");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && wakeLock == null && !PowerMode.deepSleeping) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "communitycomms:radio");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    private boolean wifiFast;

    /** Low latency (no Wi-Fi power saving) while busy; the plain lock when quiet, so Wi-Fi can doze. */
    private static int wifiMode(boolean fast) {
        if (!fast) return WifiManager.WIFI_MODE_FULL;
        return Build.VERSION.SDK_INT >= 29 ? 4 /* WIFI_MODE_FULL_LOW_LATENCY */ : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
    }

    /**
     * Every 2 s: the Wi-Fi lock follows how busy the channel is, and the opt-in Deep sleep lets the CPU
     * sleep between packets when the screen is off and nothing has been said for 2 minutes.
     */
    private synchronized void tuneLocks() {
        if (wifiLock != null) {
            boolean fast = PowerMode.busy() || (engine != null && engine.isTransmitting());
            if (fast != wifiFast) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    WifiManager.WifiLock nl = wm.createWifiLock(wifiMode(fast), "communitycomms");
                    nl.setReferenceCounted(false);
                    nl.acquire(); // the new one first, so Wi-Fi is never unlocked in between
                    wifiLock.release();
                    wifiLock = nl;
                    wifiFast = fast;
                    Log.i(TAG, "power: Wi-Fi " + (fast ? "low latency" : "power saving allowed"));
                }
            }
        }
        boolean deep = new Prefs(this).deepSleep() && engine != null && !asleep && !PowerMode.busy() && !PowerMode.missionOthers()
                && !org.communitycomms.app.audio.BurstVoice.recording() && !engine.isTransmitting();
        if (deep == PowerMode.deepSleeping) return;
        PowerMode.deepSleeping = deep;
        Log.i(TAG, "power: deep sleep " + (deep ? "on" : "off"));
        if (deep) {
            if (wakeLock != null) wakeLock.release();
            wakeLock = null;
            scheduleCheckIn(PowerMode.QUIET_PRESENCE_MS);
        } else if (!asleep) { // (Ultra low's own sleep keeps its check-in alarm)
            cancelCheckIn();
            if (multicastLock != null) acquireLocks(); // on air: take the CPU lock back
        }
    }

    /** Deep sleep: the alarm wakes the phone for a moment to send presence, then it sleeps again. */
    private void deepCheckIn() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "communitycomms:deep").acquire(4000);
        RadioEngine e = engine;
        if (e != null) e.sendPresenceNow();
        if (locationWanted()) sendFix();
        scheduleCheckIn(PowerMode.QUIET_PRESENCE_MS);
    }

    private synchronized void releaseLocks() {
        if (multicastLock != null) multicastLock.release();
        if (wifiLock != null) wifiLock.release();
        if (wakeLock != null) wakeLock.release();
        multicastLock = null;
        wifiLock = null;
        wakeLock = null;
    }

    private void goForeground(String text) {
        Notification n = buildNotification(airWords(text));
        if (Build.VERSION.SDK_INT >= 29) {
            startForegroundTyped(n);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @TargetApi(29)
    private void startForegroundTyped(Notification n) {
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        // Location type keeps position updates flowing with the screen off (only allowed once granted).
        if (hasLocationPermission()) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        try {
            startForeground(NOTIF_ID, n, type);
        } catch (RuntimeException e) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        }
    }

    // ---- automatic location sending ----

    /** How often a position goes out while sharing or transmitting. */
    public static final long LOCATION_EVERY_MS = 30000;
    public static final long RAPID_SHARE_MS = 30 * 60000L;
    private long rapidShareUntil;
    private boolean txLocationOn;
    private android.location.Location lastFix;
    private android.location.LocationListener fixListener, netListener;

    private boolean hasLocationPermission() {
        return Build.VERSION.SDK_INT < 23 || org.communitycomms.app.geo.FakeGps.enabled(this)
                || checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private org.communitycomms.app.geo.AdsbFeed adsbShare;

    /**
     * "Share ADS-B data" (map menu > Aircraft): this phone keeps fetching (at the chosen speed) even
     * with no map open, around its own position, and sends each fetch to the channel.
     */
    public void applyAdsbShare() {
        org.communitycomms.app.geo.AdsbFeed f = new org.communitycomms.app.geo.AdsbFeed(this);
        boolean want = engine != null && f.share() && f.source() != org.communitycomms.app.geo.AdsbFeed.OFF
                && !new Prefs(this).radioSilent();
        if (!want) {
            org.communitycomms.app.geo.AdsbFeed.sharer = null;
            if (adsbShare != null) adsbShare.stop();
            adsbShare = null;
            return;
        }
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
        android.location.Location l = lm == null ? org.communitycomms.app.geo.FakeGps.location(this)
                : org.communitycomms.app.geo.LocationHelper.bestKnown(lm);
        if (l != null) f.setCentre(l.getLatitude(), l.getLongitude());
        org.communitycomms.app.geo.AdsbFeed.sharer = new org.communitycomms.app.geo.AdsbFeed.Sharer() {
            @Override
            public void share(List<org.communitycomms.app.geo.AdsbFeed.Aircraft> aircraft) {
                RadioEngine e = engine;
                if (e != null && !RadioEngine.radioSilent) e.sendAdsb(aircraft);
            }
        };
        if (adsbShare == null) {
            adsbShare = f;
            adsbShare.start(null);
        }
    }

    private org.communitycomms.app.geo.ShipFeed aisShare;

    /** "Share ship data" (map menu > Ships): keeps fetching round this phone and sends to the channel. */
    public void applyAisShare() {
        org.communitycomms.app.geo.ShipFeed f = new org.communitycomms.app.geo.ShipFeed(this);
        boolean want = engine != null && f.share() && f.enabled() && !new Prefs(this).radioSilent();
        if (!want) {
            org.communitycomms.app.geo.ShipFeed.sharer = null;
            if (aisShare != null) aisShare.stop();
            aisShare = null;
            return;
        }
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
        android.location.Location l = lm == null ? org.communitycomms.app.geo.FakeGps.location(this)
                : org.communitycomms.app.geo.LocationHelper.bestKnown(lm);
        if (l != null) f.setCentre(l.getLatitude(), l.getLongitude());
        org.communitycomms.app.geo.ShipFeed.sharer = new org.communitycomms.app.geo.ShipFeed.Sharer() {
            @Override
            public void share(List<org.communitycomms.app.geo.ShipFeed.Ship> ships) {
                RadioEngine e = engine;
                if (e != null && !RadioEngine.radioSilent) e.sendAis(ships);
            }
        };
        if (aisShare == null) {
            aisShare = f;
            aisShare.start(null);
        }
    }

    /**
     * Location sharing survives a crash or restart: the end time is kept, and a new service picks it
     * up while time is left (a deliberate Exit clears it in onDestroy).
     */
    private void saveRapidShare() {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putLong("rapidShareUntil", rapidShareSessionOnly ? 0 : rapidShareUntil).commit();
    }

    private void restoreRapidShare() {
        android.content.SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        long until = sp.getLong("rapidShareUntil", 0);
        if (sp.getBoolean("rapidShareAlways", false)) until = Long.MAX_VALUE / 2;
        if (until > System.currentTimeMillis()) rapidShareUntil = until;
    }

    /** "Until app restart": not kept, so a restart (or Exit) ends it. */
    private boolean rapidShareSessionOnly;

    public static final int SHARE_STOP = 0, SHARE_2H = 1, SHARE_UNTIL_RESTART = 2, SHARE_ALWAYS = 3;

    /** Tapping Share location while sharing: stop, 2 hours, until the app restarts, or always (every start). */
    public void setRapidShareMode(int mode) {
        rapidShareSessionOnly = mode == SHARE_UNTIL_RESTART;
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("rapidShareAlways", mode == SHARE_ALWAYS).commit();
        rapidShareUntil = mode == SHARE_STOP ? 0 : mode == SHARE_2H ? System.currentTimeMillis() + 2 * 3600000L
                : Long.MAX_VALUE / 2;
        saveRapidShare();
        if (mode != SHARE_STOP) goForeground(status);
        updateLocationLoop();
    }

    public boolean rapidShareAlways() {
        return getSharedPreferences("settings", MODE_PRIVATE).getBoolean("rapidShareAlways", false);
    }

    public boolean rapidShareUntilRestart() {
        return rapidShareSessionOnly && rapidShareUntil > System.currentTimeMillis();
    }

    /** Minutes of rapid sharing left, or 0 when it is off. */
    public long rapidShareLeftMs() {
        return Math.max(0, rapidShareUntil - System.currentTimeMillis());
    }

    /** "Share location until stop" (4WD, convoy): sends every 30 s until setRapidShare(false). */
    public void setRapidShareUntilStopped() {
        rapidShareUntil = Long.MAX_VALUE / 2;
        saveRapidShare();
        goForeground(status);
        updateLocationLoop();
    }

    // ---- location requests (full map > Ask to share; private channels only, not Public) ----

    /** Chat text that asks everyone on the channel to share their location for 30 minutes. */
    public static final String LOC_REQUEST = "LOCATION REQUEST: ";

    public interface LocationAsk {
        /** On the main thread: ask the person whether to share (Mission and data > automatic accept is off). */
        void ask(String from);
    }

    /** Set by the screen in front (home, full map) so a request can be answered there. */
    public static volatile LocationAsk locationAsk;

    public static boolean autoAcceptLocation(Context c) {
        return c.getSharedPreferences("settings", MODE_PRIVATE).getBoolean("autoAcceptLocation", false);
    }

    /** A keyed channel of the group (A, B, C to H), not Public or OpenMANET*. */
    public static boolean privateSlot(int s) {
        return s != Prefs.SLOT_PUBLIC && s != Prefs.SLOT_OPENMANET;
    }

    /** Sends a location request on the channel on air; false on Public or OpenMANET* (or not joined). */
    public boolean requestLocations() {
        int s = new Prefs(this).activeSlot();
        if (messages == null || engine == null || !privateSlot(s)) return false;
        return messages.sendText(LOC_REQUEST + new Prefs(this).callsign()
                + " asks everyone on this channel to share their location (30 minutes)");
    }

    private void onLocationRequest(org.communitycomms.app.msg.MessageLog.Entry m) {
        if (m == null || m.mine || m.type != org.communitycomms.app.msg.MessageLog.TYPE_TEXT || m.body == null
                || !m.body.startsWith(LOC_REQUEST)) return;
        int s = new Prefs(this).activeSlot();
        if (!privateSlot(s)) return;
        if (rapidShareLeftMs() > 0 || !hasLocationPermission()) return; // already sharing, or cannot
        if (autoAcceptLocation(this)) {
            setRapidShare(true);
            logLocal(m.creator, "Sharing your location for 30 minutes (asked by " + m.creator + "; accepted automatically)",
                    m.channelTag);
            return;
        }
        LocationAsk a = locationAsk;
        if (a != null) a.ask(m.creator); // otherwise the message notification tells them; Share location answers it
    }

    /** "Share my location for 30 minutes": sends now and every 30 s; false = stop. */
    public void setRapidShare(boolean on) {
        rapidShareSessionOnly = false;
        if (!on) getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("rapidShareAlways", false).commit();
        rapidShareUntil = on ? System.currentTimeMillis() + RAPID_SHARE_MS : 0;
        saveRapidShare();
        if (on) goForeground(status); // picks up the location service type now permission is granted
        updateLocationLoop();
    }

    /** Start of a transmission sends a position (setting, on by default), repeated every 30 s while talking. */
    private void txLocation(boolean transmitting) {
        boolean want = transmitting && engine != null && new Prefs(this).locationOnTransmit() && hasLocationPermission();
        if (want == txLocationOn) return;
        txLocationOn = want;
        updateLocationLoop();
    }

    private final Runnable locationTick = new Runnable() {
        @Override
        public void run() {
            if (rapidShareUntil != 0 && System.currentTimeMillis() >= rapidShareUntil) {
                rapidShareUntil = 0;
                saveRapidShare();
            }
            if (!locationWanted()) {
                updateLocationLoop();
                return;
            }
            sendFix();
            main.postDelayed(this, PowerMode.locationMs(LOCATION_EVERY_MS));
        }
    };

    private boolean locationWanted() {
        return engine != null && hasLocationPermission()
                && ((txLocationOn && !PowerMode.idle()) || rapidShareUntil > System.currentTimeMillis());
    }

    // ---- reports to leaders (ReportUp) ----

    private RadioEngine upEngine() {
        RadioEngine e = engineFor(Prefs.SLOT_PUBLIC);
        return e != null && e.signedChannel() ? e : null;
    }

    public boolean canReportUp() {
        return upEngine() != null;
    }

    public void sendUp(String plain) {
        RadioEngine e = upEngine();
        org.communitycomms.app.crypto.ListenKey lk = listenKeyIfPublic(new Prefs(this).publicKey());
        if (e == null || lk == null) return;
        try {
            e.sendUp(lk.sealReport(plain.getBytes("UTF-8")));
        } catch (Exception ex) {
            Log.w(TAG, "report", ex);
        }
    }

    @SuppressLint("MissingPermission")
    public android.location.Location lastFix() {
        if (lastFix != null) return lastFix;
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
        return lm == null || !hasLocationPermission() ? null : org.communitycomms.app.geo.LocationHelper.bestKnown(lm);
    }

    /** From the receive thread: anything without a valid approved code stops here. */
    public static void upReceived(org.communitycomms.app.crypto.ListenKey lk, byte[] sealed) {
        final RadioService s = instance;
        if (s == null) return;
        final org.json.JSONObject o = ReportUp.open(s, lk, sealed);
        if (o == null) return;
        s.main.post(new Runnable() {
            @Override
            public void run() {
                s.onReport(o);
            }
        });
    }

    private void onReport(org.json.JSONObject o) {
        int kind = o.optInt("k");
        String name = o.optString("n"), code = o.optString("c"), text = o.optString("t");
        boolean hasFix = o.has("la");
        if (hasFix) Contacts.get(this).located(name, o.optDouble("la"), o.optDouble("lo"));
        sendTextTo(Prefs.SLOT_PUBLIC, ReportUp.ACK + Long.toHexString(o.optLong("id")) + "]");
        if (kind == ReportUp.LOCATION) return;
        org.communitycomms.app.msg.MessageLog.Entry m = new org.communitycomms.app.msg.MessageLog.Entry();
        m.id = o.optLong("id");
        m.type = org.communitycomms.app.msg.MessageLog.TYPE_TEXT;
        m.time = System.currentTimeMillis();
        m.creator = name;
        m.channelTag = tagNow();
        m.body = "REPORT " + ReportUp.NAMES[Math.max(0, Math.min(ReportUp.NAMES.length - 1, kind))] + " from " + name + " (" + code + ")"
                + (text.length() > 0 ? ": " + text : "") + (hasFix ? String.format(java.util.Locale.US, " at %.5f, %.5f", o.optDouble("la"), o.optDouble("lo")) : "")
                + (o.optInt("b", -1) >= 0 ? " battery " + o.optInt("b") + "%" : "");
        m.local = true;
        org.communitycomms.app.msg.MessageLog.get(this).put(m);
        notifyMessage(m);
        if (kind == ReportUp.SOS || kind == ReportUp.HELP) {
            if (kind == ReportUp.SOS) org.communitycomms.app.audio.CallRinger.ring(this);
            callWaiting(name, kind == ReportUp.SOS ? " SOS" : " needs help");
            showCallScreen(name, kind == ReportUp.SOS ? " SOS" : " needs help");
        }
    }

    // ---- Settings > Power and sleep ----

    private boolean asleep, feedsWere = true, offlineWere;
    private long asleepSince;
    private String locationWas = "";
    private int checkInUnread;

    private final android.content.BroadcastReceiver screenWatch = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            PowerMode.screenOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
            applyPower();
            tuneLocks(); // screen on: CPU and low-latency Wi-Fi straight back
        }
    };

    private void watchScreen() {
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenWatch, f);
        // Plugged in or unplugged: Android tells the app at once, even asleep, so a sleeping phone checks in.
        IntentFilter pf = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
        pf.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(powerWatch, pf);
    }

    private final android.content.BroadcastReceiver powerWatch = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            boolean in = Intent.ACTION_POWER_CONNECTED.equals(i.getAction());
            PowerMode.charging = in;
            batteryReadAt = 0;
            wakeCheckIn(in ? "plugged in" : "unplugged");
        }
    };

    /** The mode, PTT or the screen changed. Any thread. */
    public void powerChanged() {
        main.post(new Runnable() {
            @Override
            public void run() {
                applyPower();
            }
        });
    }

    private void applyPower() {
        boolean feeds = PowerMode.feedsOn();
        if (feeds != feedsWere) {
            feedsWere = feeds;
            org.communitycomms.app.geo.AdsbFeed.restart();
            org.communitycomms.app.geo.ShipFeed.restart();
            org.communitycomms.app.geo.CapFeed.restart();
        }
        boolean onAir = engine != null || compat != null;
        boolean sleep = onAir && PowerMode.sleeping();
        if (sleep != asleep) {
            asleep = sleep;
            org.communitycomms.app.audio.Mixer.resting = sleep;
            main.removeCallbacks(endCheckIn);
            if (sleep) {
                asleepSince = System.currentTimeMillis();
                releaseLocks();
                scheduleCheckIn();
            } else {
                cancelCheckIn();
                if (onAir) {
                    acquireLocks();
                    RadioEngine e = engine;
                    if (e != null) e.sendPresenceNow();
                    // Short burst naps need no catch-up (the window itself is the catch-up time).
                    if (System.currentTimeMillis() - asleepSince > 60000) catchUpAfterAway(System.currentTimeMillis() - asleepSince);
                }
            }
        }
        if (PowerMode.offline() != offlineWere) {
            offlineWere = PowerMode.offline();
            applyTunnelNow();
            if (onAir) {
                goForeground(status);
                publishState(status, true);
            }
        }
        String location = locationWanted() + ":" + PowerMode.locationMs(PowerMode.gpsMs(5000, 30000));
        if (!location.equals(locationWas)) {
            locationWas = location;
            updateLocationLoop();
        }
    }

    private android.app.PendingIntent checkInIntent() {
        int fl = Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT
                : android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        return android.app.PendingIntent.getService(this, 41, new Intent(this, RadioService.class).setAction(ACTION_CHECK_IN), fl);
    }

    private void scheduleCheckIn() {
        scheduleCheckIn(nextWakeMs());
    }

    private void scheduleCheckIn(long ms) {
        android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;
        long at = android.os.SystemClock.elapsedRealtime() + ms;
        if (Build.VERSION.SDK_INT >= 31 ? am.canScheduleExactAlarms() : Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, checkInIntent());
        else if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, checkInIntent());
        else am.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, checkInIntent());
    }

    private void cancelCheckIn() {
        android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) am.cancel(checkInIntent());
    }

    /** Ultra low: awake for a moment to say hello and ask for what was missed. */
    private void checkIn() {
        windowTick(); // a silence or burst window may be opening: that wakes the phone fully
        applyPower();
        RadioEngine e = engine;
        if (!asleep || e == null) return;
        Log.i(TAG, "power: check-in");
        acquireLocks();
        e.sendPresenceNow();
        if (locationWanted()) sendFix();
        checkInUnread = org.communitycomms.app.msg.MessageLog.unread;
        catchUpAfterAway(PowerMode.ultraCheckInMs() + 60000);
        main.postDelayed(endCheckIn, 20000);
    }

    private final Runnable endCheckIn = new Runnable() {
        @Override
        public void run() {
            if (org.communitycomms.app.msg.MessageLog.unread > checkInUnread) missedTone();
            checkCallReminder();
            if (asleep) {
                releaseLocks();
                scheduleCheckIn();
            }
        }
    };

    /** A sound for messages that came in at a check-in (silent when stealth). */
    private void missedTone() {
        if (curStatus == RadioEngine.STATUS_STEALTH) return;
        try {
            final android.media.ToneGenerator t = new android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100);
            t.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 500);
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    t.release();
                }
            }, 1000);
        } catch (RuntimeException ignored) {
            // no tone generator free
        }
    }
    @SuppressLint("MissingPermission")
    private void updateLocationLoop() {
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
        main.removeCallbacks(locationTick);
        if (fixListener != null && lm != null) lm.removeUpdates(fixListener); // the rate may have changed
        if (netListener != null && lm != null) lm.removeUpdates(netListener);
        fixListener = null;
        netListener = null;
        if (!locationWanted()) return;
        if (gpsResting) { // still: the last fix goes out as "still here", no GPS
            main.postDelayed(locationTick, 0);
            return;
        }
        if (fixListener == null && lm != null) {
            netListener = new android.location.LocationListener() {
                @Override
                public void onLocationChanged(android.location.Location l) {
                    if (lastFix == null || l.getTime() >= lastFix.getTime()) lastFix = l;
                }

                @Override
                public void onStatusChanged(String p, int st, android.os.Bundle e) {
                }

                @Override
                public void onProviderEnabled(String p) {
                }

                @Override
                public void onProviderDisabled(String p) {
                }
            };
            fixListener = new android.location.LocationListener() {
                @Override
                public void onLocationChanged(android.location.Location l) {
                    if (lastFix == null || l.getTime() >= lastFix.getTime()) lastFix = l;
                    // A good GPS fix: network location is no longer needed (it costs radio time).
                    android.location.LocationManager m = (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
                    if (netListener != null && l.hasAccuracy() && l.getAccuracy() <= 30 && m != null) {
                        m.removeUpdates(netListener);
                        netListener = null;
                    }
                }

                @Override
                public void onStatusChanged(String p, int st, android.os.Bundle e) {
                }

                @Override
                public void onProviderEnabled(String p) {
                }

                @Override
                public void onProviderDisabled(String p) {
                }
            };
            try {
                long every = PowerMode.locationMs(PowerMode.gpsMs(5000, 30000));
                if (lm.getAllProviders().contains(android.location.LocationManager.GPS_PROVIDER))
                    lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, every, 0, fixListener, Looper.getMainLooper());
                if (lm.getAllProviders().contains(android.location.LocationManager.NETWORK_PROVIDER))
                    lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, every, 0, netListener, Looper.getMainLooper());
            } catch (RuntimeException ignored) {
                // no permission or provider
            }
        }
        // Send straight away (the start of a transmission, or tapping the button), then every 30 s.
        // A fix more than 15 s old: give the GPS a moment to deliver a fresh one first.
        boolean fresh = lastFix != null && System.currentTimeMillis() - lastFix.getTime() < 15000;
        main.postDelayed(locationTick, fresh ? 0 : 2500);
    }

    private void sendFix() {
        RadioEngine e = engine;
        if (e == null) return;
        android.location.Location l = lastFix;
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
        android.location.Location known = lm == null ? null : org.communitycomms.app.geo.LocationHelper.bestKnown(lm);
        if (l == null || (known != null && known.getTime() > l.getTime())) l = known;
        if (org.communitycomms.app.geo.FakeGps.isFake(known)) l = known; // fake GPS wins over real fixes
        if (gpsResting && l != null) {
            // GPS resting because the phone has not moved: the same place, confirmed now ("still here").
            Log.i(TAG, "auto location sent (still)");
            e.sendLocation(l.getLatitude(), l.getLongitude(), l.getAccuracy(), System.currentTimeMillis(), "");
            return;
        }
        if (l == null || System.currentTimeMillis() - l.getTime() > 10 * 60000L) {
            Log.i(TAG, "auto location skipped: no recent fix");
            return;
        }
        Log.i(TAG, "auto location sent, fix age " + (System.currentTimeMillis() - l.getTime()) / 1000 + " s");
        e.sendLocation(l.getLatitude(), l.getLongitude(), l.getAccuracy(), l.getTime(), "");
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Radio", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_radio)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
