package org.communitycomms.app.audio;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Plays everyone at once: each talker gets a small jitter buffer, and the playback thread sums one
 * frame from every primed buffer per tick. The blocking AudioTrack write paces the loop.
 */
public final class Mixer {
    private static final String TAG = "Mixer";
    /** Frames buffered before a talker starts playing (smooths WiFi jitter). */
    private static final int PRIME_FRAMES = 3;
    private static final int MAX_FRAMES = 12;

    private static final class Stream {
        final ArrayDeque<short[]> frames = new ArrayDeque<short[]>();
        boolean primed;
        int lastSeq;
        boolean hasSeq;
        long lastHeardMs;
    }

    private final int frameSamples;
    private final Map<Long, Stream> streams = new HashMap<Long, Stream>();
    private final Thread thread;
    private volatile boolean running = true;
    private volatile float gain = 1.0f;

    /** -1 = left ear only, 1 = right ear only, 0 = mono (normal). */
    private final int pan;

    public Mixer(final int sampleRate, int frameSamples) {
        this(sampleRate, frameSamples, 0);
    }

    public Mixer(final int sampleRate, int frameSamples, int pan) {
        this.frameSamples = frameSamples;
        this.pan = pan;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                loop(sampleRate);
            }
        }, "mixer");
        thread.start();
    }

    public void setGain(float g) {
        gain = g;
    }

    /** Queue a decoded frame from a talker. Out-of-order or duplicate frames are dropped. */
    public void push(long sender, int seq, short[] pcm) {
        synchronized (streams) {
            Stream s = streams.get(sender);
            if (s == null) {
                s = new Stream();
                streams.put(sender, s);
            }
            if (s.hasSeq && seq - s.lastSeq <= 0) return;
            s.hasSeq = true;
            s.lastSeq = seq;
            s.lastHeardMs = System.currentTimeMillis();
            s.frames.addLast(pcm);
            while (s.frames.size() > MAX_FRAMES) s.frames.removeFirst();
            if (s.frames.size() >= PRIME_FRAMES) s.primed = true;
        }
    }

    /** Talker finished: let whatever is buffered play out even if below the prime level. */
    public void end(long sender) {
        synchronized (streams) {
            Stream s = streams.get(sender);
            if (s != null) s.primed = true;
        }
    }

    private void loop(int sampleRate) {
        boolean stereo = pan != 0;
        int chans = stereo ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int min = AudioTrack.getMinBufferSize(sampleRate, chans, AudioFormat.ENCODING_PCM_16BIT);
        int bufBytes = Math.max(min, frameSamples * 2 * 4 * (stereo ? 2 : 1));
        // Voice-call stream so the platform echo canceller sees what we play while the mic is open.
        // The call path is mono on most phones, so stereo (one channel per ear) uses the media stream.
        AudioTrack track = new AudioTrack(stereo ? AudioManager.STREAM_MUSIC : AudioManager.STREAM_VOICE_CALL,
                sampleRate, chans, AudioFormat.ENCODING_PCM_16BIT, bufBytes, AudioTrack.MODE_STREAM);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialise");
            return;
        }
        track.play();
        int[] acc = new int[frameSamples];
        short[] out = new short[frameSamples];
        short[] lr = new short[frameSamples * 2];
        try {
            while (running) {
                java.util.Arrays.fill(acc, 0);
                long now = System.currentTimeMillis();
                synchronized (streams) {
                    Iterator<Stream> it = streams.values().iterator();
                    while (it.hasNext()) {
                        Stream s = it.next();
                        if (s.primed) {
                            short[] f = s.frames.pollFirst();
                            if (f == null) {
                                s.primed = false; // underrun: rebuffer
                            } else {
                                int n = Math.min(f.length, frameSamples);
                                for (int i = 0; i < n; i++) acc[i] += f[i];
                            }
                        }
                        if (s.frames.isEmpty() && now - s.lastHeardMs > 30000) it.remove();
                    }
                }
                float g = AudioLevels.muted ? 0f : gain * AudioLevels.rxGain; // stealth / Audio screen boost
                for (int i = 0; i < frameSamples; i++) {
                    int v = (int) (acc[i] * g);
                    out[i] = (short) (v > 32767 ? 32767 : (v < -32768 ? -32768 : v));
                }
                if (stereo) {
                    for (int i = 0; i < frameSamples; i++) {
                        boolean left = (pan < 0) != AudioLevels.swapLeftRight; // Audio page: Swap left and right
                        lr[2 * i] = left ? out[i] : 0;
                        lr[2 * i + 1] = left ? 0 : out[i];
                    }
                    track.write(lr, 0, lr.length);
                } else {
                    track.write(out, 0, frameSamples);
                }
            }
        } finally {
            track.stop();
            track.release();
        }
    }

    public void close() {
        running = false;
        try {
            thread.join(500);
        } catch (InterruptedException ignored) {
        }
    }
}
