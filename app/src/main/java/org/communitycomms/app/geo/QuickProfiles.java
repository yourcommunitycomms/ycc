package org.communitycomms.app.geo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Sets of one-tap map points ("profiles") for the home screen: Friends, Security, Firefighting,
 * Military, and Custom (a copy of any of them that the user edits). Each item has the button
 * text, the icon, the announcement (the POI note others see and hear), how long it lasts and
 * where it goes (my position, or the centre of the map for "meet me there").
 *
 * Announcement placeholders: {me} = my callsign, {in10} / {in30} = clock time 10 / 30 min from now.
 */
public final class QuickProfiles {
    public static final String[] NAMES = {"Friends", "Security", "Firefighting", "Military", "Custom",
            "Scouts", "Staff", "4WDing", "Friends convoy", "Caravan / camping", "Emergency services"};
    public static final int FRIENDS = 0, SECURITY = 1, FIRE = 2, MILITARY = 3, CUSTOM = 4,
            SCOUTS = 5, STAFF = 6, FOURWD = 7, CONVOY = 8, CAMPING = 9, EMERGENCY = 10;

    /** What a quick button does: drop a map point, send a chat message, or share location until stopped. */
    public static final int KIND_POINT = 0, KIND_MESSAGE = 1, KIND_SHARE = 2;

    public static final class Item {
        public String label, note;
        public int icon;
        public long lifeMs;
        /** true = at the map's centre crosshair ("there"), false = at my GPS position. */
        public boolean atMapCentre;
        public int kind = KIND_POINT;

        public Item(String label, int icon, String note, long lifeMs, boolean atMapCentre) {
            this.label = label;
            this.icon = icon;
            this.note = note;
            this.lifeMs = lifeMs;
            this.atMapCentre = atMapCentre;
        }

        /** A button that sends the note as a chat message to the channel (no map point). */
        static Item message(String label, String note) {
            Item i = new Item(label, 0, note, 0, false);
            i.kind = KIND_MESSAGE;
            return i;
        }

        /** "Share my location" that keeps going until tapped again. */
        static Item share(String label) {
            Item i = new Item(label, 0, "", 0, false);
            i.kind = KIND_SHARE;
            return i;
        }

        public Item copy() {
            Item i = new Item(label, icon, note, lifeMs, atMapCentre);
            i.kind = kind;
            return i;
        }

        /** The announcement with placeholders filled in. */
        public String noteFor(String me) {
            SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
            long now = System.currentTimeMillis();
            return note.replace("{me}", me).replace("{in10}", f.format(new Date(now + 10 * 60000L)))
                    .replace("{in15}", f.format(new Date(now + 15 * 60000L)))
                    .replace("{in30}", f.format(new Date(now + 30 * 60000L)));
        }
    }

    private static final long H = 3600000L;

