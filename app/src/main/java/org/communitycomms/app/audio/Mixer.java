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
        /** Adaptive buffers: this talker's current start level (grows after an underrun mid-talk). */
        int prime;
        long playingSinceMs;
    }

    private final int frameSamples;
    /** Start level and the most it may grow to (both PRIME_FRAMES for normal channels: a fixed buffer). */
    private final int primeMin, primeMax, maxFrames;
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
        this(sampleRate, frameSamples, pan, PRIME_FRAMES, PRIME_FRAMES);
    }

    /**
     * An adaptive buffer: each talker starts after primeMin frames; an underrun while they are still talking
     * (the network fell behind) raises their start level towards primeMax, so a lossy, slow link (a MANET
     * hop) trades a little delay for unbroken speech.
     */
    public Mixer(final int sampleRate, int frameSamples, int pan, int primeMin, int primeMax) {
        this.frameSamples = frameSamples;
        this.pan = pan;
        this.primeMin = primeMin;
        this.primeMax = Math.max(primeMin, primeMax);
        this.maxFrames = Math.max(MAX_FRAMES, this.primeMax + 15);
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

    /** Ultra low power with the screen off: no sound until the radio is awake again. */
    public static volatile boolean resting;

    /** Queue a decoded frame from a talker. Out-of-order or duplicate frames are dropped. */
    public void push(long sender, int seq, short[] pcm) {
        synchronized (streams) {
            Stream s = streams.get(sender);
            if (s == null) {
                s = new Stream();
                s.prime = primeMin;
                streams.put(sender, s);
            }
            if (s.hasSeq && seq - s.lastSeq <= 0) return;
            s.hasSeq = true;
            s.lastSeq = seq;
            long now = System.currentTimeMillis();
            if (now - s.lastHeardMs > 3000) s.prime = primeMin; // a new transmission starts from the low level again
            s.lastHeardMs = now;
            s.frames.addLast(pcm);
            while (s.frames.size() > maxFrames) s.frames.removeFirst();
            if (!s.primed && s.frames.size() >= s.prime) {
                s.primed = true;
                s.playingSinceMs = now;
            }
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
        AudioTrack track = new AudioTrack(stereo ? AudioManager.STREAM_MUSIC : AudioLevels.voiceStream,
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
                if (resting) {
                    track.pause(); // a playing track keeps the phone awake
                    while (resting && running) Thread.sleep(200);
                    track.play();
                    continue;
                }
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
                                // Still talking (heard in the last half second): the buffer was too small for this link.
                                if (now - s.lastHeardMs < 500 && s.prime < primeMax) s.prime = Math.min(primeMax, s.prime + 5);
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
        } catch (InterruptedException e) {
            Log.w(TAG, "mixer stopped", e);
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
