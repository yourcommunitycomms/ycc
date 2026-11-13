package org.communitycomms.app.power;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Bluetti power stations over Bluetooth LE: Modbus RTU frames (slave 1, function 3 read / 6 write,
 * CRC-16/MODBUS little endian) written to characteristic ff02, answers notified on ff01.
 *
 * Newer units start with an encrypted handshake: they notify a "**" (0x2A2A) challenge right after
 * the link comes up. Our own Java implementation follows the description in the MIT licensed
 * bluetti-bt-lib (Copyright (c) 2025 Patrick762), whose encryption code credits nhurman's
 * bluetti_mqtt fork, and the framing in warhammerkid/bluetti_mqtt (MIT, Copyright 2022 Stephen
 * Augenstein); see NOTICE. Plain Java so it can be tested on a PC. Never the Bluetti cloud.
 *
 * Handshake: challenge (4 bytes) -> iv = MD5(reversed challenge), key = iv XOR LOCAL_KEY (AES-128);
 * we answer iv[8..11]; the unit sends its P-256 public key signed with its key (checked against K2);
 * we send an ephemeral public key signed with L1; it accepts; the AES-256 key is the plain ECDH
 * shared x-coordinate. After that every frame is len(2) seed(4) AES-256-CBC(iv = MD5(seed)).
 */
public final class BluettiProtocol {
    public static final String SERVICE = "0000ff00-0000-1000-8000-00805f9b34fb";
    public static final String NOTIFY = "0000ff01-0000-1000-8000-00805f9b34fb";
    public static final String WRITE = "0000ff02-0000-1000-8000-00805f9b34fb";

    private BluettiProtocol() {
    }

    // ---- Modbus ----

    public static int crc16(byte[] b, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= b[i] & 0xFF;
            for (int k = 0; k < 8; k++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
        }
        return crc;
    }

    private static byte[] frame(int fn, int a, int b) {
        byte[] f = {1, (byte) fn, (byte) (a >> 8), (byte) a, (byte) (b >> 8), (byte) b, 0, 0};
        int c = crc16(f, 0, 6);
        f[6] = (byte) c;
        f[7] = (byte) (c >> 8);
        return f;
    }

    /** Read holding registers (function 3). */
    public static byte[] read(int start, int count) {
        return frame(3, start, count);
    }

    /** Write one register (function 6). */
    public static byte[] write(int addr, int value) {
        return frame(6, addr, value);
    }

    /** How long a complete Modbus answer is once its first bytes are in (0 = not known yet). */
    public static int answerLength(byte[] b, int len) {
        if (len < 2) return 0;
        int fn = b[1] & 0xFF;
        if ((fn & 0x80) != 0) return 5;
        if (fn == 3 || fn == 4) return len < 3 ? 0 : (b[2] & 0xFF) + 5;
        if (fn == 6 || fn == 16) return 8;
        return len;
    }

    public static boolean crcOk(byte[] b, int len) {
        if (len < 4) return false;
        int c = crc16(b, 0, len - 2);
        return (b[len - 2] & 0xFF) == (c & 0xFF) && (b[len - 1] & 0xFF) == ((c >> 8) & 0xFF);
    }

    /** Register values of a function 3 answer, or null (exception or bad frame). */
    public static int[] registers(byte[] b, int len) {
        if (len < 5 || (b[1] & 0xFF) != 3) return null;
        int n = (b[2] & 0xFF) / 2;
        if (len < 5 + n * 2) return null;
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = ((b[3 + i * 2] & 0xFF) << 8) | (b[4 + i * 2] & 0xFF);
        return r;
    }

    public static String hex(byte[] b, int off, int len) {
        StringBuilder s = new StringBuilder(len * 2);
        for (int i = off; i < off + len; i++) s.append(String.format("%02x", b[i] & 0xFF));
        return s.toString();
    }

    public static String hex(byte[] b) {
        return b == null ? "" : hex(b, 0, b.length);
    }

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    // ---- encryption ----

