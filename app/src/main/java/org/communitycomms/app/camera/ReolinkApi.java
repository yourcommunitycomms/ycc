package org.communitycomms.app.camera;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Reolink's HTTP API (/cgi-bin/api.cgi), as the cameras answer it on the LAN: JSON arrays of
 * commands in, JSON arrays of answers out. Signs in with a token (cmd=Login, renewed before its
 * lease runs out) and falls back to user/password on each request for old firmware that has no
 * token login. HTTPS cameras use their own self-signed certificate, which is accepted for these
 * LAN connections only. Never logs addresses with sign-in details. Call from background threads.
 */
public final class ReolinkApi {
    /** A command the camera answered with an error (rspCode: -6 login needed, -7 bad login, -9 not supported...). */
    public static final class ApiException extends IOException {
        public final int rspCode;

        ApiException(String detail, int rspCode) {
            super(detail);
            this.rspCode = rspCode;
        }
    }

    public static final class DevInfo {
        public String model = "", name = "", firmware = "";
        public int channels = 1;
    }

    /** AI detection bits from GetAiState. */
    public static final int AI_PEOPLE = 1, AI_VEHICLE = 2, AI_ANIMAL = 4;

    private static final int CONNECT_MS = 4000, READ_MS = 8000;
    private static final Map<String, ReolinkApi> cache = new HashMap<String, ReolinkApi>();

    private final String base, user, pass;
    private String token;
    private long tokenUntil;
    private boolean noTokenLogin;

    private ReolinkApi(String base, String user, String pass) {
        this.base = base;
        this.user = user;
        this.pass = pass;
    }

    public ReolinkApi(String host, int port, boolean https, String user, String pass) {
        this(baseOf(host, port, https), user, pass);
    }

    private static String baseOf(String host, int port, boolean https) {
        return (https ? "https://" : "http://") + host + ":" + port;
    }

    /** One shared session per camera address and sign-in (both lenses of a camera share it). */
    public static ReolinkApi of(ReolinkCam c) {
        String k = baseOf(c.host, c.httpPort, c.https) + "\n" + c.user + "\n" + c.pass;
        synchronized (cache) {
            ReolinkApi a = cache.get(k);
            if (a == null) {
                a = new ReolinkApi(baseOf(c.host, c.httpPort, c.https), c.user, c.pass);
                cache.put(k, a);
            }
            return a;
        }
    }

    // ---- sign in ----

    private synchronized String auth() throws IOException {
        long now = System.currentTimeMillis();
        if (!noTokenLogin && (token == null || now > tokenUntil)) login();
        if (token != null) return "token=" + enc(token);
        return "user=" + enc(user) + "&password=" + enc(pass);
    }

    private synchronized void forgetToken() {
        token = null;
    }

    /**
     * Ends the token session. Cameras allow only a few at once, so one-off checks (scan, wizard)
     * sign out when done instead of leaving sessions to run out after an hour.
     */
    public void logout() {
        String t;
        synchronized (this) {
            t = token;
            token = null;
        }
        if (t == null) return;
        try {
            http("POST", "/cgi-bin/api.cgi?cmd=Logout&token=" + enc(t), "[{\"cmd\":\"Logout\",\"param\":{}}]", null);
        } catch (Exception ignored) {
        }
    }

    private void login() throws IOException {
        token = null;
        try {
            JSONObject u = new JSONObject().put("Version", "0").put("userName", user).put("password", pass);
            JSONArray body = new JSONArray().put(new JSONObject().put("cmd", "Login")
                    .put("param", new JSONObject().put("User", u)));
            JSONArray r = new JSONArray(http("POST", "/cgi-bin/api.cgi?cmd=Login", body.toString(), null));
            JSONObject o = r.getJSONObject(0);
            if (o.optInt("code", -1) != 0) {
                JSONObject err = o.optJSONObject("error");
                int rsp = err == null ? 0 : err.optInt("rspCode", 0);
                if (rsp == -7 || rsp == -27 || rsp == -26) throw new ApiException(loginProblem(err), rsp);
                noTokenLogin = true; // no token login here: send the sign-in with each request
                return;
            }
            JSONObject t = o.getJSONObject("value").getJSONObject("Token");
            token = t.getString("name");
            long lease = Math.max(60, t.optLong("leaseTime", 3600));
            tokenUntil = System.currentTimeMillis() + (lease - 60) * 1000L; // renew a minute early
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            noTokenLogin = true; // an old camera answering something other than JSON to Login
        }
    }

