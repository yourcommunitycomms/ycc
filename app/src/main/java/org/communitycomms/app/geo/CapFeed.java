package org.communitycomms.app.geo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.widget.CheckBox;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Emergency alerts on the map (Common Alerting Protocol), from the map menu like aircraft and ships.
 * Presets: VicEmergency (Victoria, Australia: incidents, warnings and burn areas, its public GeoJSON
 * feed) and NOAA (US National Weather Service active alerts at the map centre). Custom takes any
 * address giving CAP 1.2 / CAP-AU XML (one alert, or a feed holding several) or GeoJSON alerts.
 * Areas are drawn as shaded outlines with a marker to tap for the details. Off by default; slow mode
 * (default) checks every 10 minutes, otherwise every 2.
 */
public final class CapFeed {
    public static final int OFF = 0, VICEMERGENCY = 1, NOAA = 2, CUSTOM = 3;
    public static final String[] SOURCE_NAMES = {"Off", "VicEmergency (Victoria, Australia)",
            "NOAA / National Weather Service (USA)", "Custom feed address (CAP XML or GeoJSON)"};
    static final String VIC_URL = "https://emergency.vic.gov.au/public/osom-geojson.json";
    static final String NOAA_URL = "https://api.weather.gov/alerts/active?point=";

    public static final class Alert {
        public String id = "", title = "", event = "", severity = "", status = "", area = "", text = "", url = "";
        public String sent = "", expires = "", source = "";
        public double lat = Double.NaN, lon = Double.NaN;
        /** Outlines as {lat, lon, lat, lon, ...}; may be empty (a point alert). */
        public final List<double[]> polygons = new ArrayList<double[]>();
    }

    private final SharedPreferences sp;
    private static volatile List<Alert> latest = new ArrayList<Alert>();
    private static volatile String problem = "";
    private static Thread thread;
    private static volatile double centreLat = Double.NaN, centreLon = Double.NaN;
    private static final List<Runnable> listeners = new ArrayList<Runnable>();
    /** NOAA zone outlines, fetched once each (alerts without their own polygon). */
    private static final Map<String, List<double[]>> zones = new HashMap<String, List<double[]>>();

    public CapFeed(Context c) {
        sp = c.getSharedPreferences("cap", Context.MODE_PRIVATE);
    }

    public int source() {
        return sp.getInt("source", OFF);
    }

    public boolean enabled() {
        return source() != OFF;
    }

    public String customUrl() {
        return sp.getString("url", "").trim();
    }

    public boolean slow() {
        return sp.getBoolean("slow", true);
    }

    public String problem() {
        return enabled() ? problem : "";
    }

    public void setCentre(double lat, double lon) {
        centreLat = lat;
        centreLon = lon;
    }

