package org.communitycomms.app.power;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * Just enough elliptic-curve maths (short Weierstrass, affine, BigInteger) for the one ECDH a
 * battery handshake needs: P-256 (Anker SOLIX) and secp160r1 (EcoFlow). Old Android has no
 * secp160r1 and its P-256 support varies, so this is done here. Speed does not matter: one
 * multiplication per connection.
 */
public final class Ec {
    public final BigInteger p, a, b, gx, gy, n;
    public final int bytes;

    private Ec(String p, String a, String b, String gx, String gy, String n, int bytes) {
        this.p = new BigInteger(p, 16);
        this.a = new BigInteger(a, 16);
        this.b = new BigInteger(b, 16);
        this.gx = new BigInteger(gx, 16);
        this.gy = new BigInteger(gy, 16);
        this.n = new BigInteger(n, 16);
        this.bytes = bytes;
    }

    public static final Ec P256 = new Ec(
            "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff",
            "ffffffff00000001000000000000000000000000fffffffffffffffffffffffc",
            "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b",
            "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296",
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5",
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 32);

    public static final Ec SECP160R1 = new Ec(
            "ffffffffffffffffffffffffffffffff7fffffff",
            "ffffffffffffffffffffffffffffffff7ffffffc",
            "1c97befc54bd7a8b65acf89f81d4d4adc565fa45",
            "4a96b5688ef573284664698968c38bb913cbfc82",
            "23a628553168947d59dcc912042351377ac5fb32",
            "0100000000000000000001f4c8f927aed3ca752257", 20);

    /** A point; null is the point at infinity. */
    public static final class Pt {
        public final BigInteger x, y;

        public Pt(BigInteger x, BigInteger y) {
            this.x = x;
            this.y = y;
        }
    }

    public Pt add(Pt P, Pt Q) {
        if (P == null) return Q;
        if (Q == null) return P;
        BigInteger l;
        if (P.x.equals(Q.x)) {
            if (P.y.add(Q.y).mod(p).signum() == 0) return null;
            l = P.x.pow(2).multiply(BigInteger.valueOf(3)).add(a).multiply(P.y.shiftLeft(1).modInverse(p)).mod(p);
        } else {
            l = Q.y.subtract(P.y).multiply(Q.x.subtract(P.x).modInverse(p)).mod(p);
        }
        BigInteger x = l.pow(2).subtract(P.x).subtract(Q.x).mod(p);
        BigInteger y = l.multiply(P.x.subtract(x)).subtract(P.y).mod(p);
        return new Pt(x, y);
    }

    public Pt mul(Pt P, BigInteger k) {
        Pt r = null;
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = add(r, r);
            if (k.testBit(i)) r = add(r, P);
        }
        return r;
    }

    public Pt g() {
        return new Pt(gx, gy);
    }

    public boolean onCurve(Pt P) {
        if (P == null) return false;
        return P.y.pow(2).subtract(P.x.pow(3).add(a.multiply(P.x)).add(b)).mod(p).signum() == 0;
    }

    /** X||Y, each {@link #bytes} long. */
    public Pt point(byte[] xy, int off) {
        byte[] x = new byte[bytes], y = new byte[bytes];
        System.arraycopy(xy, off, x, 0, bytes);
        System.arraycopy(xy, off + bytes, y, 0, bytes);
        return new Pt(new BigInteger(1, x), new BigInteger(1, y));
    }

    public byte[] fixed(BigInteger v) {
        byte[] raw = v.toByteArray(), out = new byte[bytes];
        int n = Math.min(raw.length, bytes);
        System.arraycopy(raw, raw.length - n, out, bytes - n, n);
        return out;
    }

    public byte[] encode(Pt P) {
        byte[] out = new byte[bytes * 2];
        System.arraycopy(fixed(P.x), 0, out, 0, bytes);
        System.arraycopy(fixed(P.y), 0, out, bytes, bytes);
        return out;
    }

    public BigInteger randomKey() {
        SecureRandom r = new SecureRandom();
        BigInteger k;
        do {
            k = new BigInteger(n.bitLength(), r);
        } while (k.signum() == 0 || k.compareTo(n) >= 0);
        return k;
    }

    /** The shared secret: X of d * Q, as {@link #bytes} bytes; null if Q is not on the curve. */
    public byte[] ecdh(BigInteger d, Pt Q) {
        if (!onCurve(Q)) return null;
        Pt s = mul(Q, d);
        return s == null ? null : fixed(s.x);
    }
}
