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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Live trains on the map and their next stops, from GTFS Realtime feeds (the open standard most
 * transport agencies publish). Melbourne (Metro Trains and V/Line, Transport Victoria open data, free
 * key) is a tick; any other city's feed can be added by its address. Vehicle positions are read every
 * 30 seconds (or 2 minutes in slow mode) while the map is open; a train's next stops (trip updates) are
 * read when it is tapped. Station names come from the timetable's stops.txt, if loaded.
 */
public final class TrainFeed {
    public static final class Train {
        public String id = "", label = "", route = "", trip = "", net = "", stopId = "";
        public double lat, lon;
        public float bearing = -1, speed = -1;
        public long timestamp;
    }

    public static final class StopTime {
        public String stopId = "";
        public long time;
        public int delay;
        public boolean hasDelay;
    }

    /** Marker icon for trains (PoiIcons; not one of the map point icons). */
    public static final int ICON = 100;
    static final String VIC = "https://api.opendata.transport.vic.gov.au/opendata/public-transport/gtfs/realtime/v1/";
    private static final double RANGE_M = 80000;

    private final SharedPreferences sp;
    private final Context ctx;
    private static volatile List<Train> latest = new ArrayList<Train>();
    private static volatile String problem = "";
    private static Thread thread;
    private static volatile double centreLat = Double.NaN, centreLon = Double.NaN;
    private static final List<Runnable> listeners = new ArrayList<Runnable>();
    private static Map<String, String[]> stops; // id -> {name, lat, lon}

    public TrainFeed(Context c) {
        ctx = c.getApplicationContext();
        sp = c.getSharedPreferences("trains", Context.MODE_PRIVATE);
    }

    public boolean enabled() {
        return sp.getBoolean("enabled", false);
    }

    /** The Melbourne preset: ticked here, and its specialised menu on (Settings > Home screen and menus). */
    public boolean melbourne() {
        return sp.getBoolean("melbourne", false) && new org.communitycomms.app.Prefs(ctx).menuOn("trains");
    }

    public String key() {
        return sp.getString("key", "").trim();
    }

    public String customUrl() {
        return sp.getString("url", "").trim();
    }

    public String customTrips() {
        return sp.getString("trips", "").trim();
    }

    public String customHeader() {
        return sp.getString("header", "").trim();
    }

    public String customKey() {
        return sp.getString("ckey", "").trim();
    }

    public boolean slow() {
        return sp.getBoolean("slow", false);
    }

    /** Layer tick: false if no feed has been set up yet (then the set-up box opens). */
    public boolean setOn(boolean on) {
        if (on && !configured()) return false;
        sp.edit().putBoolean("enabled", on).commit();
        restart();
        return true;
    }

    public boolean configured() {
        return (melbourne() && key().length() > 0) || customUrl().length() > 0;
    }

    public String problem() {
        return enabled() ? problem : "";
    }

    public void setCentre(double lat, double lon) {
        centreLat = lat;
        centreLon = lon;
    }

