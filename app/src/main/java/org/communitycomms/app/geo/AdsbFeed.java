package org.communitycomms.app.geo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Aircraft on the map (ADS-B). The best source is a receiver on the MANET (an RTL-SDR stick on a
 * node running readsb / dump1090 / tar1090), read over the local network as aircraft.json; with
 * internet, the free community feeds adsb.lol or airplanes.live can be used instead (they are sent
 * the map centre, nothing else). Off by default; slow mode (default) checks online feeds every
 * 5 minutes. One phone can share what it fetches over the radio, so the others need no internet.
 */
public final class AdsbFeed {
    public static final int OFF = 0, LOCAL = 1, ADSB_LOL = 2, AIRPLANES_LIVE = 3;
    static final String[] NAMES = {"Off",
            "Receiver on the MANET (aircraft.json address)",
            "Online: adsb.lol (free, needs internet)",
            "Online: airplanes.live (free, needs internet)"};

    public static final class Aircraft {
        public String hex = "", flight = "", type = "";
        public double lat, lon;
        public int altFt = Integer.MIN_VALUE, speedKt = -1, trackDeg = -1;
        public boolean ground;
        public long heardAt;
    }

    private final SharedPreferences sp;
    // One fetcher for the whole app (maps, TV and the sharing service), so nothing is fetched twice.
    private static volatile List<Aircraft> latest = new ArrayList<Aircraft>();
    private static volatile String problem = "";
    private static Thread thread;
    private static volatile double centreLat, centreLon;
    private static final List<Runnable> listeners = new ArrayList<Runnable>();
    /** Aircraft other phones shared over the radio: hex → aircraft, with when it was heard. */
    private static final java.util.Map<String, Aircraft> received = new java.util.HashMap<String, Aircraft>();
    /** Set by RadioService while "Share ADS-B data" is on: sends each fresh fetch to the channel. */
    public static volatile Sharer sharer;

    public interface Sharer {
        void share(List<Aircraft> aircraft);
    }

    public AdsbFeed(Context c) {
        sp = c.getSharedPreferences("adsb", Context.MODE_PRIVATE);
    }

    /** Off unless chosen (no data used by default). */
    public int source() {
        return sp.getInt("source", OFF);
    }

    /** Layer button: off, or back on with the source used before. False if none has been chosen yet. */
    public boolean setOn(boolean on) {
        int s = source();
        if (!on) {
            if (s != OFF) sp.edit().putInt("lastSource", s).putInt("source", OFF).commit();
        } else if (s == OFF) {
            int last = sp.getInt("lastSource", OFF);
            if (last == OFF) return false;
            sp.edit().putInt("source", last).commit();
        }
        restart();
        return true;
    }
    public String localUrl() {
        return sp.getString("url", "http://10.41.0.1:8080/data/aircraft.json");
    }

    /** Slow mode (default): online feeds checked every 5 minutes to keep data use low. */
    public boolean slow() {
        return sp.getBoolean("slow", true);
    }

    /** Share what this phone fetches with everyone on the channel (they need no internet). */
    public boolean share() {
        return sp.getBoolean("share", false);
    }

    private long gapMs() {
        int s = source();
        if (s == LOCAL) return slow() ? 30000 : 5000;
        return slow() ? 5 * 60000L : 15000;
    }

    /** My own feed plus aircraft shared by others in the last 10 minutes (newest copy of each wins). */
    public List<Aircraft> aircraft() {
        java.util.Map<String, Aircraft> all = new java.util.LinkedHashMap<String, Aircraft>();
        long now = System.currentTimeMillis();
        synchronized (received) {
            java.util.Iterator<Aircraft> it = received.values().iterator();
            while (it.hasNext()) {
                Aircraft a = it.next();
                if (now - a.heardAt > 10 * 60000L) it.remove();
                else all.put(a.hex, a);
            }
        }
        if (source() != OFF) for (Aircraft a : latest) all.put(a.hex, a);
        return new ArrayList<Aircraft>(all.values());
    }

