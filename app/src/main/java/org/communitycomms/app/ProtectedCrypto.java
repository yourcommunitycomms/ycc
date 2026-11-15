package org.communitycomms.app;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The crypto behind protected notes and calendar entries (plain Java, no Android classes, so it can be
 * tested on a PC). Works on Android 4.1: only HmacSHA1, HmacSHA256 and AES/CBC/PKCS5Padding are used.
 *
 * Password -> PBKDF2-HMAC-SHA1 (RFC 2898, the same algorithm as PBKDF2WithHmacSHA1, done here over the
 * password's UTF-8 bytes because Android before 4.4 fed only the low 8 bits of each char into its own
 * PBKDF2WithHmacSHA1, which would make a non-ASCII password stop working after an OS update) with a random
 * 16-byte salt -> 20-byte master. encKey = HMAC-SHA256(master, "YCCP1 enc"), macKey = HMAC-SHA256(master,
 * "YCCP1 mac"). Each item: random 16-byte IV, AES-256-CBC, then HMAC-SHA256(macKey, "YCCP1" | IV | ct)
 * (encrypt-then-MAC). The password check is HMAC-SHA256(macKey, "YCCP1 verify").
 */
public final class ProtectedCrypto {
    public static final int DEFAULT_ITERATIONS = 10000;
    private static final byte[] TAG = ascii("YCCP1");
    private static final SecureRandom RNG = new SecureRandom();

    private ProtectedCrypto() {
    }

    /** The two keys made from a password. wipe() when done. */
    public static final class Keys {
        final byte[] enc, mac;

        Keys(byte[] enc, byte[] mac) {
            this.enc = enc;
            this.mac = mac;
        }

        public void wipe() {
            Arrays.fill(enc, (byte) 0);
            Arrays.fill(mac, (byte) 0);
        }
    }

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    /** PBKDF2-HMAC-SHA1 (RFC 2898 / RFC 6070). */
    public static byte[] pbkdf2(byte[] password, byte[] salt, int iterations, int len) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        // An empty password is not a valid HMAC key spec: a single zero byte gives the same HMAC key.
        mac.init(new SecretKeySpec(password.length == 0 ? new byte[1] : password, "HmacSHA1"));
        int hLen = mac.getMacLength();
        byte[] out = new byte[len];
        byte[] u = new byte[hLen], t = new byte[hLen];
        for (int block = 1, off = 0; off < len; block++, off += hLen) {
            mac.update(salt);
            mac.update(new byte[]{(byte) (block >>> 24), (byte) (block >>> 16), (byte) (block >>> 8), (byte) block});
            mac.doFinal(u, 0);
            System.arraycopy(u, 0, t, 0, hLen);
            for (int i = 1; i < iterations; i++) {
                mac.update(u);
                mac.doFinal(u, 0);
                for (int j = 0; j < hLen; j++) t[j] ^= u[j];
            }
            System.arraycopy(t, 0, out, off, Math.min(hLen, len - off));
        }
        Arrays.fill(u, (byte) 0);
        Arrays.fill(t, (byte) 0);
        return out;
    }

    public static Keys derive(char[] password, byte[] salt, int iterations) throws Exception {
        byte[] pw = utf8(password);
        byte[] master = pbkdf2(pw, salt, iterations, 20);
        Arrays.fill(pw, (byte) 0);
        Keys k = new Keys(hmac(master, ascii("YCCP1 enc")), hmac(master, ascii("YCCP1 mac")));
        Arrays.fill(master, (byte) 0);
        return k;
    }

    public static byte[] verifier(Keys k) throws Exception {
        return hmac(k.mac, ascii("YCCP1 verify"));
    }

    public static boolean check(Keys k, byte[] verifier) throws Exception {
        return verifier != null && MessageDigest.isEqual(verifier(k), verifier);
    }

    /** IV (16) | ciphertext | MAC (32). */
    public static byte[] seal(Keys k, byte[] plain) throws Exception {
        byte[] iv = random(16);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k.enc, "AES"), new IvParameterSpec(iv));
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[16 + ct.length + 32];
        System.arraycopy(iv, 0, out, 0, 16);
        System.arraycopy(ct, 0, out, 16, ct.length);
        System.arraycopy(tag(k, out, 16 + ct.length), 0, out, 16 + ct.length, 32);
        return out;
    }

    /** Null when the MAC does not match (wrong key or changed data). */
    public static byte[] open(Keys k, byte[] sealed) throws Exception {
        if (sealed == null || sealed.length < 16 + 16 + 32) return null;
        int n = sealed.length - 32;
        byte[] want = tag(k, sealed, n);
        byte[] got = Arrays.copyOfRange(sealed, n, sealed.length);
        if (!MessageDigest.isEqual(want, got)) return null;
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k.enc, "AES"), new IvParameterSpec(sealed, 0, 16));
        return c.doFinal(sealed, 16, n - 16);
    }

    private static byte[] tag(Keys k, byte[] data, int len) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(k.mac, "HmacSHA256"));
        m.update(TAG);
        m.update(data, 0, len);
        return m.doFinal();
    }

    static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data);
    }

    static byte[] utf8(char[] cs) {
        java.nio.ByteBuffer bb = java.nio.charset.Charset.forName("UTF-8").encode(java.nio.CharBuffer.wrap(cs));
        byte[] out = new byte[bb.remaining()];
        bb.get(out);
        if (bb.hasArray()) Arrays.fill(bb.array(), (byte) 0);
        return out;
    }

    private static byte[] ascii(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < b.length; i++) b[i] = (byte) s.charAt(i);
        return b;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        if (s == null || (s.length() & 1) != 0) return null;
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            b[i] = (byte) ((hi << 4) | lo);
        }
        return b;
    }
}
