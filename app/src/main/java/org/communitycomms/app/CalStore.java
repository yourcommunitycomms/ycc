package org.communitycomms.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.communitycomms.app.msg.MessageLog;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calendar and notes (a specialised menu). My calendar entries and my notes stay on this phone; team
 * calendar entries are messages on the channel ("[cal] " + JSON, like Tasks: the newest revision of an
 * id wins and only its author can change it). Reminders (minutes before, with or without a buzz) are
 * alarms on this phone, for my entries and for team entries that ask for one.
 */
public final class CalStore {
    public static final String PREFIX = "[cal] ";

    private CalStore() {
    }

    public static final class Entry {
        public String id = "", title = "", note = "", by = "";
        public long start, rev;
        public int durMin = 60, remindMin = 15;
        public boolean allDay, remind, vibrate = true, team, removed;
    }

    public static boolean is(String body) {
        return body != null && body.startsWith(PREFIX);
    }

    static String newId() {
        return Long.toHexString(new java.security.SecureRandom().nextLong());
    }

    // ---- my calendar ----

    private static File mineFile(Context c) {
        return new File(c.getFilesDir(), "cal-mine.json");
    }

    public static synchronized List<Entry> mine(Context c) {
        List<Entry> out = new ArrayList<Entry>();
        try {
            String t = ChannelArray.read(c, "cal-mine.json");
            if (t.length() == 0) return out;
            JSONArray a = new JSONArray(t);
            for (int i = 0; i < a.length(); i++) out.add(fromJson(a.getJSONObject(i), false));
        } catch (Exception ignored) {
        }
        return out;
    }

