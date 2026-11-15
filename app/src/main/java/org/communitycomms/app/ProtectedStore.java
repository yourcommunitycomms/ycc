package org.communitycomms.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Protected notes and calendar entries: encrypted with a password, kept only on this phone in their own
 * file (files/protected.dat), never in the channel, the setup QR code, Save my config, LAN/NOMAD sync or any
 * send path. The password and keys are never written anywhere; the keys stay in memory only while unlocked
 * and are wiped by lock(). There is no recovery: a forgotten password means erasing them (eraseAll).
 *
 * File: {"v":1,"salt":hex,"iter":n,"chk":hex,"items":{id: hex(IV|ct|MAC)}}. Each item decrypts to the JSON of
 * a note ("k":"n") or a calendar entry ("k":"c"). See ProtectedCrypto for the crypto.
 */
public final class ProtectedStore {
    static final String FILE = "protected.dat";
    private static ProtectedCrypto.Keys keys;
    private static final Map<String, String> sealed = new LinkedHashMap<String, String>();
    private static final List<CalStore.Note> notes = new ArrayList<CalStore.Note>();
    private static final List<CalStore.Entry> entries = new ArrayList<CalStore.Entry>();
    private static byte[] salt, chk;
    private static int iter = ProtectedCrypto.DEFAULT_ITERATIONS;
    /** Items that failed their check at the last unlock (changed or damaged file). */
    public static int badItems;