    public List<Train> trains() {
        return enabled() ? latest : new ArrayList<Train>();
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
        mine = onUpdate;
        synchronized (listeners) {
            if (mine != null) listeners.add(mine);
        }
        synchronized (TrainFeed.class) {
            if (!enabled() || thread != null || !org.communitycomms.app.PowerMode.feedsOn()) return;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!Thread.currentThread().isInterrupted() && enabled()) {
                        long wait = slow() ? 120000L : 30000L; // the Melbourne feeds allow about 24 calls a minute
                        if (Double.isNaN(centreLat)) {
                            wait = 5000;
                        } else {
                            try {
                                latest = fetchAll();
                                problem = "";
                            } catch (Exception e) {
                                problem = "Trains feed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                            }
                            notifyListeners();
                        }
                        try {
                            Thread.sleep(wait);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    synchronized (TrainFeed.class) {
                        if (thread == Thread.currentThread()) thread = null;
                    }
                }
            }, "trains");
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
        synchronized (TrainFeed.class) {
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

    private List<Train> fetchAll() throws Exception {
        List<Train> out = new ArrayList<Train>();
        Exception last = null;
        int worked = 0;
        if (melbourne() && key().length() > 0) {
            for (String[] f : new String[][]{{"metro", "Metro"}, {"vline", "V/Line"}}) {
                try {
                    parseVehicles(get(VIC + f[0] + "/vehicle-positions", null, key()), f[1], out);
                    worked++;
                } catch (Exception e) {
                    last = e;
                }
            }
        }
        if (customUrl().length() > 0) {
            try {
                parseVehicles(get(customUrl(), customHeader(), customKey()), "Trains", out);
                worked++;
            } catch (Exception e) {
                last = e;
            }
        }
        if (worked == 0 && last != null) throw last;
        // Only those near the map centre.
        List<Train> near = new ArrayList<Train>();
        for (Train t : out) if (Geo.distanceM(centreLat, centreLon, t.lat, t.lon) <= RANGE_M) near.add(t);
        return near;
    }

    /** A feed's bytes. header null = the Transport Victoria key headers. */
    private static byte[] get(String url, String header, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        Tls12SocketFactory.apply(c);
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "YourCommunityCommunications");
        if (key != null && key.length() > 0) {
            if (header == null) {
                c.setRequestProperty("KeyID", key); // the dataset page's header name
                c.setRequestProperty("Ocp-Apim-Subscription-Key", key); // the metro feeds' own definition
            } else if (header.length() > 0) {
                c.setRequestProperty(header, key);
            }
        }
        try {
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new Exception("the key was refused (" + code + ")");
            if (code == 429) throw new Exception("too many requests: try slow mode");
            if (code >= 400) throw new Exception("the server answered " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 8 * 1024 * 1024) throw new Exception("reply too big");
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    // ---- GTFS Realtime (Protocol Buffers) without a library ----

    static final class Pb {
        final byte[] b;
        int p;
        final int end;

        Pb(byte[] b, int start, int end) {
            this.b = b;
            p = start;
            this.end = end;
        }

        boolean more() {
            return p < end;
        }

        long varint() {
            long v = 0;
            int s = 0;
            while (p < end) {
                int x = b[p++] & 0xff;
                v |= (long) (x & 0x7f) << s;
                if (x < 0x80) break;
                s += 7;
            }
            return v;
        }

        float fixed32f() {
            int v = (b[p] & 0xff) | (b[p + 1] & 0xff) << 8 | (b[p + 2] & 0xff) << 16 | (b[p + 3] & 0xff) << 24;
            p += 4;
            return Float.intBitsToFloat(v);
        }

        /** The next field: {number, wire type}; for length-delimited, sub gives the bytes. */
        int[] tag() {
            long t = varint();
            return new int[]{(int) (t >>> 3), (int) (t & 7)};
        }

        Pb sub() {
            int len = (int) varint();
            Pb s = new Pb(b, p, Math.min(end, p + len));
            p = Math.min(end, p + len);
            return s;
        }

        String str() {
            int len = (int) varint();
            String s;
            try {
                s = new String(b, p, Math.min(len, end - p), "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                s = "";
            }
            p = Math.min(end, p + len);
            return s;
        }

        void skip(int wire) {
            if (wire == 0) varint();
            else if (wire == 1) p += 8;
            else if (wire == 2) {
                int len = (int) varint(); // read first: "p += varint()" would add to the old p
                p += len;
            }
            else if (wire == 5) p += 4;
            else p = end;
        }
    }

    /** FeedMessage > entity > vehicle (VehiclePosition). */
    static void parseVehicles(byte[] data, String net, List<Train> out) {
        Pb feed = new Pb(data, 0, data.length);
        while (feed.more()) {
            int[] t = feed.tag();
            if (t[0] != 2 || t[1] != 2) {
                feed.skip(t[1]);
                continue;
            }
            Pb ent = feed.sub();
            while (ent.more()) {
                int[] e = ent.tag();
                if (e[0] != 4 || e[1] != 2) {
                    ent.skip(e[1]);
                    continue;
                }
                Train tr = new Train();
                tr.net = net;
                boolean pos = false;
                Pb v = ent.sub();
                while (v.more()) {
                    int[] f = v.tag();
                    if (f[0] == 1 && f[1] == 2) { // trip
                        Pb trip = v.sub();
                        while (trip.more()) {
                            int[] g = trip.tag();
                            if (g[0] == 1 && g[1] == 2) tr.trip = trip.str();
                            else if (g[0] == 5 && g[1] == 2) tr.route = trip.str();
                            else trip.skip(g[1]);
                        }
                    } else if (f[0] == 2 && f[1] == 2) { // position
                        Pb ps = v.sub();
                        while (ps.more()) {
                            int[] g = ps.tag();
                            if (g[0] == 1 && g[1] == 5) tr.lat = ps.fixed32f();
                            else if (g[0] == 2 && g[1] == 5) tr.lon = ps.fixed32f();
                            else if (g[0] == 3 && g[1] == 5) tr.bearing = ps.fixed32f();
                            else if (g[0] == 5 && g[1] == 5) tr.speed = ps.fixed32f();
                            else ps.skip(g[1]);
                        }
                        pos = true;
                    } else if (f[0] == 8 && f[1] == 2) { // vehicle
                        Pb vd = v.sub();
                        while (vd.more()) {
                            int[] g = vd.tag();
                            if (g[0] == 1 && g[1] == 2) tr.id = vd.str();
                            else if (g[0] == 2 && g[1] == 2) tr.label = vd.str();
                            else vd.skip(g[1]);
                        }
                    } else if (f[0] == 5 && f[1] == 0) {
                        tr.timestamp = v.varint() * 1000;
                    } else if (f[0] == 7 && f[1] == 2) {
                        tr.stopId = v.str();
                    } else {
                        v.skip(f[1]);
                    }
                }
                if (pos && (tr.lat != 0 || tr.lon != 0)) out.add(tr);
            }
        }
    }

    /** FeedMessage > entity > trip_update for one trip: its stops still to come. */
    static List<StopTime> parseTrip(byte[] data, String tripId) {
        List<StopTime> out = new ArrayList<StopTime>();
        Pb feed = new Pb(data, 0, data.length);
        while (feed.more()) {
            int[] t = feed.tag();
            if (t[0] != 2 || t[1] != 2) {
                feed.skip(t[1]);
                continue;
            }
            Pb ent = feed.sub();
            while (ent.more()) {
                int[] e = ent.tag();
                if (e[0] != 3 || e[1] != 2) {
                    ent.skip(e[1]);
                    continue;
                }
                Pb tu = ent.sub();
                String trip = "";
                List<StopTime> times = new ArrayList<StopTime>();
                while (tu.more()) {
                    int[] f = tu.tag();
                    if (f[0] == 1 && f[1] == 2) {
                        Pb td = tu.sub();
                        while (td.more()) {
                            int[] g = td.tag();
                            if (g[0] == 1 && g[1] == 2) trip = td.str();
                            else td.skip(g[1]);
                        }
                    } else if (f[0] == 2 && f[1] == 2) {
                        Pb st = tu.sub();
                        StopTime s = new StopTime();
                        while (st.more()) {
                            int[] g = st.tag();
                            if (g[0] == 4 && g[1] == 2) s.stopId = st.str();
                            else if ((g[0] == 2 || g[0] == 3) && g[1] == 2) { // arrival, departure
                                Pb ev = st.sub();
                                while (ev.more()) {
                                    int[] h = ev.tag();
                                    if (h[0] == 1 && h[1] == 0) {
                                        int d = (int) ev.varint();
                                        if (!s.hasDelay || g[0] == 2) {
                                            s.delay = d;
                                            s.hasDelay = true;
                                        }
                                    } else if (h[0] == 2 && h[1] == 0) {
                                        long tm = ev.varint() * 1000;
                                        if (s.time == 0 || g[0] == 2) s.time = tm;
                                    } else ev.skip(h[1]);
                                }
                            } else st.skip(g[1]);
                        }
                        times.add(s);
                    } else {
                        tu.skip(f[1]);
                    }
                }
                if (trip.equals(tripId)) return times;
            }
        }
        return out;
    }

    /** A train's next stops, read now (on a background thread). */
    public String nextStops(Train t) {
        try {
            byte[] d;
            if ("Metro".equals(t.net)) d = get(VIC + "metro/trip-updates", null, key());
            else if ("V/Line".equals(t.net)) d = get(VIC + "vline/trip-updates", null, key());
            else if (customTrips().length() > 0) d = get(customTrips(), customHeader(), customKey());
            else return "No trip updates address set for this feed.";
            List<StopTime> times = parseTrip(d, t.trip);
            if (times.isEmpty()) return "No timetable for this trip in the feed right now.";
            loadStops();
            StringBuilder b = new StringBuilder();
            long now = System.currentTimeMillis();
            java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("HH:mm", Locale.US);
            int shown = 0;
            for (StopTime s : times) {
                if (s.time > 0 && s.time < now - 60000) continue; // already passed
                String[] st = stops == null ? null : stops.get(s.stopId);
                b.append(s.time > 0 ? hm.format(new java.util.Date(s.time)) : "--:--").append("  ")
                        .append(st != null ? st[0] : "stop " + s.stopId);
                if (s.hasDelay && Math.abs(s.delay) >= 60) b.append(s.delay > 0 ? "  (" + s.delay / 60 + " min late)" : "  (" + (-s.delay) / 60 + " min early)");
                b.append('\n');
                if (++shown >= 25) break;
            }
            if (stops == null) b.append("\n(Station names: Trains… > Load station names.)");
            return b.toString().trim();
        } catch (Exception e) {
            return "Could not read the timetable: " + e.getMessage();
        }
    }

    // ---- station names (stops.txt from the timetable, GTFS) ----

    private java.io.File stopsFile() {
        return new java.io.File(ctx.getFilesDir(), "train-stops.txt");
    }

    public synchronized void loadStops() {
        if (stops != null) return;
        java.io.File f = stopsFile();
        if (!f.isFile()) return;
        try {
            stops = readStops(new java.io.FileInputStream(f));
        } catch (Exception ignored) {
        }
    }

    /** Takes a stops.txt (CSV: stop_id, stop_name, stop_lat, stop_lon, …). Returns how many stops. */
    public int importStops(InputStream in) throws Exception {
        java.io.File tmp = new java.io.File(stopsFile().getPath() + ".part");
        java.io.FileOutputStream o = new java.io.FileOutputStream(tmp);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        o.close();
        in.close();
        Map<String, String[]> m = readStops(new java.io.FileInputStream(tmp));
        if (m.isEmpty()) {
            tmp.delete();
            throw new Exception("no stops found (it needs stop_id and stop_name columns)");
        }
        // Several files can be loaded (metro, regional): add to what is there.
        java.io.File cur = stopsFile();
        if (cur.isFile()) {
            Map<String, String[]> old = readStops(new java.io.FileInputStream(cur));
            old.putAll(m);
            m = old;
        }
        java.io.PrintWriter w = new java.io.PrintWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(cur), "UTF-8"));
        w.println("stop_id,stop_name,stop_lat,stop_lon");
        for (Map.Entry<String, String[]> e : m.entrySet()) {
            w.println(csv(e.getKey()) + "," + csv(e.getValue()[0]) + "," + e.getValue()[1] + "," + e.getValue()[2]);
        }
        w.close();
        tmp.delete();
        synchronized (this) {
            stops = m;
        }
        return m.size();
    }

    private static String csv(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    static Map<String, String[]> readStops(InputStream in) throws Exception {
        Map<String, String[]> m = new HashMap<String, String[]>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String head = r.readLine();
            if (head == null) return m;
            if (head.startsWith("﻿")) head = head.substring(1);
            List<String> h = fields(head);
            int id = h.indexOf("stop_id"), name = h.indexOf("stop_name"), la = h.indexOf("stop_lat"), lo = h.indexOf("stop_lon");
            if (id < 0 || name < 0) return m;
            String line;
            while ((line = r.readLine()) != null) {
                List<String> f = fields(line);
                if (f.size() <= Math.max(id, name)) continue;
                m.put(f.get(id), new String[]{f.get(name), la >= 0 && la < f.size() ? f.get(la) : "", lo >= 0 && lo < f.size() ? f.get(lo) : ""});
            }
        } finally {
            r.close();
        }
        return m;
    }

    /** Splits a CSV line (quotes allowed). */
    static List<String> fields(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder f = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    f.append('"');
                    i++;
                } else if (c == '"') q = false;
                else f.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') {
                out.add(f.toString().trim());
                f.setLength(0);
            } else f.append(c);
        }
        out.add(f.toString().trim());
        return out;
    }

    public int stopCount() {
        loadStops();
        return stops == null ? 0 : stops.size();
    }

    // ---- words for the map ----

    /** "ALM" from a Melbourne route id such as "aus:vic:vic-02-ALM:", else the id itself. */
    public static String line(Train t) {
        String r = t.route;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("vic-\\d+-([A-Za-z0-9]+)").matcher(r);
        if (m.find()) return m.group(1);
        return r;
    }

    public static String label(Train t) {
        String l = line(t);
        return (l.length() > 0 ? l : t.net) + (t.label.length() > 0 ? " " + t.label : "");
    }

    public static String details(Train t) {
        StringBuilder b = new StringBuilder();
        b.append(t.net).append(" train");
        if (line(t).length() > 0) b.append(", line ").append(line(t));
        if (t.label.length() > 0) b.append("\nTrain ").append(t.label);
        if (t.speed >= 0) b.append(String.format(Locale.US, "\nSpeed %.0f km/h", t.speed * 3.6));
        if (t.timestamp > 0) b.append("\nPosition ").append(Geo.age(System.currentTimeMillis() - t.timestamp));
        b.append(String.format(Locale.US, "\n%.5f, %.5f", t.lat, t.lon));
        return b.toString();
    }

    /** Map menu > Trains…: Melbourne tick and key, another city's feed, slow mode, station names. */
    public void choose(final Activity a, final Runnable loadStopsFile, final Runnable done) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        final CheckBox on = new CheckBox(a);
        on.setText("Show live trains on the map (needs internet)");
        on.setChecked(enabled() || !configured());
        box.addView(on);
        // The Melbourne preset shows when ticked in Settings > Home screen and menus (or already in use).
        boolean showMel = new org.communitycomms.app.Prefs(a).menuOn("trains");
        final CheckBox mel = new CheckBox(a);
        mel.setText("Melbourne: Metro Trains and V/Line (Transport Victoria open data)");
        mel.setChecked(melbourne());
        TextView kl = new TextView(a);
        kl.setText("Transport Victoria API key (free: register at opendata.transport.vic.gov.au; My Account > Profile > API Tokens)");
        final EditText key = new EditText(a);
        key.setSingleLine(true);
        key.setText(key());
        if (showMel) {
            box.addView(mel);
            box.addView(kl);
            box.addView(key);
        }
        TextView other = new TextView(a);
        other.setText((showMel ? "\n" : "") + "Your home town (recommended): its GTFS Realtime feed addresses, from its transport agency's open data page");
        box.addView(other);
        final EditText url = new EditText(a), trips = new EditText(a), header = new EditText(a), ckey = new EditText(a);
        url.setSingleLine(true);
        url.setHint("Vehicle positions address");
        url.setText(customUrl());
        trips.setSingleLine(true);
        trips.setHint("Trip updates address (for timetables, optional)");
        trips.setText(customTrips());
        header.setSingleLine(true);
        header.setHint("Key header name, if it needs one (e.g. x-api-key)");
        header.setText(customHeader());
        ckey.setSingleLine(true);
        ckey.setHint("Its key (optional)");
        ckey.setText(customKey());
        box.addView(url);
        box.addView(trips);
        box.addView(header);
        box.addView(ckey);
        final CheckBox slow = new CheckBox(a);
        slow.setText("Slow mode: every 2 minutes (otherwise every 30 seconds)");
        slow.setChecked(slow());
        box.addView(slow);
        TextView names = new TextView(a);
        names.setText("\nStation names: " + stopCount() + " loaded" + (loadStopsFile != null ? ". Load stops.txt from the timetable (GTFS) for station names in a train's next stops." : "."));
        box.addView(names);
        final boolean showMelFinal = showMel;
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        AlertDialog.Builder bld = new AlertDialog.Builder(a)
                .setTitle("Trains on the map")
                .setView(sv)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        sp.edit().putBoolean("melbourne", mel.isChecked() || (!showMelFinal && sp.getBoolean("melbourne", false))).putString("key", key.getText().toString().trim())
                                .putString("url", url.getText().toString().trim()).putString("trips", trips.getText().toString().trim())
                                .putString("header", header.getText().toString().trim()).putString("ckey", ckey.getText().toString().trim())
                                .putBoolean("slow", slow.isChecked()).commit();
                        sp.edit().putBoolean("enabled", on.isChecked() && configured()).commit();
                        latest = new ArrayList<Train>();
                        problem = on.isChecked() && !configured() ? "Trains feed: tick Melbourne and enter its key, or add a feed address" : "";
                        restart();
                        if (done != null) done.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (loadStopsFile != null) bld.setNeutralButton("Load station names…", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dlg, int w) {
                loadStopsFile.run();
            }
        });
        bld.show();
    }
}