    public String problem() {
        return source() == OFF ? "" : problem;
    }

    /** Where online feeds look (the map centre, or my position), 100 nautical miles round. */
    public void setCentre(double lat, double lon) {
        centreLat = lat;
        centreLon = lon;
    }

    /** From the radio: aircraft someone else fetched. */
    public static void onShared(List<Aircraft> list) {
        long now = System.currentTimeMillis();
        synchronized (received) {
            for (Aircraft a : list) {
                a.heardAt = now;
                received.put(a.hex, a);
            }
        }
        notifyListeners();
    }

    private static void notifyListeners() {
        List<Runnable> copy;
        synchronized (listeners) {
            copy = new ArrayList<Runnable>(listeners);
        }
        for (Runnable r : copy) r.run();
    }

    private Runnable mine;

    /** Starts (or joins) the fetcher; onUpdate runs on its thread after each check. */
    public void start(final Runnable onUpdate) {
        stop();
        mine = onUpdate != null ? onUpdate : new Runnable() {
            @Override
            public void run() {
            }
        };
        synchronized (listeners) {
            listeners.add(mine);
        }
        synchronized (AdsbFeed.class) {
            if (source() == OFF || thread != null || !org.communitycomms.app.PowerMode.feedsOn()) return;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!Thread.currentThread().isInterrupted() && source() != OFF) {
                        long wait = gapMs();
                        try {
                            latest = fetch();
                            for (Aircraft a : latest) a.heardAt = System.currentTimeMillis();
                            problem = "";
                            Sharer s = sharer;
                            if (s != null && share()) s.share(latest);
                        } catch (InterruptedException e) {
                            break;
                        } catch (Exception e) {
                            problem = "Aircraft feed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                            if (problem.contains("429")) wait = Math.max(wait, 60000);
                        }
                        notifyListeners();
                        try {
                            Thread.sleep(wait);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    synchronized (AdsbFeed.class) {
                        if (thread == Thread.currentThread()) thread = null;
                    }
                }
            }, "adsb");
            thread.start();
        }
    }

    /** Leaves the fetcher; it stops when nobody (map, TV or sharing) needs it. */
    public void stop() {
        boolean none;
        synchronized (listeners) {
            if (mine != null) listeners.remove(mine);
            mine = null;
            none = listeners.isEmpty();
        }
        synchronized (AdsbFeed.class) {
            if (none && thread != null) {
                thread.interrupt();
                thread = null;
            }
        }
    }

    /** After the settings change: start again with the new source and speed. */
    public static synchronized void restart() {
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }
    private List<Aircraft> fetch() throws Exception {
        int s = source();
        String url;
        if (s == LOCAL) {
            url = localUrl();
        } else {
            String base = s == ADSB_LOL ? "https://api.adsb.lol/v2/point/" : "https://api.airplanes.live/v2/point/";
            url = base + String.format(Locale.US, "%.4f/%.4f/100", centreLat, centreLon);
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "YourCommunityCommunications");
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new Exception("the server answered " + code + (code == 429 ? " (too many requests, slowing down)" : ""));
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 8 * 1024 * 1024) throw new Exception("reply too big");
            }
            return parse(new String(out.toByteArray(), "UTF-8"));
        } finally {
            c.disconnect();
        }
    }

    /** readsb / dump1090 aircraft.json ("aircraft") or the v2 API ("ac"); the fields are the same. */
    static List<Aircraft> parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONArray a = o.optJSONArray("aircraft");
        if (a == null) a = o.optJSONArray("ac");
        List<Aircraft> out = new ArrayList<Aircraft>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null || !x.has("lat") || !x.has("lon")) continue;
            if (x.optDouble("seen_pos", 0) > 60) continue; // position over a minute old
            Aircraft ac = new Aircraft();
            ac.hex = x.optString("hex", "").trim();
            ac.flight = x.optString("flight", "").trim();
            ac.type = x.optString("t", "").trim();
            ac.lat = x.optDouble("lat");
            ac.lon = x.optDouble("lon");
            Object alt = x.opt("alt_baro");
            if (alt == null) alt = x.opt("altitude");
            if ("ground".equals(alt)) ac.ground = true;
            else if (alt instanceof Number) ac.altFt = ((Number) alt).intValue();
            if (x.has("gs")) ac.speedKt = (int) Math.round(x.optDouble("gs"));
            else if (x.has("speed")) ac.speedKt = x.optInt("speed");
            if (x.has("track")) ac.trackDeg = (int) Math.round(x.optDouble("track"));
            out.add(ac);
        }
        return out;
    }

    public static String label(Aircraft a) {
        String id = a.flight.length() > 0 ? a.flight : a.hex.toUpperCase(Locale.US);
        if (a.ground) return id + " (ground)";
        if (a.altFt != Integer.MIN_VALUE) return id + " " + (a.altFt >= 10000 ? "FL" + (a.altFt / 100) : a.altFt + " ft");
        return id;
    }

    public static String details(Aircraft a) {
        StringBuilder s = new StringBuilder();
        s.append("Aircraft ").append(a.hex.toUpperCase(Locale.US));
        if (a.flight.length() > 0) s.append("\nFlight ").append(a.flight);
        if (a.type.length() > 0) s.append("\nType ").append(a.type);
        if (a.ground) s.append("\nOn the ground");
        else if (a.altFt != Integer.MIN_VALUE) s.append("\nAltitude ").append(a.altFt).append(" ft");
        if (a.speedKt >= 0) s.append("\nSpeed ").append(a.speedKt).append(" knots");
        if (a.trackDeg >= 0) s.append("\nHeading ").append(a.trackDeg).append("°");
        s.append(String.format(Locale.US, "\n%.5f, %.5f", a.lat, a.lon));
        return s.toString();
    }

    /** Settings for the aircraft layer: off, a receiver on the MANET, or a free online feed. */
    public void choose(final Activity a, final Runnable done) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        final RadioGroup g = new RadioGroup(a);
        for (int i = 0; i < NAMES.length; i++) {
            RadioButton r = new RadioButton(a);
            r.setText(NAMES[i]);
            r.setId(0x7a00 + i);
            g.addView(r);
        }
        g.check(0x7a00 + source());
        TextView heard = new TextView(a);
        heard.setText("Off still shows aircraft another phone shares on the channel.");
        box.addView(heard);
        box.addView(g);
        final EditText url = new EditText(a);
        url.setSingleLine(true);
        url.setText(localUrl());
        box.addView(url);
        final android.widget.CheckBox slow = new android.widget.CheckBox(a);
        slow.setText("Slow mode: check every 5 minutes (MANET receiver every 30 s) to save data");
        slow.setChecked(slow());
        box.addView(slow);
        final android.widget.CheckBox share = new android.widget.CheckBox(a);
        share.setText("Share ADS-B data: send what this phone gets to everyone on the channel (they need no internet)");
        share.setChecked(share());
        box.addView(share);
        TextView note = new TextView(a);
        note.setText("A receiver on the MANET (readsb, dump1090 or tar1090) works with no internet: give the address "
                + "of its aircraft.json, often http://node:8080/data/aircraft.json or http://node/tar1090/data/aircraft.json.\n\n"
                + "The online feeds are sent the map centre to find aircraft within 100 nautical miles. They may not work on "
                + "Android 4.x (old secure connections).\n\nShips (AIS): there is no free keyless online source, so ships are "
                + "not shown yet; an AIS receiver on the MANET could be added later.");
        box.addView(note);
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a)
                .setTitle("Aircraft on the map (ADS-B)")
                .setView(sv)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        sp.edit().putInt("source", Math.max(0, g.getCheckedRadioButtonId() - 0x7a00))
                                .putString("url", url.getText().toString().trim())
                                .putBoolean("slow", slow.isChecked()).putBoolean("share", share.isChecked()).commit();
                        latest = new ArrayList<Aircraft>();
                        restart();
                        if (done != null) done.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
