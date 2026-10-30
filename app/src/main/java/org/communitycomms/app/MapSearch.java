package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.geo.Geo;
import org.communitycomms.app.geo.LanTiles;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Full map > search: suburbs, towns and streets. Works offline from lists kept on this phone, made
 * once for the country the map is in (asked first):
 * <ul>
 * <li>Suburbs and towns from GeoNames (free, CC BY): Australia's own list (about 6 MB to fetch), the USA
 * from the world list of places with 500+ people (about 14 MB, only US kept), other countries from their
 * own list.</li>
 * <li>Streets from OpenStreetMap (Overpass) within a chosen distance of the map centre.</li>
 * </ul>
 * The suburbs list is also put on the LAN storage (Search/places-CC.tsv), so other phones get it from
 * there without internet. With internet, OpenStreetMap's search (Nominatim) fills in anything else.
 * Each list line: name TAB kind (P place, S street) TAB lat TAB lon TAB area.
 */
final class MapSearch {
    private MapSearch() {
    }

    interface Go {
        void to(double lat, double lon, int zoom, String label);
    }

    static final class Hit {
        String name, area;
        char kind;
        double lat, lon, km;

        @Override
        public String toString() {
            String k = kind == 'S' ? "Street" : kind == 'O' ? "Online" : "Place";
            return name + (area.length() > 0 ? ", " + area : "") + "\n" + k + " · " + Geo.distance(km * 1000);
        }
    }

