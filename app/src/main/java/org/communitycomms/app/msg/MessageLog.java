package org.communitycomms.app.msg;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything that went through the chat view: text messages, reports, POIs and files, sent and
 * received, plus scheduled reports waiting for their send time and "missed" placeholders learnt
 * from catch-up sync. Kept on the phone (one JSON object per line) so it survives restarts.
 */
public final class MessageLog {
    public static final int TYPE_TEXT = 1, TYPE_REPORT = 2, TYPE_POI = 3, TYPE_FILE = 4;
    /** Normal entry, waiting for its scheduled send time, or only known from a catch-up digest. */
    public static final int STATE_DONE = 0, STATE_SCHEDULED = 1, STATE_MISSING = 2;
    private static final int MAX_ENTRIES = 3000;

    public static final class Entry {
        public long id;
        public int type;
        public int version;
        /** Local clock time it was sent (or, for a scheduled one, when it is due). */
        public long time;
        public String creator = "";
        public int channelTag;
        /** Text body, serialized report, POI note or file name. */
        public String body = "";
        /** Received / sent file on this phone, if any. */
        public String path = "";
        public String mime = "";
        public boolean mine;
        public int state;
        /** POI was removed (kept as a tombstone so an old copy is not re-added). */
        public boolean removed;
        /** An automatic "message received" reply (acknowledgement mode); never acknowledged itself. */
        public boolean ack;
        /** Private message: the other person's callsign is in creator (received) or here (sent); "" = public. */
        public String privateTo = "";
        /** Kept on this phone only (e.g. a dual-watch voice recording): never advertised in catch-up. */
        public boolean local;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", Long.toHexString(id)).put("t", type).put("v", version).put("time", time)
                    .put("by", creator).put("ch", channelTag).put("body", body).put("path", path)
                    .put("mime", mime).put("mine", mine).put("st", state).put("rm", removed).put("ack", ack).put("pm", privateTo).put("loc", local);
            return o;
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.id = new java.math.BigInteger(o.optString("id", "0"), 16).longValue();
            e.type = o.optInt("t");
            e.version = o.optInt("v");
            e.time = o.optLong("time");
            e.creator = o.optString("by");
            e.channelTag = o.optInt("ch");
            e.body = o.optString("body");
            e.path = o.optString("path");
            e.mime = o.optString("mime");
            e.mine = o.optBoolean("mine");
            e.state = o.optInt("st");
            e.removed = o.optBoolean("rm");
            e.ack = o.optBoolean("ack");
            e.privateTo = o.optString("pm");
            e.local = o.optBoolean("loc");
            return e;
        }

        public boolean isImage() {
            return type == TYPE_FILE && mime.startsWith("image/");
        }
    }

    private static MessageLog instance;
    private final File file;
    private final Map<Long, Entry> entries = new LinkedHashMap<Long, Entry>();
    /** Screens showing the log (chat, reports) refresh through this. */
    public static volatile Runnable ui;

    public static synchronized MessageLog get(Context c) {
        if (instance == null) instance = new MessageLog(c.getApplicationContext());
        return instance;
    }

    /** For the app-data wipe. */
    public static synchronized void forget() {
        instance = null;
    }

    public static String fileName() {
        return "messages.jsonl";
    }

    private MessageLog(Context c) {
        file = new File(c.getFilesDir(), fileName());
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String l;
            while ((l = r.readLine()) != null) {
                try {
                    Entry e = Entry.fromJson(new JSONObject(l));
                    entries.put(e.id, e);
                } catch (Exception ignored) {
                    // skip a damaged line
                }
            }
            r.close();
        } catch (Exception ignored) {
            // no file yet
        }
    }

    /** Messages, reports and files that arrived while Chat was not open (cleared when it opens). */
    public static volatile int unread;

    public synchronized Entry find(long id) {
        return entries.get(id);
    }

    /** Oldest first. channelTag 0 = every channel. */
    public synchronized List<Entry> all(int channelTag) {
        return all(channelTag, -1);
    }

    /** Oldest first, entries of either channel (dual watch shows both). */
    public synchronized List<Entry> all(int channelTag, int alsoTag) {
        return all(channelTag, new int[]{alsoTag});
    }

    /** Oldest first: the channel on air and every channel listened to. */
    public synchronized List<Entry> all(int channelTag, int[] alsoTags) {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : entries.values()) {
            boolean also = false;
            for (int t : alsoTags) also |= e.channelTag == t;
            if (channelTag == 0 || e.channelTag == channelTag || also) out.add(e);
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.time < b.time ? -1 : a.time > b.time ? 1 : 0;
            }
        });
        return out;
    }

    /** Entries done (not scheduled or missing) since a time, for catch-up digests. */
    public synchronized List<Entry> since(long t, int channelTag) {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : entries.values()) {
            if (e.state == STATE_DONE && e.time >= t && e.channelTag == channelTag) out.add(e);
        }
        return out;
    }

    /** Time of the newest message from someone else on a channel (0 if none in the last day). */
    public synchronized long lastReceived(int channelTag) {
        long t = 0;
        for (Entry e : entries.values()) {
            if (e.state == STATE_DONE && !e.mine && !e.ack && e.channelTag == channelTag && e.time > t) t = e.time;
        }
        return t;
    }

    public synchronized List<Entry> scheduled() {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : entries.values()) if (e.state == STATE_SCHEDULED) out.add(e);
        return out;
    }

    /**
     * Adds or replaces. A real entry replaces a missing placeholder; for POIs a newer version
     * replaces an older one. Returns true if anything changed.
     */
    public synchronized boolean put(Entry e) {
        Entry old = entries.get(e.id);
        if (old != null && old.state == STATE_DONE && e.state != STATE_SCHEDULED) {
            if (e.state == STATE_MISSING) return false;
            if (e.type != TYPE_POI || e.version <= old.version) return false;
        }
        if (old != null) entries.remove(e.id); // re-insert at the end
        // New, or filling a "missed" placeholder (catch-up): unread until Chat is opened.
        if ((old == null || old.state == STATE_MISSING) && !e.mine && !e.ack && e.type != TYPE_POI
                && e.state == STATE_DONE && !org.communitycomms.app.RadioService.chatVisible) {
            unread++;
        }
        entries.put(e.id, e);
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
        save();
        changed();
        return true;
    }

    public synchronized void remove(long id) {
        if (entries.remove(id) != null) {
            save();
            changed();
        }
    }

    public synchronized void update(Entry e) {
        entries.put(e.id, e);
        save();
        changed();
    }

    private static void changed() {
        Runnable r = ui;
        if (r != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    private void save() {
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            for (Entry e : entries.values()) w.write(e.toJson().toString() + "\n");
            w.close();
        } catch (Exception ignored) {
        }
    }
}
