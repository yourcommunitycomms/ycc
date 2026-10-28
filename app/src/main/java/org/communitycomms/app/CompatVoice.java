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
 * EXPERIMENTAL: voice compatible with OpenMANET "Comms" and (per OpenMANET's release notes)
 * the ATAK Vx plugin. Opus 48 kHz mono, 20 ms frames, 32 kbit/s, in-band FEC, inside RTP
 * (payload type 111) on a talk group's multicast address:port. Raw Opus datagrams (no RTP
 * header) are also accepted on receive.
 *
 * Differences from normal channels, by necessity: NOT encrypted, half duplex
 * (you cannot transmit while someone else is talking), no acknowledgements. Untested against
 * real OpenMANET / Vx equipment.
 */
public final class CompatVoice {
    public interface Listener {
        void onTalkersChanged();

        void onTransmitChanged(boolean on);
    }

    public static final class Talker {
        public final long ssrc;
        public long lastMs;
        public String address;
        final OpusDecoder decoder;
        int lastSeq = -1;

        Talker(long ssrc) throws org.concentus.OpusException {
            this.ssrc = ssrc;
            decoder = new OpusDecoder(RATE, 1);
        }

        public String label() {
            return "Talker " + Long.toHexString(ssrc & 0xffff);
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
        mixer = new Mixer(RATE, FRAME);
        rx = new Thread(new Runnable() {
            @Override
            public void run() {
                receiveLoop();
            }
        }, "compat-rx");
        rx.start();
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
                synchronized (talkers) {
                    t = talkers.get(ssrc);
                    isNew = t == null;
                    if (isNew) {
                        t = new Talker(ssrc);
                        talkers.put(ssrc, t);
                    }
                    t.lastMs = System.currentTimeMillis();
                    t.address = dp.getAddress().getHostAddress();
                }
                int n = t.decoder.decode(buf, off, len - off, pcm, 0, pcm.length, false);
                // Feed the mixer 20 ms at a time.
                for (int p = 0; p + FRAME <= n; p += FRAME) {
                    short[] f = new short[FRAME];
                    System.arraycopy(pcm, p, f, 0, FRAME);
                    t.lastSeq = seqNo >= 0 ? seqNo : t.lastSeq + 1;
                    mixer.push(ssrc, (int) (System.nanoTime() / 1000), f);
                }
                if (isNew) listener.onTalkersChanged();
            } catch (Exception e) {
                Log.d(TAG, "bad packet: " + e.getMessage());
            }
        }
    }
}
