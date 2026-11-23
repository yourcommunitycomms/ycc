package org.communitycomms.app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import org.communitycomms.app.audio.Mixer;

import org.concentus.OpusApplication;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;
import org.concentus.OpusSignal;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Voice compatible with OpenMANET "Comms" and (per OpenMANET's release notes) the TAK Vx plugin.
 * Opus 48 kHz mono, 20 ms frames, 32 kbit/s, in-band FEC, inside RTP (payload type 111) on a talk
 * group's multicast address:port. Raw Opus datagrams (no RTP header) are also accepted on receive.
 * Tested both ways with OpenMANET 1.8.1 (openmanetd 1.4.0) web Comms.
 *
 * openmanetd sends an RTCP report on port+1 every few seconds from every node with Comms on, so those
 * nodes show as present on the talk group even when nobody talks.
 *
 * Differences from normal channels, by necessity: NOT encrypted, half duplex
 * (you cannot transmit while someone else is talking), no acknowledgements.
 */
public final class CompatVoice {
    /** openmanetd's talk group address; channel n is port 38799 + 2n (channels 1-32, ports 38801-38863). */
    public static final String ADDRESS = "239.192.41.1";
    public static final int CHANNELS = 32;
    /** TAK Vx's own default test channel ("01-test", Opus over RTP), as shipped. */
    public static final String ATAK_VX_DEFAULT = "239.255.1.1:5000";

    public static int portFor(int channel) {
        return 38799 + 2 * channel;
    }

    /** "239.192.41.1:38803" -> "channel 2 (239.192.41.1:38803)"; other groups are shown as they are. */
    public static String channelLabel(String groupAndPort) {
        String g = groupAndPort == null ? "" : groupAndPort.trim();
        if (ATAK_VX_DEFAULT.equals(g)) return "TAK Vx default channel (" + g + ")";
        int colon = g.lastIndexOf(':');
        if (colon > 0 && ADDRESS.equals(g.substring(0, colon).trim())) {
            try {
                int p = Integer.parseInt(g.substring(colon + 1).trim());
                if (p >= 38801 && p <= 38863 && (p - 38799) % 2 == 0) return "channel " + (p - 38799) / 2 + " (" + g + ")";
            } catch (NumberFormatException ignored) {
            }
        }
        return g;
    }

    /** Names a node from its address (set by the app: Mesh controller's node list); null = just the address. */
    public interface Namer {
        String name(String address);
    }

    public static volatile Namer namer;

    static String nodeName(String address) {
        Namer n = namer;
        String s = n == null || address == null ? null : n.name(address);
        return s != null && s.length() > 0 ? s : address;
    }

    public interface Listener {
        void onTalkersChanged();

        void onTransmitChanged(boolean on);
    }

    /** A node heard on the talk group's report port (openmanetd with Comms on), talking or not. */
    public static final class Present {
        public final String address;
        public long lastMs;
        /** The callsign a YCC phone puts in its report; null for OpenMANET nodes and other apps. */
        public volatile String name;

        Present(String address) {
            this.address = address;
        }

        public String label() {
            return name != null ? name : "OpenMANET " + nodeName(address);
        }
    }

    private static volatile CompatVoice current;

    /** A YCC phone's callsign at this address, from its talk group report, or null. */
    public static String yccNameAt(String address) {
        CompatVoice c = current;
        if (c == null || address == null) return null;
        synchronized (c.present) {
            Present p = c.present.get(address);
            return p == null ? null : p.name;
        }
    }

    public static final class Talker {
        public final long ssrc;
        public long lastMs;
        public String address;
        final OpusDecoder decoder;
        int lastSeq = -1;
        /** RTP sequence numbers unwrapped past 65535, so they keep counting up for the mixer. */
        int extSeq;
        boolean hasSeq;
        /** "Late" packets in a row: many means the sender's numbering restarted, not reordering. */
        int lateRun;
        // Recording at 8 kHz (Record all voice)
        final short[] rec8 = new short[RadioEngine.FRAME_SAMPLES];
        int recFill, recSeq;
        long recLastMs, voiceId;

        Talker(long ssrc) throws org.concentus.OpusException {
            this.ssrc = ssrc;
            decoder = new OpusDecoder(RATE, 1);
        }

        /** Named after the node it came from (the same name as its Present entry), or its id when unknown. */
        public String label() {
            String vx = VxData.callsignAt(address); // an TAK Vx user: their callsign from the Vx data channel
            if (vx != null) return vx;
            String ycc = yccNameAt(address); // another YCC phone: the callsign in its talk group report
            if (ycc != null) return ycc;
            return address != null ? "OpenMANET " + nodeName(address) : "Talker " + Long.toHexString(ssrc & 0xffff);
        }

        public boolean talking(long now) {
            return now - lastMs < 400;
        }
    }

    private static final String TAG = "CompatVoice";
    static final int RATE = 48000;
    static final int FRAME = 960; // 20 ms
    private static final int PT_OPUS = 111;
    private static final long BUSY_MS = 500;

    private final InetAddress group;
    private final int port;
    private final Listener listener;
    private final MulticastSocket socket;
    private final Mixer mixer;
    private final Map<Long, Talker> talkers = new HashMap<Long, Talker>();
    private final Map<String, Present> present = new HashMap<String, Present>();
    private MulticastSocket rtcp;
    private final long mySsrc;
    private volatile boolean running = true, transmitting;
    private Thread rx, tx;
    private int seq;
    private long timestamp;

    public CompatVoice(String groupAndPort, NetworkInterface iface, Listener listener) throws IOException {
        int colon = groupAndPort.lastIndexOf(':');
        if (colon < 0) throw new IOException("Talk group must be address:port, e.g. 239.10.10.10:38801");
        group = InetAddress.getByName(groupAndPort.substring(0, colon).trim());
        port = Integer.parseInt(groupAndPort.substring(colon + 1).trim());
        if (!group.isMulticastAddress()) throw new IOException(group.getHostAddress() + " is not a multicast address");
        this.listener = listener;
        SecureRandom r = new SecureRandom();
        mySsrc = r.nextInt() & 0xffffffffL;
        seq = r.nextInt() & 0xffff;
        timestamp = r.nextInt() & 0xffffffffL;

        socket = new MulticastSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port));
        socket.setTimeToLive(32); // OpenMANET uses 32 for multi-hop meshes
        socket.setLoopbackMode(true);
        if (iface != null) {
            socket.setNetworkInterface(iface);
            socket.joinGroup(new InetSocketAddress(group, port), iface);
        } else {
            socket.joinGroup(group);
        }
        // A MANET hop is slow and lossy: start each talker after 200 ms of audio and let it grow to 700 ms on
        // underruns. Receive side only; what goes on the wire stays OpenMANET / TAK's format.
        mixer = new Mixer(RATE, FRAME, 0, 10, 35);
        rx = new Thread(new Runnable() {
            @Override
            public void run() {
                receiveLoop();
            }
        }, "compat-rx");
        rx.start();
        current = this;
        // Reports (RTCP) on port+1: who is on the talk group. Optional: voice works without it.
        try {
            final MulticastSocket rs = new MulticastSocket(null);
            rs.setReuseAddress(true);
            rs.bind(new InetSocketAddress(port + 1));
            rs.setTimeToLive(32);
            if (iface != null) {
                rs.setNetworkInterface(iface);
                rs.joinGroup(new InetSocketAddress(group, port + 1), iface);
            } else {
                rs.joinGroup(group);
            }
            rtcp = rs;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    reportLoop(rs);
                }
            }, "compat-rtcp");
            t.setDaemon(true);
            t.start();
            // Our own report every 5 s, as openmanetd does: other YCC phones see this one as present on the talk
            // group even while it is quiet (only an RTCP sender report: SSRC, time and counts, no name).
            Thread s = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (running) {
                        sendReport(rs);
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }, "compat-rtcp-tx");
            s.setDaemon(true);
            s.start();
        } catch (IOException e) {
            Log.w(TAG, "talk group reports not available: " + e.getMessage());
        }
    }

    private volatile long sentPackets, sentOctets;

    /** This phone's callsign for the talk group report (SDES CNAME), so other YCC phones show its name. */
    public static volatile String myName = "";

    private void sendReport(MulticastSocket rs) {
        if (RadioEngine.radioSilent) return;
        byte[] name;
        try {
            name = myName.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            name = new byte[0];
        }
        if (name.length > 60) name = java.util.Arrays.copyOf(name, 60);
        // SR (28 bytes), then SDES with one CNAME item: header 4 + SSRC 4 + type 1 + length 1 + name + end 1, padded to 4.
        int sdesBody = 4 + 2 + name.length + 1;
        int sdesLen = 4 + ((sdesBody + 3) / 4) * 4;
        byte[] b = new byte[28 + (name.length > 0 ? sdesLen : 0)];
        if (name.length > 0) {
            int o = 28;
            b[o] = (byte) 0x81; // V=2, one chunk
            b[o + 1] = (byte) 202; // SDES
            int words = sdesLen / 4 - 1;
            b[o + 2] = (byte) (words >>> 8);
            b[o + 3] = (byte) words;
            put32(b, o + 4, mySsrc);
            b[o + 8] = 1; // CNAME
            b[o + 9] = (byte) name.length;
            System.arraycopy(name, 0, b, o + 10, name.length);
            // the rest stays 0: the end-of-items marker and padding
        }
        b[0] = (byte) 0x80; // V=2, no reception report blocks
        b[1] = (byte) 200; // SR
        b[3] = 6; // length in 32-bit words minus one
        put32(b, 4, mySsrc);
        long ms = System.currentTimeMillis();
        long ntpSec = ms / 1000 + 2208988800L;
        long ntpFrac = ((ms % 1000) << 32) / 1000;
        put32(b, 8, ntpSec);
        put32(b, 12, ntpFrac);
        put32(b, 16, timestamp);
        put32(b, 20, sentPackets);
        put32(b, 24, sentOctets);
        try {
            rs.send(new DatagramPacket(b, b.length, group, port + 1));
        } catch (IOException ignored) {
        }
    }

    private static void put32(byte[] b, int o, long v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    /** The CNAME in a compound RTCP packet's SDES part (YCC phones put their callsign there), or null. */
    private static String cname(byte[] b, int len) {
        int off = 0;
        while (off + 4 <= len) {
            int pt = b[off + 1] & 0xff;
            int words = ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
            int end = Math.min(len, off + 4 + words * 4);
            if (pt == 202 && (b[off] & 0x1f) > 0 && off + 8 < end) {
                int i = off + 8;
                while (i + 2 <= end && b[i] != 0) {
                    int type = b[i] & 0xff, l = b[i + 1] & 0xff;
                    if (i + 2 + l > end) break;
                    if (type == 1 && l > 0) {
                        try {
                            String s = new String(b, i + 2, l, "UTF-8").trim();
                            return s.length() == 0 ? null : s;
                        } catch (java.io.UnsupportedEncodingException e) {
                            return null;
                        }
                    }
                    i += 2 + l;
                }
            }
            if (end <= off) break;
            off = end;
        }
        return null;
    }

    /** Nodes heard on the report port in the last 30 s. */
    public List<Present> present() {
        long now = System.currentTimeMillis();
        List<Present> out = new ArrayList<Present>();
        synchronized (present) {
            for (Present p : present.values()) if (now - p.lastMs < 30000) out.add(p);
        }
        return out;
    }

    private void reportLoop(MulticastSocket r) {
        byte[] buf = new byte[1500];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                dp.setLength(buf.length);
                r.receive(dp);
            } catch (IOException e) {
                return;
            }
            if (dp.getLength() < 8 || (buf[0] & 0xC0) != 0x80) continue;
            int pt = buf[1] & 0xff;
            if (pt < 200 || pt > 204) continue; // only RTCP reports
            long ssrc = ((buf[4] & 0xffL) << 24) | ((buf[5] & 0xffL) << 16) | ((buf[6] & 0xffL) << 8) | (buf[7] & 0xffL);
            if (ssrc == mySsrc) continue; // our own report, looped back
            String addr = dp.getAddress().getHostAddress();
            String cname = cname(buf, dp.getLength());
            boolean isNew;
            synchronized (present) {
                Present p = present.get(addr);
                isNew = p == null;
                if (isNew) {
                    p = new Present(addr);
                    present.put(addr, p);
                }
                p.lastMs = System.currentTimeMillis();
                if (cname != null) p.name = cname;
            }
            if (isNew) listener.onTalkersChanged();
        }
    }

    public String describe() {
        return group.getHostAddress() + ":" + port;
    }

    public List<Talker> talkers() {
        synchronized (talkers) {
            return new ArrayList<Talker>(talkers.values());
        }
    }

    /** Someone else is on the air; half duplex means we must wait. */
    public boolean busy() {
        long now = System.currentTimeMillis();
        synchronized (talkers) {
            for (Talker t : talkers.values()) if (now - t.lastMs < BUSY_MS) return true;
        }
        return false;
    }

    public boolean isTransmitting() {
        return transmitting;
    }

    /** Returns false if the channel is busy (half duplex). */
    public synchronized boolean setTransmit(boolean on) {
        if (on == transmitting) return true;
        if (on) {
            if (busy()) return false;
            transmitting = true;
            tx = new Thread(new Runnable() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                    transmitLoop();
                }
            }, "compat-tx");
            tx.start();
        } else {
            transmitting = false;
            Thread t = tx;
            tx = null;
            if (t != null) {
                try {
                    t.join(500);
                } catch (InterruptedException ignored) {
                }
            }
        }
        listener.onTransmitChanged(on);
        return true;
    }

    public void stop() {
        setTransmit(false);
        running = false;
        try {
            socket.leaveGroup(group);
        } catch (IOException ignored) {
        }
        socket.close();
        MulticastSocket r = rtcp;
        if (r != null) r.close();
        VoiceRecorder vr = recorder;
        recorder = null;
        if (vr != null) vr.closeAll();
        mixer.close();
    }

    // ---- transmit ----

    private AudioRecord openMic(int rate) {
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return null;
        for (int src : new int[]{MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC}) {
            try {
                AudioRecord r = new AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min, rate / 10 * 2));
                if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
                r.release();
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private void transmitLoop() {
        // 48 kHz is what Opus wants; phones that refuse it get 16 kHz upsampled 3x.
        int factor = 1;
        AudioRecord rec = openMic(48000);
        if (rec == null) {
            rec = openMic(16000);
            factor = 3;
        }
        if (rec == null) {
            Log.e(TAG, "no microphone");
            transmitting = false;
            listener.onTransmitChanged(false);
            return;
        }
        OpusEncoder enc;
        try {
            enc = new OpusEncoder(RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            enc.setBitrate(32000);
            enc.setUseInbandFEC(true);
            enc.setPacketLossPercent(20);
            enc.setUseDTX(false);
            enc.setComplexity(3); // easy on old phones
            enc.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
        } catch (org.concentus.OpusException e) {
            Log.e(TAG, "encoder", e);
            rec.release();
            return;
        }
        short[] raw = new short[FRAME / factor];
        short[] pcm = new short[FRAME];
        byte[] pkt = new byte[1400];
        try {
            rec.startRecording();
            while (transmitting && running) {
                int got = 0;
                while (got < raw.length && transmitting) {
                    int n = rec.read(raw, got, raw.length - got);
                    if (n <= 0) break;
                    got += n;
                }
                if (got < raw.length) continue;
                if (factor == 1) {
                    System.arraycopy(raw, 0, pcm, 0, FRAME);
                } else {
                    for (int i = 0; i < FRAME; i++) pcm[i] = raw[i / factor];
                }
                org.communitycomms.app.audio.AudioLevels.apply(pcm, FRAME, org.communitycomms.app.audio.AudioLevels.micGain);
                int len = enc.encode(pcm, 0, FRAME, pkt, 12, pkt.length - 12);
                writeRtpHeader(pkt);
                if (RadioEngine.radioSilent) break; // receive only
                socket.send(new DatagramPacket(pkt, 12 + len, group, port));
                sentPackets++;
                sentOctets += len;
                seq = (seq + 1) & 0xffff;
                timestamp = (timestamp + FRAME) & 0xffffffffL;
            }
        } catch (Exception e) {
            Log.e(TAG, "transmit failed", e);
        } finally {
            try {
                rec.stop();
            } catch (IllegalStateException ignored) {
            }
            rec.release();
        }
    }

    private void writeRtpHeader(byte[] b) {
        b[0] = (byte) 0x80; // V=2, no padding/extension/CSRC
        b[1] = (byte) PT_OPUS;
        b[2] = (byte) (seq >>> 8);
        b[3] = (byte) seq;
        b[4] = (byte) (timestamp >>> 24);
        b[5] = (byte) (timestamp >>> 16);
        b[6] = (byte) (timestamp >>> 8);
        b[7] = (byte) timestamp;
        b[8] = (byte) (mySsrc >>> 24);
        b[9] = (byte) (mySsrc >>> 16);
        b[10] = (byte) (mySsrc >>> 8);
        b[11] = (byte) mySsrc;
    }

    // ---- receive ----

    private void pushFrame(Talker t, int seq, short[] pcm, int off) {
        short[] f = new short[FRAME];
        System.arraycopy(pcm, off, f, 0, FRAME);
        mixer.push(t.ssrc, seq, f);
        record(t, f);
        FrameTap tap = this.tap;
        if (tap != null) tap.onFrame(t, f);
    }

    /** Record all voice on the talk group (null = off): each talker's speech saved like other channels' voice. */
    public volatile VoiceRecorder recorder;

    /** Channel link: every decoded 48 kHz frame heard on the talk group (null = none). */
    public interface FrameTap {
        void onFrame(Talker t, short[] pcm48k);
    }

    public volatile FrameTap tap;

    /** 48 kHz -> the recorder's 8 kHz, in its 40 ms frames. */
    private void record(Talker t, short[] f) {
        VoiceRecorder r = recorder;
        if (r == null) return;
        long now = System.currentTimeMillis();
        if (now - t.recLastMs > 1500) { // a new transmission
            t.voiceId = new java.util.Random().nextLong();
            t.recFill = 0;
        }
        t.recLastMs = now;
        for (int i = 0; i + 6 <= f.length; i += 6) {
            int s = 0;
            for (int k = 0; k < 6; k++) s += f[i + k];
            t.rec8[t.recFill++] = (short) (s / 6);
            if (t.recFill == t.rec8.length) {
                r.frame(t.ssrc, t.label(), t.recSeq++, t.rec8.clone(), t.voiceId);
                t.recFill = 0;
            }
        }
    }

    // ---- channel link: voice relayed from a YCC channel onto the talk group ----

    /** Relayed voice uses its own SSRC with this marker in the top 16 bits, so no linking phone relays it back. */
    public static final long RELAY_MARK = 0x5943L; // "YC"

    public static boolean relayed(long ssrc) {
        return (ssrc >>> 16) == RELAY_MARK;
    }

    private final long relaySsrc = (RELAY_MARK << 16) | (new SecureRandom().nextInt() & 0xffff);
    private OpusEncoder relayEnc;
    private int relaySeq = new SecureRandom().nextInt() & 0xffff;
    private long relayTs = new SecureRandom().nextInt() & 0xffffffffL;
    private final short[] relayBuf = new short[FRAME];
    private int relayFill;

    /**
     * Relays 48 kHz audio onto the talk group (any length; sent in 20 ms Opus frames). Dropped while this phone
     * talks or someone else is on the talk group (half duplex). Returns false when dropped.
     */
    public synchronized boolean relay(short[] pcm48) {
        if (transmitting || busyExceptRelay() || RadioEngine.radioSilent) return false;
        try {
            if (relayEnc == null) {
                relayEnc = new OpusEncoder(RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
                relayEnc.setBitrate(32000);
                relayEnc.setUseInbandFEC(true);
                relayEnc.setPacketLossPercent(20);
                relayEnc.setComplexity(3);
                relayEnc.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
            }
            byte[] pkt = new byte[1400];
            for (short s : pcm48) {
                relayBuf[relayFill++] = s;
                if (relayFill < FRAME) continue;
                relayFill = 0;
                int len = relayEnc.encode(relayBuf, 0, FRAME, pkt, 12, pkt.length - 12);
                pkt[0] = (byte) 0x80;
                pkt[1] = (byte) PT_OPUS;
                pkt[2] = (byte) (relaySeq >>> 8);
                pkt[3] = (byte) relaySeq;
                put32(pkt, 4, relayTs);
                put32(pkt, 8, relaySsrc);
                socket.send(new DatagramPacket(pkt, 12 + len, group, port));
                relaySeq = (relaySeq + 1) & 0xffff;
                relayTs = (relayTs + FRAME) & 0xffffffffL;
            }
            return true;
        } catch (Exception e) {
            Log.d(TAG, "relay: " + e.getMessage());
            return false;
        }
    }

    /** Someone other than a relay is talking on the talk group. */
    private boolean busyExceptRelay() {
        long now = System.currentTimeMillis();
        synchronized (talkers) {
            for (Talker t : talkers.values()) if (!relayed(t.ssrc) && now - t.lastMs < BUSY_MS) return true;
        }
        return false;
    }

    /** Another phone is relaying onto this talk group (its marked SSRC heard recently). */
    public boolean otherRelayHeard() {
        long now = System.currentTimeMillis();
        synchronized (talkers) {
            for (Talker t : talkers.values()) if (relayed(t.ssrc) && t.ssrc != relaySsrc && now - t.lastMs < 60000) return true;
        }
        return false;
    }

    /** Once a second or so: closes recordings whose talker went quiet. */
    public void tickRecorder() {
        VoiceRecorder r = recorder;
        if (r != null) r.tick();
    }

    private void receiveLoop() {
        byte[] buf = new byte[2048];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        short[] pcm = new short[FRAME * 6];
        while (running) {
            try {
                dp.setLength(buf.length);
                socket.receive(dp);
            } catch (IOException e) {
                if (running) Log.w(TAG, "receive", e);
                return;
            }
            if (transmitting) continue; // half duplex: our own mic is live
            int len = dp.getLength();
            int off = 0;
            long ssrc;
            int seqNo = -1;
            if (len >= 12 && (buf[0] & 0xC0) == 0x80) {
                int cc = buf[0] & 0x0f;
                boolean ext = (buf[0] & 0x10) != 0;
                int pt = buf[1] & 0x7f;
                if (pt >= 72 && pt <= 76) continue; // RTCP on the same port: ignore
                seqNo = ((buf[2] & 0xff) << 8) | (buf[3] & 0xff);
                ssrc = ((buf[8] & 0xffL) << 24) | ((buf[9] & 0xffL) << 16) | ((buf[10] & 0xffL) << 8) | (buf[11] & 0xffL);
                off = 12 + cc * 4;
                if (ext && len >= off + 4) off += 4 + 4 * (((buf[off + 2] & 0xff) << 8) | (buf[off + 3] & 0xff));
                if ((buf[0] & 0x20) != 0 && len > off) len -= buf[len - 1] & 0xff; // padding
                if (ssrc == mySsrc) continue;
            } else {
                // Raw Opus from a non-RTP sender: identify by source address.
                ssrc = dp.getAddress().hashCode() & 0xffffffffL;
            }
            if (len - off <= 0) continue;
            try {
                Talker t;
                boolean isNew;
                long gap;
                synchronized (talkers) {
                    t = talkers.get(ssrc);
                    isNew = t == null;
                    if (isNew) {
                        t = new Talker(ssrc);
                        talkers.put(ssrc, t);
                    }
                    long now = System.currentTimeMillis();
                    gap = isNew ? 0 : now - t.lastMs;
                    t.lastMs = now;
                    t.address = dp.getAddress().getHostAddress();
                }
                // A new talk spurt after a pause: the sender may have restarted with the same SSRC and a lower
                // sequence number (TAK Vx does after its channel settings are saved). Start the count again
                // instead of dropping every packet as "late" until the old number comes round.
                if (gap > 1000) t.hasSeq = false;
                if (seqNo >= 0 && t.hasSeq && (short) (seqNo - t.lastSeq) <= 0 && ++t.lateRun > 25) t.hasSeq = false;
                if (seqNo < 0) { // raw Opus (no RTP): arrival order is all there is
                    int n = t.decoder.decode(buf, off, len - off, pcm, 0, pcm.length, false);
                    for (int p = 0; p + FRAME <= n; p += FRAME) pushFrame(t, ++t.extSeq, pcm, p);
                } else {
                    int delta = t.hasSeq ? (short) (seqNo - t.lastSeq) : 1; // signed 16-bit step
                    if (delta <= 0 && t.hasSeq) {
                        // A late or repeated packet (reordered or re-flooded by the mesh): its moment has passed.
                        // Decoding it would also upset the decoder's state, so drop it.
                        continue;
                    }
                    if (delta > 50) delta = 1; // the talker restarted (new sequence): no concealment
                    int lost = delta - 1;
                    if (lost > 0 && lost <= 5) {
                        // Hide short gaps: plain loss concealment, then the last lost frame rebuilt from this
                        // packet's forward error correction (OpenMANET / our sender use in-band FEC).
                        for (int i = 0; i < lost - 1; i++) {
                            int m = t.decoder.decode(null, 0, 0, pcm, 0, FRAME, false);
                            if (m >= FRAME) pushFrame(t, t.extSeq + 1 + i, pcm, 0);
                        }
                        int m = t.decoder.decode(buf, off, len - off, pcm, 0, FRAME, true);
                        if (m >= FRAME) pushFrame(t, t.extSeq + lost, pcm, 0);
                    }
                    t.extSeq += delta;
                    t.lastSeq = seqNo;
                    t.hasSeq = true;
                    t.lateRun = 0;
                    int n = t.decoder.decode(buf, off, len - off, pcm, 0, pcm.length, false);
                    // Feed the mixer 20 ms at a time.
                    for (int p = 0; p + FRAME <= n; p += FRAME) pushFrame(t, t.extSeq + p / FRAME, pcm, p);
                }
                if (isNew) listener.onTalkersChanged();
            } catch (Exception e) {
                Log.d(TAG, "bad packet: " + e.getMessage());
            }
        }
    }
}
