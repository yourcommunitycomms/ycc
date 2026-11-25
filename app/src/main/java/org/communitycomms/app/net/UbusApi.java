package org.communitycomms.app.net;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * OpenWrt's own web API: ubus JSON-RPC through uhttpd at http://node/ubus (rpcd sessions). Used for the read-only
 * "ycc-ro" login (mesh11s topology, the batman tables, system info) and, by the Mesh controller with the root
 * login, to install, change or remove that login. Every call: {"jsonrpc":"2.0","id":n,"method":"call",
 * "params":[session, object, method, args]} -> {"result":[status, data]} (status 0 = OK, 6 = permission denied).
 */
public final class UbusApi {
    public static final String NO_SESSION = "00000000000000000000000000000000";
    private final String host;
    private final int timeoutMs;
    private String sid;
    private int id;

    public static final class UbusException extends IOException {
        public final int status; // ubus status, -1 = HTTP / network

        UbusException(int status, String msg) {
            super(msg);
            this.status = status;
        }

        public boolean denied() {
            return status == 6;
        }

        public boolean loginFailed() {
            return status == 6 || status == -401;
        }

        public boolean noAnswer() {
            return status == -1;
        }
    }

    public UbusApi(String host, int timeoutMs) {
        this.host = host;
        this.timeoutMs = timeoutMs;
    }

    public String host() {
        return host;
    }

    public boolean loggedIn() {
        return sid != null;
    }

    public void login(String user, String pass) throws IOException {
        JSONObject a = new JSONObject();
        try {
            a.put("username", user).put("password", pass);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        JSONObject r = raw(NO_SESSION, "session", "login", a);
        String s = r == null ? "" : r.optString("ubus_rpc_session", "");
        if (s.length() == 0) throw new UbusException(-401, "Login refused");
        sid = s;
    }

    public void logout() {
        if (sid == null) return;
        try {
            raw(sid, "session", "destroy", new JSONObject());
        } catch (IOException ignored) {
        }
        sid = null;
    }

    public JSONObject call(String object, String method, JSONObject args) throws IOException {
        if (sid == null) throw new UbusException(6, "Not logged in");
        return raw(sid, object, method, args == null ? new JSONObject() : args);
    }

    private JSONObject raw(String session, String object, String method, JSONObject args) throws IOException {
        HttpURLConnection c = null;
        try {
            JSONObject req = new JSONObject().put("jsonrpc", "2.0").put("id", ++id).put("method", "call")
                    .put("params", new JSONArray().put(session).put(object).put(method).put(args));
            byte[] body = req.toString().getBytes("UTF-8");
            c = (HttpURLConnection) new URL("http://" + host + "/ubus").openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(body.length);
            OutputStream o = c.getOutputStream();
            o.write(body);
            o.close();
            int code = c.getResponseCode();
            if (code != 200) throw new UbusException(-2, "HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 512 * 1024) break;
            }
            in.close();
            JSONObject r = new JSONObject(bo.toString("UTF-8"));
            if (r.has("error")) {
                JSONObject e = r.optJSONObject("error");
                int ec = e == null ? 0 : e.optInt("code", 0);
                throw new UbusException(ec == -32002 ? 6 : -3, e == null ? "error" : e.optString("message", "error")); // -32002 = access denied
            }
            JSONArray res = r.optJSONArray("result");
            int st = res == null ? -3 : res.optInt(0, -3);
            if (st != 0) throw new UbusException(st, st == 6 ? "Permission denied" : st == 4 ? "Not found" : "ubus status " + st);
            JSONObject data = res.optJSONObject(1);
            return data == null ? new JSONObject() : data;
        } catch (UbusException e) {
            throw e;
        } catch (JSONException e) {
            throw new UbusException(-3, "Bad answer: " + e.getMessage());
        } catch (IOException e) {
            throw new UbusException(-1, "No answer (" + e.getClass().getSimpleName() + ")");
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
