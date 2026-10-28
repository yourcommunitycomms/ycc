package org.communitycomms.app.net;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * File sharing to everyone on the channel, built for lossy broadcast WiFi.
 *
 * Sender: announces the file (OFFER), streams every chunk once at a paced rate, then sends DONE.
 * Receivers write chunks straight to disk; once the stream goes quiet they send NACKs listing
 * the chunks they are missing, and the sender re-sends exactly those (one re-send serves every
 * receiver that missed the same chunk). OFFER is repeated with DONE so late joiners can start
 * by NACKing everything. The finished file is checked against the SHA-256 in the OFFER.
 *
 * Messages (all inside an encrypted TYPE_FILE packet, after the callsign):
 * <pre>
 *  OFFER  1, id(4), size(8), chunkSize(2), count(4), sha256(32), mimeLen(1), mime, nameLen(1), name
 *  CHUNK  2, id(4), index(4), data
 *  NACK   3, id(4), rangeCount(1), { start(4), length(2) } ...
 *  DONE   4, id(4)
 * </pre>
 */
public final class FileTransfer {
    public interface Wire {
        void send(byte[] body);
    }

    public interface Listener {
        void onTransfersChanged();

        void onFileReceived(Incoming in);
    }

    private static final String TAG = "FileTransfer";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    public static final int CHUNK = 1150;
    private static final int OFFER = 1, DATA = 2, NACK = 3, DONE = 4;
    /** Chunks per second while streaming. ~1.3 Mbit/s, comfortable on a mesh. */
    private static final int RATE = 140;
    private static final long NACK_QUIET_MS = 1200;
    private static final long GIVE_UP_MS = 120000;
    private static final long KEEP_SENT_MS = 15 * 60000;
    public static final long MAX_RECEIVE_BYTES = 50L * 1024 * 1024;

    public static final class Outgoing {
        public final int id;
        public final String name;
        public final long size;
        public final int count;
        final File file;
        final String mime;
        final byte[] sha;
        int nextFirstPass;
        final BitSet resend = new BitSet();
        public int repairs;
        long lastActivity = System.currentTimeMillis();
        boolean doneSent;

        Outgoing(int id, File file, String name, String mime, byte[] sha) {
            this.id = id;
            this.file = file;
            this.name = name;
            this.mime = mime;
            this.sha = sha;
            this.size = file.length();
            this.count = (int) Math.max(1, (size + CHUNK - 1) / CHUNK);
        }

        public int percentSent() {
            return (int) (100L * nextFirstPass / count);
        }
    }

    public static final class Incoming {
        public final long sender;
        public final String from;
        public final int id;
        public final String name;
        public final String mime;
        public final long size;
        public final int count;
        final int chunkSize;
        final byte[] sha;
        final BitSet have = new BitSet();
        RandomAccessFile raf;
        File part;
        long lastChunk = System.currentTimeMillis();
        long lastNack;
        long lastProgress = System.currentTimeMillis();
        public boolean done, failed;
        public String error;
        public File result;

        Incoming(long sender, String from, int id, String name, String mime, long size, int chunkSize, int count,
                 byte[] sha) {
            this.sender = sender;
            this.from = from;
            this.id = id;
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.chunkSize = chunkSize;
            this.count = count;
            this.sha = sha;
        }

        public int percent() {
            return (int) (100L * have.cardinality() / count);
        }
    }

    private final Wire wire;
    private final Listener listener;
    private final File receiveDir;
    private final File partDir;
    private final List<Outgoing> outgoing = new ArrayList<Outgoing>();
    private final Map<Long, Incoming> incoming = new HashMap<Long, Incoming>();
    private final Thread pump;
    private volatile boolean running = true;
    private final SecureRandom random = new SecureRandom();

