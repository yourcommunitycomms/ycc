package org.communitycomms.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everyone this phone has heard (the Contacts screen): callsign, status, when last seen, battery,
 * last advertised position, and a private note. Kept on this phone only ("contacts" preferences
 * file, so the app-data wipe erases it); nothing here is ever sent.
 */
public final class Contacts {
    /** Heard within this long = online (presence goes out every 5 s). */
    public static final long ONLINE_MS = 30000;

    public static final class Contact {
        public String name = "";
        public long lastSeenMs;
        public int status = -1;
        public int battery = -1;
        public double lat = Double.NaN, lon = Double.NaN;
        public float accuracy;
        public long fixTimeMs;
        /** Their last position was from fake GPS (testing). */
        public boolean fake;
        /** Channel it was last heard on: "A", "B", "Public", "OpenMANET". */
        public String heardOn = "";
        public String address = "";
        /** How often they check in when in a power mode, or 0 (not saved). */
        public long checkInMs;

        public boolean online(long now) {
            return now - lastSeenMs < checkInMs + ONLINE_MS;
        }

        public boolean hasFix() {
            return !Double.isNaN(lat);
        }

        JSONObject toJson() throws org.json.JSONException {
            JSONObject o = new JSONObject();
            o.put("n", name).put("seen", lastSeenMs).put("st", status).put("bat", battery).put("on", heardOn)
                    .put("ip", address).put("fix", fixTimeMs).put("acc", (double) accuracy).put("fake", fake);
            if (hasFix()) o.put("lat", lat).put("lon", lon);
            return o;
        }

        static Contact fromJson(JSONObject o) {
            Contact c = new Contact();
            c.name = o.optString("n");
            c.lastSeenMs = o.optLong("seen");
            c.status = o.optInt("st", -1);
            c.battery = o.optInt("bat", -1);
            c.heardOn = o.optString("on");
            c.address = o.optString("ip");
            c.fixTimeMs = o.optLong("fix");
            c.accuracy = (float) o.optDouble("acc", 0);
            c.fake = o.optBoolean("fake");
            if (o.has("lat")) {
                c.lat = o.optDouble("lat");
                c.lon = o.optDouble("lon");
            }
            return c;
        }
    }

    /** A status someone switched to, for the chat line "X is now Away". */
    public static final class Change {
        public final String name;
        public final int status;

        Change(String name, int status) {
            this.name = name;
            this.status = status;
        }
    }

    private static Contacts instance;
    private final SharedPreferences sp, notes;
    private final Map<String, Contact> byName = new HashMap<String, Contact>();
    private long savedAt;

    public static synchronized Contacts get(Context c) {
        if (instance == null) instance = new Contacts(c.getApplicationContext());
        return instance;
    }

    /** For the app-data wipe (the files themselves go with every other preferences file). */
    public static synchronized void forget() {
        instance = null;
    }

    private Contacts(Context c) {
        sp = c.getSharedPreferences("contacts", Context.MODE_PRIVATE);
        notes = c.getSharedPreferences("contactNotes", Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            try {
                Contact k = Contact.fromJson(new JSONObject(String.valueOf(e.getValue())));
                if (k.name.length() > 0) byName.put(k.name, k);
            } catch (Exception ignored) {
                // damaged entry
            }
        }
    }

    public static String statusName(int s) {
        return s == RadioEngine.STATUS_AWAY ? "Away" : s == RadioEngine.STATUS_STEALTH ? "Stealth"
                : s == RadioEngine.STATUS_AVAILABLE ? "Available" : "";
    }

    /** Text colour for a status: green available, yellow away, red stealth, grey offline. */
    public static int statusColour(int status, boolean online) {
        if (!online) return 0xff9e9e9e;
        return status == RadioEngine.STATUS_AWAY ? 0xffffd54f : status == RadioEngine.STATUS_STEALTH ? 0xffff5252 : 0xffa5d6a7;
    }

