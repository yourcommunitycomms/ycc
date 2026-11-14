package org.communitycomms.app.power;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Tesla Powerwall 2 through its Backup Gateway's local web pages (HTTPS, self-signed certificate).
 * READ-ONLY and no sign-in: it only GETs the open status pages (meters/aggregates,
 * system_status/soe, system_status/grid_status, system_status, status). It never calls /api/login,
 * /api/operation, /api/sitemaster or anything that changes the system. Written from the public
 * descriptions of these pages (pypowerwall, MIT; powerwall2 notes, Apache-2.0; see NOTICE); no code
 * copied. The gateway's address comes only from the user's settings.
 */
public final class PowerwallReader extends BatteryReader {
    private int capWh, remainWh;
    private long capAt;
    private String version = "";

    PowerwallReader(BatteryUnit u, Log log) {
        super(u, log);
    }

    /** HTTP code and body, or code -1 with lastError set. */
    private Object[] get(String path) {
        HttpURLConnection c = null;
        try {
            String host = unit.addr.trim();
            URL url = new URL((host.startsWith("http") ? host : "https://" + host).replaceAll("/+$", "") + path);
            c = (HttpURLConnection) url.openConnection();
            if (c instanceof javax.net.ssl.HttpsURLConnection) org.communitycomms.app.camera.ReolinkApi.lanTls((javax.net.ssl.HttpsURLConnection) c);
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/json");
            if (android.os.Build.VERSION.SDK_INT < 19) c.setRequestProperty("Connection", "close");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            if (in != null) {
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0 && o.size() < 200000) o.write(b, 0, n);
                in.close();
            }
            String body = o.toString("UTF-8");
            if (rawLog) log.log("GET " + path + " -> " + code + "\n" + (body.length() > 4000 ? body.substring(0, 4000) + "..." : body));
            return new Object[]{code, body};
        } catch (Exception e) {
            lastError = "can't reach the gateway (" + e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "") + ")";
            if (rawLog) log.log("GET " + path + " failed: " + e);
            return new Object[]{-1, ""};
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private JSONObject json(String path) {
        Object[] r = get(path);
        int code = (Integer) r[0];
        if (code == 401 || code == 403) {
            lastError = "the gateway wants a sign-in for " + path + ". This app reads only the open pages and never signs in.";
            return null;
        }
        if (code != 200) {
            if (code > 0) lastError = "the gateway answered " + code + " for " + path;
            return null;
        }
        try {
            return new JSONObject((String) r[1]);
        } catch (Exception e) {
            lastError = "the gateway's answer for " + path + " was not understood";
            return null;
        }
    }

    @Override
    public BatteryStatus read() {
        if (unit.addr.trim().length() == 0) {
            lastError = "no gateway address yet (tap the unit > Settings)";
            return null;
        }
        if (rawLog) {
            get("/api/status");
            get("/api/powerwalls");
        }
        JSONObject agg = json("/api/meters/aggregates");
        if (agg == null) return null;
        JSONObject soe = json("/api/system_status/soe");
        if (soe == null) return null;
        BatteryStatus s = new BatteryStatus();
        s.time = System.currentTimeMillis();
        s.model = "Powerwall";
        double raw = soe.optDouble("percentage", -1);
        // The Tesla app keeps 5% in reserve and shows the rest as 0-100%.
        if (raw >= 0) s.soc = (int) Math.round(Math.max(0, Math.min(100, (raw - 5) / 0.95)));
        s.gridW = power(agg, "site");
        s.batteryW = power(agg, "battery");
        s.solarW = power(agg, "solar");
        s.loadW = power(agg, "load");
        if (s.batteryW != BatteryStatus.NONE) {
            s.acIn = Math.max(0, -s.batteryW); // charging
            s.acOut = Math.max(0, s.batteryW); // discharging
        }
        JSONObject grid = json("/api/system_status/grid_status");
        String gs = grid == null ? "" : grid.optString("grid_status", "");
        if (gs.length() > 0) s.mains = "SystemGridConnected".equals(gs) || "SystemTransitionToGrid".equals(gs) ? 1 : 0;
        if (System.currentTimeMillis() - capAt > 600000 || rawLog) {
            JSONObject st = json("/api/system_status");
            if (st != null) {
                capWh = (int) st.optDouble("nominal_full_pack_energy", 0);
                remainWh = (int) st.optDouble("nominal_energy_remaining", 0);
                capAt = System.currentTimeMillis();
            }
            JSONObject v = json("/api/status");
            if (v != null) version = v.optString("version", "");
        }
        s.capWh = capWh;
        StringBuilder d = new StringBuilder();
        if (s.solarW != BatteryStatus.NONE) d.append("Solar ").append(kw(s.solarW)).append("\n");
        if (s.loadW != BatteryStatus.NONE) d.append("Home ").append(kw(s.loadW)).append("\n");
        if (s.gridW != BatteryStatus.NONE) d.append("Grid ").append(s.gridW >= 0 ? "importing " : "exporting ").append(kw(Math.abs(s.gridW))).append("\n");
        if (s.batteryW != BatteryStatus.NONE) d.append("Battery ").append(s.batteryW > 50 ? "giving " : s.batteryW < -50 ? "charging " : "idle, ").append(kw(Math.abs(s.batteryW))).append("\n");
        if (gs.length() > 0) d.append("Grid status: ").append(gridText(gs)).append("\n");
        if (raw >= 0) d.append(String.format(Locale.US, "Gateway charge %.1f%% (the app's %% leaves out the 5%% reserve)\n", raw));
        if (capWh > 0) d.append(String.format(Locale.US, "Battery %.1f kWh, %.1f kWh in it\n", capWh / 1000.0, remainWh / 1000.0));
        if (version.length() > 0) d.append("Firmware ").append(version);
        s.detail = d.toString().trim();
        lastError = "";
        return s;
    }

    private static int power(JSONObject agg, String part) {
        JSONObject o = agg.optJSONObject(part);
        if (o == null || !o.has("instant_power")) return BatteryStatus.NONE;
        return (int) Math.round(o.optDouble("instant_power", 0));
    }

    static String kw(int w) {
        return w >= 1000 ? String.format(Locale.US, "%.2f kW", w / 1000.0) : w + " W";
    }

    static String gridText(String gs) {
        if ("SystemGridConnected".equals(gs)) return "connected";
        if ("SystemIslandedActive".equals(gs)) return "OFF GRID (outage, running on the battery)";
        if ("SystemTransitionToGrid".equals(gs)) return "reconnecting to the grid";
        if ("SystemTransitionToIsland".equals(gs)) return "going off grid";
        if ("SystemIslandedReady".equals(gs)) return "off grid, ready";
        if ("SystemMicroGridFaulted".equals(gs)) return "off grid, FAULT";
        if ("SystemWaitForUser".equals(gs)) return "waiting for someone to restart it";
        return gs;
    }

    @Override
    public void close() {
    }

    /** Shown when the gateway can't be reached. */
    public static final String SETUP_HINT = "Use the gateway's address on your home network (from your router's device list). "
            + "Newer Powerwall firmware may refuse pages from other networks: then join this phone to the Powerwall's own Wi-Fi "
            + "(TEG-xxx, password on the sticker inside the gateway door) and use its fixed address there. "
            + "Readings need no sign-in; if the gateway asks for one, this app can't read it (it never signs in).";
}