    public FileTransfer(Wire wire, Listener listener, File receiveDir, File partDir) {
        this.wire = wire;
        this.listener = listener;
        this.receiveDir = receiveDir;
        this.partDir = partDir;
        receiveDir.mkdirs();
        partDir.mkdirs();
        pump = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "file-pump");
        pump.start();
    }

    public void close() {
        running = false;
        pump.interrupt();
        synchronized (this) {
            for (Incoming in : incoming.values()) closeQuietly(in);
        }
    }

    public synchronized List<Outgoing> outgoing() {
        return new ArrayList<Outgoing>(outgoing);
    }

    public synchronized List<Incoming> incoming() {
        return new ArrayList<Incoming>(incoming.values());
    }

    // ---- sending ----

    /** Queues a file for everyone on the channel. Hashing happens on the caller's thread. */
    public Outgoing send(File file, String name, String mime) throws IOException {
        Outgoing o = new Outgoing(random.nextInt(), file, safeName(name), mime == null ? "" : mime, sha256(file));
        synchronized (this) {
            outgoing.add(o);
        }
        wire.send(offer(o));
        listener.onTransfersChanged();
        return o;
    }

    private byte[] offer(Outgoing o) {
        byte[] mime = trunc(o.mime.getBytes(UTF8), 100);
        byte[] name = trunc(o.name.getBytes(UTF8), 200);
        byte[] b = new byte[1 + 4 + 8 + 2 + 4 + 32 + 1 + mime.length + 1 + name.length];
        int p = 0;
        b[p++] = OFFER;
        p = putInt(b, p, o.id);
        p = putInt(b, p, (int) (o.size >>> 32));
        p = putInt(b, p, (int) o.size);
        b[p++] = (byte) (CHUNK >>> 8);
        b[p++] = (byte) CHUNK;
        p = putInt(b, p, o.count);
        System.arraycopy(o.sha, 0, b, p, 32);
        p += 32;
        b[p++] = (byte) mime.length;
        System.arraycopy(mime, 0, b, p, mime.length);
        p += mime.length;
        b[p++] = (byte) name.length;
        System.arraycopy(name, 0, b, p, name.length);
        return b;
    }

    private void loop() {
        long tick = 1000 / RATE;
        long lastHousekeeping = 0;
        RandomAccessFile open = null;
        Outgoing openFor = null;
        while (running) {
            try {
                Thread.sleep(tick);
            } catch (InterruptedException e) {
                break;
            }
            Outgoing o = null;
            int idx = -1;
            boolean sendDone = false;
            synchronized (this) {
                for (Outgoing c : outgoing) {
                    int r = c.resend.nextSetBit(0);
                    if (r >= 0) {
                        c.resend.clear(r);
                        c.repairs++;
                        o = c;
                        idx = r;
                        break;
                    }
                    if (c.nextFirstPass < c.count) {
                        o = c;
                        idx = c.nextFirstPass++;
                        break;
                    }
                    if (!c.doneSent) {
                        c.doneSent = true;
                        o = c;
                        sendDone = true;
                        break;
                    }
                }
            }
            if (o != null) {
                o.lastActivity = System.currentTimeMillis();
                if (sendDone) {
                    wire.send(offer(o));
                    byte[] d = new byte[5];
                    d[0] = DONE;
                    putInt(d, 1, o.id);
                    wire.send(d);
                    listener.onTransfersChanged();
                } else {
                    try {
                        if (openFor != o) {
                            if (open != null) open.close();
                            open = new RandomAccessFile(o.file, "r");
                            openFor = o;
                        }
                        long off = (long) idx * CHUNK;
                        int len = (int) Math.min(CHUNK, o.size - off);
                        byte[] b = new byte[9 + Math.max(0, len)];
                        b[0] = DATA;
                        putInt(b, 1, o.id);
                        putInt(b, 5, idx);
                        if (len > 0) {
                            open.seek(off);
                            open.readFully(b, 9, len);
                        }
                        wire.send(b);
                        if (idx % 50 == 0) listener.onTransfersChanged();
                    } catch (IOException e) {
                        Log.w(TAG, "read failed", e);
                    }
                }
            }
            long now = System.currentTimeMillis();
            if (now - lastHousekeeping > 500) {
                lastHousekeeping = now;
                housekeeping(now);
            }
        }
        if (open != null) {
            try {
                open.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Receivers ask for gaps; old finished sends are forgotten. */
    private void housekeeping(long now) {
        List<byte[]> nacks = new ArrayList<byte[]>();
        boolean changed = false;
        synchronized (this) {
            Iterator<Outgoing> oi = outgoing.iterator();
            while (oi.hasNext()) {
                Outgoing o = oi.next();
                if (o.doneSent && o.resend.isEmpty() && now - o.lastActivity > KEEP_SENT_MS) {
                    oi.remove();
                    changed = true;
                }
            }
            for (Incoming in : incoming.values()) {
                if (in.done || in.failed) continue;
                if (now - in.lastProgress > GIVE_UP_MS) {
                    fail(in, "Sender went quiet");
                    changed = true;
                    continue;
                }
                if (now - in.lastChunk > NACK_QUIET_MS && now - in.lastNack > NACK_QUIET_MS) {
                    in.lastNack = now;
                    nacks.add(nack(in));
                }
            }
        }
        for (byte[] n : nacks) wire.send(n);
        if (changed) listener.onTransfersChanged();
    }

    private static byte[] nack(Incoming in) {
        // Up to 100 missing ranges per NACK; the next NACK picks up where repairs leave off.
        List<int[]> ranges = new ArrayList<int[]>();
        int i = in.have.nextClearBit(0);
        while (i < in.count && ranges.size() < 100) {
            int end = Math.min(in.have.nextSetBit(i) < 0 ? in.count : in.have.nextSetBit(i), in.count);
            int len = Math.min(end - i, 65535);
            ranges.add(new int[]{i, len});
            i = in.have.nextClearBit(i + len);
        }
        byte[] b = new byte[6 + ranges.size() * 6];
        b[0] = NACK;
        putInt(b, 1, in.id);
        b[5] = (byte) ranges.size();
        int p = 6;
        for (int[] r : ranges) {
            p = putInt(b, p, r[0]);
            b[p++] = (byte) (r[1] >>> 8);
            b[p++] = (byte) r[1];
        }
        return b;
    }

    // ---- receiving ----

    /** body = the TYPE_FILE payload after the callsign. */
    public void onMessage(long sender, String from, byte[] b, int off) {
        if (b.length - off < 5) return;
        int kind = b[off];
        int id = getInt(b, off + 1);
        try {
            switch (kind) {
                case OFFER:
                    onOffer(sender, from, id, b, off + 5);
                    break;
                case DATA:
                    onData(sender, id, b, off + 5);
                    break;
                case NACK:
                    onNack(id, b, off + 5);
                    break;
                case DONE:
                    synchronized (this) {
                        Incoming in = incoming.get(key(sender, id));
                        if (in != null) in.lastChunk = 0; // stream over: NACK at once
                    }
                    break;
                default:
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "bad file message", e);
        }
    }

    private void onOffer(long sender, String from, int id, byte[] b, int p) {
        synchronized (this) {
            if (incoming.containsKey(key(sender, id))) return;
        }
        long size = ((long) getInt(b, p) << 32) | (getInt(b, p + 4) & 0xffffffffL);
        p += 8;
        int chunk = ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
        p += 2;
        int count = getInt(b, p);
        p += 4;
        byte[] sha = new byte[32];
        System.arraycopy(b, p, sha, 0, 32);
        p += 32;
        int ml = b[p++] & 0xff;
        String mime = new String(b, p, ml, UTF8);
        p += ml;
        int nl = b[p++] & 0xff;
        String name = safeName(new String(b, p, nl, UTF8));
        if (chunk <= 0 || count <= 0 || size < 0 || (size + chunk - 1) / chunk > count + 1) return;
        Incoming in = new Incoming(sender, from, id, name, mime, size, chunk, count, sha);
        if (size > MAX_RECEIVE_BYTES) {
            in.failed = true;
            in.error = "Too big (" + (size / 1048576) + " MB)";
        } else {
            try {
                in.part = new File(partDir, Long.toHexString(sender) + "-" + Integer.toHexString(id) + ".part");
                in.raf = new RandomAccessFile(in.part, "rw");
                in.raf.setLength(size);
            } catch (IOException e) {
                in.failed = true;
                in.error = "No space: " + e.getMessage();
            }
        }
        synchronized (this) {
            incoming.put(key(sender, id), in);
        }
        listener.onTransfersChanged();
    }

    private void onData(long sender, int id, byte[] b, int p) throws RuntimeException {
        Incoming in;
        synchronized (this) {
            in = incoming.get(key(sender, id));
        }
        if (in == null || in.done || in.failed || b.length - p < 4) return;
        int idx = getInt(b, p);
        p += 4;
        if (idx < 0 || idx >= in.count) return;
        boolean complete;
        synchronized (in) {
            if (in.have.get(idx)) return;
            try {
                in.raf.seek((long) idx * in.chunkSize);
                in.raf.write(b, p, b.length - p);
            } catch (IOException e) {
                fail(in, "Write failed: " + e.getMessage());
                listener.onTransfersChanged();
                return;
            }
            in.have.set(idx);
            in.lastChunk = in.lastProgress = System.currentTimeMillis();
            complete = in.have.cardinality() == in.count;
        }
        if (complete) finish(in);
        else if (idx % 50 == 0) listener.onTransfersChanged();
    }

    private void onNack(int id, byte[] b, int p) {
        synchronized (this) {
            for (Outgoing o : outgoing) {
                if (o.id != id) continue;
                int n = b[p++] & 0xff;
                for (int i = 0; i < n && p + 6 <= b.length; i++) {
                    int start = getInt(b, p);
                    int len = ((b[p + 4] & 0xff) << 8) | (b[p + 5] & 0xff);
                    p += 6;
                    if (start >= 0 && start < o.count) o.resend.set(start, Math.min(o.count, start + len));
                }
                o.doneSent = false; // send OFFER + DONE again after the repairs
                o.lastActivity = System.currentTimeMillis();
                return;
            }
        }
    }

    private void finish(Incoming in) {
        try {
            in.raf.close();
            in.raf = null;
            if (!MessageDigest.isEqual(sha256(in.part), in.sha)) {
                fail(in, "Checksum mismatch");
            } else {
                File out = unique(receiveDir, in.name);
                // The part file is in internal storage and Received is usually on shared storage, a
                // different file system on most real phones and Android 11+, where rename fails: copy.
                if (!in.part.renameTo(out)) {
                    copyFile(in.part, out);
                    in.part.delete();
                }
                in.result = out;
                in.done = true;
            }
        } catch (IOException e) {
            fail(in, e.getMessage());
        }
        listener.onTransfersChanged();
        if (in.done) listener.onFileReceived(in);
    }

    private static void copyFile(File from, File to) throws IOException {
        java.io.InputStream is = new java.io.FileInputStream(from);
        java.io.OutputStream os = new java.io.FileOutputStream(to);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        } catch (IOException e) {
            os.close();
            to.delete();
            throw e;
        } finally {
            is.close();
            os.close();
        }
    }

    private void fail(Incoming in, String why) {
        in.failed = true;
        in.error = why;
        closeQuietly(in);
        if (in.part != null) in.part.delete();
    }

    private static void closeQuietly(Incoming in) {
        if (in.raf != null) {
            try {
                in.raf.close();
            } catch (IOException ignored) {
            }
            in.raf = null;
        }
    }

    // ---- helpers ----

    private static long key(long sender, int id) {
        return sender * 31 + id;
    }

    public static byte[] sha256(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            } finally {
                in.close();
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e.getMessage());
        }
    }

    static String safeName(String n) {
        String s = n.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        if (s.length() == 0 || s.startsWith(".")) s = "file" + s;
        return s.length() > 120 ? s.substring(s.length() - 120) : s;
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; f.exists(); i++) f = new File(dir, base + " (" + i + ")" + ext);
        return f;
    }

    private static byte[] trunc(byte[] b, int max) {
        if (b.length <= max) return b;
        byte[] t = new byte[max];
        System.arraycopy(b, 0, t, 0, max);
        return t;
    }

    private static int putInt(byte[] b, int p, int v) {
        b[p] = (byte) (v >>> 24);
        b[p + 1] = (byte) (v >>> 16);
        b[p + 2] = (byte) (v >>> 8);
        b[p + 3] = (byte) v;
        return p + 4;
    }

    private static int getInt(byte[] b, int p) {
        return ((b[p] & 0xff) << 24) | ((b[p + 1] & 0xff) << 16) | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
    }
}
