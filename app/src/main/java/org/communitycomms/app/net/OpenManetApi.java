package org.communitycomms.app.net;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;

/**
 * openmanetd's own web API (the one its web page on port 8080 uses): Connect-RPC with JSON over plain
 * HTTP at /rpc/&lt;service&gt;/&lt;method&gt;. Log in with POST /auth/login (the node's own login, checked by the
 * node), which answers with a token sent as "Authorization: Bearer". Nothing here stores a password.
 * Checked against openmanetd 1.4.0 (OpenMANET 1.8.1): WifiConfigService, MeshTopologyService,
 * NodeService and MeshJoinService are all in that build.
 */
public final class OpenManetApi {
    public static final int PORT = 8080;
    public static final String WIFI = "openmanet.wifi_config.v1.WifiConfigService/";
    public static final String TOPOLOGY = "openmanet.mesh_topology.v1.MeshTopologyService/";
    public static final String NODES = "openmanet.service.v1.NodeService/";

    /** A failed call: code is the HTTP status (0 = no answer at all, -1 = not JSON). */
    public static final class ApiException extends IOException {
        public final int code;

        ApiException(int code, String msg) {
            super(msg);
            this.code = code;
        }

        public boolean loginFailed() {
            return code == 401 || code == 403;
        }

        public boolean noAnswer() {
            return code == 0;
        }
    }

    private final String host;
    private final int timeoutMs;
    private String token;

    public OpenManetApi(String host, int timeoutMs) {
        this.host = host;
        this.timeoutMs = timeoutMs;
    }

    public String host() {
        return host;
    }

    public boolean loggedIn() {
        return token != null;
    }

