package org.communitycomms.app.crypto;

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECFieldFp;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.util.Locale;

/**
 * Read-only ("listen") keys for a Public channel whose key was changed from the default.
 * <p>
 * From the full Public key N, everyone with N works out:
 * <ul>
 * <li>a listen secret S = PBKDF2-HMAC-SHA256(N, "communitycomms/listen/v1", 20000, 20); the channel's
 * encryption and tag come from ChannelCrypto("L:" + base32(S));</li>
 * <li>a signing key: d = (SHA-256(PBKDF2-HMAC-SHA256(N, "communitycomms/sign/v1", 20000, 32)) mod (n-1)) + 1
 * on P-256, Q = d·G.</li>
 * </ul>
 * The shareable listen key is "YCCL1-" + base32(S) + "-" + base32(Q compressed, 33 bytes): it opens
 * and checks everything on the channel but cannot sign, so a phone holding only it can listen and
 * read, while everything it might send is dropped. Every packet on such a channel carries a 64-byte
 * signature (r||s) at the end of its plaintext body, over type(1) sender(8) seq(4) tag(4) body.
 */
public final class ListenKey {
    public static final String PREFIX = "YCCL1-";
    public static final int SIG_LEN = 64;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    // P-256 (secp256r1)
    private static final BigInteger P = new BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
    private static final BigInteger A = P.subtract(BigInteger.valueOf(3));
    private static final BigInteger B = new BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16);
    private static final BigInteger GX = new BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16);
    private static final BigInteger GY = new BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16);
    private static final BigInteger N = new BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16);
    private static final ECParameterSpec SPEC = new ECParameterSpec(
            new EllipticCurve(new ECFieldFp(P), A, B), new ECPoint(GX, GY), N, 1);

    private final byte[] secret;
    private final ECPoint q;
    private final PrivateKey priv; // null = listen only
    private final PublicKey pub;

    private ListenKey(byte[] secret, ECPoint q, BigInteger d) throws GeneralSecurityException {
        this.secret = secret;
        this.q = q;
        KeyFactory kf = KeyFactory.getInstance("EC");
        pub = kf.generatePublic(new ECPublicKeySpec(q, SPEC));
        priv = d == null ? null : kf.generatePrivate(new ECPrivateKeySpec(d, SPEC));
    }

    public static boolean isListenKey(String s) {
        return s != null && s.trim().toUpperCase(Locale.US).startsWith(PREFIX);
    }

    /** The default Public key, which stays an ordinary (unsigned) channel. */
    public static final String DEFAULT_PUBLIC = "publicchannelkey";

    /** True when this Public key means a signed channel: changed from the default, or a listen key. */
    public static boolean signedPublic(String key) {
        String k = key == null ? "" : key.trim();
        return k.length() > 0 && !k.equals(DEFAULT_PUBLIC);
    }

    /** Full member: derives everything from the full Public key (slow on old phones: run off the UI thread). */
    public static ListenKey fromFull(String fullKey) throws GeneralSecurityException {
        byte[] name = fullKey.trim().getBytes(UTF8);
        byte[] s = ChannelCrypto.pbkdf2Sha256(name, "communitycomms/listen/v1".getBytes(UTF8), 20000, 20);
        byte[] seed = ChannelCrypto.pbkdf2Sha256(name, "communitycomms/sign/v1".getBytes(UTF8), 20000, 32);
        BigInteger d = new BigInteger(1, MessageDigest.getInstance("SHA-256").digest(seed))
                .mod(N.subtract(BigInteger.ONE)).add(BigInteger.ONE);
        return new ListenKey(s, multiply(d), d);
    }

    /** Listener: parses a shared listen key. */
    public static ListenKey parse(String listenKey) throws GeneralSecurityException {
        String k = listenKey.trim().toUpperCase(Locale.US);
        if (!k.startsWith(PREFIX)) throw new GeneralSecurityException("Not a listen key");
        String[] parts = k.substring(PREFIX.length()).split("-");
        if (parts.length != 2) throw new GeneralSecurityException("Damaged listen key");
        byte[] s = Base32.decode(parts[0]);
        byte[] qc = Base32.decode(parts[1]);
        if (s.length != 20 || qc.length != 33) throw new GeneralSecurityException("Damaged listen key");
        return new ListenKey(s, decompress(qc), null);
    }

    /** The name the channel's encryption keys and tag come from. */
    public String channelName() {
        return "L:" + Base32.encode(secret);
    }

    public String shareable() {
        return PREFIX + Base32.encode(secret) + "-" + Base32.encode(compress(q));
    }

    public boolean canSend() {
        return priv != null;
    }

    public byte[] sign(byte[] msg) throws GeneralSecurityException {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(priv);
        s.update(msg);
        return derToRaw(s.sign());
    }

    public boolean verify(byte[] msg, byte[] raw, int off) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(pub);
            s.update(msg);
            return s.verify(rawToDer(raw, off));
        } catch (Exception e) {
            return false;
        }
    }

    // ---- P-256 point maths (only for working out Q = d·G and decompressing Q) ----

    private static ECPoint multiply(BigInteger k) {
        ECPoint r = null, a = new ECPoint(GX, GY);
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = r == null ? null : add(r, r);
            if (k.testBit(i)) r = r == null ? a : add(r, a);
        }
        return r;
    }

    private static ECPoint add(ECPoint p1, ECPoint p2) {
        BigInteger x1 = p1.getAffineX(), y1 = p1.getAffineY(), x2 = p2.getAffineX(), y2 = p2.getAffineY();
        BigInteger l;
        if (x1.equals(x2)) {
            if (!y1.equals(y2) || y1.signum() == 0) throw new ArithmeticException("point at infinity");
            l = x1.pow(2).multiply(BigInteger.valueOf(3)).add(A).multiply(y1.shiftLeft(1).modInverse(P)).mod(P);
        } else {
            l = y2.subtract(y1).multiply(x2.subtract(x1).modInverse(P)).mod(P);
        }
        BigInteger x3 = l.pow(2).subtract(x1).subtract(x2).mod(P);
        BigInteger y3 = l.multiply(x1.subtract(x3)).subtract(y1).mod(P);
        return new ECPoint(x3, y3);
    }

    private static byte[] compress(ECPoint q) {
        byte[] out = new byte[33];
        out[0] = (byte) (q.getAffineY().testBit(0) ? 3 : 2);
        byte[] x = fixed(q.getAffineX());
        System.arraycopy(x, 0, out, 1, 32);
        return out;
    }

    private static ECPoint decompress(byte[] c) throws GeneralSecurityException {
        BigInteger x = new BigInteger(1, java.util.Arrays.copyOfRange(c, 1, 33));
        BigInteger rhs = x.pow(3).add(A.multiply(x)).add(B).mod(P);
        BigInteger y = rhs.modPow(P.add(BigInteger.ONE).shiftRight(2), P); // P ≡ 3 (mod 4)
        if (!y.pow(2).mod(P).equals(rhs)) throw new GeneralSecurityException("Damaged listen key");
        if (y.testBit(0) != (c[0] == 3)) y = P.subtract(y);
        return new ECPoint(x, y);
    }

    private static byte[] fixed(BigInteger v) {
        byte[] b = v.toByteArray(), out = new byte[32];
        int n = Math.min(32, b.length);
        System.arraycopy(b, b.length - n, out, 32 - n, n);
        return out;
    }

    // ---- DER <-> raw r||s ----

    private static byte[] derToRaw(byte[] der) {
        int i = 2;
        if ((der[1] & 0x80) != 0) i += der[1] & 0x7f;
        byte[] out = new byte[64];
        for (int k = 0; k < 2; k++) {
            int len = der[i + 1] & 0xff;
            byte[] v = java.util.Arrays.copyOfRange(der, i + 2, i + 2 + len);
            byte[] f = fixed(new BigInteger(1, v));
            System.arraycopy(f, 0, out, k * 32, 32);
            i += 2 + len;
        }
        return out;
    }

    private static byte[] rawToDer(byte[] raw, int off) {
        byte[] r = new BigInteger(1, java.util.Arrays.copyOfRange(raw, off, off + 32)).toByteArray();
        byte[] s = new BigInteger(1, java.util.Arrays.copyOfRange(raw, off + 32, off + 64)).toByteArray();
        byte[] out = new byte[6 + r.length + s.length];
        out[0] = 0x30;
        out[1] = (byte) (4 + r.length + s.length);
        out[2] = 0x02;
        out[3] = (byte) r.length;
        System.arraycopy(r, 0, out, 4, r.length);
        out[4 + r.length] = 0x02;
        out[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, out, 6 + r.length, s.length);
        return out;
    }

    /** RFC 4648 base32 without padding (A–Z, 2–7). */
    static final class Base32 {
        private static final String ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

        static String encode(byte[] b) {
            StringBuilder sb = new StringBuilder();
            int buf = 0, bits = 0;
            for (byte x : b) {
                buf = (buf << 8) | (x & 0xff);
                bits += 8;
                while (bits >= 5) {
                    sb.append(ABC.charAt((buf >> (bits - 5)) & 31));
                    bits -= 5;
                }
            }
            if (bits > 0) sb.append(ABC.charAt((buf << (5 - bits)) & 31));
            return sb.toString();
        }

        static byte[] decode(String s) throws GeneralSecurityException {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int buf = 0, bits = 0;
            for (char c : s.toCharArray()) {
                int v = ABC.indexOf(c);
                if (v < 0) throw new GeneralSecurityException("Damaged listen key");
                buf = (buf << 5) | v;
                bits += 5;
                if (bits >= 8) {
                    out.write((buf >> (bits - 8)) & 0xff);
                    bits -= 8;
                }
            }
            return out.toByteArray();
        }
    }
}