    public static synchronized void saveMine(Context c, Entry e) {
        List<Entry> all = mine(c);
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).id.equals(e.id)) all.remove(i);
        if (!e.removed) all.add(e);
        writeMine(c, all);
        schedule(c, e);
    }

    private static void writeMine(Context c, List<Entry> all) {
        JSONArray a = new JSONArray();
        for (Entry e : all) {
            try {
                a.put(toJson(e));
            } catch (Exception ignored) {
            }
        }
        ChannelArray.write(c, "cal-mine.json", a.toString());
    }

    static JSONObject toJson(Entry e) throws org.json.JSONException {
        JSONObject o = new JSONObject().put("id", e.id).put("t", e.title).put("s", e.start).put("d", e.durMin)
                .put("ad", e.allDay).put("n", e.note).put("r", e.remind).put("rm", e.remindMin).put("v", e.vibrate).put("rev", e.rev);
        if (e.removed) o.put("del", true);
        return o;
    }

    static Entry fromJson(JSONObject o, boolean team) {
        Entry e = new Entry();
        e.id = o.optString("id");
        e.title = o.optString("t");
        e.start = o.optLong("s");
        e.durMin = o.optInt("d", 60);
        e.allDay = o.optBoolean("ad");
        e.note = o.optString("n");
        e.remind = o.optBoolean("r");
        e.remindMin = o.optInt("rm", 15);
        e.vibrate = o.optBoolean("v", true);
        e.rev = o.optLong("rev");
        e.removed = o.optBoolean("del");
        e.team = team;
        return e;
    }

    // ---- team calendar (messages on the channels on air) ----

    public static List<Entry> team(Context c) {
        RadioService s = RadioService.instance();
        if (s == null || s.engine() == null) return new ArrayList<Entry>();
        Map<String, Entry> best = new LinkedHashMap<String, Entry>();
        for (MessageLog.Entry m : MessageLog.get(c).all(s.engine().channelTag(), s.watchTags())) {
            Entry e = parse(m);
            if (e == null) continue;
            Entry old = best.get(e.id);
            if (old != null && (!old.by.equals(e.by) || old.rev >= e.rev)) continue; // the first author owns the id
            best.put(e.id, e);
        }
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : best.values()) if (!e.removed) out.add(e);
        return out;
    }

    static Entry parse(MessageLog.Entry m) {
        if (m == null || !is(m.body)) return null;
        try {
            Entry e = fromJson(new JSONObject(m.body.substring(PREFIX.length())), true);
            e.by = m.creator; // as heard on air (mine: this phone's callsign)
            return e;
        } catch (Exception x) {
            return null;
        }
    }

    private static Context ctx;

    public static boolean sendTeam(Context c, Entry e) {
        RadioService s = RadioService.instance();
        if (s == null || s.messages() == null) return false;
        try {
            e.rev = System.currentTimeMillis();
            boolean ok = s.messages().sendText(PREFIX + toJson(e));
            if (ok) schedule(c, e);
            return ok;
        } catch (org.json.JSONException x) {
            return false;
        }
    }

    /** A message arrived: a team entry with a reminder gets its alarm here too. */
    public static void onMessage(Context c, MessageLog.Entry m) {
        ctx = c.getApplicationContext();
        Entry e = parse(m);
        if (e != null) schedule(c, e);
    }

    /** Mine and the team's, sorted by start. */
    public static List<Entry> combined(Context c, boolean mine, boolean team) {
        ctx = c.getApplicationContext();
        List<Entry> out = new ArrayList<Entry>();
        if (mine) out.addAll(mine(c));
        if (team) out.addAll(team(c));
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.start < b.start ? -1 : a.start > b.start ? 1 : 0;
            }
        });
        return out;
    }

    // ---- reminders ----

    static void schedule(Context c, Entry e) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(c, CalReminder.class).putExtra("id", e.id).putExtra("title", e.title)
                .putExtra("start", e.start).putExtra("allDay", e.allDay).putExtra("vibrate", e.vibrate).putExtra("team", e.team)
                .putExtra("by", e.by);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(c, ("cal" + e.id).hashCode(), i, fl);
        long at = e.start - e.remindMin * 60000L;
        if (e.removed || !e.remind || at < System.currentTimeMillis() - 60000) {
            am.cancel(pi);
            return;
        }
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.set(AlarmManager.RTC_WAKEUP, at, pi);
    }

    /** At the app's start: every future reminder set again (they do not survive a restart of the phone). */
    public static void rescheduleAll(Context c) {
        ctx = c.getApplicationContext();
        for (Entry e : mine(c)) schedule(c, e);
        for (Entry e : team(c)) schedule(c, e);
    }

    // ---- notes ----

    public static final class Note {
        public String id = "", name = "", body = "";
        public long date, edited;
    }

    public static File noteImages(Context c) {
        File d = new File(c.getFilesDir(), "note-images");
        d.mkdirs();
        return d;
    }

    public static synchronized List<Note> notes(Context c) {
        List<Note> out = new ArrayList<Note>();
        try {
            String t = ChannelArray.read(c, "notes.json");
            if (t.length() == 0) return out;
            JSONArray a = new JSONArray(t);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Note n = new Note();
                n.id = o.optString("id");
                n.name = o.optString("name");
                n.body = o.optString("body");
                n.date = o.optLong("date");
                n.edited = o.optLong("edited");
                out.add(n);
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out, new Comparator<Note>() {
            @Override
            public int compare(Note a, Note b) {
                return a.date > b.date ? -1 : a.date < b.date ? 1 : 0;
            }
        });
        return out;
    }

    public static synchronized void saveNote(Context c, Note n, boolean delete) {
        List<Note> all = notes(c);
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).id.equals(n.id)) all.remove(i);
        if (!delete) all.add(n);
        JSONArray a = new JSONArray();
        for (Note x : all) {
            try {
                a.put(new JSONObject().put("id", x.id).put("name", x.name).put("body", x.body).put("date", x.date).put("edited", x.edited));
            } catch (Exception ignored) {
            }
        }
        ChannelArray.write(c, "notes.json", a.toString());
    }
}