    /** Logs in; throws ApiException (code 401) on a wrong user or password. */
    public void login(String user, String password) throws IOException {
        JSONObject b = new JSONObject();
        try {
            b.put("username", user);
            b.put("password", password);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        JSONObject r = post("/auth/login", b, false);
        String t = r.optString("token", "");
        if (t.length() == 0) throw new ApiException(-1, "No token in the login answer");
        token = t;
    }

    /** Forgets the token (and asks the node to end the session; errors ignored). */
    public void logout() {
        if (token == null) return;
        try {
            post("/auth/logout", new JSONObject(), true);
        } catch (IOException ignored) {
        }
        token = null;
    }

    /** One API call, e.g. call(WIFI + "ListRadios", new JSONObject()). */
    public JSONObject call(String procedure, JSONObject body) throws IOException {
        return post("/rpc/" + procedure, body == null ? new JSONObject() : body, true);
    }

    private JSONObject post(String path, JSONObject body, boolean auth) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + host + ":" + PORT + path).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs + 4000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Connect-Protocol-Version", "1");
            if (auth && token != null) c.setRequestProperty("Authorization", "Bearer " + token);
            byte[] out = body.toString().getBytes("UTF-8");
            c.setFixedLengthStreamingMode(out.length);
            OutputStream os = c.getOutputStream();
            os.write(out);
            os.close();
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : read(in);
            if (code >= 400) {
                String msg = text;
                try {
                    JSONObject e = new JSONObject(text);
                    msg = e.optString("message", e.optString("error", text));
                } catch (JSONException ignored) {
                }
                throw new ApiException(code, msg.length() > 200 ? msg.substring(0, 200) : msg);
            }
            try {
                return text.length() == 0 ? new JSONObject() : new JSONObject(text);
            } catch (JSONException e) {
                throw new ApiException(-1, "Not an openmanetd answer");
            }
        } catch (ApiException e) {
            throw e;
        } catch (SocketTimeoutException e) {
            throw new ApiException(0, "No answer (timed out)");
        } catch (IOException e) {
            throw new ApiException(0, "No answer (" + e.getClass().getSimpleName() + ")");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    /** Does anything answer on the node's web port? (No login needed.) */
    public static boolean answers(String host, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, PORT), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** The radio of the given band ("WIFI_BAND_S1G" for HaLow), or null. */
    public String radioName(String band) throws IOException {
        JSONArray radios = call(WIFI + "ListRadios", new JSONObject()).optJSONArray("radios");
        if (radios == null) return null;
        for (int i = 0; i < radios.length(); i++) {
            JSONObject r = radios.optJSONObject(i);
            if (r != null && band.equals(r.optString("band"))) return r.optString("name", null);
        }
        return null;
    }

    public JSONObject radioSettings(String radio) throws IOException {
        JSONObject b = new JSONObject();
        try {
            b.put("radioName", radio);
        } catch (JSONException ignored) {
        }
        return call(WIFI + "GetRadioSettings", b);
    }

    public JSONObject radioStatus(String radio) throws IOException {
        JSONObject b = new JSONObject();
        try {
            b.put("radioName", radio);
        } catch (JSONException ignored) {
        }
        return call(WIFI + "GetRadioStatus", b).optJSONObject("status");
    }

    /**
     * Writes new settings to a radio; openmanetd commits them and reloads the network straight away, so
     * the node leaves the old mesh within seconds. Returns the node's message, or throws.
     */
    public String updateRadio(String radio, JSONObject settings) throws IOException {
        JSONObject b = new JSONObject();
        try {
            b.put("radioName", radio);
            b.put("settings", settings);
        } catch (JSONException ignored) {
        }
        JSONObject r = call(WIFI + "UpdateRadioSettings", b);
        if (!r.optBoolean("success", false)) {
            String m = r.optString("message", "");
            throw new ApiException(-2, m.length() > 0 ? m : "The node did not accept the settings");
        }
        return r.optString("message", "");
    }

    /**
     * Resolves name.local with one multicast DNS query (avahi on OpenMANET nodes answers these across the
     * mesh). Returns the IPv4 address or null.
     */
    public static String mdnsLookup(String name, int timeoutMs) {
        if (name == null || name.length() == 0) return null;
        String fq = name.endsWith(".local") ? name : name + ".local";
        DatagramSocket s = null;
        try {
            ByteArrayOutputStream q = new ByteArrayOutputStream();
            int id = (int) (System.nanoTime() & 0xffff);
            q.write(id >> 8);
            q.write(id);
            q.write(0);
            q.write(0); // flags: standard query
            q.write(0);
            q.write(1); // one question
            for (int i = 0; i < 6; i++) q.write(0);
            for (String label : fq.split("\\.")) {
                byte[] l = label.getBytes("UTF-8");
                q.write(l.length);
                q.write(l, 0, l.length);
            }
            q.write(0);
            q.write(0);
            q.write(1); // type A
            q.write(0x80);
            q.write(1); // class IN, unicast response wanted
            byte[] pkt = q.toByteArray();
            s = new DatagramSocket();
            s.setSoTimeout(Math.max(200, timeoutMs / 2));
            InetAddress group = InetAddress.getByName("224.0.0.251");
            long end = System.currentTimeMillis() + timeoutMs;
            for (int attempt = 0; attempt < 2 && System.currentTimeMillis() < end; attempt++) {
                s.send(new DatagramPacket(pkt, pkt.length, group, 5353));
                byte[] buf = new byte[1500];
                while (System.currentTimeMillis() < end) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                    String a = parseA(buf, p.getLength(), fq);
                    if (a != null) return a;
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (s != null) s.close();
        }
        return null;
    }

    private static String parseA(byte[] b, int len, String want) {
        if (len < 12) return null;
        int qd = ((b[4] & 0xff) << 8) | (b[5] & 0xff);
        int an = ((b[6] & 0xff) << 8) | (b[7] & 0xff);
        int ar = ((b[10] & 0xff) << 8) | (b[11] & 0xff);
        int[] pos = {12};
        try {
            for (int i = 0; i < qd; i++) {
                readName(b, len, pos);
                pos[0] += 4;
            }
            int total = an + ar;
            for (int i = 0; i < total && pos[0] < len; i++) {
                String n = readName(b, len, pos);
                int type = ((b[pos[0]] & 0xff) << 8) | (b[pos[0] + 1] & 0xff);
                int rdl = ((b[pos[0] + 8] & 0xff) << 8) | (b[pos[0] + 9] & 0xff);
                pos[0] += 10;
                if (type == 1 && rdl == 4 && n.equalsIgnoreCase(want)) {
                    return (b[pos[0]] & 0xff) + "." + (b[pos[0] + 1] & 0xff) + "." + (b[pos[0] + 2] & 0xff) + "." + (b[pos[0] + 3] & 0xff);
                }
                pos[0] += rdl;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static String readName(byte[] b, int len, int[] pos) {
        StringBuilder sb = new StringBuilder();
        int p = pos[0];
        boolean jumped = false;
        int guard = 0;
        while (p < len && guard++ < 64) {
            int l = b[p] & 0xff;
            if (l == 0) {
                p++;
                break;
            }
            if ((l & 0xc0) == 0xc0) {
                int off = ((l & 0x3f) << 8) | (b[p + 1] & 0xff);
                if (!jumped) pos[0] = p + 2;
                jumped = true;
                p = off;
                continue;
            }
            if (sb.length() > 0) sb.append('.');
            try {
                sb.append(new String(b, p + 1, l, "UTF-8"));
            } catch (java.io.UnsupportedEncodingException e) {
                return "";
            }
            p += l + 1;
        }
        if (!jumped) pos[0] = p;
        return sb.toString();
    }
}
