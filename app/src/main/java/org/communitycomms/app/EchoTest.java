package org.communitycomms.app;

import android.util.Log;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Mesh testing: Echo Test. While on, this phone also runs a station called "ECHO TEST" (editable) on the channel on
 * air: it advertises it like a contact (presence flag bit 3 = echo, so no Echo Test ever echoes another), records
 * each transmission from a real person, direct or (unless turned off) through a relay, the internet relay, a cloud
 * group or a channel link, saying which in the report (never its own, never
 * another Echo Test), and when the talker lets go: after 2 s a chat note "Echo Test: received from X", 3 s later a
 * link report as a text-as-voice message (read aloud on the phones, and shown in chat), then the recording played
 * back exactly as it arrived (lost frames left as gaps). One test at a time, the rest wait in a queue.
 */
public final class EchoTest {
    private static final String TAG = "EchoTest";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int FRAME_MS = RadioEngine.FRAME_SAMPLES * 1000 / RadioEngine.SAMPLE_RATE;

    private static volatile boolean on;
    private static volatile RadioEngine onEngine;
    private static volatile String status = "";
    private static volatile String lastReport = "";
    private static Thread worker;
    private static final ArrayDeque<Rec> queue = new ArrayDeque<Rec>();
    private static final Map<Long, Rec> recording = new HashMap<Long, Rec>();
    private static long lastEchoEnd;
    private static volatile boolean playing;

    private EchoTest() {
    }

    // ---- settings ----

    public static String name() {
        String n = MeshTest.sp().getString("echo_name", "ECHO TEST").trim();
        return n.length() == 0 ? "ECHO TEST" : n.length() > 32 ? n.substring(0, 32) : n;
    }

    public static int maxSec() {
        return Math.max(3, Math.min(120, MeshTest.sp().getInt("echo_max", 30)));
    }

    public static int cooldownSec() {
        return Math.max(0, Math.min(120, MeshTest.sp().getInt("echo_cool", 5)));
    }

    public static boolean chatEcho() {
        return MeshTest.sp().getBoolean("echo_chat", true);
    }

    public static boolean onMap() {
        return MeshTest.sp().getBoolean("echo_map", false);
    }

    public static boolean running() {
        return on;
    }

    public static String status() {
        return status;
    }

    public static String lastReport() {
        return lastReport;
    }

    // ---- start / stop ----

