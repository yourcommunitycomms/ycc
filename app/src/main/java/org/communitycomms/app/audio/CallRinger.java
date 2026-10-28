package org.communitycomms.app.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Vibrator;
import android.util.Log;

/**
 * The "call" alert, like a radio's call button: a loud two-tone ring for a few seconds. It plays
 * the phone's own ringtone on the ringer, and a generated ring on the media stream so it also
 * comes out of the speaker, Bluetooth and headphones. Volumes are the phone's own; nothing here
 * changes system settings.
 */
public final class CallRinger {
    private static final String TAG = "CallRinger";
    public static final int RING_MS = 6000;
    private static final int RATE = 16000;

    private static volatile boolean ringing;
    private static MediaPlayer player;

    public static boolean isRinging() {
        return ringing;
    }

    public static synchronized void ring(Context c) {
        if (ringing) return;
        ringing = true;
        final Context app = c.getApplicationContext();
        startRingtone(app);
        try {
            Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(new long[]{0, 700, 300, 700, 300, 700, 300, 700, 300, 700}, -1);
        } catch (RuntimeException e) {
            Log.w(TAG, "vibrate", e);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    playTones();
                } finally {
                    stop();
                }
            }
        }, "call-ring").start();
    }

    /** Stops the ring and the buzzing straight away (the call screen's Acknowledge / Stop). */
    public static synchronized void stopNow(Context c) {
        stop();
        try {
            Vibrator v = (Vibrator) c.getApplicationContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.cancel();
        } catch (RuntimeException ignored) {
        }
    }

    public static synchronized void stop() {
        ringing = false;
        if (player != null) {
            try {
                player.stop();
            } catch (RuntimeException ignored) {
            }
            player.release();
            player = null;
        }
    }

    private static void startRingtone(Context c) {
        try {
            Uri u = RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_RINGTONE);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (u == null) return;
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_RING);
            mp.setDataSource(c, u);
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception e) {
            Log.w(TAG, "no ringtone", e); // no ringtone set (or none on the emulator): the tones still play
        }
    }

    /** Classic two-tone trill: 1 s of alternating 1000/1300 Hz every 25 ms, then 0.5 s gap. */
    private static void playTones() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE), AudioTrack.MODE_STREAM);
        if (t.getState() != AudioTrack.STATE_INITIALIZED) return;
        try {
            AudioRoute.applyRing(t, org.communitycomms.app.CommsApp.context());
        } catch (RuntimeException ignored) {
        }
        short[] cycle = new short[RATE * 3 / 2];
        double ph = 0;
        for (int i = 0; i < RATE; i++) {
            double f = (i / (RATE / 40)) % 2 == 0 ? 1000 : 1300;
            ph += 2 * Math.PI * f / RATE;
            cycle[i] = (short) (Math.sin(ph) * 30000);
        }
        t.play();
        try {
            long end = System.currentTimeMillis() + RING_MS;
            while (ringing && System.currentTimeMillis() < end) t.write(cycle, 0, cycle.length);
        } finally {
            t.stop();
            t.release();
        }
    }
}