    /** Takes in what an engine has heard. Returns status switches worth a chat line. */
    public synchronized List<Change> merge(List<RadioEngine.Station> stations, String on) {
        List<Change> changes = new ArrayList<Change>();
        boolean dirty = false;
        for (RadioEngine.Station s : stations) {
            if (s.name == null || s.name.length() == 0 || "?".equals(s.name)) continue;
            Contact c = byName.get(s.name);
            if (c == null) {
                c = new Contact();
                c.name = s.name;
                byName.put(s.name, c);
                dirty = true;
            }
            if (s.lastSeenMs > c.lastSeenMs) {
                c.lastSeenMs = s.lastSeenMs;
                c.heardOn = on;
                if (s.address != null) c.address = s.address;
            }
            if (s.status >= 0 && s.status != c.status) {
                // Coming on air already away or in stealth is news too; plain "available" at first sight is not.
                if (c.status >= 0 || s.status != RadioEngine.STATUS_AVAILABLE) changes.add(new Change(s.name, s.status));
                c.status = s.status;
                dirty = true;
            }
            if (s.battery >= 0 && s.battery != c.battery) {
                c.battery = s.battery;
                dirty = true;
            }
            if (!Double.isNaN(s.lat) && s.fixTimeMs > c.fixTimeMs) {
                c.lat = s.lat;
                c.lon = s.lon;
                c.accuracy = s.accuracy;
                c.fixTimeMs = s.fixTimeMs;
                c.fake = s.fake;
                dirty = true;
            }
        }
        long now = System.currentTimeMillis();
        if (dirty || now - savedAt > 60000) save(now);
        return changes;
    }

    /** OpenMANET* talkers: only a name and when they were last heard. */
    public synchronized void heard(String name, long when, String on, String address) {
        if (name == null || name.length() == 0) return;
        Contact c = byName.get(name);
        if (c == null) {
            c = new Contact();
            c.name = name;
            byName.put(name, c);
        }
        if (when > c.lastSeenMs) {
            c.lastSeenMs = when;
            c.heardOn = on;
            c.address = address == null ? "" : address;
        }
    }

    /** Online first, then most recently seen. */
    public synchronized List<Contact> all() {
        List<Contact> out = new ArrayList<Contact>(byName.values());
        final long now = System.currentTimeMillis();
        Collections.sort(out, new Comparator<Contact>() {
            @Override
            public int compare(Contact a, Contact b) {
                boolean ao = a.online(now), bo = b.online(now);
                if (ao != bo) return ao ? -1 : 1;
                if (ao) return a.name.compareToIgnoreCase(b.name);
                return a.lastSeenMs > b.lastSeenMs ? -1 : a.lastSeenMs < b.lastSeenMs ? 1 : 0;
            }
        });
        return out;
    }

    public synchronized Contact find(String name) {
        return byName.get(name);
    }

    public synchronized int onlineCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Contact c : byName.values()) if (c.online(now)) n++;
        return n;
    }

    public String note(String name) {
        return notes.getString(name, "");
    }

    public void setNote(String name, String text) {
        if (text.trim().length() == 0) notes.edit().remove(name).commit();
        else notes.edit().putString(name, text).commit();
    }

    /** Removes someone from the list (their note too). They come back if heard again. */
    public synchronized void remove(String name) {
        byName.remove(name);
        sp.edit().remove(name).commit();
        notes.edit().remove(name).commit();
    }

    /** Clear mission data: forget everyone's last position (names, teams and notes stay). */
    public synchronized void clearPositions() {
        for (Contact c : byName.values()) {
            c.lat = Double.NaN;
            c.lon = Double.NaN;
            c.accuracy = 0;
            c.fixTimeMs = 0;
        }
        save(System.currentTimeMillis());
    }

    private void save(long now) {
        savedAt = now;
        SharedPreferences.Editor ed = sp.edit().clear();
        for (Contact c : byName.values()) {
            try {
                ed.putString(c.name, c.toJson().toString());
            } catch (org.json.JSONException ignored) {
            }
        }
        ed.apply();
    }
}
