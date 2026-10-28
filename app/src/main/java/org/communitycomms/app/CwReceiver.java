package org.communitycomms.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import org.communitycomms.app.audio.Morse;

import java.io.File;
import java.util.Arrays;

/**
 * Receive CW: listens (the radio's audio into the phone), records each message from the first tone
 * until the clear air after it, decodes as it goes and when it ends, and keeps each one as an RX file.
 */
final class CwReceiver {
    interface Listener {
        /** Main thread: what is happening, and the text so far. */
        void onProgress(String state, String textSoFar);

        /** Main thread: a whole message (saved as file). */
        void onMessage(File wav, String text);

        void onError(String why);
    }

    /** A CW message is coming in now (the relay waits for it to end). */
    static volatile boolean hearing;

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private Thread thread;

    CwReceiver(Context c, Listener l) {
        app = c.getApplicationContext();
        listener = l;
    }

    boolean running() {
        return running;
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "cw-rx");
        thread.start();
    }

    void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private AudioRecord open() {
        int rate = Morse.RATE;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return null;
        // Voice recognition: no noise suppression or level changes that would eat the tone.
        int[] sources = {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        for (int src : sources) {
            try {
                AudioRecord r = new AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate));
                if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
                r.release();
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private void post(final String state, final String text) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onProgress(state, text);
            }
        });
    }

    private void loop() {
        AudioRecord rec = open();
        if (rec == null) {
            running = false;
            main.post(new Runnable() {
                @Override
                public void run() {
                    listener.onError("The microphone (or the radio's audio input) could not be opened");
                }
            });
            return;
        }
        final int frame = Morse.RATE / 50; // 20 ms
        short[] buf = new short[frame];
        short[] pre = new short[Morse.RATE]; // the last second, so the first tone is not cut
        int preAt = 0;
        boolean preFull = false;
        short[] msg = new short[Morse.RATE * 30];
        int msgLen = 0;
        boolean active = false;
        double floor = -1;
        long lastTone = 0, lastDecode = 0, started = System.currentTimeMillis();
        int clearMs = Cw.clearAirSec(app) * 1000;
        try {
            rec.startRecording();
            post("Listening for CW…", "");
            while (running) {
                int got = 0;
                while (got < frame && running) {
                    int n = rec.read(buf, got, frame - got);
                    if (n <= 0) break;
                    got += n;
                }
                if (got < frame) continue;
                double rms = Morse.rms(buf, 0, frame);
                long now = System.currentTimeMillis();
                if (floor < 0) floor = Math.max(30, rms);
                boolean tone = rms > floor * 4 && rms > 250 && now - started > 300;
                if (!tone) floor = floor * 0.98 + rms * 0.02;
                else floor *= 1.0005; // a very long tone slowly counts as the new background
                if (!active) {
                    System.arraycopy(buf, 0, pre, preAt, frame);
                    preAt = (preAt + frame) % pre.length;
                    if (preAt == 0) preFull = true;
                    if (tone) {
                        active = true;
                        hearing = true;
                        msgLen = 0;
                        if (preFull) {
                            System.arraycopy(pre, preAt, msg, 0, pre.length - preAt);
                            System.arraycopy(pre, 0, msg, pre.length - preAt, preAt);
                            msgLen = pre.length;
                        } else {
                            System.arraycopy(pre, 0, msg, 0, preAt);
                            msgLen = preAt;
                        }
                        lastTone = now;
                        post("Receiving…", "");
                    }
                    continue;
                }
                if (msgLen + frame > msg.length) {
                    if (msg.length >= Morse.RATE * 300) { // 5 minutes: end it here
                        lastTone = 0;
                    } else {
                        msg = Arrays.copyOf(msg, msg.length * 2);
                    }
                }
                if (msgLen + frame <= msg.length) {
                    System.arraycopy(buf, 0, msg, msgLen, frame);
                    msgLen += frame;
                }
                if (tone) lastTone = now;
                if (now - lastDecode > 1000) {
                    lastDecode = now;
                    post("Receiving…", Morse.decode(Arrays.copyOf(msg, msgLen), 0));
                }
                if (now - lastTone >= clearMs) {
                    active = false;
                    hearing = false;
                    final short[] whole = Arrays.copyOf(msg, msgLen);
                    final String text = Morse.decode(whole, 0);
                    File f = null;
                    try {
                        f = Cw.newFile(app, "RX", "");
                        Morse.writeWav(f, whole);
                        Cw.setNote(f, text);
                    } catch (Exception ignored) {
                    }
                    final File saved = f;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onMessage(saved, text);
                        }
                    });
                    post("Listening for CW…", "");
                    preAt = 0;
                    preFull = false;
                }
            }
        } catch (IllegalStateException e) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    listener.onError("Recording stopped");
                }
            });
        } finally {
            try {
                rec.stop();
            } catch (IllegalStateException ignored) {
            }
            rec.release();
            running = false;
            hearing = false;
        }
    }
}