    public List<Alert> alerts() {
        return enabled() ? latest : new ArrayList<Alert>();
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
        synchronized (CapFeed.class) {
            if (!enabled() || thread != null) return;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!Thread.currentThread().isInterrupted() && enabled()) {
                        long wait = slow() ? 10 * 60000L : 2 * 60000L;
                        if (source() == NOAA && Double.isNaN(centreLat)) {
                            wait = 5000; // no map centre yet
                        } else {
                            try {
                                latest = fetch();
                                problem = "";
                            } catch (InterruptedException e) {
                                break;
                            } catch (Exception e) {
                                problem = "Alerts feed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                            }
                            notifyListeners();
                        }
                        try {
                            Thread.sleep(wait);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    synchronized (CapFeed.class) {
                        if (thread == Thread.currentThread()) thread = null;
                    }
                }
            }, "cap");
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
        synchronized (CapFeed.class) {
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

    // ---- fetching ----

    static String get(String address, String accept) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "YourCommunityCommunications (emergency alerts map)");
        if (accept != null) c.setRequestProperty("Accept", accept);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new Exception("the server answered " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 8 * 1024 * 1024) throw new Exception("reply too big");
            }
            byte[] b = out.toByteArray();
            // Some servers send gzip even when it was not asked for.
            if (b.length > 2 && (b[0] & 0xff) == 0x1f && (b[1] & 0xff) == 0x8b) {
                java.util.zip.GZIPInputStream z = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(b));
                out.reset();
                while ((n = z.read(buf)) > 0) out.write(buf, 0, n);
                b = out.toByteArray();
            }
            return new String(b, "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private List<Alert> fetch() throws Exception {
        int s = source();
        if (s == VICEMERGENCY) return parseGeoJson(get(VIC_URL, "application/json"), "VicEmergency");
        if (s == NOAA) {
            List<Alert> out = parseGeoJson(get(NOAA_URL + String.format(Locale.US, "%.4f,%.4f", centreLat, centreLon),
                    "application/geo+json"), "NOAA / National Weather Service");
            return out;
        }
        String url = customUrl();
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw new Exception("enter the feed address, starting http");
        String body = get(url, "application/cap+xml, application/xml, application/geo+json, application/json");
        String t = body.trim();
        return t.startsWith("{") ? parseGeoJson(t, "Custom feed") : parseCap(t, "Custom feed");
    }

    // ---- GeoJSON (VicEmergency, NOAA and others) ----

    static List<Alert> parseGeoJson(String json, String source) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONArray f = o.optJSONArray("features");
        List<Alert> out = new ArrayList<Alert>();
        if (f == null) return out;
        for (int i = 0; i < f.length() && out.size() < 300; i++) {
            JSONObject feat = f.optJSONObject(i);
            if (feat == null) continue;
            JSONObject p = feat.optJSONObject("properties");
            if (p == null) p = new JSONObject();
            Alert a = new Alert();
            a.source = source;
            a.id = first(p, "id", "sourceId", "identifier");
            a.title = first(p, "headline", "name", "sourceTitle", "title", "event");
            a.event = first(p, "event", "category2", "category1", "feedType");
            a.severity = first(p, "severity", "status", "action");
            a.status = first(p, "certainty", "status");
            a.area = first(p, "areaDesc", "location");
            a.text = first(p, "description", "webBody", "text");
            String instr = p.optString("instruction", "");
            if (instr.length() > 0 && !"null".equals(instr)) a.text += "\n\n" + instr;
            a.sent = first(p, "sent", "updated", "created", "effective");
            a.expires = first(p, "expires", "ends");
            a.url = first(p, "url", "web");
            addGeometry(a, feat.optJSONObject("geometry"));
            if (a.polygons.isEmpty() && Double.isNaN(a.lat)) {
                // NOAA: many alerts name forecast zones instead of a polygon; draw the first few zones.
                JSONArray z = p.optJSONArray("affectedZones");
                for (int k = 0; z != null && k < Math.min(4, z.length()); k++) {
                    for (double[] ring : zone(z.optString(k))) a.polygons.add(ring);
                }
            }
            if (Double.isNaN(a.lat) && !a.polygons.isEmpty()) centre(a);
            if (Double.isNaN(a.lat)) continue;
            if (a.title.length() == 0) a.title = a.event.length() > 0 ? a.event : "Alert";
            out.add(a);
        }
        return out;
    }

    private static String first(JSONObject p, String... keys) {
        for (String k : keys) {
            Object v = p.opt(k);
            if (v == null || v == JSONObject.NULL) continue;
            String s = String.valueOf(v).trim();
            if (s.length() > 0) return s;
        }
        return "";
    }

    private static void addGeometry(Alert a, JSONObject g) {
        if (g == null) return;
        String type = g.optString("type");
        JSONArray c = g.optJSONArray("coordinates");
        if ("Point".equals(type) && c != null && c.length() >= 2) {
            a.lon = c.optDouble(0);
            a.lat = c.optDouble(1);
        } else if ("Polygon".equals(type) && c != null) {
            ring(a, c.optJSONArray(0));
        } else if ("MultiPolygon".equals(type) && c != null) {
            for (int i = 0; i < c.length() && i < 40; i++) {
                JSONArray poly = c.optJSONArray(i);
                if (poly != null) ring(a, poly.optJSONArray(0));
            }
        } else if ("GeometryCollection".equals(type)) {
            JSONArray gs = g.optJSONArray("geometries");
            for (int i = 0; gs != null && i < gs.length(); i++) addGeometry(a, gs.optJSONObject(i));
        }
    }

    private static void ring(Alert a, JSONArray r) {
        if (r == null || r.length() < 3) return;
        int step = Math.max(1, r.length() / 400); // keep drawing quick on old phones
        List<Double> pts = new ArrayList<Double>();
        for (int i = 0; i < r.length(); i += step) {
            JSONArray xy = r.optJSONArray(i);
            if (xy == null || xy.length() < 2) continue;
            pts.add(xy.optDouble(1));
            pts.add(xy.optDouble(0));
        }
        if (pts.size() < 6) return;
        double[] d = new double[pts.size()];
        for (int i = 0; i < d.length; i++) d[i] = pts.get(i);
        a.polygons.add(d);
    }

    private static List<double[]> zone(String url) {
        if (url == null || !url.startsWith("https://api.weather.gov/")) return new ArrayList<double[]>();
        synchronized (zones) {
            List<double[]> z = zones.get(url);
            if (z != null) return z;
        }
        List<double[]> got = new ArrayList<double[]>();
        try {
            Alert tmp = new Alert();
            addGeometry(tmp, new JSONObject(get(url, "application/geo+json")).optJSONObject("geometry"));
            got = tmp.polygons;
        } catch (Exception ignored) {
            // No outline for this zone: the alert still shows if another zone or a point has one.
        }
        synchronized (zones) {
            if (zones.size() > 200) zones.clear();
            zones.put(url, got);
        }
        return got;
    }

    /** The marker goes at the middle of the first outline's bounding box. */
    private static void centre(Alert a) {
        double[] r = a.polygons.get(0);
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (int i = 0; i + 1 < r.length; i += 2) {
            minLat = Math.min(minLat, r[i]);
            maxLat = Math.max(maxLat, r[i]);
            minLon = Math.min(minLon, r[i + 1]);
            maxLon = Math.max(maxLon, r[i + 1]);
        }
        a.lat = (minLat + maxLat) / 2;
        a.lon = (minLon + maxLon) / 2;
    }

    // ---- CAP 1.2 / CAP-AU XML ----

    private static final Pattern INFO = Pattern.compile("<(?:\\w+:)?info\\b[^>]*>([\\s\\S]*?)</(?:\\w+:)?info>");
    private static final Pattern AREA = Pattern.compile("<(?:\\w+:)?area\\b[^>]*>([\\s\\S]*?)</(?:\\w+:)?area>");

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<(?:\\w+:)?" + name + "\\b[^>]*>([\\s\\S]*?)</(?:\\w+:)?" + name + ">").matcher(xml);
        return m.find() ? unxml(m.group(1)) : "";
    }

    private static List<String> tags(String xml, String name) {
        List<String> out = new ArrayList<String>();
        Matcher m = Pattern.compile("<(?:\\w+:)?" + name + "\\b[^>]*>([\\s\\S]*?)</(?:\\w+:)?" + name + ">").matcher(xml);
        while (m.find()) out.add(unxml(m.group(1)));
        return out;
    }

    private static String unxml(String s) {
        s = s.trim();
        if (s.startsWith("<![CDATA[") && s.endsWith("]]>")) s = s.substring(9, s.length() - 3);
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&").trim();
    }

    /** Every <alert> in the document (a single CAP message, or a feed that holds several). */
    static List<Alert> parseCap(String xml, String source) {
        List<Alert> out = new ArrayList<Alert>();
        Matcher am = Pattern.compile("<(?:\\w+:)?alert\\b[^>]*>([\\s\\S]*?)</(?:\\w+:)?alert>").matcher(xml);
        while (am.find() && out.size() < 300) {
            String alert = am.group(1);
            String status = tag(alert, "status"), msgType = tag(alert, "msgType");
            if ("Cancel".equalsIgnoreCase(msgType) || "Exercise".equalsIgnoreCase(status) || "Test".equalsIgnoreCase(status)) continue;
            Matcher im = INFO.matcher(alert);
            if (!im.find()) continue;
            String info = im.group(1);
            Alert a = new Alert();
            a.source = source;
            a.id = tag(alert, "identifier");
            a.sent = tag(alert, "sent");
            a.status = tag(info, "certainty");
            a.event = tag(info, "event");
            a.title = tag(info, "headline");
            if (a.title.length() == 0) a.title = a.event;
            a.severity = tag(info, "severity");
            a.text = tag(info, "description");
            String instr = tag(info, "instruction");
            if (instr.length() > 0) a.text += "\n\n" + instr;
            a.expires = tag(info, "expires");
            a.url = tag(info, "web");
            Matcher arm = AREA.matcher(info);
            StringBuilder desc = new StringBuilder();
            while (arm.find()) {
                String area = arm.group(1);
                String d = tag(area, "areaDesc");
                if (d.length() > 0) desc.append(desc.length() > 0 ? "; " : "").append(d);
                for (String poly : tags(area, "polygon")) {
                    String[] pts = poly.trim().split("\\s+");
                    if (pts.length < 3) continue;
                    double[] r = new double[pts.length * 2];
                    int n = 0;
                    for (String pt : pts) {
                        String[] ll = pt.split(",");
                        if (ll.length < 2) continue;
                        try {
                            r[n++] = Double.parseDouble(ll[0]);
                            r[n++] = Double.parseDouble(ll[1]);
                        } catch (NumberFormatException e) {
                            n -= n % 2;
                        }
                    }
                    if (n >= 6) a.polygons.add(java.util.Arrays.copyOf(r, n));
                }
                for (String circle : tags(area, "circle")) {
                    // "lat,lon radius_km": drawn as a 32-sided outline.
                    String[] f = circle.trim().split("[\\s,]+");
                    if (f.length < 3) continue;
                    try {
                        double lat = Double.parseDouble(f[0]), lon = Double.parseDouble(f[1]), km = Double.parseDouble(f[2]);
                        if (km <= 0) {
                            a.lat = lat;
                            a.lon = lon;
                            continue;
                        }
                        double[] r = new double[64];
                        for (int i = 0; i < 32; i++) {
                            double[] p = Geo.destination(lat, lon, i * 360.0 / 32, km * 1000);
                            r[2 * i] = p[0];
                            r[2 * i + 1] = p[1];
                        }
                        a.polygons.add(r);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            a.area = desc.toString();
            if (Double.isNaN(a.lat) && !a.polygons.isEmpty()) centre(a);
            if (Double.isNaN(a.lat)) continue;
            out.add(a);
        }
        return out;
    }

    // ---- showing ----

    /** Red for extreme or emergency, orange severe or watch-and-act, yellow moderate or advice, blue the rest. */
    public static int colour(Alert a) {
        String s = (a.severity + " " + a.event + " " + a.title).toLowerCase(Locale.US);
        if (s.contains("extreme") || s.contains("emergency warning") || s.contains("evacuat")) return 0xffd32f2f;
        if (s.contains("severe") || s.contains("watch and act") || s.contains("warning")) return 0xfff57c00;
        if (s.contains("moderate") || s.contains("advice") || s.contains("watch")) return 0xfffbc02d;
        if (s.contains("burn")) return 0xff8d6e63;
        return 0xff1e88e5;
    }

    public static String label(Alert a) {
        String t = a.event.length() > 0 && a.event.length() < 28 ? a.event : a.title;
        return t.length() > 36 ? t.substring(0, 34) + "…" : t;
    }

    public static String details(Alert a) {
        StringBuilder b = new StringBuilder();
        b.append(a.title);
        if (a.event.length() > 0 && !a.event.equals(a.title)) b.append("\n").append(a.event);
        if (a.severity.length() > 0) b.append("\nSeverity / status: ").append(a.severity);
        if (a.area.length() > 0) b.append("\nArea: ").append(a.area);
        if (a.sent.length() > 0) b.append("\nIssued: ").append(a.sent);
        if (a.expires.length() > 0) b.append("\nExpires: ").append(a.expires);
        if (a.text.length() > 0) b.append("\n\n").append(a.text.length() > 3000 ? a.text.substring(0, 3000) + "…" : a.text);
        if (a.url.length() > 0) b.append("\n\n").append(a.url);
        b.append("\n\nSource: ").append(a.source).append(". Always follow official advice.");
        return b.toString();
    }

    /** Map menu > Alerts (CAP)…: source preset or custom address, slow mode. */
    public void choose(final Activity a, final Runnable done) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        final RadioGroup g = new RadioGroup(a);
        for (int i = 0; i < SOURCE_NAMES.length; i++) {
            RadioButton r = new RadioButton(a);
            r.setText(SOURCE_NAMES[i]);
            r.setId(100 + i);
            g.addView(r);
        }
        g.check(100 + source());
        box.addView(g);
        TextView ul = new TextView(a);
        ul.setText("Custom feed address (CAP 1.2 / CAP-AU XML, one alert or a feed of them, or GeoJSON)");
        box.addView(ul);
        final EditText url = new EditText(a);
        url.setSingleLine(true);
        url.setHint("https://…/alerts.xml");
        url.setText(customUrl());
        box.addView(url);
        final CheckBox slow = new CheckBox(a);
        slow.setText("Slow mode: check every 10 minutes (otherwise every 2) to save data");
        slow.setChecked(slow());
        box.addView(slow);
        TextView note = new TextView(a);
        note.setText("Needs internet. Alert areas are shaded on the map (red extreme / emergency, orange severe / watch "
                + "and act, yellow moderate / advice, blue other, brown burn areas); tap a marker for the details. "
                + "VicEmergency shows all of Victoria. NOAA shows the alerts in force at the map centre (move the map "
                + "and it follows at the next check). Always follow official advice.");
        box.addView(note);
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a)
                .setTitle("Emergency alerts (CAP)")
                .setView(sv)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        int s = Math.max(0, g.getCheckedRadioButtonId() - 100);
                        sp.edit().putInt("source", s).putString("url", url.getText().toString().trim())
                                .putBoolean("slow", slow.isChecked()).commit();
                        latest = new ArrayList<Alert>();
                        problem = "";
                        restart();
                        if (done != null) done.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