    private static String loginProblem(JSONObject err) {
        String d = err == null ? "" : err.optString("detail", "");
        return "the camera refused the user name or password" + (d.length() > 0 ? " (" + d + ")" : "");
    }

    // ---- commands ----

    /** Sends commands in one request; returns each answer's "value" (null where that command failed). */
    public JSONObject[] commands(String[] cmds, JSONObject[] params) throws IOException {
        for (int attempt = 0; ; attempt++) {
            JSONArray body = new JSONArray();
            try {
                for (int i = 0; i < cmds.length; i++) {
                    body.put(new JSONObject().put("cmd", cmds[i]).put("action", 0)
                            .put("param", params[i] == null ? new JSONObject() : params[i]));
                }
            } catch (Exception e) {
                throw new IOException("bad command");
            }
            String text = http("POST", "/cgi-bin/api.cgi?cmd=" + cmds[0] + "&" + auth(), body.toString(), null);
            JSONObject[] out = new JSONObject[cmds.length];
            ApiException first = null;
            try {
                JSONArray r = new JSONArray(text);
                for (int i = 0; i < r.length() && i < out.length; i++) {
                    JSONObject o = r.getJSONObject(i);
                    if (o.optInt("code", -1) == 0) {
                        out[i] = o.optJSONObject("value");
                        if (out[i] == null) out[i] = new JSONObject();
                    } else if (first == null) {
                        JSONObject err = o.optJSONObject("error");
                        first = new ApiException(err == null ? "error" : err.optString("detail", "error"),
                                err == null ? 0 : err.optInt("rspCode", 0));
                    }
                }
            } catch (Exception e) {
                throw new IOException("the camera's answer was not understood");
            }
            if (first != null && first.rspCode == -6 && attempt == 0) { // token expired early: sign in again
                forgetToken();
                continue;
            }
            if (first != null && (first.rspCode == -7 || first.rspCode == -27)) throw new ApiException(loginProblem(null), first.rspCode);
            if (cmds.length == 1 && out[0] == null && first != null) throw first;
            return out;
        }
    }

    public JSONObject command(String cmd, JSONObject param) throws IOException {
        return commands(new String[]{cmd}, new JSONObject[]{param})[0];
    }

    public DevInfo devInfo() throws IOException {
        JSONObject v = command("GetDevInfo", new JSONObject());
        JSONObject d = v.optJSONObject("DevInfo");
        if (d == null) throw new IOException("no device information in the answer");
        DevInfo i = new DevInfo();
        i.model = d.optString("model", "");
        i.name = d.optString("name", "");
        i.firmware = d.optString("firmVer", "");
        i.channels = Math.max(1, d.optInt("channelNum", 1));
        return i;
    }

