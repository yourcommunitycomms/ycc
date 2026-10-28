package org.communitycomms.app.audio;

/**
 * IMA ADPCM, 4 bits per sample. Every frame carries its own starting predictor and step index,
 * so a lost packet never corrupts the next one. Chosen because Opus encoding is not available in
 * the framework before Android 10 and this costs almost nothing on a 2012 phone.
 *
 * Frame layout: predictor (2 bytes, big-endian), step index (1 byte), then samples/2 bytes with
 * the first sample in the low nibble.
 */
public final class ImaAdpcm {
    private static final int[] INDEX_TABLE = {-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8};
    private static final int[] STEP_TABLE = {
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
            2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767};

    private int predictor;
    private int index;

    public static int encodedSize(int samples) {
        return 3 + samples / 2;
    }

    /** Encodes pcm (even length) into out starting at off. Returns bytes written. */
    public int encode(short[] pcm, int samples, byte[] out, int off) {
        out[off] = (byte) (predictor >> 8);
        out[off + 1] = (byte) predictor;
        out[off + 2] = (byte) index;
        int o = off + 3;
        for (int i = 0; i < samples; i += 2) {
            int lo = encodeSample(pcm[i]);
            int hi = encodeSample(pcm[i + 1]);
            out[o++] = (byte) (lo | (hi << 4));
        }
        return o - off;
    }

    /** Decodes a frame of len bytes into pcm. Returns samples written. */
    public static int decode(byte[] in, int off, int len, short[] pcm) {
        int pred = (short) (((in[off] & 0xff) << 8) | (in[off + 1] & 0xff));
        int idx = Math.min(88, Math.max(0, in[off + 2] & 0xff));
        int n = 0;
        int[] state = {pred, idx};
        for (int i = off + 3; i < off + len && n + 1 < pcm.length; i++) {
            int b = in[i] & 0xff;
            pcm[n++] = decodeNibble(b & 0x0f, state);
            pcm[n++] = decodeNibble(b >> 4, state);
        }
        return n;
    }

    private int encodeSample(int sample) {
        int step = STEP_TABLE[index];
        int diff = sample - predictor;
        int nibble = 0;
        if (diff < 0) {
            nibble = 8;
            diff = -diff;
        }
        int delta = step >> 3;
        if (diff >= step) {
            nibble |= 4;
            diff -= step;
            delta += step;
        }
        step >>= 1;
        if (diff >= step) {
            nibble |= 2;
            diff -= step;
            delta += step;
        }
        step >>= 1;
        if (diff >= step) {
            nibble |= 1;
            delta += step;
        }
        predictor += (nibble & 8) != 0 ? -delta : delta;
        if (predictor > 32767) predictor = 32767;
        else if (predictor < -32768) predictor = -32768;
        index += INDEX_TABLE[nibble];
        if (index < 0) index = 0;
        else if (index > 88) index = 88;
        return nibble;
    }

    private static short decodeNibble(int nibble, int[] state) {
        int step = STEP_TABLE[state[1]];
        int delta = step >> 3;
        if ((nibble & 4) != 0) delta += step;
        if ((nibble & 2) != 0) delta += step >> 1;
        if ((nibble & 1) != 0) delta += step >> 2;
        int pred = state[0] + ((nibble & 8) != 0 ? -delta : delta);
        if (pred > 32767) pred = 32767;
        else if (pred < -32768) pred = -32768;
        int idx = state[1] + INDEX_TABLE[nibble];
        state[0] = pred;
        state[1] = idx < 0 ? 0 : (idx > 88 ? 88 : idx);
        return (short) pred;
    }
}