    static final byte[] LOCAL_AES_KEY = unhex("459FC535808941F17091E0993EE3E93D");
    static final String PRIVATE_KEY_L1 = "4F19A16E3E87BDD9BD24D3E5495B88041511943CBC8B969ADE9641D0F56AF337";
    static final String PUBLIC_KEY_K2 = "3059301306072a8648ce3d020106082a8648ce3d03010703420004"
            + "A73ABF5D2232C8C1C72E68304343C272495E3A8FD6F30EA96DE2F4B3CE60B251EE21AC667CF8A71E18B46B664EAEFFE3C489F24F695B6411DB7E22CCC85A8594";
    static final String P256_SPKI_PREFIX = "3059301306072a8648ce3d020106082a8648ce3d03010703420004";

    /** Starts with "**": a key exchange message (never a Modbus answer, which starts with 01). */
    public static boolean isKex(byte[] b, int len) {
        return len >= 2 && b[0] == 0x2A && b[1] == 0x2A;
    }

    static byte[] kexFrame(byte[] body) {
        byte[] f = new byte[body.length + 4];
        f[0] = 0x2A;
        f[1] = 0x2A;
        System.arraycopy(body, 0, f, 2, body.length);
        int sum = 0;
        for (byte x : body) sum += x & 0xFF;
        f[f.length - 2] = (byte) (sum >> 8);
        f[f.length - 1] = (byte) sum;
        return f;
    }