    private static final String UA = "YourCommunityCommunications (offline radio app; map search)";
    private static final String[] AU_STATES = {"", "ACT", "NSW", "NT", "QLD", "SA", "TAS", "VIC", "WA"};

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "search");
        d.mkdirs();
        return d;
    }

    static File placesFile(Context c, String cc) {
        return new File(dir(c), "places-" + cc + ".tsv");
    }

    static File streetsFile(Context c) {
        return new File(dir(c), "streets.tsv");
    }

    /** Areas whose streets are kept: "lat,lon,km" per line. */
    static File streetAreas(Context c) {
        return new File(dir(c), "streets-areas.txt");
    }

    /** Country code for a position: Australia and the USA by their outline boxes, else OpenStreetMap (online). */
    static String countryFor(double lat, double lon) {
        if (lat < -9 && lat > -44.5 && lon > 112 && lon < 154.5) return "AU";
        if ((lat > 24 && lat < 49.5 && lon > -125.5 && lon < -66) || (lat > 51 && lat < 72 && lon > -170 && lon < -129)
                || (lat > 18.5 && lat < 22.5 && lon > -161 && lon < -154)) return "US";
        try {
            JSONObject o = new JSONObject(get("https://nominatim.openstreetmap.org/reverse?zoom=3&format=jsonv2&lat="
                    + lat + "&lon=" + lon));
            JSONObject a = o.optJSONObject("address");
            String cc = a == null ? "" : a.optString("country_code", "");
            return cc.toUpperCase(Locale.US);
        } catch (Exception e) {
            return "";
        }
    }

    static String countryName(String cc) {
        if (cc.equals("AU")) return "Australia";
        if (cc.equals("US")) return "the USA";
        String n = new Locale("", cc).getDisplayCountry(Locale.UK);
        return n.length() > 0 ? n : cc;
    }

    // ---- fetching ----

    static String get(String url) throws Exception {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new Exception("the server answered " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0 && out.size() < 16 * 1024 * 1024) out.write(b, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(90000);
        c.setRequestProperty("User-Agent", UA);
        return c;
    }

    interface Progress {
        void say(String s);
    }

    /** Suburbs and towns for a country: from the LAN storage if it has them, else GeoNames. Returns how many. */
    static int downloadPlaces(Context c, String cc, Progress p) throws Exception {
        File out = placesFile(c, cc);
        LanTiles lan = new LanTiles(c);
        if (lan.configured()) {
            p.say("Looking on the LAN storage…");
            File tmp = new File(out.getPath() + ".part");
            if (lan.getPathToFile("Search/places-" + cc + ".tsv", tmp) && tmp.length() > 0 && tmp.renameTo(out)) return lines(out);
            tmp.delete();
        }
        boolean world = cc.equals("US") || (!cc.equals("AU") && bigCountry(cc));
        String url = world ? "https://download.geonames.org/export/dump/cities500.zip"
                : "https://download.geonames.org/export/dump/" + cc + ".zip";
        p.say("Downloading " + (world ? "the world places list (about 14 MB, " + cc + " kept)" : countryName(cc) + "'s places") + "…");
        HttpURLConnection con = open(url);
        int count = 0;
        Writer w = new OutputStreamWriter(new FileOutputStream(out + ".part"), "UTF-8");
        try {
            if (con.getResponseCode() >= 400) throw new Exception("GeoNames answered " + con.getResponseCode());
            ZipInputStream z = new ZipInputStream(con.getInputStream());
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!e.getName().endsWith(".txt") || e.getName().startsWith("readme")) continue;
                BufferedReader r = new BufferedReader(new InputStreamReader(z, "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    String[] f = line.split("\t", -1);
                    if (f.length < 11 || !f[8].equals(cc)) continue;
                    if (!f[6].equals("P")) continue; // populated places: suburbs, towns, cities
                    String area = f[10];
                    if (cc.equals("AU")) {
                        try {
                            area = AU_STATES[Integer.parseInt(f[10])];
                        } catch (RuntimeException ignored) {
                            area = "";
                        }
                    }
                    w.write(f[1] + "\tP\t" + f[4] + "\t" + f[5] + "\t" + area + "\n");
                    if (++count % 5000 == 0) p.say("Read " + count + " places…");
                }
                break;
            }
        } finally {
            w.close();
            con.disconnect();
        }
        File part = new File(out + ".part");
        if (count == 0) {
            part.delete();
            throw new Exception("no places found for " + cc);
        }
        out.delete();
        part.renameTo(out);
        if (lan.configured()) {
            p.say("Putting it on the LAN storage for the other phones…");
            lan.putPath("Search/places-" + cc + ".tsv", out);
        }
        return count;
    }

    /** Countries whose own GeoNames file is very large: use the world places list instead. */
    private static boolean bigCountry(String cc) {
        return "CA GB DE FR IN CN RU BR MX JP IT ES".contains(cc);
    }

    /** Streets within km of a point from OpenStreetMap, one entry per name per kilometre. Returns how many. */
    static int downloadStreets(Context c, double lat, double lon, int km, Progress p) throws Exception {
        p.say("Asking OpenStreetMap for the streets within " + km + " km (can take a minute)…");
        String q = "[out:csv(name,::lat,::lon;false)][timeout:120];way[highway][name](around:" + km * 1000 + ","
                + String.format(Locale.US, "%.5f,%.5f", lat, lon) + ");out center;";
        HttpURLConnection con = open("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8"));
        Set<String> seen = new HashSet<String>();
        File f = streetsFile(c);
        if (f.exists()) {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] x = line.split("\t");
                if (x.length >= 4) seen.add(key(x[0], x[2], x[3]));
            }
            r.close();
        }
        int count = 0;
        Writer w = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
        try {
            if (con.getResponseCode() >= 400) throw new Exception("OpenStreetMap answered " + con.getResponseCode() + " (busy: try again later)");
            BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] x = line.split("\t");
                if (x.length < 3 || x[0].trim().length() == 0) continue;
                String k = key(x[0], x[1], x[2]);
                if (!seen.add(k)) continue;
                w.write(x[0].trim() + "\tS\t" + x[1] + "\t" + x[2] + "\t\n");
                if (++count % 5000 == 0) p.say("Kept " + count + " streets…");
            }
        } finally {
            w.close();
            con.disconnect();
        }
        Writer a = new OutputStreamWriter(new FileOutputStream(streetAreas(c), true), "UTF-8");
        a.write(String.format(Locale.US, "%.4f,%.4f,%d\n", lat, lon, km));
        a.close();
        return count;
    }

    /** One entry per street name per about a kilometre (OpenStreetMap splits streets into many pieces). */
    private static String key(String name, String lat, String lon) {
        try {
            return name.trim().toLowerCase(Locale.US) + "|" + Math.round(Double.parseDouble(lat) * 100) + "|"
                    + Math.round(Double.parseDouble(lon) * 100);
        } catch (RuntimeException e) {
            return name.trim().toLowerCase(Locale.US);
        }
    }

    private static int lines(File f) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        int n = 0;
        while (r.readLine() != null) n++;
        r.close();
        return n;
    }

    /** Is there a street list covering this point? */
    static boolean streetsCover(Context c, double lat, double lon) {
        File f = streetAreas(c);
        if (!f.exists()) return false;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            boolean in = false;
            while ((line = r.readLine()) != null) {
                String[] x = line.split(",");
                if (x.length == 3 && Geo.distanceM(lat, lon, Double.parseDouble(x[0]), Double.parseDouble(x[1]))
                        < Integer.parseInt(x[2].trim()) * 1000 * 0.8) in = true;
            }
            r.close();
            return in;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- searching ----

    /** Offline search of every list kept: best matches first (starts with, then word start, then nearer). */
    static List<Hit> searchOffline(Context c, String query, double lat, double lon) {
        final String q = query.trim().toLowerCase(Locale.US);
        List<Hit> out = new ArrayList<Hit>();
        if (q.length() < 2) return out;
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".tsv")) continue;
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 65536);
                String line;
                while ((line = r.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    String name = line.substring(0, tab);
                    if (!name.toLowerCase(Locale.US).contains(q)) continue;
                    String[] x = line.split("\t", -1);
                    if (x.length < 4) continue;
                    Hit h = new Hit();
                    h.name = name;
                    h.kind = x[1].length() > 0 ? x[1].charAt(0) : 'P';
                    h.lat = Double.parseDouble(x[2]);
                    h.lon = Double.parseDouble(x[3]);
                    h.area = x.length > 4 ? x[4] : "";
                    h.km = Geo.distanceM(lat, lon, h.lat, h.lon) / 1000;
                    out.add(h);
                }
                r.close();
            } catch (Exception ignored) {
            }
        }
        Collections.sort(out, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                int ra = rank(a.name, q), rb = rank(b.name, q);
                if (ra != rb) return ra - rb;
                return Double.compare(a.km, b.km);
            }
        });
        // A long street is kept once per kilometre: show it once per 3 km (the nearest piece first).
        List<Hit> one = new ArrayList<Hit>();
        for (Hit h : out) {
            boolean dup = false;
            for (Hit k : one) {
                if (k.kind == h.kind && k.name.equalsIgnoreCase(h.name) && Geo.distanceM(k.lat, k.lon, h.lat, h.lon) < 3000) {
                    dup = true;
                    break;
                }
            }
            if (!dup) one.add(h);
            if (one.size() >= 60) break;
        }
        return one;
    }

    private static int rank(String name, String q) {
        String n = name.toLowerCase(Locale.US);
        if (n.equals(q)) return 0;
        if (n.startsWith(q)) return 1;
        if (n.contains(" " + q)) return 2;
        return 3;
    }

    /** OpenStreetMap search (needs internet), near the map centre first. */
    static List<Hit> searchOnline(String query, String cc, double lat, double lon) throws Exception {
        String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=15&q=" + URLEncoder.encode(query, "UTF-8")
                + (cc.length() == 2 ? "&countrycodes=" + cc.toLowerCase(Locale.US) : "")
                + String.format(Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f", lon - 1, lat + 1, lon + 1, lat - 1);
        JSONArray a = new JSONArray(get(url));
        List<Hit> out = new ArrayList<Hit>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Hit h = new Hit();
            String d = o.optString("display_name", "");
            int comma = d.indexOf(',');
            h.name = comma > 0 ? d.substring(0, comma) : d;
            h.area = comma > 0 ? d.substring(comma + 1).trim() : "";
            if (h.area.length() > 60) h.area = h.area.substring(0, 58) + "…";
            h.kind = 'O';
            h.lat = o.getDouble("lat");
            h.lon = o.getDouble("lon");
            h.km = Geo.distanceM(lat, lon, h.lat, h.lon) / 1000;
            out.add(h);
        }
        return out;
    }

    // ---- the screen ----

    /** The search box with results; offers the offline lists for this country first if they are not here yet. */
    static void show(final Activity a, final double lat, final double lon, final Go go) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String cc = countryFor(lat, lon);
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (a.isFinishing()) return;
                        boolean havePlaces = cc.length() == 2 && placesFile(a, cc).exists();
                        boolean haveStreets = streetsCover(a, lat, lon);
                        if (cc.length() == 2 && (!havePlaces || !haveStreets)
                                && !a.getSharedPreferences("map", Context.MODE_PRIVATE).getBoolean("searchAsked" + cc, false)) {
                            offerDownload(a, cc, lat, lon, !havePlaces, !haveStreets, new Runnable() {
                                @Override
                                public void run() {
                                    box(a, cc, lat, lon, go);
                                }
                            });
                        } else {
                            box(a, cc, lat, lon, go);
                        }
                    }
                });
            }
        }, "map-search-country").start();
    }

    private static void offerDownload(final Activity a, final String cc, final double lat, final double lon,
                                      final boolean places, final boolean streets, final Runnable then) {
        a.getSharedPreferences("map", Context.MODE_PRIVATE).edit().putBoolean("searchAsked" + cc, true).commit();
        final String[] kms = {"5 km", "10 km", "20 km", "40 km"};
        final int[] kmv = {5, 10, 20, 40};
        final int[] pick = {1};
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView t = new TextView(a);
        t.setText("Search works without internet once these are kept on this phone:\n"
                + (places ? "• Suburbs and towns of " + countryName(cc) + (cc.equals("US") ? " (towns of 500+ people; about 14 MB to fetch, ~1 MB kept)"
                : cc.equals("AU") ? " (about 6 MB to fetch, ~1 MB kept)" : "") + ", also put on the LAN storage for the other phones\n" : "")
                + (streets ? "• Streets around the map centre, within:" : ""));
        box.addView(t);
        final android.widget.Spinner sp = new android.widget.Spinner(a);
        sp.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, kms));
        sp.setSelection(1);
        if (streets) box.addView(sp);
        TextView n = new TextView(a);
        n.setTextSize(12);
        n.setText("Until then (and for anything not in them) it searches OpenStreetMap online. Data: GeoNames (CC BY) and "
                + "OpenStreetMap contributors (ODbL). Map menu > Search lists… adds more later.");
        box.addView(n);
        new AlertDialog.Builder(a).setTitle("Offline search for " + countryName(cc) + "?").setView(box)
                .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        pick[0] = sp.getSelectedItemPosition();
                        download(a, cc, lat, lon, places, streets ? kmv[pick[0]] : 0, then);
                    }
                })
                .setNegativeButton("Not now", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        then.run();
                    }
                }).show();
    }

    /** Fetches the lists in the background with a progress note, then runs then. */
    static void download(final Activity a, final String cc, final double lat, final double lon, final boolean places, final int streetKm,
                         final Runnable then) {
        final AlertDialog prog = new AlertDialog.Builder(a).setTitle("Search lists").setMessage("Starting…").setCancelable(false).show();
        final Progress p = new Progress() {
            @Override
            public void say(final String s) {
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (prog.isShowing()) prog.setMessage(s);
                    }
                });
            }
        };
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder done = new StringBuilder();
                if (places) {
                    try {
                        done.append(downloadPlaces(a, cc, p)).append(" suburbs and towns kept. ");
                    } catch (Exception e) {
                        done.append("Suburbs and towns: could not get them (").append(e.getMessage()).append("). ");
                    }
                }
                if (streetKm > 0) {
                    try {
                        done.append(downloadStreets(a, lat, lon, streetKm, p)).append(" streets kept. ");
                    } catch (Exception e) {
                        done.append("Streets: could not get them (").append(e.getMessage()).append("). ");
                    }
                }
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (prog.isShowing()) prog.dismiss();
                        Toast.makeText(a, done.toString().trim(), Toast.LENGTH_LONG).show();
                        if (then != null) then.run();
                    }
                });
            }
        }, "map-search-download").start();
    }

    private static void box(final Activity a, final String cc, final double lat, final double lon, final Go go) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), 0);
        LinearLayout row = new LinearLayout(a);
        final EditText q = new EditText(a);
        q.setSingleLine(true);
        q.setHint("Suburb, town or street");
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        row.addView(q, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button find = new Button(a);
        find.setText("Search");
        find.setAllCaps(false);
        row.addView(find);
        box.addView(row);
        final TextView state = new TextView(a);
        state.setTextSize(12);
        boolean havePlaces = cc.length() == 2 && placesFile(a, cc).exists();
        state.setText((havePlaces ? "Offline: " + countryName(cc) + " places" : "No offline places list yet")
                + (streetsCover(a, lat, lon) ? " + streets here" : "") + ". Online search fills in when there is internet.");
        box.addView(state);
        final ListView list = new ListView(a);
        final List<Hit> hits = new ArrayList<Hit>();
        final ArrayAdapter<Hit> ad = new ArrayAdapter<Hit>(a, android.R.layout.simple_list_item_1, hits);
        list.setAdapter(ad);
        box.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (320 * d)));
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("Search the map").setView(box)
                .setNeutralButton("Search lists…", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        a.getSharedPreferences("map", Context.MODE_PRIVATE).edit().putBoolean("searchAsked" + cc, false).commit();
                        offerDownload(a, cc.length() == 2 ? cc : "AU", lat, lon, true, true, new Runnable() {
                            @Override
                            public void run() {
                            }
                        });
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
        final Runnable run = new Runnable() {
            @Override
            public void run() {
                final String text = q.getText().toString().trim();
                if (text.length() < 2) return;
                state.setText("Searching…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final List<Hit> got = searchOffline(a, text, lat, lon);
                        String note = got.size() + " found on this phone";
                        if (got.size() < 8) {
                            try {
                                List<Hit> on = searchOnline(text, cc, lat, lon);
                                got.addAll(on);
                                note += ", " + on.size() + " online";
                            } catch (Exception e) {
                                note += " (online search not reachable)";
                            }
                        }
                        final String n = note;
                        a.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                hits.clear();
                                hits.addAll(got);
                                ad.notifyDataSetChanged();
                                state.setText(n + (got.isEmpty() ? ". Try fewer letters, or Search lists… to add streets here." : ". Tap one to go there."));
                            }
                        });
                    }
                }, "map-search").start();
            }
        };
        find.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run.run();
            }
        });
        q.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int id, android.view.KeyEvent e) {
                run.run();
                return true;
            }
        });
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                Hit h = hits.get(pos);
                dlg.dismiss();
                go.to(h.lat, h.lon, h.kind == 'P' ? 14 : 17, h.name);
            }
        });
    }
}