    /** Returns an error to show, or null when started. */
    public static synchronized String start() {
        if (on) return null;
        RadioService rs = RadioService.instance();
        if (rs == null) return "The radio is not running";
        if (rs.compat() != null) return "Echo Test works on YCC channels (not the OM/TAK talk group)";
        if (rs.engine() == null) return "Join a channel first";
        if (rs.engine().listenOnly()) return "This channel is listen-only on this phone";
        on = true;
        status = "Waiting for a test on " + MeshTest.channelNow();
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "echo-test");
        worker.start();
        Log.i(TAG, "started as " + name());
        return null;
    }

    public static synchronized void stop() {
        if (!on) return;
        on = false;
        if (worker != null) worker.interrupt();
        worker = null;
        synchronized (queue) {
            queue.clear();
        }
        synchronized (recording) {
            recording.clear();
        }
        final RadioEngine e = onEngine;
        onEngine = null;
        if (e != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    e.sendGoodbyeAs(name());
                }
            }, "echo-bye").start();
        }
        status = "Off";
        Log.i(TAG, "stopped");
    }

    /** The channel on air may be used: private, or public with the user's tick. */
    private static boolean allowedOn(RadioEngine e) {
        return !MeshTest.isPublic(e) || MeshTest.allowPublic("echo");
    }

    private static RadioEngine engineNow() {
        RadioService rs = RadioService.instance();
        return rs == null || rs.compat() != null ? null : rs.engine();
    }

    // ---- receiving ----

    /** One transmission being recorded. */
    static final class Rec {
        final long sender;
        final String name;
        final int tx;
        final RadioEngine engine;
        final long startMs = System.currentTimeMillis();
        long lastMs = startMs, endMs;
        final TreeMap<Integer, short[]> frames = new TreeMap<Integer, short[]>(); // seq -> pcm (on time only)
        final Map<Integer, Long> arrival = new HashMap<Integer, Long>();
        final Set<Integer> otherSeqs = new HashSet<Integer>(); // non-voice packets from the talker meanwhile
        int maxSeq = Integer.MIN_VALUE, late, dup, afterEnd;
        String via = "direct";
        boolean ended, truncated;

        Rec(RadioEngine e, long sender, String name, int tx) {
            engine = e;
            this.sender = sender;
            this.name = name;
            this.tx = tx;
        }
    }

    /**
     * Never echoed: Echo Tests (this one or another, also when a channel link relays it as "via OpenMANET: ECHO TEST"),
     * and this phone's own relays towards TAK ("NAME (YCC A)").
     */
    static boolean ignoredName(String n) {
        String low = n.toLowerCase(Locale.US);
        return n.contains("(YCC") || low.contains(name().toLowerCase(Locale.US)) || low.contains("echo test");
    }

    /** Answer tests that came through a relay, the internet relay / tunnel, a cloud group or a channel link (on by default). */
    public static boolean answerRelayed() {
        return MeshTest.sp().getBoolean("echo_relayed", true);
    }

    /** How a transmission reached this phone, for the report: "direct", "via internet relay", "via 2 mesh hops"… */
    static String pathOf(RadioEngine e, String name, InetAddress from) {
        if (name.startsWith("via OpenMANET")) return "relayed from the OpenMANET talk group";
        if (e.viaRelay(from)) {
            if (from == null || from.isLoopbackAddress()) return "via internet relay";
            return "via a relay";
        }
        int h = MeshTest.topologyHops(from == null ? null : from.getHostAddress());
        return h >= 2 ? "via " + h + " mesh hops" : h == 1 ? "direct" : "direct off the network, mesh hops unknown";
    }

    private static boolean isEchoStation(RadioEngine e, long sender) {
        for (RadioEngine.Station s : e.stations()) if (s.id == sender) return s.echo;
        return false;
    }

    static void onFrame(RadioEngine e, long sender, String name, int seq, int tx, short[] pcm, boolean late, InetAddress from) {
        if (!on || e.isMonitor() || e != engineNow() || !allowedOn(e)) return;
        long now = System.currentTimeMillis();
        synchronized (recording) {
            Rec r = recording.get(sender);
            if (late) { // after its END: count it against the transmission it belongs to
                if (r != null && r.tx == tx) r.afterEnd++;
                else lateAfterEnd(sender, tx);
                return;
            }
            if (r == null || r.tx != tx) {
                if (r != null) finish(r);
                if (ignoredName(name) || isEchoStation(e, sender)) return;
                String via = pathOf(e, name, from);
                if (!via.startsWith("direct") && !answerRelayed()) {
                    Log.i(TAG, "ignored " + name + ": " + via + " (Answer relayed and remote tests is off)");
                    return;
                }
                r = new Rec(e, sender, name, tx);
                r.via = via;
                recording.put(sender, r);
                status = "Recording " + name + "…";
                Log.i(TAG, "recording " + name);
            }
            r.lastMs = now;
            if (r.frames.containsKey(seq)) {
                r.dup++;
                return;
            }
            if (r.maxSeq != Integer.MIN_VALUE && seq < r.maxSeq) {
                r.late++; // arrived out of order: a phone's player drops these, so the echo does too
                return;
            }
            if (r.frames.size() >= maxSec() * 1000 / FRAME_MS) {
                r.truncated = true;
                return;
            }
            r.maxSeq = seq;
            r.frames.put(seq, pcm == null ? new short[RadioEngine.FRAME_SAMPLES] : pcm.clone());
            r.arrival.put(seq, now);
        }
    }

    /** Late frames of a transmission already handed to the queue. */
    private static void lateAfterEnd(long sender, int tx) {
        synchronized (queue) {
            for (Rec q : queue) if (q.sender == sender && q.tx == tx) q.afterEnd++;
        }
    }

    static void onOtherPacket(RadioEngine e, long sender, int seq) {
        if (!on) return;
        synchronized (recording) {
            Rec r = recording.get(sender);
            if (r != null && r.engine == e) r.otherSeqs.add(seq);
        }
    }

    static void onEnd(RadioEngine e, long sender, int tx) {
        if (!on) return;
        synchronized (recording) {
            Rec r = recording.get(sender);
            if (r != null && r.tx == tx && !r.ended) {
                r.ended = true;
                r.endMs = System.currentTimeMillis();
            }
        }
    }

    /** Recording done: into the queue (one test at a time). Caller holds the recording lock. */
    private static void finish(Rec r) {
        recording.remove(r.sender);
        if (r.frames.size() < 5) { // a tap, not a test
            Log.i(TAG, "too short from " + r.name + ": " + r.frames.size() + " frames");
            return;
        }
        synchronized (queue) {
            if (queue.size() >= 5) {
                Log.i(TAG, "queue full, dropped a test from " + r.name);
                return;
            }
            queue.addLast(r);
            queue.notifyAll();
        }
    }

    /** Chat: "echo", "echo test" or "@ECHO TEST …" gets a short link note back (when Echo chat is ticked). */
    static void onChat(RadioEngine e, long sender, String fromName, InetAddress from, byte[] p, int off) {
        if (!on || !chatEcho() || e != engineNow() || !allowedOn(e) || ignoredName(fromName) || isEchoStation(e, sender)) return;
        ByteBuffer b = ByteBuffer.wrap(p, off, p.length - off);
        b.getLong();
        long age = b.getLong();
        if (age > 60000) return; // a catch-up copy of an old message
        int cl = b.get() & 0xff;
        byte[] c = new byte[cl];
        b.get(c);
        String creator = new String(c, UTF8);
        byte[] t = new byte[b.getShort() & 0xffff];
        b.get(t);
        String text = new String(t, UTF8).trim();
        String low = text.toLowerCase(Locale.US);
        String n = name().toLowerCase(Locale.US);
        if (!(low.equals("echo") || low.equals("echo test") || low.startsWith("@" + n) || low.startsWith(n + ":") || low.startsWith("@echo"))) return;
        if (ignoredName(creator)) return;
        String via = pathOf(e, fromName, from);
        if (!via.startsWith("direct") && !answerRelayed()) return;
        MeshTest.Link l = MeshTest.link(e, sender);
        long now = System.currentTimeMillis();
        double loss = l.loss(now);
        int jit = l.jitterMs(now);
        final String reply = "Echo Test: got your message, " + creator + " (" + via + ")."
                + " Recent link: " + (loss < 0 ? "loss unknown" : Math.round(loss * 100) + "% loss")
                + (jit >= 0 ? ", jitter " + jit + " ms" : "") + ", signal " + MeshTest.gradeWord(MeshTest.grade(loss, jit)) + ".";
        final RadioEngine eng = e;
        new Thread(new Runnable() {
            @Override
            public void run() {
                sleep(2000);
                post(eng, reply);
            }
        }, "echo-chat").start();
    }

    // ---- the worker: presence, finishing recordings, reports and playback ----

    private static void loop() {
        long lastPresence = 0;
        while (on) {
            try {
                long now = System.currentTimeMillis();
                RadioEngine e = engineNow();
                RadioEngine old = onEngine;
                if (e != old) {
                    if (old != null) old.sendGoodbyeAs(name());
                    onEngine = e;
                    lastPresence = 0;
                }
                if (e != null && allowedOn(e) && now - lastPresence > 10000) {
                    lastPresence = now;
                    e.sendPresenceAs(name(), true);
                    if (onMap()) sendPosition(e);
                }
                if (e != null && !allowedOn(e)) status = "Paused: " + MeshTest.channelNow() + " is public (not allowed)";
                // Close recordings: END heard (plus 0.5 s for stragglers), or 1.5 s of silence (END lost).
                synchronized (recording) {
                    for (Rec r : new ArrayList<Rec>(recording.values())) {
                        if ((r.ended && now - r.endMs > 500) || now - r.lastMs > 1500) finish(r);
                    }
                }
                Rec next = null;
                synchronized (queue) {
                    if (!queue.isEmpty() && now - lastEchoEnd >= cooldownSec() * 1000L) next = queue.pollFirst();
                }
                if (next != null) {
                    echo(next);
                    lastEchoEnd = System.currentTimeMillis();
                    if (on) status = "Waiting for a test on " + MeshTest.channelNow();
                } else {
                    synchronized (queue) {
                        queue.wait(250);
                    }
                }
            } catch (InterruptedException ex) {
                return;
            } catch (RuntimeException ex) {
                Log.w(TAG, "loop", ex);
            }
        }
    }

    /** The Echo Test station on the map: at the fixed station point if one is set, else this phone's position. */
    private static void sendPosition(RadioEngine e) {
        double[] f = MeshMap.fixedPoint();
        if (f != null) {
            e.sendLocationAs(name(), f[0], f[1], 10f, System.currentTimeMillis());
            return;
        }
        RadioService rs = RadioService.instance();
        android.location.Location l = rs == null ? null : MeshWalk.myLocation(rs);
        if (l != null) e.sendLocationAs(name(), l.getLatitude(), l.getLongitude(), l.getAccuracy(), l.getTime());
    }

    /** The numbers for one recording. */
    static final class Stats {
        int received, lost, late, dup, slots;
        double seconds;
        int jitterMs = -1;
        int grade;
        String report;
    }

    static Stats stats(Rec r) {
        Stats s = new Stats();
        int min = r.frames.firstKey(), max = r.frames.lastKey();
        // Voice slots from the first frame to the last: sequence numbers in between, less the talker's other packets.
        int other = 0;
        for (int q : r.otherSeqs) if (q > min && q < max) other++;
        s.slots = Math.max(r.frames.size(), max - min + 1 - other);
        s.received = r.frames.size();
        s.late = r.late + r.afterEnd;
        s.dup = r.dup;
        s.lost = Math.max(0, s.slots - s.received - s.late); // late frames did arrive, just too late to play
        s.seconds = s.slots * FRAME_MS / 1000.0;
        // Jitter (RFC 3550 style): arrival spacing against the 40 ms the frames were sent apart.
        double j = -1;
        Integer prevSeq = null;
        long prevArr = 0;
        int prevSlot = 0;
        for (Map.Entry<Integer, short[]> en : r.frames.entrySet()) {
            int seq = en.getKey();
            int slot = slotOf(r, min, seq);
            long arr = r.arrival.get(seq);
            if (prevSeq != null) {
                double d = Math.abs((arr - prevArr) - (slot - prevSlot) * (double) FRAME_MS);
                j = j < 0 ? d : j + (d - j) / 16.0;
            }
            prevSeq = seq;
            prevArr = arr;
            prevSlot = slot;
        }
        s.jitterMs = j < 0 ? -1 : (int) Math.round(j);
        double loss = s.slots == 0 ? 0 : (s.lost + s.late) / (double) s.slots; // late ones are gaps for the listener
        s.grade = MeshTest.grade(loss, s.jitterMs);
        boolean direct = r.via.startsWith("direct");
        s.report = String.format(Locale.US, "Received a test from %s, %s. %d packets, %d lost, %d late, %d duplicate%s, duration %.1f seconds, average jitter %s, signal %s.",
                r.name, r.via, s.received, s.lost, s.late, s.dup, s.dup == 1 ? "" : "s", s.seconds,
                s.jitterMs < 0 ? "unknown" : s.jitterMs + " milliseconds", MeshTest.gradeWord(s.grade))
                + (direct ? "" : " Loss and jitter are for the whole path, relay included.")
                + (r.truncated ? " Recording stopped at " + maxSec() + " seconds." : "")
                + " No forward error correction on YCC channels, so nothing was recovered.";
        return s;
    }

    /** Voice slot index of a sequence number: its distance from the first, less the talker's other packets between. */
    private static int slotOf(Rec r, int min, int seq) {
        int other = 0;
        for (int q : r.otherSeqs) if (q > min && q < seq) other++;
        return seq - min - other;
    }

    private static void echo(Rec r) throws InterruptedException {
        RadioEngine e = r.engine;
        if (e != engineNow() || !allowedOn(e)) {
            Log.i(TAG, "channel changed: dropped the test from " + r.name);
            return;
        }
        Stats s = stats(r);
        lastReport = s.report;
        Log.i(TAG, "REPORT " + s.report);
        status = "Answering " + r.name + "…";
        Thread.sleep(2000);
        if (!on) return;
        post(e, "Echo Test: received from " + r.name);
        Thread.sleep(3000);
        if (!on) return;
        post(e, RadioService.SAY + s.report);
        // Let the phones read the report out before the playback starts (about 15 characters a second).
        Thread.sleep(Math.min(14000, 1500 + s.report.length() * 65L));
        if (!on) return;
        playBack(e, r);
    }

    private static void post(RadioEngine e, String text) {
        if (RadioEngine.radioSilent) return;
        RadioService rs = RadioService.instance();
        if (rs == null || rs.messages() == null) return;
        rs.messages().sendTextAs(e, name(), text);
        Log.i(TAG, "chat: " + text);
    }

    /** The recording as it arrived: frames in order, lost ones left as silence, at the normal 40 ms pace. */
    private static void playBack(RadioEngine e, Rec r) throws InterruptedException {
        boolean started = false;
        for (int t = 0; t < 40 && on; t++) { // up to 10 s for this phone's own talk (or a relay) to finish
            if (e.injectStart(name())) {
                started = true;
                break;
            }
            Thread.sleep(250);
        }
        if (!started) {
            post(e, "Echo Test: busy, playback of " + r.name + "'s test skipped");
            return;
        }
        playing = true;
        status = "Playing back " + r.name + "'s test";
        int min = r.frames.firstKey();
        List<short[]> line = new ArrayList<short[]>();
        short[] gap = new short[RadioEngine.FRAME_SAMPLES];
        int lastSlot = -1;
        for (Map.Entry<Integer, short[]> en : r.frames.entrySet()) {
            int slot = slotOf(r, min, en.getKey());
            for (int k = lastSlot + 1; k < slot; k++) line.add(gap);
            line.add(en.getValue());
            lastSlot = slot;
        }
        int gaps = 0;
        for (short[] f : line) if (f == gap) gaps++;
        Log.i(TAG, "playback " + line.size() + " frames (" + gaps + " gaps) to " + MeshTest.label(e));
        try {
            long next = System.currentTimeMillis();
            for (short[] f : line) {
                if (!on || e != engineNow()) break;
                e.injectFrame(f);
                next += FRAME_MS;
                long w = next - System.currentTimeMillis();
                if (w > 0) Thread.sleep(w);
            }
        } finally {
            e.injectEnd();
            playing = false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
