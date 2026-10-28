package org.communitycomms.app.msg;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Per-phone key pair for private messages. The channel key lets everyone on the channel read
 * everything, so a private message is additionally sealed for one phone's public key:
 *
 * <pre>
 *  sealed = rsaLen(2) RSA-2048-OAEP(secret 32) iv(16) AES-256-CTR(text) HMAC-SHA256(iv||ct)[16]
 *  AES key = SHA-256("enc" || secret), MAC key = SHA-256("mac" || secret)
 * </pre>
 *
 * Fingerprint = first 8 bytes of SHA-256(public key DER). Keys are trusted on first sight (whoever
 * announces under a callsign); they live in the app's preferences, so the app-data wipe erases them.
 */
public final class PrivateKeys {
    private static final String RSA = "RSA/ECB/OAEPWithSHA-1AndMGF1Padding";
    private static volatile KeyPair pair;

    private PrivateKeys() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** This phone's key pair, made once (a few seconds on old phones: call off the main thread). */
    public static synchronized KeyPair get(Context c) {
        if (pair != null) return pair;
        try {
            String pub = sp(c).getString("pmPub", ""), priv = sp(c).getString("pmPriv", "");
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (pub.length() > 0 && priv.length() > 0) {
                pair = new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP))));
                return pair;
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048, new SecureRandom());
            pair = g.generateKeyPair();
            sp(c).edit().putString("pmPub", Base64.encodeToString(pair.getPublic().getEncoded(), Base64.NO_WRAP))
                    .putString("pmPriv", Base64.encodeToString(pair.getPrivate().getEncoded(), Base64.NO_WRAP)).commit();
            return pair;
        } catch (Exception e) {
            return null;
        }
    }

    /** Forget the in-memory pair (after the app-data wipe). */
    public static synchronized void forget() {
        pair = null;
    }

    public static long fingerprint(byte[] publicDer) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(publicDer);
            long f = 0;
            for (int i = 0; i < 8; i++) f = (f << 8) | (h[i] & 0xff);
            return f;
        } catch (Exception e) {
            return 0;
        }
    }

    private static byte[] derive(String label, byte[] secret) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        d.update(label.getBytes("UTF-8"));
        d.update(secret);
        return d.digest();
    }

    public static byte[] seal(byte[] publicDer, byte[] plain) throws Exception {
        PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicDer));
        SecureRandom r = new SecureRandom();
        byte[] secret = new byte[32], iv = new byte[16];
        r.nextBytes(secret);
        r.nextBytes(iv);
        Cipher rsa = Cipher.getInstance(RSA);
        rsa.init(Cipher.ENCRYPT_MODE, pub);
        byte[] wrapped = rsa.doFinal(secret);
        Cipher aes = Cipher.getInstance("AES/CTR/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derive("enc", secret), "AES"), new IvParameterSpec(iv));
        byte[] ct = aes.doFinal(plain);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(derive("mac", secret), "HmacSHA256"));
        mac.update(iv);
        byte[] tag = mac.doFinal(ct);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(2 + wrapped.length + 16 + ct.length + 16);
        b.putShort((short) wrapped.length).put(wrapped).put(iv).put(ct).put(tag, 0, 16);
        return b.array();
    }

    /** Returns the text, or null if it is not for this key or was tampered with. */
    public static byte[] open(PrivateKey priv, byte[] sealed) {
        try {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(sealed);
            byte[] wrapped = new byte[b.getShort() & 0xffff];
            b.get(wrapped);
            byte[] iv = new byte[16];
            b.get(iv);
            byte[] ct = new byte[b.remaining() - 16];
            b.get(ct);
            byte[] tag = new byte[16];
            b.get(tag);
            Cipher rsa = Cipher.getInstance(RSA);
            rsa.init(Cipher.DECRYPT_MODE, priv);
            byte[] secret = rsa.doFinal(wrapped);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(derive("mac", secret), "HmacSHA256"));
            mac.update(iv);
            byte[] want = mac.doFinal(ct);
            byte[] w16 = new byte[16];
            System.arraycopy(want, 0, w16, 0, 16);
            if (!MessageDigest.isEqual(w16, tag)) return null;
            Cipher aes = Cipher.getInstance("AES/CTR/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derive("enc", secret), "AES"), new IvParameterSpec(iv));
            return aes.doFinal(ct);
        } catch (Exception e) {
            return null;
        }
    }
}
