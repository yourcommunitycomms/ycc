package org.communitycomms.app.net;

import org.communitycomms.app.crypto.ChannelCrypto;

import java.security.GeneralSecurityException;

/**
 * Wire format, big-endian. Shared with the future Windows app, so keep it documented.
 *
 * <pre>
 *  0  'U' 'B'           magic
 *  2  version (1)
 *  3  type              see TYPE_*
 *  4  channel tag (4)   from ChannelCrypto, cleartext, lets receivers drop other channels
 *  8  sender id (8)     random per app run
 * 16  sequence (4)      per sender, increments every packet
 * 20  ciphertext (n)    AES-256-CTR, IV = sender id || sequence || 00000000
 * 20+n  mac (10)        HMAC-SHA256(bytes 0 .. 20+n) truncated
 * </pre>
 */
public final class Packet {
    public static final int HEADER = 20;
    public static final byte VERSION = 1;

    public static final int TYPE_VOICE = 1;
    public static final int TYPE_VOICE_END = 2;
    public static final int TYPE_PRESENCE = 3;
    /** Receiver confirms a transmission: txId(4), sender being acked(8), frames heard(2). */
    public static final int TYPE_ACK = 4;
    // VOICE payload after the name: txId(4), then the ADPCM frame. VOICE_END: txId(4).
    // Reserved for later phases.
    public static final int TYPE_CHAT = 10;
    public static final int TYPE_LOCATION = 11;
    public static final int TYPE_MARK = 12;
    /**
     * DRYAD sheet transfer chunk. Payload after the name: transfer id (4), chunk index (2),
     * chunk count (2), then UTF-8 text bytes. The joined text is DryadSheet.toText() output.
     */
    public static final int TYPE_DRYAD = 13;
    /** File transfer message, see FileTransfer for the body layout. */
    public static final int TYPE_FILE = 14;
    /** Call alert ("ring everyone"). Payload after the name: call id (4). Sent 3 times, same id. */
    public static final int TYPE_CALL = 15;
    /**
     * Voice for one callsign only (intercom: talk to one room). Payload after the name: txId (4),
     * target length (1), target callsign (UTF-8), then the ADPCM frame. Other phones drop it; the
     * VOICE_END that follows is the normal one (only the target heard it, so only it acks).
     */
    public static final int TYPE_VOICE_TO = 16;
    /**
     * Live camera picture (about 5 a second, small JPEGs). Payload after the name: streamId(4)
     * frameNo(4) chunkIndex(1) chunkCount(1) then up to 1100 bytes of the JPEG. A frame is shown
     * only when all its chunks arrived; late or broken frames are dropped (no repair).
     * Chunk count 0 = audio with the pictures: streamId(4) seq(4) 0 0 then IMA ADPCM frames.
     */
    public static final int TYPE_VIDEO = 17;
    /**
     * Shared aircraft (ADS-B), sent by a phone with "Share ADS-B data" on after each fetch. Payload
     * after the name: count(1), then per aircraft 25 bytes: ICAO hex(3) lat(4, 1e-5 deg) lon(4)
     * altitude(2, ft/25; -32768 ground, 32767 unknown) track(2, deg or -1) speed(2, kt or -1)
     * flight(8, ASCII, space padded). At most 40 per packet; more go in further packets.
     */
    public static final int TYPE_ADSB = 18;
    /**
     * Shared ships (AIS), like TYPE_ADSB. Payload after the name: count(1), then per ship 29 bytes:
     * MMSI(4) lat(4, 1e-5 deg) lon(4) speed(2, 0.1 kt or -1) course(2, 0.1 deg or -1) ITU type(1)
     * name(12, ASCII, space padded). At most 35 per packet.
     */
    public static final int TYPE_AIS = 19;

    public int type;
    public long sender;
    public int seq;
    public byte[] payload;

    public static byte[] seal(ChannelCrypto crypto, int type, long sender, int seq, byte[] payload, int payloadLen)
            throws GeneralSecurityException {
        byte[] out = new byte[HEADER + payloadLen + ChannelCrypto.MAC_LEN];
        out[0] = 'U';
        out[1] = 'B';
        out[2] = VERSION;
        out[3] = (byte) type;
        putInt(out, 4, crypto.channelTag);
        putLong(out, 8, sender);
        putInt(out, 16, seq);
        System.arraycopy(payload, 0, out, HEADER, payloadLen);
        crypto.ctr(iv(sender, seq), out, HEADER, payloadLen);
        byte[] mac = crypto.mac(out, 0, HEADER + payloadLen);
        System.arraycopy(mac, 0, out, HEADER + payloadLen, ChannelCrypto.MAC_LEN);
        return out;
    }

    /** Returns null for anything not on this channel or failing authentication. */
    public static Packet open(ChannelCrypto crypto, byte[] buf, int len) {
        if (len < HEADER + ChannelCrypto.MAC_LEN) return null;
        if (buf[0] != 'U' || buf[1] != 'B' || buf[2] != VERSION) return null;
        if (getInt(buf, 4) != crypto.channelTag) return null;
        int bodyLen = len - HEADER - ChannelCrypto.MAC_LEN;
        try {
            if (!crypto.verify(buf, 0, HEADER + bodyLen, buf, HEADER + bodyLen)) return null;
            Packet p = new Packet();
            p.type = buf[3] & 0xff;
            p.sender = getLong(buf, 8);
            p.seq = getInt(buf, 16);
            p.payload = new byte[bodyLen];
            System.arraycopy(buf, HEADER, p.payload, 0, bodyLen);
            crypto.ctr(iv(p.sender, p.seq), p.payload, 0, bodyLen);
            return p;
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    private static byte[] iv(long sender, int seq) {
        byte[] iv = new byte[16];
        putLong(iv, 0, sender);
        putInt(iv, 8, seq);
        return iv;
    }

    static void putInt(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    static int getInt(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    static void putLong(byte[] b, int o, long v) {
        putInt(b, o, (int) (v >>> 32));
        putInt(b, o + 4, (int) v);
    }

    static long getLong(byte[] b, int o) {
        return ((long) getInt(b, o) << 32) | (getInt(b, o + 4) & 0xffffffffL);
    }
}
