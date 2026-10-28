package org.communitycomms.app;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Saves each received transmission as its own WAV file (8 kHz, 16-bit mono, about 16 KB a second).
 * A recording ends on the talker's end-of-transmission, or after 2 s without audio.
 */
public final class VoiceRecorder {
    public interface Listener {
        /** Called from a background thread once a recording is finished. */
        void onRecording(File file, String from, int seconds, long voiceId);
    }

    private static final long QUIET_END_MS = 2000;
    /** Shorter than this is dropped (a stray frame or two). */
    private static final int MIN_FRAMES = 10;

    private static final class Rec {
        RandomAccessFile raf;
        File file;
        String from;
        long voiceId;
        int frames;
        long lastMs;
        final java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
    }

    private final File dir;
    private final String prefix;
    private final Listener listener;
    private final Map<Long, Rec> open = new HashMap<Long, Rec>();

    /** prefix names the channel in the file name, e.g. "B". */
    public VoiceRecorder(File dir, String prefix, Listener l) {
        this.dir = dir;
        this.prefix = prefix;
        this.listener = l;
        dir.mkdirs();
    }

    public synchronized void frame(long sender, String from, int seq, short[] pcm, long voiceId) {
        Rec r = open.get(sender);
        // The same packet can arrive more than once (broadcast plus a relay or extra peer): keep one.
        if (r != null && r.seen.contains(seq)) return;
        try {
            if (r == null) {
                r = new Rec();
                r.from = from;
                r.voiceId = voiceId;
                String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
                r.file = new File(dir, "rec-" + prefix + "-" + safe(from) + "-" + stamp + ".wav");
                r.raf = new RandomAccessFile(r.file, "rw");
                r.raf.setLength(0);
                r.raf.write(new byte[44]); // header written on close
                open.put(sender, r);
            }
            byte[] b = new byte[pcm.length * 2];
            for (int i = 0; i < pcm.length; i++) {
                b[2 * i] = (byte) pcm[i];
                b[2 * i + 1] = (byte) (pcm[i] >> 8);
            }
            r.raf.write(b);
            r.seen.add(seq);
            r.frames++;
            r.lastMs = System.currentTimeMillis();
        } catch (IOException e) {
            open.remove(sender);
        }
    }

    public synchronized void end(long sender) {
        Rec r = open.remove(sender);
        if (r != null) finish(r);
    }

    /** Once a second: close recordings whose talker went quiet without an end packet. */
    public synchronized void tick() {
        long now = System.currentTimeMillis();
        Iterator<Rec> it = open.values().iterator();
        while (it.hasNext()) {
            Rec r = it.next();
            if (now - r.lastMs > QUIET_END_MS) {
                it.remove();
                finish(r);
            }
        }
    }

    public synchronized void closeAll() {
        for (Rec r : open.values()) finish(r);
        open.clear();
    }

    private void finish(Rec r) {
        try {
            int data = (int) (r.raf.length() - 44);
            r.raf.seek(0);
            r.raf.write(header(data));
            r.raf.close();
        } catch (IOException ignored) {
        }
        if (r.frames < MIN_FRAMES) {
            r.file.delete();
            return;
        }
        listener.onRecording(r.file, r.from, r.frames * RadioEngine.FRAME_SAMPLES / RadioEngine.SAMPLE_RATE, r.voiceId);
    }

    /** Writes this phone's own sent frames (IMA ADPCM, as on air) as a WAV. Seconds, or -1. */
    public static int writeAdpcm(File f, java.util.List<byte[]> frames) {
        if (frames.size() < MIN_FRAMES) return -1;
        short[] pcm = new short[RadioEngine.FRAME_SAMPLES];
        byte[] b = new byte[pcm.length * 2];
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "rw");
            raf.setLength(0);
            raf.write(new byte[44]);
            for (byte[] fr : frames) {
                org.communitycomms.app.audio.ImaAdpcm.decode(fr, 0, fr.length, pcm);
                for (int i = 0; i < pcm.length; i++) {
                    b[2 * i] = (byte) pcm[i];
                    b[2 * i + 1] = (byte) (pcm[i] >> 8);
                }
                raf.write(b);
            }
            int data = (int) (raf.length() - 44);
            raf.seek(0);
            raf.write(header(data));
            raf.close();
        } catch (IOException e) {
            f.delete();
            return -1;
        }
        return frames.size() * RadioEngine.FRAME_SAMPLES / RadioEngine.SAMPLE_RATE;
    }

    private static byte[] header(int dataBytes) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + dataBytes).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RadioEngine.SAMPLE_RATE).putInt(RadioEngine.SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(dataBytes);
        return b.array();
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
