package org.communitycomms.app.power;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One watched battery: its type picks its one reader. Kept as JSON in the "bluetti" prefs ("units"). */
public final class BatteryUnit {
    public String id = "", type = BatteryReader.BLUETTI;
    /** Bluetooth address, or the Powerwall gateway's host (from the user's settings only). */
    public String addr = "";
    public String name = "";
    /** 0 = work it out. */
    public int capWh;
    /** Overload alert at watts out, 0 off; -1 = the type's default. */
    public int overloadW = -1;
    /** Bluetti register layout ("v1"/"v2"/""), Anker SOLIX model, EcoFlow serial number and user ID. */
    public String layout = "", model = "", serial = "", userId = "";

    public int overload() {
        if (overloadW >= 0) return overloadW;
        if (BatteryReader.BLUETTI.equals(type)) return 300;
        if (BatteryReader.POWERWALL.equals(type)) return 5000;
        return 0;
    }

    JSONObject json() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id).put("type", type).put("addr", addr).put("name", name).put("capWh", capWh).put("overloadW", overloadW)
                .put("layout", layout).put("model", model).put("serial", serial).put("userId", userId);
        return o;
    }

    static BatteryUnit from(JSONObject o) {
        BatteryUnit u = new BatteryUnit();
        u.id = o.optString("id", "");
        u.type = o.optString("type", BatteryReader.BLUETTI);
        u.addr = o.optString("addr", "");
        u.name = o.optString("name", "");
        u.capWh = o.optInt("capWh", 0);
        u.overloadW = o.optInt("overloadW", -1);
        u.layout = o.optString("layout", "");
        u.model = o.optString("model", "");
        u.serial = o.optString("serial", "");
        u.userId = o.optString("userId", "");
        return u;
    }

    public static String toJson(List<BatteryUnit> units) {
        JSONArray a = new JSONArray();
        try {
            for (BatteryUnit u : units) a.put(u.json());
        } catch (Exception ignored) {
        }
        return a.toString();
    }

    public static List<BatteryUnit> fromJson(String s) {
        List<BatteryUnit> out = new ArrayList<BatteryUnit>();
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) out.add(from(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }
}
