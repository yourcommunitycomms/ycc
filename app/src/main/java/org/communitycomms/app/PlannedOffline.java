package org.communitycomms.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.communitycomms.app.msg.MessageLog;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * "Going offline as planned" (the Exit prompt when others are on the channel). One chat message: the words
 * people read, then a hidden line "[offline:BACK_MS]" (0 = no time given). Phones that hear it show the
 * person as offline as planned, hold the lost contact warning for them, and say they are overdue if they
 * are not heard by the time given plus a grace period. Being heard again clears it.
 */
public final class PlannedOffline {
    public static final String TAG = "\n[offline:";
    static final long GRACE_MS = 15 * 60000L;

    private PlannedOffline() {
    }

    public static boolean is(String body) {
        return body != null && body.contains(TAG);
    }

    /** The words people read (the hidden line removed). */
    public static String text(String body) {
        int i = body.indexOf(TAG);
        return i < 0 ? body : body.substring(0, i);
    }

    static String body(String me, long backAt) {
        String words = me + " is offline as planned" + (backAt > 0 ? ", back about " + when(backAt) : "") + ".";
        return words + TAG + backAt + "]";
    }

    static String when(long ms) {
        boolean today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(ms))
                .equals(new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()));
        return new SimpleDateFormat(today ? "HH:mm" : "EEE HH:mm", Locale.getDefault()).format(new Date(ms));
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("plannedOffline", Context.MODE_PRIVATE);
    }

    /** Heard on the channel: remember who went offline as planned, when, and until when. */
    static void onMessage(Context c, MessageLog.Entry m) {
        if (m == null || m.mine || !is(m.body) || m.creator == null) return;
        long back = 0;
        try {
            String s = m.body.substring(m.body.indexOf(TAG) + TAG.length());
            back = Long.parseLong(s.substring(0, s.indexOf(']')));
        } catch (Exception ignored) {
        }
        sp(c).edit().putString(m.creator, System.currentTimeMillis() + "," + back + ",0").commit();
    }

    private static long[] get(Context c, String name) {
        String v = sp(c).getString(name, null);
        if (v == null) return null;
        try {
            String[] p = v.split(",");
            return new long[]{Long.parseLong(p[0]), Long.parseLong(p[1]), p.length > 2 ? Long.parseLong(p[2]) : 0};
        } catch (Exception e) {
            return null;
        }
    }

    /** Offline as planned and not heard since it was said. */
    public static boolean planned(Context c, String name, long lastSeenMs) {
        long[] v = get(c, name);
        return v != null && lastSeenMs <= v[0] + 5000;
    }

    /** For contact lists: "offline as planned, back about 18:30" (or overdue), or "" when nothing planned. */
    public static String describe(Context c, String name, long lastSeenMs) {
        long[] v = get(c, name);
        if (v == null || lastSeenMs > v[0] + 5000) return "";
        if (v[1] > 0 && System.currentTimeMillis() > v[1] + GRACE_MS) return "overdue (planned back " + when(v[1]) + ")";
        return "offline as planned" + (v[1] > 0 ? ", back about " + when(v[1]) : "");
    }

    /**
     * About once a minute: forget people heard again, and name those past their time plus the grace
     * (once each). Returns the names newly overdue.
     */
    static java.util.List<String> check(Context c) {
        java.util.List<String> overdue = new java.util.ArrayList<String>();
        long now = System.currentTimeMillis();
        SharedPreferences.Editor ed = null;
        for (Map.Entry<String, ?> e : sp(c).getAll().entrySet()) {
            String name = e.getKey();
            long[] v = get(c, name);
            if (v == null) continue;
            Contacts.Contact k = Contacts.get(c).find(name);
            long seen = k == null ? 0 : k.lastSeenMs;
            if (seen > v[0] + 5000 || now - v[0] > 7 * 86400000L) { // back (or a week old): forget it
                if (ed == null) ed = sp(c).edit();
                ed.remove(name);
                continue;
            }
            if (v[1] > 0 && v[2] == 0 && now > v[1] + GRACE_MS) {
                overdue.add(name);
                if (ed == null) ed = sp(c).edit();
                ed.putString(name, v[0] + "," + v[1] + ",1");
            }
        }
        if (ed != null) ed.commit();
        return overdue;
    }
}