    static byte[] md5(byte[] b) {
        try {
            return MessageDigest.getInstance("MD5").digest(b);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static byte[] aes(boolean enc, byte[] key, byte[] iv, byte[] data, int off, int len) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(enc ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(data, off, len);
    }

    static byte[] pad16(byte[] m) {
        byte[] p = new byte[(m.length + 15) / 16 * 16];
        System.arraycopy(m, 0, p, 0, m.length);
        return p;
    }

    /** Raw r||s (32 + 32) from a DER ECDSA signature. */
    static byte[] derToRaw(byte[] der) {
        int i = 2;
        if ((der[1] & 0x80) != 0) i += der[1] & 0x7F;
        byte[] out = new byte[64];
        for (int k = 0; k < 2; k++) {
            int l = der[i + 1] & 0xFF;
            byte[] v = new byte[l];
            System.arraycopy(der, i + 2, v, 0, l);
            byte[] fixed = new BigInteger(1, v).toByteArray();
            int skip = fixed.length > 32 ? fixed.length - 32 : 0;
            int n = fixed.length - skip;
            System.arraycopy(fixed, skip, out, k * 32 + 32 - n, n);
            i += 2 + l;
        }
        return out;
    }

    static byte[] rawToDer(byte[] raw, int off) {
        byte[] r = new BigInteger(1, java.util.Arrays.copyOfRange(raw, off, off + 32)).toByteArray();
        byte[] s = new BigInteger(1, java.util.Arrays.copyOfRange(raw, off + 32, off + 64)).toByteArray();
        byte[] der = new byte[6 + r.length + s.length];
        der[0] = 0x30;
        der[1] = (byte) (4 + r.length + s.length);
        der[2] = 2;
        der[3] = (byte) r.length;
        System.arraycopy(r, 0, der, 4, r.length);
        der[4 + r.length] = 2;
        der[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, der, 6 + r.length, s.length);
        return der;
    }

    static byte[] cat(byte[] a, byte[] b) {
        byte[] c = new byte[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    static byte[] fixed32(BigInteger v) {
        byte[] b = v.toByteArray();
        byte[] o = new byte[32];
        int skip = b.length > 32 ? b.length - 32 : 0;
        System.arraycopy(b, skip, o, 32 - (b.length - skip), b.length - skip);
        return o;
    }

    /**
     * One link's encryption state. Feed every notification to {@link #onNotify}; it returns frames to
     * write back (handshake replies) and decrypted Modbus answers through {@link #answer}.
     */
    public static final class Session {
        public interface Out {
            void send(byte[] wire);

            void log(String s);
        }

        public static final int PLAIN = 0, CHALLENGED = 1, WAIT_PEER = 2, WAIT_ACCEPT = 3, SECURE = 4, FAILED = 5;
        public volatile int state = PLAIN;
        private final Out out;
        private byte[] uKey, uIv, secureKey;
        private KeyPair mine;
        private byte[] buf = new byte[0];
        /** A whole Modbus answer (decrypted if needed), or null. */
        public byte[] answer;
        /** Test hook: another K2 (the unit's signing key) when simulating a unit. */
        static PublicKey testK2;

        public Session(Out out) {
            this.out = out;
        }

        public boolean encrypted() {
            return state != PLAIN;
        }

        public boolean secure() {
            return state == SECURE;
        }

        /** Wraps a Modbus frame for the air: as is when plain, encrypted once secure. */
        public byte[] wrap(byte[] modbus) throws Exception {
            if (state != SECURE) return modbus;
            byte[] seed = new byte[4];
            new SecureRandom().nextBytes(seed);
            byte[] c = aes(true, secureKey, md5(seed), pad16(modbus), 0, pad16(modbus).length);
            byte[] w = new byte[6 + c.length];
            w[0] = (byte) (modbus.length >> 8);
            w[1] = (byte) modbus.length;
            System.arraycopy(seed, 0, w, 2, 4);
            System.arraycopy(c, 0, w, 6, c.length);
            return w;
        }

        private byte[] wrapUnsecure(byte[] m) throws Exception {
            byte[] p = pad16(m);
            byte[] c = aes(true, uKey, uIv, p, 0, p.length);
            byte[] w = new byte[2 + c.length];
            w[0] = (byte) (m.length >> 8);
            w[1] = (byte) m.length;
            System.arraycopy(c, 0, w, 2, c.length);
            return w;
        }

        public void reset() {
            buf = new byte[0];
            answer = null;
        }

        /** One notification. Returns true when {@link #answer} holds a complete Modbus answer. */
        public boolean onNotify(byte[] v) {
            answer = null;
            buf = cat(buf, v);
            try {
                return drain();
            } catch (Exception e) {
                out.log("decode failed: " + e);
                buf = new byte[0];
                return false;
            }
        }

        private boolean drain() throws Exception {
            boolean got = false;
            while (buf.length > 0) {
                if (state == PLAIN || (state == CHALLENGED && isKex(buf, buf.length))) {
                    if (isKex(buf, buf.length)) {
                        // plain key exchange frame: "**" type sub data... sum(2). Only the challenge comes plain.
                        if (buf.length < 5) return got;
                        int need = buf[2] == 1 ? 10 : buf.length;
                        if (buf.length < need) return got;
                        byte[] f = java.util.Arrays.copyOf(buf, need);
                        buf = java.util.Arrays.copyOfRange(buf, need, buf.length);
                        onKex(f);
                        continue;
                    }
                    if (state != PLAIN) return got;
                    int need = answerLength(buf, buf.length);
                    if (need == 0 || buf.length < need) return got;
                    answer = java.util.Arrays.copyOf(buf, need);
                    buf = java.util.Arrays.copyOfRange(buf, need, buf.length);
                    got = true;
                    continue;
                }
                if (state == FAILED) {
                    buf = new byte[0];
                    return got;
                }
                // encrypted: len(2) [seed(4) when secure] cipher
                if (buf.length < 2) return got;
                int len = ((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF);
                int head = state == SECURE ? 6 : 2;
                int total = head + (len + 15) / 16 * 16;
                // Real units (EL30V2) repeat their last handshake message a few times after the link is
                // secure, still under the handshake key (no seed): spot and drop it.
                if (state == SECURE && len <= 32 && buf.length >= 2 + (len + 15) / 16 * 16 && uKey != null) {
                    int t2 = 2 + (len + 15) / 16 * 16;
                    byte[] p = aes(false, uKey, uIv, buf, 2, t2 - 2);
                    if (isKex(p, len)) {
                        buf = java.util.Arrays.copyOfRange(buf, t2, buf.length);
                        continue;
                    }
                }
                if (len == 0 || len > 1024) {
                    out.log("odd length " + len + ", dropped " + hex(buf));
                    buf = new byte[0];
                    return got;
                }
                if (buf.length < total) return got;
                byte[] iv = state == SECURE ? md5(java.util.Arrays.copyOfRange(buf, 2, 6)) : uIv;
                byte[] key = state == SECURE ? secureKey : uKey;
                byte[] plain = java.util.Arrays.copyOf(aes(false, key, iv, buf, head, total - head), len);
                buf = java.util.Arrays.copyOfRange(buf, total, buf.length);
                if (isKex(plain, plain.length)) onKex(plain);
                else {
                    answer = plain;
                    got = true;
                }
            }
            return got;
        }

        private void onKex(byte[] f) throws Exception {
            int type = f[2] & 0xFF;
            out.log("key exchange message type " + type + " (" + f.length + " bytes)");
            if (type == 1) { // challenge
                byte[] ch = {f[7], f[6], f[5], f[4]};
                uIv = md5(ch);
                uKey = new byte[16];
                for (int i = 0; i < 16; i++) uKey[i] = (byte) (uIv[i] ^ LOCAL_AES_KEY[i]);
                state = CHALLENGED;
                out.send(kexFrame(new byte[]{2, 4, uIv[8], uIv[9], uIv[10], uIv[11]}));
            } else if (type == 3) { // challenge accepted
                state = WAIT_PEER;
            } else if (type == 4) { // the unit's public key + signature
                if (f.length < 4 + 128 + 2) throw new Exception("short public key message");
                byte[] p = java.util.Arrays.copyOfRange(f, 4, 68);
                byte[] sig = java.util.Arrays.copyOfRange(f, 68, 132);
                PublicKey k2 = testK2 != null ? testK2
                        : KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(unhex(PUBLIC_KEY_K2)));
                Signature v = Signature.getInstance("SHA256withECDSA");
                v.initVerify(k2);
                v.update(cat(p, uIv));
                if (!v.verify(rawToDer(sig, 0))) {
                    state = FAILED;
                    throw new Exception("the unit's key signature did not check out");
                }
                KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
                g.initialize(new ECGenParameterSpec("secp256r1"));
                mine = g.generateKeyPair();
                ECParameterSpec params = ((ECPublicKey) mine.getPublic()).getParams();
                ECPoint w = ((ECPublicKey) mine.getPublic()).getW();
                byte[] q = cat(fixed32(w.getAffineX()), fixed32(w.getAffineY()));
                PrivateKey l1 = KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(PRIVATE_KEY_L1, 16), params));
                Signature s = Signature.getInstance("SHA256withECDSA");
                s.initSign(l1);
                s.update(cat(q, uIv));
                byte[] body = cat(new byte[]{5, (byte) 0x80}, cat(q, derToRaw(s.sign())));
                PublicKey peer = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(
                        new ECPoint(new BigInteger(1, java.util.Arrays.copyOfRange(p, 0, 32)), new BigInteger(1, java.util.Arrays.copyOfRange(p, 32, 64))), params));
                KeyAgreement ka = KeyAgreement.getInstance("ECDH");
                ka.init(mine.getPrivate());
                ka.doPhase(peer, true);
                secureKey = ka.generateSecret();
                if (secureKey.length > 32) secureKey = java.util.Arrays.copyOfRange(secureKey, secureKey.length - 32, secureKey.length);
                state = WAIT_ACCEPT;
                out.send(wrapUnsecure(kexFrame(body)));
            } else if (type == 6) {
                if (f.length >= 5 && f[4] == 0) {
                    state = SECURE;
                    out.log("encrypted link ready");
                } else {
                    state = FAILED;
                    out.log("the unit refused our key");
                }
            }
        }
    }
}
