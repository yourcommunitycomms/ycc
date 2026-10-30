package org.communitycomms.app.geo;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Map picker options. Offline tile sets are folders of z/x/y.jpg|png (the layout ATAK's imagery
 * downloader and most tile tools produce) under the app's own storage, so no storage permission
 * is needed: Android/data/org.communitycomms.app/files/maps/NAME/z/x/y.jpg
 */
public final class MapPrefs {
    // Index 2 is the custom URL; new sources go on the end so saved choices keep their meaning.
    public static final String[] ONLINE_NAMES = {"OpenStreetMap", "Google satellite + labels (check Google's terms)", "Custom URL",
            "OpenTopoMap (topographic, contours)", "Google terrain (contours, relief; check Google's terms)",
            "Esri World Topo (check Esri's terms)"};
    public static final String[] ONLINE_URLS = {
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            "https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}",
            "",
            "https://tile.opentopomap.org/{z}/{x}/{y}.png",
            "https://mt1.google.com/vt/lyrs=p&x={x}&y={y}&z={z}",
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}"};
    public static final int CUSTOM = 2;

    private final SharedPreferences sp;
    private final Context context;

    public MapPrefs(Context c) {
        context = c.getApplicationContext();
        sp = c.getSharedPreferences("map", Context.MODE_PRIVATE);
    }

    public static File mapsRoot(Context c) {
        File ext = c.getExternalFilesDir("maps");
        File d = ext != null ? ext : new File(c.getFilesDir(), "maps");
        d.mkdirs();
        return d;
    }

    /** Offline tile sets found on the phone (folder names). */
    public List<String> offlineSets() {
        List<String> out = new ArrayList<String>();
        File[] dirs = mapsRoot(context).listFiles();
        if (dirs == null) return out;
        Arrays.sort(dirs);
        for (File d : dirs) {
            if (d.isDirectory() && !d.getName().startsWith("_")) out.add(d.getName());
        }
        return out;
    }

    /** Folder for the chosen offline set, or null. */
    public File offlineFolder() {
        String n = sp.getString("offlineSet", "");
        if (n.length() == 0) {
            List<String> sets = offlineSets();
            if (sets.isEmpty()) return null;
            n = sets.get(0);
        }
        File f = new File(mapsRoot(context), n);
        return f.isDirectory() ? f : null;
    }

    public String offlineSetName() {
        File f = offlineFolder();
        return f == null ? "" : f.getName();
    }

    public void setOfflineSet(String name) {
        sp.edit().putString("offlineSet", name).commit();
    }

    /** The on/off switch for fetching tiles from the internet. Off by default. */
    public boolean onlineEnabled() {
        return sp.getBoolean("onlineEnabled", false);
    }

    public void setOnlineEnabled(boolean on) {
        sp.edit().putBoolean("onlineEnabled", on).commit();
    }

    /** Online tiles switched on for a while from the "no offline tiles here" prompt (ms epoch, 0 = not). */
    public long onlineUntil() {
        return sp.getLong("onlineUntil", 0);
    }

    public void setOnlineFor(long ms) {
        sp.edit().putLong("onlineUntil", ms <= 0 ? 0 : System.currentTimeMillis() + ms).commit();
    }

    /** Whether tiles may be fetched now: the switch or a temporary window, and never while radio silent. */
    public boolean onlineActive() {
        if (new org.communitycomms.app.Prefs(context).radioSilent()) return false;
        return (onlineEnabled() || System.currentTimeMillis() < onlineUntil()) && onlineUrl().length() > 0;
    }

    public int onlinePreset() {
        return sp.getInt("onlinePreset", 0);
    }

    public String onlineUrl() {
        return urlOfPreset(onlinePreset());
    }

    /** A preset's tile address on this phone (see onlineUrl). */
    public String urlOfPreset(int p) {
        if (p == CUSTOM || p < 0 || p >= ONLINE_URLS.length) return sp.getString("customUrl", "");
        // Android 4.x cannot make the secure connections OSM, OpenTopoMap and Esri now require, so
        // their tiles never load there: use Google's road map instead (it still works on 4.x).
        if (android.os.Build.VERSION.SDK_INT < 20 && (p == 0 || p == 3 || p == 5)) return OLD_ANDROID_URL;
        return ONLINE_URLS[p];
    }