    private ProtectedStore() {
    }

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), FILE);
    }

    /** A password has been set on this phone. */
    public static synchronized boolean hasPassword(Context c) {
        if (!load(c)) return false;
        return salt != null && chk != null;
    }

    public static synchronized boolean unlocked() {
        return keys != null;
    }

    /** Forget the keys and everything decrypted. */
    public static synchronized void lock() {
        if (keys != null) keys.wipe();
        keys = null;
        notes.clear();
        entries.clear();
    }

    /** First use: set the password (slow, call off the UI thread). */
    public static synchronized boolean create(Context c, char[] pw) {
        try {
            lock();
            sealed.clear();
            salt = ProtectedCrypto.random(16);
            iter = ProtectedCrypto.DEFAULT_ITERATIONS;
            keys = ProtectedCrypto.derive(pw, salt, iter);
            chk = ProtectedCrypto.verifier(keys);
            return write(c);
        } catch (Exception e) {
            lock();
            return false;
        } finally {
            Arrays.fill(pw, '\0');
        }
    }

    /** False for a wrong password (slow, call off the UI thread). */
    public static synchronized boolean unlock(Context c, char[] pw) {
        try {
            lock();
            if (!load(c) || salt == null || chk == null) return false;
            ProtectedCrypto.Keys k = ProtectedCrypto.derive(pw, salt, iter);
            if (!ProtectedCrypto.check(k, chk)) {
                k.wipe();
                return false;
            }
            keys = k;
            badItems = 0;
            for (Map.Entry<String, String> e : sealed.entrySet()) {
                byte[] plain = ProtectedCrypto.open(keys, ProtectedCrypto.unhex(e.getValue()));
                if (plain == null) {
                    badItems++;
                    continue;
                }
                JSONObject o = new JSONObject(new String(plain, "UTF-8"));
                Arrays.fill(plain, (byte) 0);
                if ("n".equals(o.optString("k"))) {
                    CalStore.Note n = new CalStore.Note();
                    n.id = e.getKey();
                    n.name = o.optString("name");
                    n.body = o.optString("body");
                    n.date = o.optLong("date");
                    n.edited = o.optLong("edited");
                    n.prot = true;
                    notes.add(n);
                } else {
                    CalStore.Entry en = CalStore.fromJson(o, false);
                    en.id = e.getKey();
                    en.prot = true;
                    entries.add(en);
                }
            }
            for (CalStore.Entry en : entries) CalStore.schedule(c, en); // reminders come back after a restart
            return true;
        } catch (Exception e) {
            lock();
            return false;
        } finally {
            Arrays.fill(pw, '\0');
        }
    }

    public static synchronized List<CalStore.Note> notes() {
        return new ArrayList<CalStore.Note>(notes);
    }

    public static synchronized List<CalStore.Entry> entries() {
        return new ArrayList<CalStore.Entry>(entries);
    }

    public static synchronized CalStore.Note note(String id) {
        for (CalStore.Note n : notes) if (n.id.equals(id)) return n;
        return null;
    }

    /** False while locked (nothing is saved then). */
    public static synchronized boolean saveNote(Context c, CalStore.Note n, boolean delete) {
        if (keys == null) return false;
        n.prot = true;
        for (Iterator<CalStore.Note> it = notes.iterator(); it.hasNext(); ) if (it.next().id.equals(n.id)) it.remove();
        try {
            if (delete) sealed.remove(n.id);
            else {
                JSONObject o = new JSONObject().put("k", "n").put("name", n.name).put("body", n.body).put("date", n.date).put("edited", n.edited);
                sealed.put(n.id, seal(o));
                notes.add(n);
            }
            return write(c);
        } catch (Exception e) {
            return false;
        }
    }

    /** Saves or (removed) deletes. False while locked. */
    public static synchronized boolean saveEntry(Context c, CalStore.Entry e) {
        if (keys == null) return false;
        e.prot = true;
        e.team = false;
        for (Iterator<CalStore.Entry> it = entries.iterator(); it.hasNext(); ) if (it.next().id.equals(e.id)) it.remove();
        try {
            if (e.removed) sealed.remove(e.id);
            else {
                JSONObject o = CalStore.toJson(e).put("k", "c");
                sealed.put(e.id, seal(o));
                entries.add(e);
            }
            boolean ok = write(c);
            CalStore.schedule(c, e);
            return ok;
        } catch (Exception x) {
            return false;
        }
    }

    /** Needs the old password; re-encrypts everything with a new salt. Slow, call off the UI thread. */
    public static synchronized boolean changePassword(Context c, char[] oldPw, char[] newPw) {
        try {
            if (!unlock(c, oldPw)) return false;
            List<CalStore.Note> ns = notes();
            List<CalStore.Entry> es = entries();
            lock();
            salt = ProtectedCrypto.random(16);
            iter = ProtectedCrypto.DEFAULT_ITERATIONS;
            keys = ProtectedCrypto.derive(newPw, salt, iter);
            chk = ProtectedCrypto.verifier(keys);
            sealed.clear();
            for (CalStore.Note n : ns) {
                sealed.put(n.id, seal(new JSONObject().put("k", "n").put("name", n.name).put("body", n.body).put("date", n.date).put("edited", n.edited)));
                notes.add(n);
            }
            for (CalStore.Entry e : es) {
                sealed.put(e.id, seal(CalStore.toJson(e).put("k", "c")));
                entries.add(e);
            }
            boolean ok = write(c);
            lock();
            return ok;
        } catch (Exception e) {
            lock();
            return false;
        } finally {
            Arrays.fill(oldPw, '\0');
            Arrays.fill(newPw, '\0');
        }
    }

    /** How many protected items are stored (works while locked). */
    public static synchronized int count(Context c) {
        load(c);
        return sealed.size();
    }

    /** Forgot the password: everything protected, and the password itself, is deleted. No password needed. */
    public static synchronized void eraseAll(Context c) {
        load(c);
        for (String id : sealed.keySet()) { // cancel any reminder left for them
            CalStore.Entry e = new CalStore.Entry();
            e.id = id;
            e.removed = true;
            CalStore.schedule(c, e);
        }
        lock();
        sealed.clear();
        salt = null;
        chk = null;
        File f = file(c);
        overwrite(f);
        f.delete();
        new File(f.getPath() + ".tmp").delete();
    }

    /** For the duress / lost-phone wipe: no reminders touched, just gone. */
    public static synchronized void forget(Context c) {
        lock();
        sealed.clear();
        salt = null;
        chk = null;
        File f = file(c);
        overwrite(f);
        f.delete();
        new File(f.getPath() + ".tmp").delete();
    }

    // ---- file ----

    private static String seal(JSONObject o) throws Exception {
        byte[] plain = o.toString().getBytes("UTF-8");
        String s = ProtectedCrypto.hex(ProtectedCrypto.seal(keys, plain));
        Arrays.fill(plain, (byte) 0);
        return s;
    }

    private static boolean load(Context c) {
        File f = file(c);
        sealed.clear();
        salt = null;
        chk = null;
        if (!f.isFile()) return true;
        try {
            String t = ChannelArray.read(c, FILE);
            if (t.length() == 0) return true;
            JSONObject o = new JSONObject(t);
            salt = ProtectedCrypto.unhex(o.optString("salt", null));
            chk = ProtectedCrypto.unhex(o.optString("chk", null));
            iter = o.optInt("iter", ProtectedCrypto.DEFAULT_ITERATIONS);
            JSONObject items = o.optJSONObject("items");
            if (items != null) {
                List<String> ids = new ArrayList<String>();
                for (Iterator<String> it = items.keys(); it.hasNext(); ) ids.add(it.next());
                Collections.sort(ids, new Comparator<String>() {
                    @Override
                    public int compare(String a, String b) {
                        return a.compareTo(b);
                    }
                });
                for (String id : ids) sealed.put(id, items.getString(id));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean write(Context c) {
        try {
            JSONObject items = new JSONObject();
            for (Map.Entry<String, String> e : sealed.entrySet()) items.put(e.getKey(), e.getValue());
            JSONObject o = new JSONObject().put("v", 1).put("salt", ProtectedCrypto.hex(salt)).put("iter", iter)
                    .put("chk", ProtectedCrypto.hex(chk)).put("items", items);
            File f = file(c), tmp = new File(f.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(o.toString().getBytes("UTF-8"));
            out.getFD().sync();
            out.close();
            return tmp.renameTo(f) || (f.delete() && tmp.renameTo(f));
        } catch (Exception e) {
            return false;
        }
    }

    private static void overwrite(File f) {
        if (!f.isFile()) return;
        try {
            long n = f.length();
            FileOutputStream out = new FileOutputStream(f);
            byte[] z = new byte[4096];
            for (long done = 0; done < n; done += z.length) out.write(z, 0, (int) Math.min(z.length, n - done));
            out.getFD().sync();
            out.close();
        } catch (Exception ignored) {
        }
    }
}
