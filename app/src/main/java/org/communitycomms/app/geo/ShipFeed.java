package org.communitycomms.app.geo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
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
 * Ships on the map (AIS) from Open Waters (ais.openwaters.io, free, open government feeds and
 * volunteer receivers). Asks for a box about 100 km round the map centre (well under the anonymous
 * 100 square degrees and 120 requests a minute), only ships heard in the last 30 minutes, and keeps
 * those within 100 km. Off by default; slow mode (default) checks every 5 minutes. A personal key is
 * optional. If the first answer has no ships at all (inland), it turns itself off and says so.
 * One phone can share what it gets over the radio, like aircraft.
 */
public final class ShipFeed {
    public static final class Ship {
        public int mmsi;
        public String name = "";
        public double lat, lon;
        /** Speed in knots and course in degrees, or -1 when unknown. */
        public float sog = -1, cog = -1;
        public int type;
        public long heardAt;
    }

    private static final double RANGE_M = 100000;
    private final SharedPreferences sp;
    private static volatile List<Ship> latest = new ArrayList<Ship>();
    private static volatile String problem = "";
    /** Set when the feed turned itself off (no ships at all): shown once by the map. */
    public static volatile String autoOffNote;
    private static Thread thread;
    private static volatile double centreLat = Double.NaN, centreLon = Double.NaN;
    private static final List<Runnable> listeners = new ArrayList<Runnable>();
    private static final java.util.Map<Integer, Ship> received = new java.util.HashMap<Integer, Ship>();
    public static volatile Sharer sharer;

    public interface Sharer {
        void share(List<Ship> ships);
    }

    public ShipFeed(Context c) {
        sp = c.getSharedPreferences("ais", Context.MODE_PRIVATE);
    }

    public boolean enabled() {
        return sp.getBoolean("enabled", false);
    }

    public String key() {
        return sp.getString("key", "").trim();
    }

    public boolean slow() {
        return sp.getBoolean("slow", true);
    }

    public boolean share() {
        return sp.getBoolean("share", false);
    }

    public String problem() {
        return enabled() ? problem : "";
    }

    public void setCentre(double lat, double lon) {
        centreLat = lat;
        centreLon = lon;
    }

    /** My own ships plus ones others shared in the last 15 minutes. */
    public List<Ship> ships() {
        java.util.Map<Integer, Ship> all = new java.util.LinkedHashMap<Integer, Ship>();
        long now = System.currentTimeMillis();
        synchronized (received) {
            java.util.Iterator<Ship> it = received.values().iterator();
            while (it.hasNext()) {
                Ship s = it.next();
                if (now - s.heardAt > 15 * 60000L) it.remove();
                else all.put(s.mmsi, s);
            }
        }
        if (enabled()) for (Ship s : latest) all.put(s.mmsi, s);
        return new ArrayList<Ship>(all.values());
    }

    public static void onShared(List<Ship> list) {
        long now = System.currentTimeMillis();
        synchronized (received) {
            for (Ship s : list) {
                s.heardAt = now;
                received.put(s.mmsi, s);
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

    public void start(Runnable onUpdate) {
        stop();
        mine = onUpdate != null ? onUpdate : new Runnable() {
            @Override
            public void run() {
            }
        };
        synchronized (listeners) {
            listeners.add(mine);
        }
        synchronized (ShipFeed.class) {
            if (!enabled() || thread != null) return;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean first = true;
                    while (!Thread.currentThread().isInterrupted() && enabled()) {
                        long wait = slow() ? 5 * 60000L : 60000L;
                        if (Double.isNaN(centreLat)) {
                            wait = 5000; // no position yet
                        } else {
                            try {
                                List<Ship> got = fetch();
                                if (first && got.isEmpty()) {
                                    // Nothing at all near here (inland): stop using data on it.
                                    sp.edit().putBoolean("enabled", false).commit();
                                    autoOffNote = "No ships were found within 100 km, so Ships (AIS) has been turned off. "
                                            + "Turn it on again from the map menu when near the coast or a waterway.";
                                    latest = new ArrayList<Ship>();
                                    notifyListeners();
                                    break;
                                }
                                first = false;
                                long now = System.currentTimeMillis();
                                for (Ship s : got) s.heardAt = now;
                                latest = got;
                                problem = "";
                                Sharer sh = sharer;
                                if (sh != null && share()) sh.share(got);
                            } catch (InterruptedException e) {
                                break;
                            } catch (Exception e) {
                                problem = "Ships feed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                                if (problem.contains("429")) wait = Math.max(wait, 5 * 60000L);
                            }
                            notifyListeners();
                        }
                        try {
                            Thread.sleep(wait);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    synchronized (ShipFeed.class) {
                        if (thread == Thread.currentThread()) thread = null;
                    }
                }
            }, "ais");
            thread.start();
        }
    }

    public void stop() {
        boolean none;
        synchronized (listeners) {
            if (mine != null) listeners.remove(mine);
            mine = null;
            none = listeners.isEmpty();
        }
        synchronized (ShipFeed.class) {
            if (none && thread != null) {
                thread.interrupt();
                thread = null;
            }
        }
    }

    public static synchronized void restart() {
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private List<Ship> fetch() throws Exception {
        double lat = centreLat, lon = centreLon;
        double dLat = RANGE_M / 111320.0;
        double dLon = Math.min(10, dLat / Math.max(0.1, Math.cos(Math.toRadians(lat))));
        String bbox = String.format(Locale.US, "%.3f,%.3f,%.3f,%.3f", Math.max(-90, lat - dLat), Math.max(-180, lon - dLon),
                Math.min(90, lat + dLat), Math.min(180, lon + dLon));
        HttpURLConnection c = (HttpURLConnection) new URL("https://ais.openwaters.io/v1/vessels?bbox=" + bbox
                + "&max_age=30m").openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "YourCommunityCommunications");
        if (key().length() > 0) c.setRequestProperty("Authorization", "Bearer " + key());
        try {
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new Exception("the key was refused (" + code + ")");
            if (code >= 400) throw new Exception("the server answered " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 4 * 1024 * 1024) throw new Exception("reply too big");
            }
            return parse(new String(out.toByteArray(), "UTF-8"), lat, lon);
        } finally {
            c.disconnect();
        }
    }

    /** GeoJSON features (Point, properties mmsi / name / sog / cog / type), kept within 100 km. */
    static List<Ship> parse(String json, double lat, double lon) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONArray f = o.optJSONArray("features");
        List<Ship> out = new ArrayList<Ship>();
        if (f == null) return out;
        for (int i = 0; i < f.length(); i++) {
            JSONObject feat = f.optJSONObject(i);
            if (feat == null) continue;
            JSONObject g = feat.optJSONObject("geometry");
            JSONObject p = feat.optJSONObject("properties");
            if (g == null || p == null) continue;
            if (p.has("kind") && !"vessel".equals(p.optString("kind"))) continue; // receiving stations, aids to navigation
            JSONArray xy = g.optJSONArray("coordinates");
            if (xy == null || xy.length() < 2) continue;
            Ship s = new Ship();
            s.lon = xy.optDouble(0);
            s.lat = xy.optDouble(1);
            if (Geo.distanceM(lat, lon, s.lat, s.lon) > RANGE_M) continue;
            s.mmsi = p.optInt("mmsi");
            s.name = p.optString("name", "").trim();
            if (p.has("sog")) s.sog = (float) p.optDouble("sog");
            if (p.has("cog")) s.cog = (float) p.optDouble("cog");
            s.type = p.optInt("type", 0);
            out.add(s);
        }
        return out;
    }

