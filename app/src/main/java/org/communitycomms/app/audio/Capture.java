package org.communitycomms.app.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;
import android.util.Log;

/**
 * Microphone capture at 8 kHz mono, delivered in fixed frames. Falls back to 16 kHz with 2:1
 * decimation on devices that refuse 8 kHz. Runs only while transmitting.
 */
public final class Capture {
    public interface FrameSink {
        void onFrame(short[] pcm, int samples);
    }

    private static final String TAG = "Capture";
    private final int frameSamples;
    private final FrameSink sink;
    private Thread thread;
    private volatile boolean running;

    public Capture(int frameSamples, FrameSink sink) {
        this.frameSamples = frameSamples;
        this.sink = sink;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                loop();
            }
        }, "capture");
        thread.start();
    }

    public void stop() {
        Thread t;
        synchronized (this) {
            running = false;
            t = thread;
            thread = null;
        }
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private AudioRecord open(int rate) {
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return null;
        int[] sources = {MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC};
        for (int src : sources) {
            try {
                AudioRecord r = new AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, frameSamples * 2 * 4 * (rate / 8000)));
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    try {
                        AudioRoute.applyMic(r, org.communitycomms.app.CommsApp.context()); // Audio page mic choice
                    } catch (RuntimeException e) {
                        Log.w(TAG, "chosen microphone not applied", e);
                    }
                    return r;
                }
                r.release();
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "source " + src + " @" + rate + " rejected");
            }
        }
        return null;
    }

    private void loop() {
        int factor = 1;
        AudioRecord rec = open(8000);
        if (rec == null) {
            rec = open(16000);
            factor = 2;
        }
        if (rec == null) {
            Log.e(TAG, "no usable microphone configuration");
            running = false;
            return;
        }
        // Full duplex on a loudspeaker needs echo cancellation, or everyone hears themselves back.
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(rec.getAudioSessionId());
                if (aec != null) aec.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(rec.getAudioSessionId());
                if (ns != null) ns.setEnabled(true);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "audio effects unavailable", e);
        }
        short[] raw = new short[frameSamples * factor];
        short[] frame = new short[frameSamples];
        try {
            rec.startRecording();
            while (running) {
                int got = 0;
                while (got < raw.length && running) {
                    int n = rec.read(raw, got, raw.length - got);
                    if (n <= 0) break;
                    got += n;
                }
                if (got < raw.length) continue;
                if (factor == 1) {
                    System.arraycopy(raw, 0, frame, 0, frameSamples);
                } else {
                    for (int i = 0; i < frameSamples; i++) frame[i] = (short) ((raw[2 * i] + raw[2 * i + 1]) / 2);
                }
                AudioLevels.apply(frame, frameSamples, AudioLevels.micGain); // Audio screen mic level
                sink.onFrame(frame, frameSamples);
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "recording failed", e);
        } finally {
            try {
                rec.stop();
            } catch (IllegalStateException ignored) {
            }
            if (aec != null) aec.release();
            if (ns != null) ns.release();
            rec.release();
        }
    }
}
