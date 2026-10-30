package org.communitycomms.app;

import android.util.Log;

import org.communitycomms.app.audio.Capture;
import org.communitycomms.app.audio.ImaAdpcm;
import org.communitycomms.app.audio.Mixer;
import org.communitycomms.app.crypto.ChannelCrypto;
import org.communitycomms.app.net.FileTransfer;
import org.communitycomms.app.net.Packet;
import org.communitycomms.app.net.UdpLink;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/** The radio: one channel, many talkers, full duplex. No Android UI in here. */
public final class RadioEngine {
    public interface Listener {
        /** Called from background threads. */
        void onRosterChanged();

        void onTransmitChanged(boolean transmitting);

        /** A complete DRYAD transfer arrived from another station. */
        void onDryadReceived(String fromName, String text);

        /**
         * ACK_WAIT_MS after a transmission ended: who confirmed hearing it. Empty means nobody did,
         * and resendLast() can replay the same audio.
         */
        void onTransmitResult(List<String> heardBy);

        /** A map POI message (see geo.Poi for the body layout). */
        void onPoiMessage(String fromName, byte[] payload, int off);

        /** Someone pressed Call: ring this phone. */
        void onCall(String fromName);

        /** A chat / report / catch-up message (see msg.MessageCenter for the body layout). */
        void onChatMessage(String fromName, byte[] payload, int off);
    }

    /** Sends a chat body (msg.MessageCenter) to the channel, repeated for loss. */
    public void sendChatBody(byte[] body, int times) {
        byte[] p = namePayload(body.length);
        System.arraycopy(body, 0, p, p.length - body.length, body.length);
        for (int i = 0; i < times; i++) send(Packet.TYPE_CHAT, p, p.length);
    }

    /** This run's random station id (what receivers see as the sender). */
    public long myId() {
        return myId;
    }

    /** Sends a POI body (geo.Poi.encode or a sync request) to the channel, twice for loss. */
    public void sendPoiBody(byte[] body) {
        byte[] p = namePayload(body.length);
        System.arraycopy(body, 0, p, p.length - body.length, body.length);
        send(Packet.TYPE_MARK, p, p.length);
        send(Packet.TYPE_MARK, p, p.length);
    }

