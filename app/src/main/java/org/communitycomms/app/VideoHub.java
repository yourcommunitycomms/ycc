package org.communitycomms.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live camera feeds heard on the air: the latest picture from each person, for the Tactical
 * videos and TV screens. Frames come in chunks (Packet.TYPE_VIDEO) and are put back together here.
 */
public final class VideoHub {
    private VideoHub() {
    }

    public static final class Feed {
        public final String name;
        public Bitmap picture;
        public long atMs;
        public int frames;
        /** A camera on this LAN (Reolink), not a person's camera heard on the air. */
        public boolean local;
        /** Shown after the name, e.g. "offline: ..." for a LAN camera that stopped answering. */
        public volatile String note;
        /** What set it off ("Person", "Motion"...) while it is triggered, else null. */
        public volatile String alert;

        Feed(String name) {
            this.name = name;
        }
    }

    private static final Map<String, Feed> feeds = new LinkedHashMap<String, Feed>();
    /** Frames being put together: sender:stream:frame → chunks. */
    private static final Map<String, byte[][]> pending = new HashMap<String, byte[][]>();
    private static final Map<String, Long> pendingAt = new HashMap<String, Long>();
    public static volatile Runnable ui;

    /** Plays camera audio; set only while the Tactical videos screen is open (nobody else hears it). */
    public interface AudioOut {
        /** Whether this person's audio should be heard (shown, and in focus if one is). */
        boolean wants(String name);

        void play(long sender, int seq, short[] pcm);
    }

    public static volatile AudioOut audio;

    private static void onAudio(String name, long sender, byte[] p, int off) {
        AudioOut out = audio;
        if (out == null || !out.wants(name)) return;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(p, off, p.length - off);
        b.getInt();
        int seq = b.getInt();
        int frame = RadioEngine.FRAME_SAMPLES, per = org.communitycomms.app.audio.ImaAdpcm.encodedSize(frame);
        int at = off + 10, n = 0;
        while (p.length - at >= per) {
            short[] pcm = new short[frame];
            org.communitycomms.app.audio.ImaAdpcm.decode(p, at, per, pcm);
            out.play(sender, seq * 8 + n, pcm);
            at += per;
            n++;
        }
    }

    /** One chunk from the air. Decodes the frame when it is complete. */
    static void onChunk(String name, long sender, byte[] p, int off) {
        if (p.length - off < 10) return;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(p, off, p.length - off);
        int stream = b.getInt(), frame = b.getInt();
        int idx = b.get() & 0xff, count = b.get() & 0xff;
        if (count == 0) {
            onAudio(name, sender, p, off);
            return;
        }
        if (idx >= count) return;
        byte[] data = new byte[b.remaining()];
        b.get(data);
        String key = Long.toHexString(sender) + ":" + stream + ":" + frame;
        byte[] jpeg = null;
        synchronized (pending) {
            long now = System.currentTimeMillis();
            // Forget half-arrived frames after 2 s.
            java.util.Iterator<Map.Entry<String, Long>> it = pendingAt.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> e = it.next();
                if (now - e.getValue() > 2000) {
                    pending.remove(e.getKey());
                    it.remove();
                }
            }
            byte[][] parts = pending.get(key);
            if (parts == null) {
                parts = new byte[count][];
                pending.put(key, parts);
                pendingAt.put(key, now);
            }
            if (parts.length != count) return;
            parts[idx] = data;
            int total = 0;
            for (byte[] x : parts) {
                if (x == null) return;
                total += x.length;
            }
            jpeg = new byte[total];
            int o = 0;
            for (byte[] x : parts) {
                System.arraycopy(x, 0, jpeg, o, x.length);
                o += x.length;
            }
            pending.remove(key);
            pendingAt.remove(key);
        }
        Bitmap bm = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bm == null) return;
        synchronized (feeds) {
            Feed f = feeds.get(name);
            if (f == null) {
                f = new Feed(name);
                feeds.put(name, f);
            }
            f.picture = bm;
            f.atMs = System.currentTimeMillis();
            f.frames++;
        }
        Runnable r = ui;
        if (r != null) r.run();
    }

    /** A picture from a LAN camera (Reolink): shown with the others on the walls. */
    public static void putLocal(String name, Bitmap bm, String note) {
        synchronized (feeds) {
            Feed f = feeds.get(name);
            if (f == null) {
                f = new Feed(name);
                f.local = true;
                feeds.put(name, f);
            }
            if (bm != null) {
                f.picture = bm;
                f.frames++;
            }
            f.note = note;
            f.atMs = System.currentTimeMillis();
        }
        Runnable r = ui;
        if (r != null) r.run();
    }

    /** Takes LAN camera tiles off the walls (cameras removed or watching stopped). */
    public static void removeLocal(java.util.Collection<String> keep) {
        synchronized (feeds) {
            java.util.Iterator<Map.Entry<String, Feed>> it = feeds.entrySet().iterator();
            while (it.hasNext()) {
                Feed f = it.next().getValue();
                if (f.local && (keep == null || !keep.contains(f.name))) it.remove();
            }
        }
        Runnable r = ui;
        if (r != null) r.run();
    }

    public static Feed feed(String name) {
        synchronized (feeds) {
            return feeds.get(name);
        }
    }

    /** Triggered LAN cameras and until when each stays in front (its own last movement + the hold time). */
    private static final Map<String, Long> alerts = new LinkedHashMap<String, Long>();

    /** A LAN camera was triggered: its tile comes to the front until the hold time after its last movement. */
    public static void alert(String name, String what, long untilMs) {
        Feed f = feed(name);
        if (f != null) f.alert = what;
        synchronized (alerts) {
            alerts.remove(name); // newest last
            alerts.put(name, untilMs);
        }
        Runnable r = ui;
        if (r != null) r.run();
    }

    /** Every camera to show in front now (several at once share the screen), oldest first. */
    public static List<String> alertingAll() {
        List<String> out = new ArrayList<String>();
        long now = System.currentTimeMillis();
        synchronized (alerts) {
            java.util.Iterator<Map.Entry<String, Long>> it = alerts.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> e = it.next();
                if (now >= e.getValue()) it.remove();
                else out.add(e.getKey());
            }
        }
        return out;
    }

    public static boolean isAlerting(String name) {
        return alertingAll().contains(name);
    }

    public static void clearAlert(String name) {
        Feed f = feed(name);
        if (f != null) f.alert = null;
    }

    /** The most recently triggered camera still in front, or null. */
    public static String alerting() {
        List<String> l = alertingAll();
        return l.isEmpty() ? null : l.get(l.size() - 1);
    }

    /** Feeds heard in the last 10 minutes, newest first. */
    public static List<Feed> feeds() {
        List<Feed> out = new ArrayList<Feed>();
        long now = System.currentTimeMillis();
        synchronized (feeds) {
            for (Feed f : feeds.values()) if (now - f.atMs < 600000) out.add(f);
        }
        return out;
    }
}
