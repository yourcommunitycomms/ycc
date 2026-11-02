package org.communitycomms.app;

import android.content.Context;

import org.communitycomms.app.msg.MessageLog;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tasks and notes, top down: a message on the channel ("[task] " + JSON). "scope" is empty for everyone on the
 * channel, or a team name, or a callsign. The newest revision of an id wins, and only the person who made a
 * task can change it. Everyone can read them.
 */
public final class Tasks {
    public static final String PREFIX = "[task] ";

    private Tasks() {
    }

    public static final class Task {
        public String id = "", by = "", scope = "", text = "", photo = "";
        public long due, rev;
        public boolean removed;
    }

    public static boolean is(String body) {
        return body != null && body.startsWith(PREFIX);
    }

    /** The current tasks on the channels on air, soonest due first. */
    public static List<Task> current(Context c) {
        RadioService s = RadioService.instance();
        if (s == null || s.engine() == null) return new ArrayList<Task>();
        Map<String, Task> best = new LinkedHashMap<String, Task>();
        for (MessageLog.Entry m : MessageLog.get(c).all(s.engine().channelTag(), s.watchTags())) {
            if (!is(m.body)) continue;
            Task t = parse(m);
            if (t == null) continue;
            Task old = best.get(t.id);
            // Someone else cannot take over a task: the first author owns the id.
            if (old != null && (!old.by.equals(t.by) || old.rev >= t.rev)) continue;
            best.put(t.id, t);
        }
        List<Task> out = new ArrayList<Task>();
        for (Task t : best.values()) if (!t.removed) out.add(t);
        Collections.sort(out, new Comparator<Task>() {
            @Override
            public int compare(Task a, Task b) {
                long x = a.due == 0 ? Long.MAX_VALUE : a.due, y = b.due == 0 ? Long.MAX_VALUE : b.due;
                return x < y ? -1 : x > y ? 1 : 0;
            }
        });
        return out;
    }

    private static Task parse(MessageLog.Entry m) {
        try {
            JSONObject o = new JSONObject(m.body.substring(PREFIX.length()));
            Task t = new Task();
            t.id = o.getString("id");
            t.by = m.creator; // as heard on air, never what the text claims
            t.scope = o.optString("to");
            t.text = o.optString("t");
            t.photo = o.optString("p");
            t.due = o.optLong("due");
            t.rev = o.optLong("rev");
            t.removed = o.optBoolean("del");
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean send(Task t) {
        RadioService s = RadioService.instance();
        if (s == null || s.messages() == null) return false;
        try {
            t.rev = System.currentTimeMillis();
            JSONObject o = new JSONObject().put("id", t.id).put("to", t.scope).put("t", t.text).put("rev", t.rev).put("p", t.photo).put("due", t.due);
            if (t.removed) o.put("del", true);
            return s.messages().sendText(PREFIX + o);
        } catch (org.json.JSONException e) {
            return false;
        }
    }
}