    private static JSONObject ch(int channel) {
        try {
            return new JSONObject().put("channel", channel);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /**
     * Motion and AI state in one request. Returns bit 8 for motion plus the AI_* bits, or -1 if
     * neither command answered.
     */
    public int triggerState(int channel, boolean motion, boolean ai) throws IOException {
        JSONObject[] r;
        if (motion && ai) r = commands(new String[]{"GetMdState", "GetAiState"}, new JSONObject[]{ch(channel), ch(channel)});
        else if (motion) r = new JSONObject[]{command("GetMdState", ch(channel)), null};
        else r = new JSONObject[]{null, command("GetAiState", ch(channel))};
        if (r[0] == null && r[1] == null) return -1;
        int s = 0;
        if (r[0] != null && r[0].optInt("state", 0) == 1) s |= 8;
        if (r[1] != null) {
            if (alarm(r[1], "people")) s |= AI_PEOPLE;
            if (alarm(r[1], "vehicle")) s |= AI_VEHICLE;
            if (alarm(r[1], "dog_cat")) s |= AI_ANIMAL;
        }
        return s;
    }

    /** Newer firmware: {"people":{"alarm_state":1}}; older (e.g. RLC-810A v3.0): {"people_state":1}. */
    private static boolean alarm(JSONObject v, String k) {
        JSONObject o = v.optJSONObject(k);
        return (o != null && o.optInt("alarm_state", 0) == 1) || v.optInt(k + "_state", 0) == 1;
    }

    /**
     * Whether this lens has a floodlight / spotlight, from GetAbility (abilityChn[ch]: supportFLswitch or
     * floodLight). GetWhiteLed alone is not enough: cameras without a light (RLC-510A, RLC-810A) answer it too.
     * Null if GetAbility gave no answer (then fall back to GetWhiteLed).
     */
    public Boolean floodAbility(int channel) throws IOException {
        try {
            JSONObject v = command("GetAbility", new JSONObject().put("User", new JSONObject().put("userName", user)));
            JSONObject ab = v == null ? null : v.optJSONObject("Ability");
            JSONArray chn = ab == null ? null : ab.optJSONArray("abilityChn");
            if (chn == null || channel >= chn.length()) return null;
            JSONObject c = chn.optJSONObject(channel);
            if (c == null) return null;
            return ver(c, "supportFLswitch") > 0 || ver(c, "floodLight") > 0;
        } catch (ApiException e) {
            if (e.rspCode == -7 || e.rspCode == -27) throw e;
            return null;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    private static int ver(JSONObject c, String k) {
        JSONObject o = c.optJSONObject(k);
        return o == null ? 0 : o.optInt("ver", 0);
    }

    /** Floodlight check for the wizard and Test: GetAbility, else whether GetWhiteLed answers. */
    public boolean hasFlood(int channel) throws IOException {
        Boolean a = floodAbility(channel);
        return a != null ? a : whiteLed(channel) != null;
    }

    /** The floodlight's on/off state, or null if this camera/lens has no floodlight. */
    public Integer whiteLed(int channel) throws IOException {
        try {
            JSONObject v = command("GetWhiteLed", ch(channel));
            JSONObject w = v == null ? null : v.optJSONObject("WhiteLed");
            return w == null ? null : w.optInt("state", 0);
        } catch (ApiException e) {
            if (e.rspCode == -7 || e.rspCode == -27) throw e;
            return null; // not supported (rspCode -9) or similar
        }
    }

    public void setWhiteLed(int channel, int state) throws IOException {
        try {
            JSONObject w = new JSONObject().put("channel", channel).put("state", state);
            command("SetWhiteLed", new JSONObject().put("WhiteLed", w));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("bad command");
        }
    }

    private static final SecureRandom rnd = new SecureRandom();

    /** One JPEG from the lens (Snap), at the camera's main picture size (4-8 megapixels, up to 6 MB). */
    public byte[] snap(int channel) throws IOException {
        return snap(channel, 0, 0);
    }

    private volatile boolean noSnapSize;

    /**
     * One JPEG about w x h (the camera keeps its own shape, e.g. 1536x576 on a Duo Floodlight). Firmware
     * that does not take a size gets the full picture. Small pictures matter: a 4K camera's full Snap is
     * 1.3 MB, at 640x360 about 30 KB.
     */
    public byte[] snap(int channel, int w, int h) throws IOException {
        if (w > 0 && h > 0 && !noSnapSize) {
            try {
                return snapOnce(channel, "&width=" + w + "&height=" + h);
            } catch (ApiException e) {
                if (e.rspCode == -7 || e.rspCode == -27) throw e;
                noSnapSize = true; // this firmware does not take a size
            }
        }
        return snapOnce(channel, "");
    }

    private byte[] snapOnce(int channel, String size) throws IOException {
        for (int attempt = 0; ; attempt++) {
            String rs = Long.toHexString(rnd.nextLong() & 0xffffffffffL);
            String[] type = new String[1];
            byte[] b = httpBytes("GET", "/cgi-bin/api.cgi?cmd=Snap&channel=" + channel + size + "&rs=" + rs + "&" + auth(), null, type);
            if (type[0] != null && type[0].toLowerCase(java.util.Locale.US).startsWith("image/")) return b;
            if (b.length > 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8) return b; // JPEG without a type
            int rsp = 0;
            String detail = "no picture";
            try {
                JSONObject o = new JSONArray(new String(b, "UTF-8")).getJSONObject(0);
                JSONObject err = o.optJSONObject("error");
                if (err != null) {
                    rsp = err.optInt("rspCode", 0);
                    detail = err.optString("detail", detail);
                }
            } catch (Exception ignored) {
            }
            if (rsp == -6 && attempt == 0) {
                forgetToken();
                continue;
            }
            if (rsp == -7 || rsp == -27) throw new ApiException(loginProblem(null), rsp);
            throw new ApiException("the camera sent " + detail, rsp);
        }
    }

    // ---- HTTP ----

    private String http(String method, String path, String body, String[] type) throws IOException {
        return new String(httpBytes(method, path, body, type), "UTF-8");
    }

    /**
     * One retry: old Android (4.x) reuses kept-alive sockets the camera has already closed, and the
     * request then fails at once. Every command sent here is safe to repeat.
     */
    private byte[] httpBytes(String method, String path, String body, String[] type) throws IOException {
        try {
            return httpOnce(method, path, body, type);
        } catch (IOException e) {
            String m = e.getMessage() + "";
            if (m.startsWith("the camera answered") || m.startsWith("nothing answered") || m.startsWith("the answer is too big")) throw e;
            return httpOnce(method, path, body, type);
        }
    }

    private byte[] httpOnce(String method, String path, String body, String[] type) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + path).openConnection();
            if (c instanceof HttpsURLConnection) lanTls((HttpsURLConnection) c);
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setUseCaches(false);
            c.setRequestMethod(method);
            // Before Android 4.4's OkHttp, pooled connections to a camera go stale; there, use a fresh one each time.
            // (Not on newer Android: there "close" made the emulator cut answers short.)
            if (android.os.Build.VERSION.SDK_INT < 19) c.setRequestProperty("Connection", "close");
            if (body != null) {
                byte[] b = body.getBytes("UTF-8");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(b.length);
                OutputStream os = c.getOutputStream();
                os.write(b);
                os.close();
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("the camera answered HTTP " + code);
            if (type != null) type[0] = c.getContentType();
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(65536);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 12 * 1048576) throw new IOException("the answer is too big");
            }
            in.close();
            return bo.toByteArray();
        } catch (java.net.SocketTimeoutException e) {
            throw new IOException("the camera did not answer in time");
        } catch (java.net.ConnectException e) {
            throw new IOException("nothing answered at that address and port");
        } catch (javax.net.ssl.SSLException e) {
            throw new IOException("secure connection failed (try HTTP port 80)");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static SSLSocketFactory lanFactory;

    /** Cameras use self-signed certificates: accept them, for these LAN camera connections only. */
    public static synchronized void lanTls(HttpsURLConnection c) {
        try {
            if (lanFactory == null) {
                TrustManager[] all = {new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] x, String a) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] x, String a) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }};
                SSLContext ctx;
                try {
                    ctx = SSLContext.getInstance("TLSv1.2");
                } catch (Exception e) {
                    ctx = SSLContext.getInstance("TLS");
                }
                ctx.init(null, all, new SecureRandom());
                lanFactory = org.communitycomms.app.geo.Tls12SocketFactory.wrap(ctx.getSocketFactory());
            }
            c.setSSLSocketFactory(lanFactory);
            c.setHostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String h, SSLSession s) {
                    return true;
                }
            });
        } catch (Exception ignored) {
        }
    }

    /**
     * Finds how to talk to a camera at this address: HTTP 80, then HTTPS 443 (newer firmware turns
     * HTTP off), or the port given. Returns {port, https?1:0} once GetDevInfo answers.
     */
    public static Object[] probe(String host, int port, String user, String pass) throws IOException {
        int[][] tries = port > 0 ? new int[][]{{port, port == 443 ? 1 : 0}, {port, port == 443 ? 0 : 1}}
                : new int[][]{{80, 0}, {443, 1}};
        IOException last = null;
        for (int[] t : tries) {
            try {
                ReolinkApi a = new ReolinkApi(host, t[0], t[1] == 1, user, pass);
                DevInfo d = a.devInfo();
                a.logout();
                return new Object[]{t[0], t[1] == 1, d};
            } catch (ApiException e) {
                throw e; // it answered: wrong password etc.
            } catch (IOException e) {
                if (last == null || !(e.getMessage() + "").startsWith("nothing answered")) last = e;
            }
        }
        throw last != null ? last : new IOException("no answer");
    }
}