    public static String label(Ship s) {
        String n = s.name.length() > 0 ? s.name : "MMSI " + s.mmsi;
        return s.sog >= 0.5f ? n + String.format(Locale.US, " %.0f kn", s.sog) : n;
    }

    public static String details(Ship s) {
        StringBuilder b = new StringBuilder();
        b.append(s.name.length() > 0 ? s.name : "Ship").append("\nMMSI ").append(s.mmsi);
        if (s.type > 0) b.append("\nType ").append(typeName(s.type)).append(" (").append(s.type).append(")");
        if (s.sog >= 0) b.append(String.format(Locale.US, "\nSpeed %.1f knots", s.sog));
        if (s.cog >= 0) b.append(String.format(Locale.US, "\nCourse %.0f°", s.cog));
        b.append(String.format(Locale.US, "\n%.5f, %.5f", s.lat, s.lon));
        b.append("\n\nData: Open Waters AIS (openwaters.io/ais)");
        return b.toString();
    }

    /** ITU ship type groups. */
    static String typeName(int t) {
        if (t == 30) return "fishing";
        if (t == 31 || t == 32 || t == 52) return "towing / tug";
        if (t == 35) return "military";
        if (t == 36) return "sailing";
        if (t == 37) return "pleasure craft";
        if (t >= 40 && t < 50) return "high speed craft";
        if (t == 50) return "pilot";
        if (t == 51) return "search and rescue";
        if (t == 55) return "law enforcement";
        if (t == 58) return "medical";
        if (t >= 60 && t < 70) return "passenger";
        if (t >= 70 && t < 80) return "cargo";
        if (t >= 80 && t < 90) return "tanker";
        return "other";
    }

    /** Map menu > Ships (AIS)…: on / off, optional key, slow mode, share. */
    public void choose(final Activity a, final Runnable done) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        final CheckBox on = new CheckBox(a);
        on.setText("Show ships from Open Waters (needs internet, or another phone sharing)");
        on.setChecked(enabled());
        box.addView(on);
        TextView kl = new TextView(a);
        kl.setText("Personal key (optional; leave empty to use anonymous access)");
        box.addView(kl);
        final EditText key = new EditText(a);
        key.setSingleLine(true);
        key.setText(key());
        box.addView(key);
        final CheckBox slow = new CheckBox(a);
        slow.setText("Slow mode: check every 5 minutes (otherwise every minute) to save data");
        slow.setChecked(slow());
        box.addView(slow);
        final CheckBox share = new CheckBox(a);
        share.setText("Share ship data: send what this phone gets to everyone on the channel");
        share.setChecked(share());
        box.addView(share);
        TextView note = new TextView(a);
        note.setText("Asks for ships heard in the last 30 minutes within about 100 km of the map centre (or this phone, "
                + "when sharing). Anonymous use allows 100 square degrees and 120 requests a minute; this stays well under "
                + "(about 4 square degrees, one request every 1 or 5 minutes). If no ships at all come back (inland), "
                + "it turns itself off. Off still shows ships another phone shares.");
        box.addView(note);
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a)
                .setTitle("Ships on the map (AIS)")
                .setView(sv)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        sp.edit().putBoolean("enabled", on.isChecked()).putString("key", key.getText().toString().trim())
                                .putBoolean("slow", slow.isChecked()).putBoolean("share", share.isChecked()).commit();
                        latest = new ArrayList<Ship>();
                        autoOffNote = null;
                        restart();
                        if (done != null) done.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