    /** The built-in profiles (Custom starts as a copy of whichever was in use). */
    public static List<Item> builtIn(int profile) {
        List<Item> l = new ArrayList<Item>();
        switch (profile) {
            case FRIENDS:
                l.add(new Item("I am here", 0, "{me} is here", H, false));
                l.add(new Item("Toilets", 13, "Toilets here", 12 * H, false));
                l.add(new Item("Meet me here", 14, "Meet {me} here", H, false));
                l.add(new Item("Meet there 10 min", 14, "Meet {me} here at {in10}", H, true));
                l.add(new Item("Parked here", 15, "{me} parked here", 12 * H, false));
                l.add(new Item("Food / camp", 29, "Food / camp here", 12 * H, false));
                break;
            case FIRE:
                l.add(new Item("Fire front", 16, "Fire front", 2 * H, false));
                l.add(new Item("Spot fire", 17, "Spot fire", 2 * H, false));
                l.add(new Item("Fire truck", 18, "Fire truck ({me})", 2 * H, false));
                l.add(new Item("Tanker", 19, "Tanker truck", 2 * H, false));
                l.add(new Item("Water point", 20, "Water point", 12 * H, false));
                l.add(new Item("Road closed", 30, "Road closed", 6 * H, false));
                l.add(new Item("Danger", 25, "Danger", 2 * H, false));
                l.add(new Item("Casualty", 26, "Casualty", H, false));
                l.add(new Item("Me here", 0, "{me} is here", H, false));
                l.add(new Item("Assembly area", 14, "Assembly area", 6 * H, true));
                break;
            case MILITARY:
                l.add(new Item("Contact", 21, "Contact: enemy", H, false));
                l.add(new Item("Friendly", 22, "Friendly position ({me})", H, false));
                l.add(new Item("Unknown", 23, "Unknown contact", H, false));
                l.add(new Item("Casualty / CCP", 26, "Casualty collection point", H, false));
                l.add(new Item("OP", 24, "Observation post", 6 * H, false));
                l.add(new Item("LZ", 27, "Landing zone", 6 * H, true));
                l.add(new Item("RV point", 14, "RV point", 6 * H, true));
                l.add(new Item("Checkpoint", 28, "Checkpoint", 6 * H, false));
                l.add(new Item("IED / hazard", 25, "IED / hazard", 6 * H, false));
                l.add(new Item("Vehicle", 3, "Vehicle", H, false));
                break;
            case EMERGENCY:
                l.add(new Item("Tree down", 30, "Tree down", 12 * H, false));
                l.add(new Item("Power down", 25, "Power lines down", 12 * H, false));
                l.add(new Item("Ambulance (emergency)", 6, "AMBULANCE REQUIRED (emergency), with {me}", 2 * H, false));
                l.add(new Item("Medic (non-emergency)", 26, "Medic required (non-emergency), with {me}", 2 * H, false));
                l.add(new Item("Vehicle accident", 3, "Vehicle accident", 6 * H, false));
                l.add(new Item("Debris", 25, "Debris on road", 6 * H, false));
                l.add(new Item("Casualty collection", 26, "Casualty collection point", 12 * H, true));
                l.add(new Item("Emergency shelter", 14, "Emergency shelter", 24 * H, true));
                l.add(new Item("Food station", 29, "Food station", 24 * H, true));
                l.add(new Item("Helipad", 27, "Helipad / landing zone", 12 * H, true));
                break;
            case SCOUTS:
                l.add(new Item("Meeting point", 14, "Meeting point", 12 * H, true));
                l.add(new Item("Evacuation point", 14, "Evacuation point", 24 * H, true));
                l.add(new Item("First aid station", 26, "First aid station", 24 * H, false));
                l.add(new Item("Trail start", 28, "Trail start", 24 * H, false));
                l.add(new Item("Trail end", 28, "Trail end", 24 * H, false));
                l.add(new Item("Help needed", 25, "{me} needs help here", H, false));
                l.add(new Item("Water", 20, "Drinking water", 24 * H, false));
                l.add(new Item("Toilets", 13, "Toilets", 24 * H, false));
                l.add(new Item("Group here", 2, "{me}'s group is here", H, false));
                l.add(Item.message("All good", "{me}: all good, on track"));
                break;
            case STAFF:
                l.add(new Item("Help", 25, "{me} needs help here", H, false));
                l.add(new Item("Medical", 26, "Medical help needed, with {me}", H, false));
                l.add(new Item("Security", 1, "Security needed, with {me}", H, false));
                l.add(Item.message("Long break", "{me} is going on a long break"));
                l.add(Item.message("Short break", "{me} is going on a short break"));
                l.add(Item.message("Back on duty", "{me} is back on duty"));
                l.add(Item.message("On my way", "{me} is on the way"));
                l.add(Item.message("Spill / cleanup", "{me}: cleanup needed"));
                break;
            case FOURWD:
                l.add(new Item("Flat tyre", 3, "{me}: flat tyre, stopped here", 2 * H, false));
                l.add(new Item("Rescue needed", 25, "RESCUE NEEDED: {me} is here", 6 * H, false));
                l.add(Item.share("Share location until stop"));
                l.add(new Item("Bogged", 3, "{me} is bogged here", 2 * H, false));
                l.add(new Item("Hazard / washout", 25, "Track hazard / washout", 12 * H, false));
                l.add(new Item("Track closed", 30, "Track closed", 12 * H, false));
                l.add(new Item("Camp here", 29, "Camp spot", 12 * H, false));
                l.add(new Item("Regroup point", 14, "Regroup here", 2 * H, true));
                l.add(Item.message("Stopping", "{me} is stopping"));
                l.add(Item.message("All clear", "{me}: all clear, moving on"));
                break;
            case CONVOY:
                l.add(Item.message("Issue", "{me} has an issue, please pull over"));
                l.add(Item.message("Need fuel", "{me} needs fuel at the next stop"));
                l.add(Item.message("Next toilet", "{me} needs the next toilet stop"));
                l.add(Item.share("Share location until stop"));
                l.add(Item.message("Food stop", "{me}: food stop at the next town?"));
                l.add(Item.message("Slow down", "{me} is falling behind, slow down please"));
                l.add(new Item("Meet here", 14, "Meet {me} here", 2 * H, false));
                l.add(new Item("Parked here", 15, "{me} parked here", 6 * H, false));
                break;
            case CAMPING:
                l.add(Item.message("Meal ready now", "Meal is ready now! ({me})"));
                l.add(Item.message("Meal in 15 min", "Meal ready in 15 minutes, at {in15} ({me})"));
                l.add(new Item("BBQ", 29, "BBQ", 24 * H, false));
                l.add(new Item("Our camp site", 29, "{me}'s camp site", 72 * H, false));
                l.add(new Item("Cabin", 14, "{me}'s cabin", 72 * H, false));
                l.add(new Item("Car parked", 15, "{me}'s car is parked here", 72 * H, false));
                l.add(new Item("Playground", 2, "Playground", 72 * H, false));
                l.add(new Item("Toilets / showers", 13, "Toilets / showers", 72 * H, false));
                l.add(new Item("Camp kitchen", 29, "Camp kitchen", 72 * H, false));
                l.add(new Item("Friends' site", 22, "Friends' camp site", 72 * H, false));
                l.add(new Item("Pool / swim", 20, "Pool / swimming", 72 * H, false));
                l.add(Item.message("Kids come back", "Kids, come back to camp please ({me})"));
                break;
            default: // SECURITY: the original four
                l.add(new Item("Me here", 0, "{me}", H, false));
                l.add(new Item("Hostile", 1, "Hostile", H, false));
                l.add(new Item("Vehicle", 3, "Vehicle", H, false));
                l.add(new Item("Casualty", 6, "Casualty", H, false));
        }
        return l;
    }

