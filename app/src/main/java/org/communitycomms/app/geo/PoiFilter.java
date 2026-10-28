package org.communitycomms.app.geo;

import android.content.Context;

import org.communitycomms.app.Prefs;
import org.communitycomms.app.crypto.ChannelCrypto;
import org.communitycomms.app.msg.MessageLog;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps: show only the map points of one channel (A, B, Public, C–H), or all. A point's channel is
 * the one its catch-up entry was logged on; points with no entry (made before this existed) show only
 * under All. The channel tags are worked out once per name, in the background (PBKDF2 takes a moment).
 */
public final class PoiFilter {
    private static final Map<String, Integer> TAGS = new HashMap<String, Integer>();
    private static final java.util.Set<String> WORKING = new java.util.HashSet<String>();

    private PoiFilter() {
    }

    /** The chosen channel slot, or -1 for all channels. */
    public static int slot(Context c) {
        return c.getSharedPreferences("map", Context.MODE_PRIVATE).getInt("poiFilter", -1);
    }

    public static void setSlot(Context c, int slot) {
        c.getSharedPreferences("map", Context.MODE_PRIVATE).edit().putInt("poiFilter", slot).commit();
    }

    /** Map > Points > a layer: only points in it ("" = any layer). */
    public static String layer(Context c) {
        return c.getSharedPreferences("map", Context.MODE_PRIVATE).getString("poiLayer", "");
    }

    public static void setLayer(Context c, String layer) {
        c.getSharedPreferences("map", Context.MODE_PRIVATE).edit().putString("poiLayer", layer == null ? "" : layer).commit();
    }

    /** True if the point should be drawn under the current filter. */
    public static boolean shows(Context c, Poi p) {
        String l = layer(c);
        if (l.length() > 0 && !l.equalsIgnoreCase(Poi.layer(p.note))) return false;
        int slot = slot(c);
        if (slot < 0) return true;
        Integer tag = tag(c, slot);
        if (tag == null) return true; // still working the tag out: show everything meanwhile
        MessageLog.Entry m = MessageLog.get(c).find(p.id);
        return m != null && m.channelTag == tag;
    }

    /** The channel tag of a slot's current name, or null while it is being worked out. */
    public static Integer tag(Context c, int slot) {
        Prefs pr = new Prefs(c);
        final String name = slot == Prefs.SLOT_PUBLIC ? pr.publicKey() : pr.effectiveChannel(pr.channel(slot)).trim();
        if (name.length() == 0) return 0;
        synchronized (TAGS) {
            Integer t = TAGS.get(name);
            if (t != null || !WORKING.add(name)) return t;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                int t = 0;
                try {
                    t = new ChannelCrypto(name).channelTag;
                } catch (Exception ignored) {
                }
                synchronized (TAGS) {
                    TAGS.put(name, t);
                    WORKING.remove(name);
                }
            }
        }, "poi-filter-tag").start();
        return null;
    }

    /** Label for the filter button, e.g. "Points: A" or "Points: all channels". */
    public static String label(Context c) {
        int s = slot(c);
        return s < 0 ? "Points: all channels" : "Points: " + Prefs.letter(s);
    }
}