    /**
     * A map point sealed for another channel (the Public one) and sent through this engine's socket:
     * "share on Public" without listening to Public. Sent twice like any map point.
     */
    public void sendPoiBodyFor(ChannelCrypto other, org.communitycomms.app.crypto.ListenKey lk, byte[] body) {
        UdpLink l = link;
        if (l == null || radioSilent || other == null) return;
        byte[] p = namePayload(body.length);
        System.arraycopy(body, 0, p, p.length - body.length, body.length);
        try {
            for (int i = 0; i < 2; i++) {
                byte[] sealed = sealSigned(other, lk, Packet.TYPE_MARK, p, p.length);
                if (sealed != null) l.send(sealed);
            }
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "seal failed", e);
        }
    }

    /** How long after the end of a transmission we wait for acknowledgements. */
    public static final long ACK_WAIT_MS = 5000;
    /** Longest transmission kept for resending (3 minutes of 40 ms frames). */
    private static final int MAX_RECORDED_FRAMES = 4500;

    private final SecureRandom random = new SecureRandom();
    private volatile int txId;
    private final List<byte[]> recording = new ArrayList<byte[]>();
    private List<byte[]> lastRecording = new ArrayList<byte[]>();
    private final Map<Integer, List<String>> acks = new HashMap<Integer, List<String>>();
    private volatile boolean replaying;

    public boolean canResend() {
        synchronized (recording) {
            return !lastRecording.isEmpty();
        }
    }

    /** Replays the last transmission (e.g. after nobody acknowledged it), with a fresh id. */
    public boolean resendLast() {
        final List<byte[]> frames;
        synchronized (recording) {
            if (transmitting || lastRecording.isEmpty() || radioSilent) return false;
            frames = new ArrayList<byte[]>(lastRecording);
        }
        transmitting = true;
        replaying = true;
        txId = random.nextInt();
        listener.onTransmitChanged(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                long next = System.currentTimeMillis();
                for (byte[] f : frames) {
                    if (!replaying) break;
                    byte[] p = namePayload(4 + f.length);
                    int o = p.length - 4 - f.length;
                    putTx(p, o, txId);
                    System.arraycopy(f, 0, p, o + 4, f.length);
                    send(Packet.TYPE_VOICE, p, p.length);
                    next += 40;
                    long wait = next - System.currentTimeMillis();
                    if (wait > 0) {
                        try {
                            Thread.sleep(wait);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
                replaying = false;
                endTransmission();
            }
        }, "resend").start();
        return true;
    }

    private static void putTx(byte[] p, int o, int id) {
        p[o] = (byte) (id >>> 24);
        p[o + 1] = (byte) (id >>> 16);
        p[o + 2] = (byte) (id >>> 8);
        p[o + 3] = (byte) id;
    }

    private static int getTx(byte[] p, int o) {
        return ((p[o] & 0xff) << 24) | ((p[o + 1] & 0xff) << 16) | ((p[o + 2] & 0xff) << 8) | (p[o + 3] & 0xff);
    }

    /** Sends END (three times: it is what triggers the acks), then checks for acks later. */
    private void endTransmission() {
        endTransmission(true);
    }

    /** expectAcks false: nothing was sent (a tap too short for one frame), so no "failed" result. */
    private void endTransmission(boolean expectAcks) {
        final int id = txId;
        if (expectAcks) {
            synchronized (acks) {
                acks.put(id, new ArrayList<String>());
            }
        }
        byte[] p = namePayload(4);
        putTx(p, p.length - 4, id);
        for (int i = 0; i < 3; i++) send(Packet.TYPE_VOICE_END, p, p.length);
        final List<RadioEngine> sentAlso = mirrors;
        for (RadioEngine m : sentAlso) m.mirrorEnd(id);
        transmitting = false;
        listener.onTransmitChanged(false);
        Timer t = timer;
        if (t == null) return;
        try {
            t.schedule(new TimerTask() {
                @Override
                public void run() {
                    List<String> heard;
                    synchronized (acks) {
                        heard = acks.remove(id);
                    }
                    if (heard != null) { // a broadcast: who heard it on each other channel too
                        for (RadioEngine m : sentAlso) {
                            for (String n : m.takeAcks(id)) if (!heard.contains(n)) heard.add(n);
                        }
                    }
                    if (heard != null) listener.onTransmitResult(heard);
                }
            }, ACK_WAIT_MS);
        } catch (IllegalStateException ignored) {
            // timer cancelled: we left the channel
        }
    }

    /** Receiver side: confirm a transmission once, on its END or when it stops arriving. */
    private void sendAck(Station s) {
        if (s.rxAcked || !s.rxHasTx) return;
        s.rxAcked = true;
        byte[] p = namePayload(14);
        int o = p.length - 14;
        putTx(p, o, s.rxTx);
        for (int i = 0; i < 8; i++) p[o + 4 + i] = (byte) (s.id >>> (56 - 8 * i));
        p[o + 12] = (byte) (s.rxFrames >>> 8);
        p[o + 13] = (byte) s.rxFrames;
        send(Packet.TYPE_ACK, p, p.length);
    }

    private static final int DRYAD_CHUNK = 1000;
    private static final int DRYAD_REPEATS = 3;

    private static final class Transfer {
        String from;
        byte[][] chunks;
        int have;
        long lastMs;
    }

    private final Map<Long, Transfer> transfers = new HashMap<Long, Transfer>();
    private final java.util.Set<Long> finishedTransfers = new java.util.HashSet<Long>();

    /**
     * Sends sheet text to everyone on the channel. Each chunk goes out several times because
     * broadcast has no retries; receivers ignore copies they already have.
     */
    public void sendDryad(final String text) {
        final byte[] all = text.getBytes(UTF8);
        final int count = (all.length + DRYAD_CHUNK - 1) / DRYAD_CHUNK;
        final int id = new SecureRandom().nextInt();
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int rep = 0; rep < DRYAD_REPEATS; rep++) {
                    for (int i = 0; i < count; i++) {
                        int off = i * DRYAD_CHUNK;
                        int len = Math.min(DRYAD_CHUNK, all.length - off);
                        byte[] p = namePayload(8 + len);
                        int o = p.length - 8 - len;
                        p[o] = (byte) (id >>> 24);
                        p[o + 1] = (byte) (id >>> 16);
                        p[o + 2] = (byte) (id >>> 8);
                        p[o + 3] = (byte) id;
                        p[o + 4] = (byte) (i >>> 8);
                        p[o + 5] = (byte) i;
                        p[o + 6] = (byte) (count >>> 8);
                        p[o + 7] = (byte) count;
                        System.arraycopy(all, off, p, o + 8, len);
                        send(Packet.TYPE_DRYAD, p, p.length);
                        try {
                            Thread.sleep(15);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }
        }, "dryad-tx").start();
    }

    private void handleDryad(long sender, String name, byte[] p, int off) {
        if (p.length - off < 8) return;
        int id = ((p[off] & 0xff) << 24) | ((p[off + 1] & 0xff) << 16) | ((p[off + 2] & 0xff) << 8) | (p[off + 3] & 0xff);
        int idx = ((p[off + 4] & 0xff) << 8) | (p[off + 5] & 0xff);
        int count = ((p[off + 6] & 0xff) << 8) | (p[off + 7] & 0xff);
        if (count == 0 || idx >= count || count > 2000) return;
        long key = (sender << 32) ^ (id & 0xffffffffL);
        String done = null;
        synchronized (transfers) {
            if (finishedTransfers.contains(key)) return;
            Transfer t = transfers.get(key);
            if (t == null) {
                t = new Transfer();
                t.from = name;
                t.chunks = new byte[count][];
                transfers.put(key, t);
            }
            if (t.chunks.length != count || t.chunks[idx] != null) return;
            byte[] c = new byte[p.length - off - 8];
            System.arraycopy(p, off + 8, c, 0, c.length);
            t.chunks[idx] = c;
            t.have++;
            t.lastMs = System.currentTimeMillis();
            if (t.have == count) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                for (byte[] b : t.chunks) bo.write(b, 0, b.length);
                done = new String(bo.toByteArray(), UTF8);
                transfers.remove(key);
                finishedTransfers.add(key);
            }
        }
        if (done != null) listener.onDryadReceived(name, done);
    }

    public static final class Station {
        public final long id;
        public String name = "?";
        public long lastSeenMs;
        public long lastVoiceMs;
        public String address;
        int rxTx;
        boolean rxHasTx, rxAcked;
        int rxFrames;
        /** Last advertised position (NaN until they send one). */
        public double lat = Double.NaN, lon = Double.NaN;
        public float accuracy;
        public long fixTimeMs;
        public String w3w = "";
        /** The icon they chose to show as on maps, or -1 (a dot). */
        public int icon = -1;
        /** Their last position came from fake GPS (testing): maps show "(Fake)". */
        public boolean fake;
        /** From their presence: STATUS_*, or -1 when their app is too old to say. */
        public int status = -1;
        /** Battery percent from their presence, or -1 when unknown. */
        public int battery = -1;
        /** From their presence: how often they check in when slower than normal (a power mode), or 0. */
        public long checkInMs;

        Station(long id) {
            this.id = id;
        }

        public boolean isTalking(long now) {
            return now - lastVoiceMs < 400;
        }
    }

    private static final String TAG = "RadioEngine";
    public static final int SAMPLE_RATE = 8000;
    /** 40 ms frames: 320 samples, 163 bytes of ADPCM. */
    public static final int FRAME_SAMPLES = 320;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final ChannelCrypto crypto;
    /** Signed channel (Public with a changed key), or null. */
    private final org.communitycomms.app.crypto.ListenKey listenKey;
    private final String channelName;
    private final long myId;
    private volatile String callsign;
    private final Listener listener;
    private final Map<Long, Station> stations = new HashMap<Long, Station>();
    private final Object seqLock = new Object();
    private int seq;

    private volatile UdpLink link;
    private Mixer mixer;
    private Capture capture;
    private final ImaAdpcm encoder = new ImaAdpcm();
    private Timer timer;
    private volatile boolean transmitting;

    /** Slow (PBKDF2): call off the main thread. */
    public RadioEngine(String channelName, String callsign, Listener listener) throws GeneralSecurityException {
        this(channelName, null, callsign, listener);
    }

    /**
     * A signed Public channel (its key changed from the default): keys come from the listen key,
     * every packet is signed (or, with only a listen key, nothing is sent at all) and checked.
     * channelName stays the name as set in the app, for matching it to a channel.
     */
    public RadioEngine(String channelName, org.communitycomms.app.crypto.ListenKey lk, String callsign, Listener listener)
            throws GeneralSecurityException {
        this.channelName = channelName.trim();
        this.crypto = new ChannelCrypto(lk == null ? channelName : lk.channelName());
        this.listenKey = lk;
        this.callsign = callsign;
        this.listener = listener;
        SecureRandom r = new SecureRandom();
        this.myId = r.nextLong();
        this.seq = r.nextInt() & 0x7fffffff;
    }

    /** Stereo dual watch: -1 = play this channel in the left ear, 1 = right, 0 = normal (both). */
    private int pan;

    public void setPan(int p) {
        pan = p;
    }

    public void start(int port, List<InetSocketAddress> destinations, InetAddress group,
                      java.net.NetworkInterface iface) throws IOException {
        mixer = new Mixer(SAMPLE_RATE, FRAME_SAMPLES, pan);
        link = new UdpLink(port, destinations, group, iface, new UdpLink.Receiver() {
            @Override
            public void onDatagram(byte[] buf, int len, InetAddress from) {
                handle(buf, len, from);
            }
        });
        capture = new Capture(FRAME_SAMPLES, new Capture.FrameSink() {
            @Override
            public void onFrame(short[] pcm, int samples) {
                if (vox) voxFrame(pcm, samples);
                else if (micToAir) sendVoice(pcm, samples);
                // Mic open only for the gunshot listener*: never while talking, or while someone is
                // heard (the loudspeaker could clip the mic).
                org.communitycomms.app.audio.GunshotDetector g = gunshot;
                if (g != null && !micToAir && !(vox && voxSending)
                        && System.currentTimeMillis() - lastRxVoiceMs > 600) {
                    g.frame(pcm, samples);
                }
            }
        });
        timer = new Timer("presence", true);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                if (now - lastPresenceMs >= PowerMode.presenceMs() - 500) {
                    lastPresenceMs = now;
                    sendPresence();
                }
                pruneStations();
            }
        }, 200, 5000);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                ackStalled();
                VoiceRecorder r = recorder;
                if (r != null) r.tick(); // away / stealth recordings of the channel on air
            }
        }, 500, 500);
    }

    private volatile FileTransfer files;

    /** Starts file sharing on this channel; files land in receiveDir. */
    public FileTransfer startFiles(java.io.File receiveDir, java.io.File partDir, FileTransfer.Listener l) {
        files = new FileTransfer(new FileTransfer.Wire() {
            @Override
            public void send(byte[] body) {
                byte[] p = namePayload(body.length);
                System.arraycopy(body, 0, p, p.length - body.length, body.length);
                RadioEngine.this.send(Packet.TYPE_FILE, p, p.length);
            }
        }, l, receiveDir, partDir);
        return files;
    }

    public FileTransfer files() {
        return files;
    }

    public void stop() {
        stop(false);
    }

    /** silent = close the socket first, so not even an end-of-transmission packet goes out. */
    public void stop(boolean silent) {
        if (silent && link != null) {
            UdpLink l = link;
            link = null;
            l.close();
        }
        Map<Integer, RadioEngine> mons = monitors;
        monitors = new HashMap<Integer, RadioEngine>();
        for (RadioEngine mon : mons.values()) mon.stop(silent);
        VoiceRecorder r = recorder;
        recorder = null;
        if (r != null) r.closeAll();
        if (files != null) files.close();
        gunshot = null;
        setVox(false);
        setTransmit(false);
        if (capture != null) capture.stop();
        if (timer != null) timer.cancel();
        if (link != null) link.close();
        if (mixer != null) mixer.close();
    }

    public String channelName() {
        return channelName;
    }

    /** On a signed channel with only the listen key: this phone can hear but never send. */
    public boolean listenOnly() {
        return listenKey != null && !listenKey.canSend();
    }

    public boolean signedChannel() {
        return listenKey != null;
    }

    /** What a signature covers: type sender seq tag, then the body. */
    private static byte[] signedPart(int type, long sender, int seq, int tag, byte[] body, int len) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(17 + len);
        b.put((byte) type).putLong(sender).putInt(seq).putInt(tag).put(body, 0, len);
        return b.array();
    }

    /** A sealed packet for this channel: signed when the channel is signed. Null when it must not be sent. */
    private byte[] sealSigned(ChannelCrypto c, org.communitycomms.app.crypto.ListenKey lk, int type, byte[] payload, int len)
            throws GeneralSecurityException {
        int s = nextSeq();
        if (lk == null) return Packet.seal(c, type, myId, s, payload, len);
        if (!lk.canSend()) return null;
        byte[] sig = lk.sign(signedPart(type, myId, s, c.channelTag, payload, len));
        byte[] p = new byte[len + sig.length];
        System.arraycopy(payload, 0, p, 0, len);
        System.arraycopy(sig, 0, p, len, sig.length);
        return Packet.seal(c, type, myId, s, p, p.length);
    }

    public int channelTag() {
        return crypto.channelTag;
    }

    public List<InetSocketAddress> destinations() {
        return link == null ? new ArrayList<InetSocketAddress>() : link.destinations();
    }

    public void setCallsign(String c) {
        callsign = c;
    }

    public boolean isTransmitting() {
        return transmitting;
    }

    /** Someone's live voice was heard in the last `ms`, or this phone is on air (PTT, open mic, VOX). */
    public boolean channelBusy(long ms) {
        return transmitting || micToAir || System.currentTimeMillis() - lastRxVoiceMs < ms;
    }

    // ---- VOX: voice-operated transmit ----
    //
    // The mic stays open. Every frame goes through a 3 s delay line; a frame leaving the line is
    // sent if anything loud happened in the 3 s after it (pre-roll, it is still in the line) or
    // within 5 s before it (hang time). Listeners therefore hear you about 3 s late, but they get
    // the start of every sentence.

    private static final int PRE_FRAMES = 3000 / 40, HANG_FRAMES = 5000 / 40;
    private volatile boolean vox;
    private volatile double voxThresholdDb = -35;
    private volatile double lastLevelDb = -90;
    private final ArrayDeque<short[]> voxLine = new ArrayDeque<short[]>();
    private final ArrayDeque<Boolean> voxLoud = new ArrayDeque<Boolean>();
    private int loudInLine, framesSinceLoudSent = Integer.MAX_VALUE / 2;
    private boolean voxSending;

    public boolean voxOn() {
        return vox;
    }

    /** Live mic level in dBFS (for the meter); about -90 when silent or mic closed. */
    public double levelDb() {
        return lastLevelDb;
    }

    public void setVoxThresholdDb(double db) {
        voxThresholdDb = db;
    }

    public synchronized void setVox(boolean on) {
        if (capture == null || on == vox) return;
        if (on) {
            setTransmit(false);
            synchronized (voxLine) {
                voxLine.clear();
                voxLoud.clear();
                loudInLine = 0;
                framesSinceLoudSent = Integer.MAX_VALUE / 2;
                voxSending = false;
            }
            vox = true;
            capture.start();
        } else {
            vox = false;
            if (gunshot == null) capture.stop();
            if (voxSending) voxStop();
            lastLevelDb = -90;
        }
    }

    // ---- gunshot listener* (untested): keeps the mic open while on air, nothing is sent ----

    private volatile org.communitycomms.app.audio.GunshotDetector gunshot;
    private volatile long lastRxVoiceMs;
    /** Mic frames go on air (PTT pressed); false while the mic is only open for listening. */
    private volatile boolean micToAir;

    public synchronized void setGunshotDetector(org.communitycomms.app.audio.GunshotDetector g) {
        gunshot = g;
        if (capture == null) return;
        if (g != null) capture.start();
        else if (!vox && !micToAir) capture.stop();
    }

    private void voxFrame(short[] pcm, int samples) {
        double sum = 0;
        for (int i = 0; i < samples; i++) sum += (double) pcm[i] * pcm[i];
        double db = 20 * Math.log10(Math.sqrt(sum / samples) / 32768.0 + 1e-9);
        lastLevelDb = db;
        boolean loud = db >= voxThresholdDb;
        short[] out = null;
        boolean outLoud = false;
        synchronized (voxLine) {
            voxLine.addLast(pcm.clone());
            voxLoud.addLast(loud);
            if (loud) loudInLine++;
            if (voxLine.size() > PRE_FRAMES) {
                out = voxLine.removeFirst();
                outLoud = voxLoud.removeFirst();
                if (outLoud) loudInLine--;
            }
        }
        if (out == null) return;
        if (outLoud) framesSinceLoudSent = 0;
        else framesSinceLoudSent++;
        boolean send = !radioSilent && (loudInLine > 0 || outLoud || framesSinceLoudSent <= HANG_FRAMES);
        if (send && !voxSending) voxStart();
        if (send) sendVoice(out, samples);
        else if (voxSending) voxStop();
    }

    private void voxStart() {
        Log.i(TAG, "VOX start");
        voxSending = true;
        transmitting = true;
        txId = random.nextInt();
        synchronized (recording) {
            recording.clear();
        }
        listener.onTransmitChanged(true);
    }

    private void voxStop() {
        Log.i(TAG, "VOX stop");
        voxSending = false;
        boolean sent;
        synchronized (recording) {
            sent = !recording.isEmpty();
            if (sent) lastRecording = new ArrayList<byte[]>(recording);
        }
        endTransmission(sent);
        if (sent) saveSent(lastRecording);
    }

    /** Enigma page > CW mode: the radio is plugged in for CW, so no voice goes out (PTT and VOX off). */
    public static volatile boolean cwMode;

    public void setTransmit(boolean on) {
        if (capture == null || vox) return; // VOX decides when to transmit
        if (on && (radioSilent || cwMode)) return;
        if (!on && replaying) {
            replaying = false; // PTT during a resend stops it; the resend thread ends it
            return;
        }
        if (on == transmitting) return;
        if (on) {
            transmitting = true;
            txId = random.nextInt();
            synchronized (recording) {
                recording.clear();
            }
            toneLeft = startTone ? TONE_FRAMES : 0;
            tonePhase = 0;
            micToAir = true;
            PowerMode.pttUsed();
            capture.start();
            listener.onTransmitChanged(true);
        } else {
            micToAir = false;
            if (gunshot == null) capture.stop();
            boolean sent;
            synchronized (recording) {
                sent = !recording.isEmpty();
                if (sent) lastRecording = new ArrayList<byte[]>(recording);
            }
            endTransmission(sent);
            if (sent) saveSent(lastRecording);
        }
    }

    public List<Station> stations() {
        synchronized (stations) {
            return new ArrayList<Station>(stations.values());
        }
    }

    // ---- transmit ----

    private int nextSeq() {
        synchronized (seqLock) {
            return seq++;
        }
    }

    /** Payload prefix shared by all packet types: [nameLen][name utf8], with room for extra bytes. */
    private byte[] namePayload(int extra) {
        byte[] n = callsign.getBytes(UTF8);
        int nl = Math.min(n.length, 32);
        byte[] p = new byte[1 + nl + extra];
        p[0] = (byte) nl;
        System.arraycopy(n, 0, p, 1, nl);
        return p;
    }

    // ---- broadcast: the same transmission also goes out on listened-to channels ----

    private volatile List<RadioEngine> mirrors = new ArrayList<RadioEngine>();

    /** Listen-only engines whose channels also get everything said (empty = this channel only). */
    public void setBroadcast(List<RadioEngine> targets) {
        mirrors = targets == null ? new ArrayList<RadioEngine>() : new ArrayList<RadioEngine>(targets);
    }

    public List<RadioEngine> broadcastTargets() {
        return new ArrayList<RadioEngine>(mirrors);
    }

    /** On a broadcast target: one voice frame, already encoded, under this channel's keys. */
    void mirrorVoice(int tx, byte[] frame, int off, int len) {
        byte[] p = namePayload(4 + len);
        putTx(p, p.length - 4 - len, tx);
        System.arraycopy(frame, off, p, p.length - len, len);
        send(Packet.TYPE_VOICE, p, p.length);
    }

    /** On a broadcast target: END for the transmission, and start collecting its acks. */
    void mirrorEnd(int tx) {
        synchronized (acks) {
            acks.put(tx, new ArrayList<String>());
        }
        byte[] p = namePayload(4);
        putTx(p, p.length - 4, tx);
        for (int i = 0; i < 3; i++) send(Packet.TYPE_VOICE_END, p, p.length);
    }

    List<String> takeAcks(int tx) {
        synchronized (acks) {
            List<String> l = acks.remove(tx);
            return l == null ? new ArrayList<String>() : l;
        }
    }

    /** Intercom: callsign the voice is for (only that phone plays it), or null for everyone. */
    public volatile String voiceTarget;

    /** Start-of-talk tone (Settings): the first frames of each transmission carry a short beep. */
    public static volatile boolean startTone;
    private static final int TONE_FRAMES = 4; // 160 ms: two rising notes
    private int toneLeft;
    private double tonePhase;

    /** Replaces the start of the microphone audio with the beep, so listeners know someone is talking. */
    private void toneInto(short[] pcm, int samples) {
        int f = TONE_FRAMES - toneLeft;
        double hz = f < TONE_FRAMES / 2 ? 880 : 1320;
        for (int i = 0; i < samples; i++) {
            tonePhase += 2 * Math.PI * hz / SAMPLE_RATE;
            double env = Math.min(1, Math.min(i, samples - i) / 40.0); // no clicks at the edges
            pcm[i] = (short) (Math.sin(tonePhase) * 9000 * env);
        }
        toneLeft--;
    }

    private void sendVoice(short[] pcm, int samples) {
        if (toneLeft > 0) toneInto(pcm, samples);
        int enc = ImaAdpcm.encodedSize(samples);
        byte[] p = namePayload(4 + enc);
        int off = p.length - enc;
        putTx(p, off - 4, txId);
        encoder.encode(pcm, samples, p, off);
        String to = voiceTarget;
        if (to != null) {
            // Intercom: talking to one room. Same frame, with the callsign it is for in front.
            byte[] t = to.getBytes(UTF8);
            int tl = Math.min(t.length, 32);
            byte[] q = namePayload(4 + 1 + tl + enc);
            int qo = q.length - 4 - 1 - tl - enc;
            putTx(q, qo, txId);
            q[qo + 4] = (byte) tl;
            System.arraycopy(t, 0, q, qo + 5, tl);
            System.arraycopy(p, off, q, q.length - enc, enc);
            send(Packet.TYPE_VOICE_TO, q, q.length);
        } else {
            send(Packet.TYPE_VOICE, p, p.length);
            for (RadioEngine m : mirrors) m.mirrorVoice(txId, p, off, enc);
        }
        synchronized (recording) {
            if (recording.size() < MAX_RECORDED_FRAMES) {
                byte[] f = new byte[enc];
                System.arraycopy(p, off, f, 0, enc);
                recording.add(f);
            }
        }
    }

    /**
     * Shares a position with the channel. Payload after the name: lat(8, double), lon(8),
     * accuracy m(4, float), age of the fix in ms(8), labelLen(1), optional text label (unused
     * for now, kept for a future grid reference such as MGRS).
     * The age (not a clock time) is sent so phones with wrong clocks still agree.
     */
    private final int videoStream = new SecureRandom().nextInt();
    private int videoFrame;

    /** Aircraft this phone fetched, to everyone on the channel (TYPE_ADSB, 40 a packet). */
    public void sendAdsb(java.util.List<org.communitycomms.app.geo.AdsbFeed.Aircraft> list) {
        java.util.List<org.communitycomms.app.geo.AdsbFeed.Aircraft> ok = new ArrayList<org.communitycomms.app.geo.AdsbFeed.Aircraft>();
        for (org.communitycomms.app.geo.AdsbFeed.Aircraft a : list) if (a.hex.matches("[0-9a-fA-F]{6}")) ok.add(a);
        for (int start = 0; start < ok.size(); start += 40) {
            int n = Math.min(40, ok.size() - start);
            byte[] p = namePayload(1 + n * 25);
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(p, p.length - 1 - n * 25, 1 + n * 25);
            b.put((byte) n);
            for (int i = 0; i < n; i++) {
                org.communitycomms.app.geo.AdsbFeed.Aircraft a = ok.get(start + i);
                int hex = Integer.parseInt(a.hex, 16);
                b.put((byte) (hex >> 16)).put((byte) (hex >> 8)).put((byte) hex);
                b.putInt((int) Math.round(a.lat * 1e5)).putInt((int) Math.round(a.lon * 1e5));
                b.putShort(a.ground ? Short.MIN_VALUE : a.altFt == Integer.MIN_VALUE ? Short.MAX_VALUE
                        : (short) Math.max(-32000, Math.min(32000, a.altFt / 25)));
                b.putShort((short) a.trackDeg).putShort((short) a.speedKt);
                byte[] f = (a.flight + "        ").substring(0, 8).getBytes(UTF8);
                b.put(f, 0, 8);
            }
            send(Packet.TYPE_ADSB, p, p.length);
        }
    }

    private static java.util.List<org.communitycomms.app.geo.AdsbFeed.Aircraft> adsbDecode(byte[] p, int off) {
        java.util.List<org.communitycomms.app.geo.AdsbFeed.Aircraft> out = new ArrayList<org.communitycomms.app.geo.AdsbFeed.Aircraft>();
        if (p.length <= off) return out;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(p, off, p.length - off);
        int n = b.get() & 0xff;
        for (int i = 0; i < n && b.remaining() >= 25; i++) {
            org.communitycomms.app.geo.AdsbFeed.Aircraft a = new org.communitycomms.app.geo.AdsbFeed.Aircraft();
            int hex = ((b.get() & 0xff) << 16) | ((b.get() & 0xff) << 8) | (b.get() & 0xff);
            a.hex = String.format(java.util.Locale.US, "%06x", hex);
            a.lat = b.getInt() / 1e5;
            a.lon = b.getInt() / 1e5;
            short alt = b.getShort();
            if (alt == Short.MIN_VALUE) a.ground = true;
            else if (alt != Short.MAX_VALUE) a.altFt = alt * 25;
            a.trackDeg = b.getShort();
            a.speedKt = b.getShort();
            byte[] f = new byte[8];
            b.get(f);
            a.flight = new String(f, UTF8).trim();
            out.add(a);
        }
        return out;
    }

    /** Ships this phone fetched, to everyone on the channel (TYPE_AIS, 35 a packet). */
    public void sendAis(java.util.List<org.communitycomms.app.geo.ShipFeed.Ship> list) {
        for (int start = 0; start < list.size(); start += 35) {
            int n = Math.min(35, list.size() - start);
            byte[] p = namePayload(1 + n * 29);
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(p, p.length - 1 - n * 29, 1 + n * 29);
            b.put((byte) n);
            for (int i = 0; i < n; i++) {
                org.communitycomms.app.geo.ShipFeed.Ship s = list.get(start + i);
                b.putInt(s.mmsi);
                b.putInt((int) Math.round(s.lat * 1e5)).putInt((int) Math.round(s.lon * 1e5));
                b.putShort(s.sog < 0 ? (short) -1 : (short) Math.min(32000, Math.round(s.sog * 10)));
                b.putShort(s.cog < 0 ? (short) -1 : (short) Math.min(3600, Math.round(s.cog * 10)));
                b.put((byte) s.type);
                String name = s.name.replaceAll("[^\\x20-\\x7e]", "");
                byte[] nm = (name + "            ").substring(0, 12).getBytes(UTF8);
                b.put(nm, 0, 12);
            }
            send(Packet.TYPE_AIS, p, p.length);
        }
    }

    private static java.util.List<org.communitycomms.app.geo.ShipFeed.Ship> aisDecode(byte[] p, int off) {
        java.util.List<org.communitycomms.app.geo.ShipFeed.Ship> out = new ArrayList<org.communitycomms.app.geo.ShipFeed.Ship>();
        if (p.length <= off) return out;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(p, off, p.length - off);
        int n = b.get() & 0xff;
        for (int i = 0; i < n && b.remaining() >= 29; i++) {
            org.communitycomms.app.geo.ShipFeed.Ship s = new org.communitycomms.app.geo.ShipFeed.Ship();
            s.mmsi = b.getInt();
            s.lat = b.getInt() / 1e5;
            s.lon = b.getInt() / 1e5;
            short sog = b.getShort(), cog = b.getShort();
            s.sog = sog < 0 ? -1 : sog / 10f;
            s.cog = cog < 0 ? -1 : cog / 10f;
            s.type = b.get() & 0xff;
            byte[] nm = new byte[12];
            b.get(nm);
            s.name = new String(nm, UTF8).trim();
            out.add(s);
        }
        return out;
    }

    /** One camera picture (a small JPEG) to everyone on the channel, in chunks (TYPE_VIDEO). */
    public void sendVideoFrame(byte[] jpeg) {
        final int chunk = 1100;
        int count = (jpeg.length + chunk - 1) / chunk;
        if (count > 255) return; // too big: the sender keeps pictures small
        int frame = videoFrame++;
        for (int i = 0; i < count; i++) {
            int len = Math.min(chunk, jpeg.length - i * chunk);
            byte[] p = namePayload(10 + len);
            int o = p.length - 10 - len;
            putTx(p, o, videoStream);
            putTx(p, o + 4, frame);
            p[o + 8] = (byte) i;
            p[o + 9] = (byte) count;
            System.arraycopy(jpeg, i * chunk, p, o + 10, len);
            send(Packet.TYPE_VIDEO, p, p.length);
        }
    }

    /**
     * Audio with my camera (Share my camera > Send audio): a TYPE_VIDEO packet with chunk count 0, so
     * older phones drop it. Body: streamId(4) seq(4) 0 0, then IMA ADPCM frames (FRAME_SAMPLES each).
     */
    public void sendVideoAudio(byte[] adpcm, int seq) {
        byte[] p = namePayload(10 + adpcm.length);
        int o = p.length - 10 - adpcm.length;
        putTx(p, o, videoStream);
        putTx(p, o + 4, seq);
        System.arraycopy(adpcm, 0, p, o + 10, adpcm.length);
        send(Packet.TYPE_VIDEO, p, p.length);
    }

    /** Settings > My map icon: the PoiIcons index others see me as (e.g. an ambulance), -1 = a dot. */
    public static volatile int myIcon = -1;

    public void sendLocation(double lat, double lon, float accuracy, long fixTime, String w3w) {
        // Settings > Obscure location: others get a spot near the real one (fake GPS is already made up).
        double[] ob = org.communitycomms.app.geo.FakeGps.obscure(lat, lon, accuracy);
        lat = ob[0];
        lon = ob[1];
        accuracy = (float) ob[2];
        byte[] words = (w3w == null ? "" : w3w).getBytes(UTF8);
        int wl = Math.min(words.length, 100);
        long age = Math.max(0, System.currentTimeMillis() - fixTime);
        // One byte appended (2026-10-25): the icon others see me as (255 = none). Older phones ignore it.
        // And a flags byte after it (2026-10-27): bit 0 = a fake GPS position.
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(29 + wl + 2);
        b.putDouble(lat).putDouble(lon).putFloat(accuracy).putLong(age).put((byte) wl).put(words, 0, wl);
        b.put((byte) (myIcon < 0 ? 255 : myIcon));
        b.put((byte) (org.communitycomms.app.geo.FakeGps.location() != null ? 1 : 0));
        byte[] body = b.array();
        byte[] p = namePayload(body.length);
        System.arraycopy(body, 0, p, p.length - body.length, body.length);
        // Sent twice: broadcast has no retries and a position is small.
        send(Packet.TYPE_LOCATION, p, p.length);
        send(Packet.TYPE_LOCATION, p, p.length);
    }

    private long lastCallFrom;
    private int lastCallId;

    /**
     * Rings everyone on the channel, or only the callsign in target (null = everyone). Payload after
     * the name: call id(4), then optionally targetLen(1) target(UTF-8). Older apps ignore the target
     * and ring anyway. Sent 3 times (broadcast has no retries); false if radio silent.
     */
    public boolean sendCall(String target) {
        if (radioSilent || link == null) return false;
        byte[] t = target == null ? new byte[0] : target.getBytes(UTF8);
        int tl = Math.min(t.length, 32);
        final byte[] p = namePayload(4 + (tl > 0 ? 1 + tl : 0));
        int o = p.length - 4 - (tl > 0 ? 1 + tl : 0);
        putTx(p, o, random.nextInt());
        if (tl > 0) {
            p[o + 4] = (byte) tl;
            System.arraycopy(t, 0, p, o + 5, tl);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 3; i++) {
                    send(Packet.TYPE_CALL, p, p.length);
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "call").start();
        return true;
    }

    public static final int STATUS_AVAILABLE = 0, STATUS_AWAY = 1, STATUS_STEALTH = 2;
    /** This phone's status and battery percent (-1 unknown), sent with every presence. */
    public static volatile int myStatus = STATUS_AVAILABLE, myBattery = -1;

    /**
     * Presence payload after the name: status(1) battery(1, 0-100, 255 = unknown). Older apps send
     * only the name and ignore the extra bytes, so both directions still work.
     */
    private long lastPresenceMs;

    private void sendPresence() {
        byte[] p = namePayload(3);
        p[p.length - 3] = (byte) myStatus;
        p[p.length - 2] = (byte) (myBattery < 0 || myBattery > 100 ? 255 : myBattery);
        p[p.length - 1] = (byte) PowerMode.checkInByte();
        send(Packet.TYPE_PRESENCE, p, p.length);
    }

    /** Sends presence straight away (e.g. the status just changed) instead of at the next 5 s tick. */
    public void sendPresenceNow() {
        if (monitorMode) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                sendPresence();
            }
        }, "presence").start();
    }

    /**
     * Radio silent (receive only): while set, nothing at all leaves this phone, not voice, acks,
     * presence, positions, messages or file repairs. Everything received still plays and is kept.
     */
    public static volatile boolean radioSilent;

    private void send(int type, byte[] payload, int len) {
        UdpLink l = link;
        // Dual watch: the listen-only engine still confirms what it heard, through the socket of the
        // engine on air, so a talker on the other channel is not told nobody heard them. Acks only.
        RadioEngine via = sendVia;
        // Besides acks, a listen-only channel carries only what the user sends to it on purpose:
        // a team message, a call to someone on it, or a broadcast (never presence or sync).
        if (l == null && monitorMode && via != null && (type == Packet.TYPE_ACK || type == Packet.TYPE_CHAT
                || type == Packet.TYPE_VOICE || type == Packet.TYPE_VOICE_END || type == Packet.TYPE_CALL)) {
            l = via.link;
        }
        if (l == null || radioSilent) return;
        if (len < 0) len = payload.length;
        try {
            byte[] sealed = sealSigned(crypto, listenKey, type, payload, len);
            if (sealed != null) l.send(sealed); // null: listen-only key, nothing is ever sent
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "seal failed", e);
        }
    }

    // ---- receive ----

    // ---- dual watch: receive-only engines for other channels (B, Public, C–H…) ----

    /** Channel tag → listen-only engine. Copy-on-write: read on the receive thread without locking. */
    private volatile Map<Integer, RadioEngine> monitors = new HashMap<Integer, RadioEngine>();
    private volatile boolean monitorMode;
    private volatile VoiceRecorder recorder;

    /** Packets for that channel's tag go to this listen-only engine (dual watch). */
    public synchronized void addMonitor(RadioEngine m) {
        m.sendVia = this;
        Map<Integer, RadioEngine> next = new HashMap<Integer, RadioEngine>(monitors);
        next.put(m.channelTag(), m);
        monitors = next;
    }

    /** For a listen-only engine: the engine on air whose socket carries its acks. */
    private volatile RadioEngine sendVia;

    public List<RadioEngine> monitors() {
        return new ArrayList<RadioEngine>(monitors.values());
    }

    /**
     * Starts this engine as a listener only: it has no socket of its own (it is fed by the engine on
     * air) and no microphone. The only thing it ever sends is an ack for a transmission it heard,
     * through the engine on air (see setMonitor); never voice, presence or messages.
     */
    public void startMonitor(boolean playAudio, VoiceRecorder rec) {
        monitorMode = true;
        mixer = playAudio ? new Mixer(SAMPLE_RATE, FRAME_SAMPLES, pan) : null;
        // Quiet mode: a little quieter than the channel on air. Stereo: full volume in its own ear.
        if (mixer != null && pan == 0) mixer.setGain(0.7f);
        recorder = rec;
        timer = new Timer("monitor", true);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                pruneStations();
                ackStalled();
                VoiceRecorder r = recorder;
                if (r != null) r.tick();
            }
        }, 1000, 1000);
    }

    /** A talker whose END we never got: ack once their audio has stopped for 1 s. */
    private void ackStalled() {
        long now = System.currentTimeMillis();
        synchronized (stations) {
            for (Station s : stations.values()) {
                if (s.rxHasTx && !s.rxAcked && now - s.lastVoiceMs > 1000) sendAck(s);
            }
        }
    }

    public boolean isMonitor() {
        return monitorMode;
    }

    /** Away / stealth: also save what is heard on this channel (null = stop saving). */
    public void setRecorder(VoiceRecorder r) {
        VoiceRecorder old = recorder;
        recorder = r;
        if (old != null && old != r) old.closeAll();
    }

    public VoiceRecorder recorder() {
        return recorder;
    }

    /**
     * The same id on every phone for one transmission (talker's sender id and txId), so recordings of
     * it made by different phones match in the voice catch-up (MessageCenter KIND_VOICE).
     */
    public static long voiceId(long sender, int tx) {
        return sender * 0x9E3779B97F4A7C15L + tx;
    }

    /** Record all voice: gets each finished transmission of this phone's own (encoded frames). */
    public interface SentSaver {
        void onSent(List<byte[]> frames, long voiceId);
    }

    private volatile SentSaver sentSaver;

    public void setSentSaver(SentSaver s) {
        sentSaver = s;
    }

    private void saveSent(final List<byte[]> frames) {
        final SentSaver s = sentSaver;
        final long vid = voiceId(myId, txId);
        if (s == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                s.onSent(frames, vid);
            }
        }, "save-sent").start();
    }

    /** Fed by the engine on air with packets that are not for its channel. */
    void handleForeign(byte[] buf, int len, InetAddress from) {
        handle(buf, len, from);
    }

    private void handle(byte[] buf, int len, InetAddress from) {
        Map<Integer, RadioEngine> mons = monitors;
        if (!mons.isEmpty() && len >= 8 && buf[0] == 'U' && buf[1] == 'B') {
            int tag = ((buf[4] & 0xff) << 24) | ((buf[5] & 0xff) << 16) | ((buf[6] & 0xff) << 8) | (buf[7] & 0xff);
            if (tag != crypto.channelTag) {
                RadioEngine mon = mons.get(tag);
                if (mon != null) mon.handleForeign(buf, len, from);
                return;
            }
        }
        Packet p = Packet.open(crypto, buf, len);
        if (p == null || p.sender == myId || p.payload.length < 1) return;
        if (listenKey != null) {
            // Signed channel: only packets signed by a full key holder count (listen-key phones cannot sign).
            int n = p.payload.length - org.communitycomms.app.crypto.ListenKey.SIG_LEN;
            if (n < 1 || !listenKey.verify(signedPart(p.type, p.sender, p.seq, crypto.channelTag, p.payload, n), p.payload, n)) return;
            p.payload = java.util.Arrays.copyOf(p.payload, n);
        }
        int nl = p.payload[0] & 0xff;
        if (1 + nl > p.payload.length) return;
        String name = new String(p.payload, 1, nl, UTF8);
        if (p.type == Packet.TYPE_VOICE_TO) {
            // Voice for one callsign (intercom): others drop it; for us it becomes ordinary voice.
            int o = 1 + nl + 4;
            if (p.payload.length < o + 1) return;
            int tl = p.payload[o] & 0xff;
            if (p.payload.length < o + 1 + tl) return;
            String to = new String(p.payload, o + 1, tl, UTF8);
            if (!to.equalsIgnoreCase(callsign)) return;
            byte[] v = new byte[p.payload.length - 1 - tl];
            System.arraycopy(p.payload, 0, v, 0, o);
            System.arraycopy(p.payload, o + 1 + tl, v, o, p.payload.length - o - 1 - tl);
            p.payload = v;
            p.type = Packet.TYPE_VOICE;
        }
        long now = System.currentTimeMillis();
        boolean changed;
        synchronized (stations) {
            Station s = stations.get(p.sender);
            changed = s == null || !s.name.equals(name);
            if (s == null) {
                s = new Station(p.sender);
                // A restarted app gets a new sender id: drop the old entry for the same callsign so
                // the person is not listed twice. Keep its last position until a new one arrives.
                java.util.Iterator<Station> it = stations.values().iterator();
                while (it.hasNext()) {
                    Station old = it.next();
                    if (!old.name.equals(name)) continue;
                    if (Double.isNaN(s.lat) && !Double.isNaN(old.lat)) {
                        s.lat = old.lat;
                        s.lon = old.lon;
                        s.accuracy = old.accuracy;
                        s.fixTimeMs = old.fixTimeMs;
                        s.w3w = old.w3w;
                        s.fake = old.fake;
                    }
                    it.remove();
                }
                stations.put(p.sender, s);
            }
            s.name = name;
            s.lastSeenMs = now;
            s.address = from.getHostAddress();
            int off = 1 + nl;
            if (p.type == Packet.TYPE_PRESENCE && p.payload.length - off >= 2) {
                int st = p.payload[off] & 0xff, bat = p.payload[off + 1] & 0xff;
                if (st != s.status) changed = true;
                s.status = st;
                s.battery = bat > 100 ? -1 : bat;
                s.checkInMs = p.payload.length - off >= 3 ? (p.payload[off + 2] & 0xff) * 10000L : 0;
            } else if (p.type == Packet.TYPE_VOICE && p.payload.length - off >= 7) {
                changed |= !s.isTalking(now);
                s.lastVoiceMs = now;
                lastRxVoiceMs = now;
                int tx = getTx(p.payload, off);
                if (!s.rxHasTx || tx != s.rxTx) {
                    s.rxHasTx = true;
                    s.rxTx = tx;
                    s.rxAcked = false;
                    s.rxFrames = 0;
                }
                s.rxFrames++;
            } else if (p.type == Packet.TYPE_VOICE_END) {
                s.lastVoiceMs = 0;
                changed = true;
                if (p.payload.length - off >= 4 && s.rxHasTx && getTx(p.payload, off) == s.rxTx) sendAck(s);
            } else if (p.type == Packet.TYPE_LOCATION && p.payload.length - off >= 29) {
                java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(p.payload, off, p.payload.length - off);
                double la = b.getDouble(), lo = b.getDouble();
                float acc = b.getFloat();
                long t = now - Math.max(0, b.getLong());
                int wl = Math.min(b.get() & 0xff, b.remaining());
                if (la >= -90 && la <= 90 && lo >= -180 && lo <= 180 && t >= s.fixTimeMs) {
                    s.lat = la;
                    s.lon = lo;
                    s.accuracy = acc;
                    s.fixTimeMs = t;
                    s.w3w = new String(p.payload, off + 29, wl, UTF8);
                    int io = off + 29 + wl;
                    if (p.payload.length > io) {
                        int ic = p.payload[io] & 0xff;
                        s.icon = ic >= org.communitycomms.app.geo.Poi.ICON_NAMES.length ? -1 : ic;
                    }
                    s.fake = p.payload.length > io + 1 && (p.payload[io + 1] & 1) != 0;
                    changed = true;
                }
            } else if (p.type == Packet.TYPE_ACK && p.payload.length - off >= 14) {
                int tx = getTx(p.payload, off);
                long target = 0;
                for (int i = 0; i < 8; i++) target = (target << 8) | (p.payload[off + 4 + i] & 0xff);
                if (target == myId) {
                    synchronized (acks) {
                        List<String> l = acks.get(tx);
                        if (l != null && !l.contains(name)) l.add(name);
                    }
                }
            }
        }
        if (p.type == Packet.TYPE_VOICE) {
            int off = 1 + nl + 4;
            short[] pcm = new short[FRAME_SAMPLES];
            if (p.payload.length - off >= 3) {
                ImaAdpcm.decode(p.payload, off, p.payload.length - off, pcm);
                Mixer mx = mixer;
                if (mx != null) mx.push(p.sender, p.seq, pcm);
                VoiceRecorder r = recorder;
                if (r != null) r.frame(p.sender, name, p.seq, pcm, voiceId(p.sender, getTx(p.payload, 1 + nl)));
            }
        } else if (p.type == Packet.TYPE_VOICE_END) {
            Mixer mx = mixer;
            if (mx != null) mx.end(p.sender);
            VoiceRecorder r = recorder;
            if (r != null) r.end(p.sender);
        } else if (p.type == Packet.TYPE_VIDEO) {
            VideoHub.onChunk(name, p.sender, p.payload, 1 + nl);
        } else if (p.type == Packet.TYPE_ADSB) {
            org.communitycomms.app.geo.AdsbFeed.onShared(adsbDecode(p.payload, 1 + nl));
        } else if (p.type == Packet.TYPE_AIS) {
            org.communitycomms.app.geo.ShipFeed.onShared(aisDecode(p.payload, 1 + nl));
        } else if (p.type == Packet.TYPE_DRYAD) {
            handleDryad(p.sender, name, p.payload, 1 + nl);
        } else if (p.type == Packet.TYPE_FILE) {
            FileTransfer ft = files;
            if (ft != null) ft.onMessage(p.sender, name, p.payload, 1 + nl);
        } else if (p.type == Packet.TYPE_MARK && p.payload.length > 1 + nl) {
            listener.onPoiMessage(name, p.payload, 1 + nl);
        } else if (p.type == Packet.TYPE_CHAT && p.payload.length > 1 + nl) {
            listener.onChatMessage(name, p.payload, 1 + nl);
        } else if (p.type == Packet.TYPE_CALL && p.payload.length - (1 + nl) >= 4) {
            int id = getTx(p.payload, 1 + nl);
            int to = 1 + nl + 4;
            if (p.payload.length > to) { // a call for one callsign: only that phone rings
                int tl = p.payload[to] & 0xff;
                if (p.payload.length >= to + 1 + tl
                        && !new String(p.payload, to + 1, tl, UTF8).equalsIgnoreCase(callsign.trim())) {
                    return;
                }
            }
            boolean fresh;
            synchronized (this) { // the 3 copies of one call ring once
                fresh = p.sender != lastCallFrom || id != lastCallId;
                lastCallFrom = p.sender;
                lastCallId = id;
            }
            if (fresh) listener.onCall(name);
        }
        if (changed) listener.onRosterChanged();
    }

    private void pruneStations() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        synchronized (stations) {
            java.util.Iterator<Station> it = stations.values().iterator();
            while (it.hasNext()) {
                Station st = it.next();
                // Keep anyone who advertised a position for a day, so "plot last advertised" works.
                boolean hasFix = !Double.isNaN(st.lat) && now - st.lastSeenMs < 24 * 3600000L;
                if (now - st.lastSeenMs > Math.max(60000, st.checkInMs * 2) && !hasFix) {
                    it.remove();
                    changed = true;
                }
            }
        }
        synchronized (transfers) {
            // Give up on transfers that stalled (sender left, too much loss).
            java.util.Iterator<Transfer> it = transfers.values().iterator();
            while (it.hasNext()) {
                if (now - it.next().lastMs > 120000) it.remove();
            }
        }
        if (changed) listener.onRosterChanged();
    }
}