    /**
     * The profile's items plus many more of the same kind, for the tablet commander view's
     * map-point panel (it has room for them all). Custom shows just its own items.
     */
    public List<Item> commanderItems() {
        int p = profile();
        List<Item> l = new ArrayList<Item>(items());
        if (p == CUSTOM || p >= SCOUTS) return l;
        switch (p) {
            case FRIENDS:
                l.add(new Item("Lost child", 2, "Lost child seen here", H, false));
                l.add(new Item("Help needed", 26, "Help needed here", H, false));
                l.add(new Item("Bus stop", 5, "Bus / pick-up here", 6 * H, false));
                l.add(new Item("Water", 20, "Drinking water", 12 * H, false));
                l.add(new Item("Danger", 25, "Careful: hazard here", 2 * H, false));
                l.add(new Item("Road closed", 30, "Road closed", 6 * H, false));
                l.add(new Item("First aid", 26, "First aid here", 12 * H, false));
                l.add(new Item("Meet there 30 min", 14, "Meet {me} here at {in30}", H, true));
                break;
            case FIRE:
                l.add(new Item("Ember attack", 17, "Ember attack", H, false));
                l.add(new Item("Helicopter", 7, "Water bomber / helicopter", H, false));
                l.add(new Item("Aircraft", 8, "Aircraft", H, false));
                l.add(new Item("Drone", 9, "Drone up", H, false));
                l.add(new Item("Ambulance", 6, "Ambulance", H, false));
                l.add(new Item("Refuge", 14, "Safer place / refuge", 12 * H, true));
                l.add(new Item("Staging area", 14, "Staging area", 6 * H, true));
                l.add(new Item("Control point", 28, "Control point", 6 * H, false));
                l.add(new Item("Power lines down", 25, "Power lines down", 6 * H, false));
                l.add(new Item("Tree down", 30, "Tree down on road", 6 * H, false));
                break;
            case MILITARY:
                l.add(new Item("Enemy vehicle", 4, "Enemy vehicle", H, false));
                l.add(new Item("Enemy air", 7, "Enemy aircraft", H, false));
                l.add(new Item("Drone", 9, "Drone sighted", H, false));
                l.add(new Item("Friendly vehicle", 3, "Friendly vehicle", H, false));
                l.add(new Item("MEDEVAC", 6, "MEDEVAC required", H, false));
                l.add(new Item("Obstacle", 30, "Obstacle", 6 * H, false));
                l.add(new Item("Objective", 10, "Objective", 6 * H, true));
                l.add(new Item("Phase line", 11, "Phase line", 6 * H, true));
                l.add(new Item("Supply / resupply", 29, "Resupply point", 6 * H, true));
                l.add(new Item("Last known pos", 23, "Last known position", 2 * H, true));
                break;
            default: // SECURITY
                l.add(new Item("Suspicious", 2, "Suspicious person", H, false));
                l.add(new Item("Friendly", 22, "Friendly ({me})", H, false));
                l.add(new Item("Unknown", 23, "Unknown person", H, false));
                l.add(new Item("Truck", 4, "Truck", H, false));
                l.add(new Item("Drone", 9, "Drone sighted", H, false));
                l.add(new Item("Danger", 25, "Danger", 2 * H, false));
                l.add(new Item("Checkpoint", 28, "Checkpoint", 6 * H, false));
                l.add(new Item("Meeting point", 14, "Meeting point", 6 * H, true));
                l.add(new Item("Ambulance", 6, "Ambulance", H, false));
                l.add(new Item("Road closed", 30, "Road closed", 6 * H, false));
        }
        return l;
    }

