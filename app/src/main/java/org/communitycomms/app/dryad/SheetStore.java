package org.communitycomms.app.dryad;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Sheets saved as text files in private app storage (nothing leaves the phone unless shared),
 * plus the age rules: delete when marked used, delete a set time after expiry.
 */
public final class SheetStore {
    public static final class Entry {
        public final String id;
        public final DryadSheet sheet;

        Entry(String id, DryadSheet sheet) {
            this.id = id;
            this.sheet = sheet;
        }
    }

    private final File dir;
    private final SharedPreferences prefs;

    public SheetStore(Context c) {
        dir = new File(c.getFilesDir(), "dryad");
        dir.mkdirs();
        prefs = c.getSharedPreferences("dryad", Context.MODE_PRIVATE);
    }

    // ---- age settings ----

    public boolean deleteWhenUsed() {
        return prefs.getBoolean("deleteWhenUsed", true);
    }

    public boolean deleteWhenExpired() {
        return prefs.getBoolean("deleteWhenExpired", true);
    }

    /** Hours an expired sheet is kept before auto-delete (lets late traffic still be read). */
    public int keepExpiredHours() {
        return prefs.getInt("keepExpiredHours", 24);
    }

    public void setAgeRules(boolean whenUsed, boolean whenExpired, int keepHours) {
        prefs.edit().putBoolean("deleteWhenUsed", whenUsed).putBoolean("deleteWhenExpired", whenExpired)
                .putInt("keepExpiredHours", keepHours).commit();
    }

    /** Applies the age rules. Returns how many sheets were deleted. */
    public int purge() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Entry e : loadAll(false)) {
            DryadSheet s = e.sheet;
            boolean gone = (s.used && deleteWhenUsed())
                    || (deleteWhenExpired() && s.validTo != 0 && now >= s.validTo + keepExpiredHours() * 3600000L);
            if (gone) {
                delete(e.id);
                n++;
            }
        }
        return n;
    }

    // ---- storage ----

    /** All sheets, ordered by start of validity then name. */
    public List<Entry> loadAll(boolean purgeFirst) {
        if (purgeFirst) purge();
        List<Entry> out = new ArrayList<Entry>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".txt")) continue;
            try {
                out.add(new Entry(f.getName().substring(0, f.getName().length() - 4), read(f)));
            } catch (Exception ignored) {
                // A damaged file is skipped rather than blocking every other sheet.
            }
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.sheet.validFrom != b.sheet.validFrom) return a.sheet.validFrom < b.sheet.validFrom ? -1 : 1;
                return a.sheet.name.compareToIgnoreCase(b.sheet.name);
            }
        });
        return out;
    }

    public DryadSheet load(String id) throws IOException {
        return read(file(id));
    }

    /** Stores a new sheet; returns its id. */
    public String add(DryadSheet s) throws IOException {
        String id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
        while (file(id).exists()) id += "x";
        write(id, s);
        return id;
    }

    /** Imports sheets, skipping any already held (same letters). Returns how many were new. */
    public int importAll(List<DryadSheet> sheets) throws IOException {
        java.util.Set<String> have = new java.util.HashSet<String>();
        for (Entry e : loadAll(false)) have.add(e.sheet.fingerprint());
        int added = 0;
        for (DryadSheet s : sheets) {
            if (have.add(s.fingerprint())) {
                add(s);
                added++;
            }
        }
        return added;
    }

    public void update(String id, DryadSheet s) throws IOException {
        write(id, s);
    }

    public void delete(String id) {
        file(id).delete();
    }

    private void write(String id, DryadSheet s) throws IOException {
        FileOutputStream out = new FileOutputStream(file(id));
        try {
            out.write(s.toText().getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static DryadSheet read(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return DryadSheet.parse(new String(bo.toByteArray(), "UTF-8"));
        } finally {
            in.close();
        }
    }

    private File file(String id) {
        return new File(dir, id + ".txt");
    }
}
