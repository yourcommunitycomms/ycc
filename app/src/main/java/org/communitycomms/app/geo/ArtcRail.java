package org.communitycomms.app.geo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The ARTC rail network (Australia's interstate and freight track) as lines over the map, from ARTC's
 * public Rail Centreline layer (ArcGIS, CC BY 4.0). Read in squares of about 70 km (map zoom 9 tiles)
 * as the map moves, simplified to about 20 m, and kept on the phone (maps/_artc) so areas seen before
 * show offline.
 */
public final class ArtcRail {
    static final String LAYER = "https://services7.arcgis.com/VhGABKWvW4uih8Rb/arcgis/rest/services/ARTC_Rail_Centreline_Ln_view/FeatureServer/8/query";
    private static final int Z = 9;
    /** Below this map zoom the lines are not drawn (the whole country would be too many). */
    public static final int MIN_ZOOM = 6;

    private final SharedPreferences sp;
    private final File dir;
    private static final Map<String, List<double[]>> loaded = new HashMap<String, List<double[]>>();
    private static final Set<String> busy = new HashSet<String>(), failed = new HashSet<String>();
    private static volatile String problem = "";

    public ArtcRail(Context c) {
        sp = c.getSharedPreferences("artc", Context.MODE_PRIVATE);
        dir = new File(MapPrefs.mapsRoot(c), "_artc");
        dir.mkdirs();
    }

    public boolean enabled() {
        return sp.getBoolean("enabled", false);
    }

    public void setOn(boolean on) {
        sp.edit().putBoolean("enabled", on).commit();
    }

    public String problem() {
        return enabled() ? problem : "";
    }

    /**
     * The lines for what the map shows now (box = {north, west, south, east}); squares not on the phone
     * are fetched in the background and changed runs when one arrives.
     */
    public List<double[]> lines(double[] box, int zoom, final Runnable changed) {
        List<double[]> out = new ArrayList<double[]>();
        if (!enabled() || zoom < MIN_ZOOM || box == null) return out;
        int x0 = (int) Geo.lonToX(box[1], Z), x1 = (int) Geo.lonToX(box[3], Z);
        int y0 = (int) Geo.latToY(box[0], Z), y1 = (int) Geo.latToY(box[2], Z);
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) > 64) return out; // far zoomed out: too many squares
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                final String key = x + "_" + y;
                List<double[]> l;
                synchronized (loaded) {
                    l = loaded.get(key);
                }
                if (l == null) l = fromDisk(key);
                if (l != null) {
                    out.addAll(l);
                    continue;
                }
                synchronized (busy) {
                    if (busy.contains(key) || failed.contains(key)) continue;
                    busy.add(key);
                }
                final int fx = x, fy = y;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            List<double[]> got = fetch(fx, fy, key);
                            synchronized (loaded) {
                                loaded.put(key, got);
                            }
                            problem = "";
                            if (changed != null) changed.run();
                        } catch (Exception e) {
                            problem = "ARTC rail: " + e.getMessage();
                            synchronized (busy) {
                                failed.add(key); // tried once this time the app is open
                            }
                        } finally {
                            synchronized (busy) {
                                busy.remove(key);
                            }
                        }
                    }
                }, "artc").start();
            }
        }
        return out;
    }

    private List<double[]> fromDisk(String key) {
        File f = new File(dir, key + ".json");
        if (!f.isFile()) return null;
        try {
            List<double[]> l = parse(read(new FileInputStream(f)));
            synchronized (loaded) {
                loaded.put(key, l);
            }
            return l;
        } catch (Exception e) {
            f.delete();
            return null;
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) {
            bo.write(b, 0, n);
            if (bo.size() > 16 * 1024 * 1024) throw new Exception("reply too big");
        }
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    /** One square: every page of the query, kept on the phone as one GeoJSON-like file. */
    private List<double[]> fetch(int x, int y, String key) throws Exception {
        double w = Geo.xToLon(x, Z), e = Geo.xToLon(x + 1, Z), n = Geo.yToLat(y, Z), s = Geo.yToLat(y + 1, Z);
        List<double[]> all = new ArrayList<double[]>();
        JSONArray keep = new JSONArray();
        for (int offset = 0; offset < 20000; offset += 1000) {
            String q = LAYER + String.format(Locale.US, "?where=1%%3D1&geometry=%.5f,%.5f,%.5f,%.5f&geometryType=esriGeometryEnvelope&inSR=4326"
                    + "&spatialRel=esriSpatialRelIntersects&outFields=OBJECTID&returnGeometry=true&outSR=4326&maxAllowableOffset=0.0002"
                    + "&geometryPrecision=5&resultOffset=%d&resultRecordCount=1000&f=json", w, s, e, n, offset);
            HttpURLConnection c = (HttpURLConnection) new URL(q).openConnection();
            Tls12SocketFactory.apply(c);
            c.setConnectTimeout(8000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "YourCommunityCommunications");
            String body;
            try {
                int code = c.getResponseCode();
                if (code >= 400) throw new Exception("the server answered " + code);
                body = read(c.getInputStream());
            } finally {
                c.disconnect();
            }
            JSONObject o = new JSONObject(body);
            if (o.has("error")) throw new Exception(o.getJSONObject("error").optString("message", "error"));
            JSONArray f = o.optJSONArray("features");
            if (f != null) {
                for (int i = 0; i < f.length(); i++) {
                    JSONObject g = f.getJSONObject(i).optJSONObject("geometry");
                    JSONArray paths = g == null ? null : g.optJSONArray("paths");
                    if (paths == null) continue;
                    for (int k = 0; k < paths.length(); k++) {
                        JSONArray p = paths.getJSONArray(k);
                        double[] line = new double[p.length() * 2];
                        JSONArray kp = new JSONArray();
                        for (int j = 0; j < p.length(); j++) {
                            JSONArray pt = p.getJSONArray(j);
                            line[j * 2] = pt.getDouble(1); // lat
                            line[j * 2 + 1] = pt.getDouble(0); // lon
                            kp.put(line[j * 2]).put(line[j * 2 + 1]);
                        }
                        if (line.length >= 4) {
                            all.add(line);
                            keep.put(kp);
                        }
                    }
                }
            }
            if (!o.optBoolean("exceededTransferLimit", false)) break;
        }
        FileOutputStream fo = new FileOutputStream(new File(dir, key + ".json"));
        fo.write(keep.toString().getBytes("UTF-8"));
        fo.close();
        return all;
    }

    /** The stored form: an array of lines, each [lat, lon, lat, lon, …]. */
    static List<double[]> parse(String s) throws Exception {
        List<double[]> out = new ArrayList<double[]>();
        JSONArray a = new JSONArray(s);
        for (int i = 0; i < a.length(); i++) {
            JSONArray p = a.getJSONArray(i);
            double[] line = new double[p.length()];
            for (int j = 0; j < line.length; j++) line[j] = p.getDouble(j);
            out.add(line);
        }
        return out;
    }
}
