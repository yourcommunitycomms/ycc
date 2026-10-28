package org.communitycomms.app.geo;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All known POIs, kept on the phone so they survive restarts. Expired ones are dropped. */
public final class PoiStore {
    private static PoiStore instance;
    private final File file;
    private final Map<Long, Poi> pois = new LinkedHashMap<Long, Poi>();

    public static synchronized PoiStore get(Context c) {
        if (instance == null) instance = new PoiStore(c.getApplicationContext());
        return instance;
    }

    /** For the secret wipe. */
    public static synchronized void wipe(Context c) {
        new File(c.getApplicationContext().getFilesDir(), "pois.txt").delete();
        instance = null;
    }

    private PoiStore(Context c) {
        file = new File(c.getFilesDir(), "pois.txt");
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String l;
            while ((l = r.readLine()) != null) {
                Poi p = Poi.fromLine(l);
                if (p != null) pois.put(p.id, p);
            }
            r.close();
        } catch (Exception ignored) {
            // no file yet
        }
    }

    public synchronized List<Poi> all() {
        prune();
        return new ArrayList<Poi>(pois.values());
    }

    /** Stores p if it is new or a newer version. Returns true if anything changed. */
    public synchronized boolean apply(Poi p) {
        if (p == null || p.expired(System.currentTimeMillis())) {
            // An edit that expires it now means "remove".
            if (p != null) {
                Poi old = pois.get(p.id);
                if (old != null && p.version > old.version) {
                    pois.remove(p.id);
                    save();
                    return true;
                }
            }
            return false;
        }
        Poi old = pois.get(p.id);
        if (old != null && old.version >= p.version) return false;
        pois.put(p.id, p);
        save();
        return true;
    }

    public synchronized void remove(long id) {
        if (pois.remove(id) != null) save();
    }

    private void prune() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        Iterator<Poi> it = pois.values().iterator();
        while (it.hasNext()) {
            if (it.next().expired(now)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) save();
    }

    private void save() {
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            for (Poi p : pois.values()) w.write(p.toLine() + "\n");
            w.close();
        } catch (Exception ignored) {
        }
    }
}