    /**
     * The source the user chose, even where Android 4.x uses Google's road map instead: the LAN
     * storage location is shared by every phone, so all of them look in the chosen source's folder.
     */
    public String chosenUrl() {
        int p = onlinePreset();
        if (p == CUSTOM || p < 0 || p >= ONLINE_URLS.length) return sp.getString("customUrl", "");
        return ONLINE_URLS[p];
    }

    /** Road map for Android 4.x in place of the sources it cannot reach. */
    public static final String OLD_ANDROID_URL = "https://mt1.google.com/vt/lyrs=m&x={x}&y={y}&z={z}";

    /** True when the chosen source was swapped for Google's road map (Android 4.x). */
    public boolean usingOldAndroidFallback() {
        return OLD_ANDROID_URL.equals(onlineUrl());
    }

    // ---- overlay layer: "" none, "set:NAME" an offline set, "web:N" an online source ----

    public String overlay() {
        return sp.getString("overlay", "");
    }

    public int overlayOpacity() {
        return sp.getInt("overlayOpacity", 50);
    }

    public void setOverlay(String which, int opacityPct) {
        sp.edit().putString("overlay", which).putInt("overlayOpacity", opacityPct).commit();
    }

    /** Applies the overlay choice to a map; online fetching follows the same rules as the base layer. */
    public void applyOverlay(TileMapView map) {
        String o = overlay();
        if (o.startsWith("set:")) {
            File f = new File(mapsRoot(context), o.substring(4));
            map.setOverlay(f.isDirectory() ? f : null, null, null, overlayOpacity());
        } else if (o.startsWith("web:")) {
            int i;
            try {
                i = Integer.parseInt(o.substring(4));
            } catch (NumberFormatException e) {
                i = -1;
            }
            String url = i >= 0 && i < ONLINE_URLS.length && i != CUSTOM ? ONLINE_URLS[i] : "";
            if (url.length() == 0) {
                map.setOverlay(null, null, null, 0);
                return;
            }
            map.setOverlay(null, onlineActive() ? url : null, cacheFor(url), overlayOpacity());
        } else {
            map.setOverlay(null, null, null, 0);
        }
    }

    /** The online overlay's tile URL (even while online is off), or "" when the overlay is not online. */
    public String overlayUrl() {
        String o = overlay();
        if (!o.startsWith("web:")) return "";
        try {
            int i = Integer.parseInt(o.substring(4));
            return i >= 0 && i < ONLINE_URLS.length && i != CUSTOM ? ONLINE_URLS[i] : "";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    public String customUrl() {
        return sp.getString("customUrl", "");
    }

    public void setOnline(int preset, String customUrl) {
        sp.edit().putInt("onlinePreset", preset).putString("customUrl", customUrl).commit();
    }

    /** Online tiles are kept here so areas you have looked at work offline later. */
    public File onlineCache() {
        // One cache per tile source, so Google and OSM tiles never mix. The old shared cache could
        // hold OSM's "Access blocked" tiles, so it is removed.
        File old = new File(mapsRoot(context), "_online-cache");
        if (old.exists()) deleteTree(old);
        return cacheFor(onlineUrl());
    }

    /** The folder a tile source's tiles are saved in on this phone. */
    public File cacheOf(String url) {
        return cacheFor(url);
    }

    private File cacheFor(String url) {
        File d = new File(new File(mapsRoot(context), "_tile-cache"), Integer.toHexString(url.hashCode()));
        d.mkdirs();
        return d;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    // Last map view, so it reopens where you left it.
    public double centerLat() {
        return Double.longBitsToDouble(sp.getLong("cLat", Double.doubleToLongBits(-33.8568)));
    }

    public double centerLon() {
        return Double.longBitsToDouble(sp.getLong("cLon", Double.doubleToLongBits(151.2153)));
    }

    public int zoom() {
        return sp.getInt("zoom", 12);
    }

    public void saveView(double lat, double lon, int zoom) {
        sp.edit().putLong("cLat", Double.doubleToLongBits(lat)).putLong("cLon", Double.doubleToLongBits(lon))
                .putInt("zoom", zoom).apply();
    }
}
