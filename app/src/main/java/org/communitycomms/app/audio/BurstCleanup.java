package org.communitycomms.app.audio;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Audio page > Clean up received bursts (off by default): before a burst plays it is decoded,
 * cleaned and played from a WAV copy. Removes low rumble (high-pass at about 120 Hz), turns down the
 * hiss between words (a downward expander under the noise floor heard in the quietest parts), then
 * evens the level: quiet speech is turned up (up to 20 dB) towards a common loudness, with a soft
 * limiter so loud bursts never clip. Any failure plays the original instead.
 */
@TargetApi(16)
public final class BurstCleanup {
    private BurstCleanup() {
    }

    public static volatile boolean enabled;

    /** The cleaned WAV next to the original, or null (play the original). */
    public static File clean(File in) {
        try {
            int[] rate = new int[1];
            short[] pcm = decode(in, rate);
            if (pcm == null || pcm.length < rate[0] / 4) return null;
            process(pcm, rate[0]);
            File out = new File(in.getParentFile(), in.getName() + ".clean.wav");
            writeWav(out, pcm, rate[0]);
            return out;
        } catch (Throwable e) {
            return null;
        }
    }

    // ---- decode (AMR, AMR-WB, AAC) to 16-bit mono ----

    @SuppressWarnings("deprecation")
    public static short[] decode(File f, int[] rateOut) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(f.getPath());
        int track = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat m = ex.getTrackFormat(i);
            String mime = m.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                track = i;
                fmt = m;
                break;
            }
        }
        if (track < 0) {
            ex.release();
            return null;
        }
        ex.selectTrack(track);
        int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
        MediaCodec codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        codec.configure(fmt, null, null, 0);
        codec.start();
        ByteBuffer[] ins = codec.getInputBuffers();
        ByteBuffer[] outs = codec.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        boolean inDone = false, outDone = false;
        long guard = System.currentTimeMillis() + 20000;
        byte[] chunk = new byte[0];
        while (!outDone && System.currentTimeMillis() < guard) {
            if (!inDone) {
                int ii = codec.dequeueInputBuffer(10000);
                if (ii >= 0) {
                    ByteBuffer b = ins[ii];
                    b.clear();
                    int n = ex.readSampleData(b, 0);
                    if (n < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inDone = true;
                    } else {
                        codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }
            int oi = codec.dequeueOutputBuffer(info, 10000);
            if (oi >= 0) {
                if (info.size > 0) {
                    ByteBuffer b = outs[oi];
                    b.position(info.offset);
                    b.limit(info.offset + info.size);
                    if (chunk.length < info.size) chunk = new byte[info.size];
                    b.get(chunk, 0, info.size);
                    raw.write(chunk, 0, info.size);
                }
                codec.releaseOutputBuffer(oi, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
            } else if (oi == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                outs = codec.getOutputBuffers();
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat nf = codec.getOutputFormat();
                if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            }
        }
        codec.stop();
        codec.release();
        ex.release();
        byte[] all = raw.toByteArray();
        ShortBuffer16 sb = new ShortBuffer16(all);
        int frames = sb.count / Math.max(1, channels);
        short[] mono = new short[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int ch = 0; ch < channels; ch++) sum += sb.get(i * channels + ch);
            mono[i] = (short) (sum / Math.max(1, channels));
        }
        rateOut[0] = rate;
        return mono;
    }

    private static final class ShortBuffer16 {
        final java.nio.ShortBuffer s;
        final int count;

        ShortBuffer16(byte[] b) {
            s = ByteBuffer.wrap(b).order(ByteOrder.nativeOrder()).asShortBuffer();
            count = s.remaining();
        }

        short get(int i) {
            return s.get(i);
        }
    }

    // ---- clean ----

    static void process(short[] pcm, int rate) {
        int n = pcm.length;
        float[] x = new float[n];
        // 1. High-pass (one pole, ~120 Hz): rumble, wind and handling noise.
        float a = (float) Math.exp(-2 * Math.PI * 120 / rate);
        float prevIn = 0, prevOut = 0;
        for (int i = 0; i < n; i++) {
            float v = pcm[i];
            float y = a * (prevOut + v - prevIn);
            prevIn = v;
            prevOut = y;
            x[i] = y;
        }
        // Frame loudness (20 ms).
        int fl = Math.max(80, rate / 50);
        int frames = (n + fl - 1) / fl;
        float[] rms = new float[frames];
        for (int f = 0; f < frames; f++) {
            double s = 0;
            int end = Math.min(n, (f + 1) * fl);
            for (int i = f * fl; i < end; i++) s += x[i] * x[i];
            rms[f] = (float) Math.sqrt(s / Math.max(1, end - f * fl));
        }
        float[] sorted = rms.clone();
        Arrays.sort(sorted);
        float noise = Math.max(30f, sorted[Math.min(frames - 1, frames / 10)]); // the quietest tenth: background
        // 2. Downward expander: frames near the noise floor are turned down (smoothly), speech is left alone.
        float thr = noise * 2.5f, g = 1f;
        float[] gain = new float[frames];
        for (int f = 0; f < frames; f++) {
            float want = rms[f] >= thr ? 1f : Math.max(0.12f, (rms[f] / thr) * (rms[f] / thr));
            g = want > g ? g + (want - g) * 0.6f : g + (want - g) * 0.15f; // quick to open, slow to close
            gain[f] = g;
        }
        // 3. Level: speech frames (above the threshold) brought towards about -18 dBFS, at most +20 dB.
        double sp = 0;
        int spn = 0;
        for (int f = 0; f < frames; f++) {
            if (rms[f] >= thr) {
                sp += rms[f] * rms[f];
                spn++;
            }
        }
        float speech = spn == 0 ? sorted[frames - 1] : (float) Math.sqrt(sp / spn);
        float level = speech <= 1 ? 1f : Math.max(0.5f, Math.min(10f, 4100f / speech));
        // Turning speech up must not turn the hiss up: between words the total gain stays at half or less.
        for (int f = 0; f < frames; f++) {
            gain[f] *= level;
            if (rms[f] < thr) gain[f] = Math.min(gain[f], 0.5f);
        }
        level = 1f;
        for (int f = 0; f < frames; f++) {
            float g0 = gain[f], g1 = f + 1 < frames ? gain[f + 1] : g0;
            int end = Math.min(n, (f + 1) * fl);
            for (int i = f * fl; i < end; i++) {
                float t = (i - f * fl) / (float) fl;
                float v = x[i] * (g0 + (g1 - g0) * t) * level;
                // 4. Soft limiter above about -3 dBFS.
                float av = Math.abs(v), knee = 23000f;
                if (av > knee) v = Math.signum(v) * (knee + (32000f - knee) * (float) Math.tanh((av - knee) / (32000f - knee)));
                pcm[i] = (short) Math.max(-32767, Math.min(32767, Math.round(v)));
            }
        }
    }

    // ---- WAV ----

    static void writeWav(File out, short[] pcm, int rate) throws Exception {
        int data = pcm.length * 2;
        ByteBuffer h = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes("US-ASCII")).putInt(36 + data).put("WAVE".getBytes("US-ASCII"));
        h.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate)
                .putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        h.put("data".getBytes("US-ASCII")).putInt(data);
        for (short s : pcm) h.putShort(s);
        FileOutputStream fo = new FileOutputStream(out);
        try {
            fo.write(h.array());
        } finally {
            fo.close();
        }
    }
}
