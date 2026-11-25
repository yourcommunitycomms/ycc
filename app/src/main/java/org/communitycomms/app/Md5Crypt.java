package org.communitycomms.app;

import java.security.MessageDigest;

/**
 * The classic "$1$" MD5-crypt password hash (as crypt(3) / openssl passwd -1), which OpenWrt's rpcd accepts for a
 * login's password option. Plain Java. Used only for the read-only "ycc-ro" login, whose password is meant to be
 * shared and read out anyway.
 */
public final class Md5Crypt {
    private static final String ITOA = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private Md5Crypt() {
    }

    public static String newSalt() {
        byte[] r = ProtectedCrypto.random(8);
        StringBuilder s = new StringBuilder();
        for (byte b : r) s.append(ITOA.charAt((b & 0xff) % 64));
        return s.toString();
    }

    public static String crypt(String password, String salt) throws Exception {
        byte[] pw = password.getBytes("UTF-8");
        if (salt.length() > 8) salt = salt.substring(0, 8);
        byte[] sl = salt.getBytes("UTF-8");
        byte[] magic = "$1$".getBytes("UTF-8");
        MessageDigest alt = MessageDigest.getInstance("MD5");
        alt.update(pw);
        alt.update(sl);
        alt.update(pw);
        byte[] fin = alt.digest();
        MessageDigest ctx = MessageDigest.getInstance("MD5");
        ctx.update(pw);
        ctx.update(magic);
        ctx.update(sl);
        for (int pl = pw.length; pl > 0; pl -= 16) ctx.update(fin, 0, Math.min(pl, 16));
        for (int i = pw.length; i != 0; i >>>= 1) {
            if ((i & 1) != 0) ctx.update((byte) 0);
            else ctx.update(pw[0]);
        }
        fin = ctx.digest();
        for (int i = 0; i < 1000; i++) {
            MessageDigest c = MessageDigest.getInstance("MD5");
            if ((i & 1) != 0) c.update(pw);
            else c.update(fin, 0, 16);
            if (i % 3 != 0) c.update(sl);
            if (i % 7 != 0) c.update(pw);
            if ((i & 1) != 0) c.update(fin, 0, 16);
            else c.update(pw);
            fin = c.digest();
        }
        StringBuilder out = new StringBuilder("$1$").append(salt).append('$');
        to64(out, ((fin[0] & 0xff) << 16) | ((fin[6] & 0xff) << 8) | (fin[12] & 0xff), 4);
        to64(out, ((fin[1] & 0xff) << 16) | ((fin[7] & 0xff) << 8) | (fin[13] & 0xff), 4);
        to64(out, ((fin[2] & 0xff) << 16) | ((fin[8] & 0xff) << 8) | (fin[14] & 0xff), 4);
        to64(out, ((fin[3] & 0xff) << 16) | ((fin[9] & 0xff) << 8) | (fin[15] & 0xff), 4);
        to64(out, ((fin[4] & 0xff) << 16) | ((fin[10] & 0xff) << 8) | (fin[5] & 0xff), 4);
        to64(out, fin[11] & 0xff, 2);
        return out.toString();
    }

    private static void to64(StringBuilder s, int v, int n) {
        while (--n >= 0) {
            s.append(ITOA.charAt(v & 0x3f));
            v >>>= 6;
        }
    }
}
