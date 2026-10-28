package org.communitycomms.app.crypto;

import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keys derived from a channel name. Anyone who types the same channel name gets the same keys,
 * so the name is the passcode.
 *
 * Derivation: PBKDF2-HMAC-SHA256(name, "communitycomms/v1", 20000) -> 64 bytes, split into an
 * AES-256 key and an HMAC-SHA256 key. PBKDF2 is done by hand because the platform
 * PBKDF2WithHmacSHA1 on Android before 4.4 only used the low 8 bits of each character, which
 * would make non-ASCII channel names disagree between old and new phones.
 *
 * Packets are AES-CTR then HMAC-SHA256 (truncated). GCM is avoided because GCMParameterSpec
 * only exists from API 19.
 */
public final class ChannelCrypto {
    public static final int MAC_LEN = 10;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final byte[] SALT = "communitycomms/v1".getBytes(UTF8);
    private static final int ITERATIONS = 20000;

    private final SecretKeySpec encKey;
    private final SecretKeySpec macKey;
    /** Public 32-bit tag so receivers can skip other channels' packets without trial decryption. */
    public final int channelTag;

    public ChannelCrypto(String channelName) throws GeneralSecurityException {
        byte[] okm = pbkdf2Sha256(channelName.trim().getBytes(UTF8), SALT, ITERATIONS, 64);
        byte[] ek = new byte[32];
        byte[] mk = new byte[32];
        System.arraycopy(okm, 0, ek, 0, 32);
        System.arraycopy(okm, 32, mk, 0, 32);
        encKey = new SecretKeySpec(ek, "AES");
        macKey = new SecretKeySpec(mk, "HmacSHA256");
        byte[] t = hmac(macKey, "channel-tag".getBytes(UTF8), 0, 11);
        channelTag = ((t[0] & 0xff) << 24) | ((t[1] & 0xff) << 16) | ((t[2] & 0xff) << 8) | (t[3] & 0xff);
    }

    /** Encrypt in place: buf[off..off+len) is plaintext and becomes ciphertext. */
    public void ctr(byte[] iv16, byte[] buf, int off, int len) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, encKey, new IvParameterSpec(iv16));
        int n = c.doFinal(buf, off, len, buf, off);
        if (n != len) throw new GeneralSecurityException("CTR length mismatch");
    }

    /** Truncated HMAC over buf[off..off+len). */
    public byte[] mac(byte[] buf, int off, int len) throws GeneralSecurityException {
        byte[] full = hmac(macKey, buf, off, len);
        byte[] out = new byte[MAC_LEN];
        System.arraycopy(full, 0, out, 0, MAC_LEN);
        return out;
    }

    public boolean verify(byte[] buf, int off, int len, byte[] tag, int tagOff) throws GeneralSecurityException {
        byte[] want = mac(buf, off, len);
        byte[] got = new byte[MAC_LEN];
        System.arraycopy(tag, tagOff, got, 0, MAC_LEN);
        return MessageDigest.isEqual(want, got);
    }

    private static byte[] hmac(SecretKeySpec key, byte[] buf, int off, int len) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(key);
        m.update(buf, off, len);
        return m.doFinal();
    }

    public static byte[] pbkdf2Sha256(byte[] password, byte[] salt, int iterations, int outLen)
            throws GeneralSecurityException {
        Mac prf = Mac.getInstance("HmacSHA256");
        // HMAC rejects empty keys; an empty channel name is refused by the UI anyway.
        prf.init(new SecretKeySpec(password.length == 0 ? new byte[1] : password, "HmacSHA256"));
        int hLen = 32;
        byte[] out = new byte[outLen];
        byte[] u = new byte[hLen];
        byte[] t = new byte[hLen];
        for (int block = 1, pos = 0; pos < outLen; block++, pos += hLen) {
            prf.update(salt);
            prf.update(new byte[]{(byte) (block >>> 24), (byte) (block >>> 16), (byte) (block >>> 8), (byte) block});
            prf.doFinal(u, 0);
            System.arraycopy(u, 0, t, 0, hLen);
            for (int i = 1; i < iterations; i++) {
                prf.update(u);
                prf.doFinal(u, 0);
                for (int j = 0; j < hLen; j++) t[j] ^= u[j];
            }
            System.arraycopy(t, 0, out, pos, Math.min(hLen, outLen - pos));
        }
        return out;
    }
}