    private final SharedPreferences sp;

    public QuickProfiles(Context c) {
        sp = c.getSharedPreferences("quickProfiles", Context.MODE_PRIVATE);
    }

    public int profile() {
        return sp.getInt("profile", SECURITY);
    }

    /** Switching to Custom for the first time copies the profile in use as the starting point. */
    public void setProfile(int p) {
        if (p == CUSTOM && !sp.contains("custom")) saveCustom(items());
        sp.edit().putInt("profile", p).commit();
    }

    public List<Item> items() {
        int p = profile();
        if (p != CUSTOM) return builtIn(p);
        List<Item> l = loadCustom();
        return l.isEmpty() ? builtIn(SECURITY) : l;
    }

    public List<Item> loadCustom() {
        List<Item> l = new ArrayList<Item>();
        try {
            JSONArray a = new JSONArray(sp.getString("custom", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item(o.optString("label"), o.optInt("icon"), o.optString("note"),
                        o.optLong("life", H), o.optBoolean("centre"));
                it.kind = o.optInt("kind", KIND_POINT);
                l.add(it);
            }
        } catch (Exception ignored) {
        }
        return l;
    }

    public void saveCustom(List<Item> items) {
        JSONArray a = new JSONArray();
        try {
            for (Item it : items) {
                a.put(new JSONObject().put("label", it.label).put("icon", it.icon).put("note", it.note)
                        .put("life", it.lifeMs).put("centre", it.atMapCentre).put("kind", it.kind));
            }
        } catch (Exception ignored) {
        }
        sp.edit().putString("custom", a.toString()).commit();
    }

    /** Every icon, with the current profile's icons first (in profile order), then the rest. */
    public int[] iconOrder() {
        List<Integer> order = new ArrayList<Integer>();
        for (Item it : items()) if (!order.contains(it.icon) && it.icon != Poi.ICON_PHOTO) order.add(it.icon);
        for (int i = 0; i < Poi.ICON_NAMES.length; i++) if (!order.contains(i)) order.add(i);
        int[] out = new int[order.size()];
        for (int i = 0; i < out.length; i++) out[i] = order.get(i);
        return out;
    }
}
