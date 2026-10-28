package org.communitycomms.app;

import android.app.Activity;
import android.content.Context;

import java.io.File;

/**
 * Erases everything this app stores on the phone: channel names and keys, settings and PIN,
 * chat and report history, map points, DRYAD sheets, received and cached files, offline map
 * tiles in the app's own folder. Nothing is sent on air, and nothing outside the app is touched.
 */
public final class AppWipe {
    private AppWipe() {
    }

    public static void wipe(Activity a) {
        Context c = a.getApplicationContext();
        RadioService s = RadioService.instance();
        if (s != null) s.stopSilently();
        PinLock.lockNow();
        org.communitycomms.app.geo.PoiStore.wipe(c);
        org.communitycomms.app.msg.MessageLog.forget();
        org.communitycomms.app.msg.PrivateKeys.forget();
        Contacts.forget(); // contacts, their notes and last positions
        // Every preferences file, cleared in memory as well as on disk.
        File prefsDir = new File(c.getApplicationInfo().dataDir, "shared_prefs");
        String[] names = prefsDir.list();
        if (names != null) {
            for (String n : names) {
                if (n.endsWith(".xml")) {
                    c.getSharedPreferences(n.substring(0, n.length() - 4), Context.MODE_PRIVATE).edit().clear().commit();
                }
            }
        }
        deleteChildren(prefsDir);
        deleteChildren(c.getFilesDir());
        deleteChildren(c.getCacheDir());
        File ext = c.getExternalFilesDir(null);
        if (ext != null) deleteChildren(ext);
        File extCache = c.getExternalCacheDir();
        if (extCache != null) deleteChildren(extCache);
        for (String db : c.databaseList()) c.deleteDatabase(db);
    }

    /**
     * Clear mission data (Settings): chat and report history, pictures and files sent or received,
     * voice recordings, map points and contacts' last positions. Keeps DRYAD sheets, channel names,
     * settings, contacts' names and notes, and offline maps. Call only when off air.
     */
    public static void clearMission(Context a) {
        Context c = a.getApplicationContext();
        org.communitycomms.app.geo.PoiStore.wipe(c);
        new File(c.getFilesDir(), org.communitycomms.app.msg.MessageLog.fileName()).delete();
        org.communitycomms.app.msg.MessageLog.forget();
        org.communitycomms.app.msg.MessageLog.unread = 0;
        Contacts.get(c).clearPositions();
        deleteChildren(RadioService.receivedDir(c));
        deleteChildren(new File(c.getFilesDir(), "Received"));
        for (String d : new String[]{"camera", "outgoing", "parts"}) deleteChildren(new File(c.getCacheDir(), d));
    }

    /** Wipe, then close every screen and end the process so nothing is left in memory. */
    public static void wipeAndExit(Activity a) {
        wipe(a);
        a.finishAffinity();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private static void deleteChildren(File dir) {
        File[] kids = dir == null ? null : dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            deleteChildren(k);
            k.delete();
        }
    }
}
