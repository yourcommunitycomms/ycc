package org.communitycomms.app;

import android.content.Context;

import org.communitycomms.app.crypto.ListenKey;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reports to leaders from a phone with the read-only Public key: locked to the key in the listen key, so only
 * full key holders can open them. Each carries an approved code issued by leaders (ListenKey.memberCode):
 * anything without a valid code, or with a blocked one, is dropped before it can alert anyone.
 */
public final class ReportUp {
    private ReportUp() {
    }

    public static final int LOCATION = 0, OK = 1, HELP = 2, SOS = 3, TEXT = 4, TALK = 5;
    public static final String[] NAMES = {"Location", "OK", "NEED HELP", "SOS", "Message", "Asks to talk"};
    public static final String ACK = "[up-ack:";
    private static final int SOS_REPEATS = 20;
    private static final long SOS_GAP_MS = 30000;

    /** Sent id -> delivered yet, newest last, for the Report screen. */
    private static final Map<Long, Boolean> sent = new LinkedHashMap<Long, Boolean>();

    public static synchronized boolean delivered(long id) {
        return Boolean.TRUE.equals(sent.get(id));
    }

    /** A leader's phone confirmed one (from Chat: "[up-ack:id]"). */
    public static synchronized void acked(String body) {
        try {
            sent.put(new java.math.BigInteger(body.substring(ACK.length(), body.indexOf(']')), 16).longValue(), true);
        } catch (RuntimeException ignored) {
        }
    }

    /** Sends it; an SOS repeats every 30 seconds until a leader's phone confirms. Null, or why it cannot go. */
    public static String send(final Context c, final int kind, final String text, final android.location.Location at) {
        Prefs pr = new Prefs(c);
        final String code = pr.reportCode();
        final RadioService s = RadioService.instance();
        if (code.length() == 0) return "This phone has no approved code from your leaders";
        if (s == null || !s.canReportUp()) return "Join a channel with the read-only Public key first";
        final long id = new java.security.SecureRandom().nextLong();
        JSONObject o = new JSONObject();
        try {
            o.put("k", kind).put("id", id).put("c", code).put("n", pr.airName()).put("t", text == null ? "" : text)
                    .put("at", System.currentTimeMillis()).put("b", RadioEngine.myBattery);
            if (at != null) o.put("la", at.getLatitude()).put("lo", at.getLongitude());
        } catch (JSONException e) {
            return "Could not make the report";
        }
        final String plain = o.toString();
        synchronized (ReportUp.class) {
            sent.put(id, false);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                int times = kind == SOS ? SOS_REPEATS : 3;
                for (int i = 0; i < times && !delivered(id); i++) {
                    s.sendUp(plain);
                    try {
                        Thread.sleep(kind == SOS ? SOS_GAP_MS : 5000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "report-up").start();
        lastId = id;
        return null;
    }

    public static volatile long lastId;

    // ---- on a leader's phone ----

    private static final Set<Long> seen = new HashSet<Long>();
    private static long windowStart;
    private static int inWindow;

    /** At most this many opened a second, so a flood cannot flatten a leader's battery. */
    private static synchronized boolean allow() {
        long now = System.currentTimeMillis();
        if (now - windowStart > 1000) {
            windowStart = now;
            inWindow = 0;
        }
        return ++inWindow <= 5;
    }

    /** One packet, on the receive thread. Null when it must be ignored. */
    public static JSONObject open(Context c, ListenKey lk, byte[] sealed) {
        if (!lk.canSend() || !allow()) return null;
        byte[] plain = lk.openReport(sealed);
        if (plain == null) return null;
        try {
            JSONObject o = new JSONObject(new String(plain, "UTF-8"));
            String code = o.optString("c");
            if (!lk.checkMember(code) || blocked(c).contains(code)) return null;
            synchronized (seen) {
                if (!seen.add(o.getLong("id"))) return null;
            }
            return Math.abs(System.currentTimeMillis() - o.optLong("at")) > 24 * 3600000L ? null : o;
        } catch (Exception e) {
            return null;
        }
    }

    public static Set<String> blocked(Context c) {
        String[] p = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("upBlocked", "").split(",");
        Set<String> out = new HashSet<String>();
        for (String s : p) if (s.length() > 0) out.add(s);
        return out;
    }

    public static void setBlocked(Context c, String code, boolean on) {
        Set<String> all = blocked(c);
        if (on) all.add(code);
        else all.remove(code);
        StringBuilder sb = new StringBuilder();
        for (String s : all) sb.append(sb.length() == 0 ? "" : ",").append(s);
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("upBlocked", sb.toString()).commit();
    }
}
