import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pretend Reolink dual-lens camera for testing the app on an emulator, with no real camera and
 * no sound. Answers the HTTP API the app uses (Login, GetDevInfo, Snap, GetMdState, GetAiState,
 * GetWhiteLed, SetWhiteLed) and opens ports 9000 and 554 so the network sweep finds it (they
 * accept and close: there is no Baichuan or RTSP behind them). Binds to 127.0.0.1 only; an
 * Android emulator reaches it at 10.0.2.2.
 *
 *   java FakeReolink.java [--http 80] [--user admin] [--pass test1234] [--lease 3600] [--notoken] [--manual]
 *
 * Lens 1 (channel 0) has a floodlight and "sees a person" for 6 s every 45 s; lens 2 (channel 1)
 * has no floodlight and only plain motion for 4 s every 70 s. GET /fake/trigger?ch=1&secs=8 sets one
 * off now. Everything is logged without passwords.
 */
public class FakeReolink {
    static String user = "admin", pass = "test1234";
    static int lease = 3600;
    static boolean noToken, manual;
    static final Map<String, Long> tokens = new HashMap<>();
    static final int[] led = {0, -1}; // -1: no floodlight on that lens
    static final long[] forcedUntil = {0, 0};
    static final long start = System.currentTimeMillis();

    public static void main(String[] a) throws Exception {
        int http = 80;
        for (int i = 0; i < a.length; i++) {
            switch (a[i]) {
                case "--http": http = Integer.parseInt(a[++i]); break;
                case "--user": user = a[++i]; break;
                case "--pass": pass = a[++i]; break;
                case "--lease": lease = Integer.parseInt(a[++i]); break;
                case "--notoken": noToken = true; break;
                case "--manual": manual = true; break; // no timed triggers, only /fake/trigger
                default: System.err.println("unknown option " + a[i]);
            }
        }
        for (int p : new int[]{9000, 554}) acceptAndClose(p);
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", http), 16);
        s.createContext("/cgi-bin/api.cgi", FakeReolink::api);
        s.createContext("/fake/trigger", ex -> {
            Map<String, String> q = query(ex);
            int ch = Integer.parseInt(q.getOrDefault("ch", "0"));
            int secs = Integer.parseInt(q.getOrDefault("secs", "8"));
            forcedUntil[ch] = System.currentTimeMillis() + secs * 1000L;
            log("forced trigger on lens " + (ch + 1) + " for " + secs + " s");
            send(ex, 200, "text/plain", "ok\n".getBytes());
        });
        s.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        s.start();
        log("fake Reolink on 127.0.0.1:" + http + (noToken ? " (old firmware: no token login)" : "") + ", ports 9000/554 open");
    }

    static void acceptAndClose(int port) {
        try {
            ServerSocket ss = new ServerSocket();
            ss.bind(new InetSocketAddress("127.0.0.1", port));
            Thread t = new Thread(() -> {
                while (true) {
                    try (Socket c = ss.accept()) {
                        c.getInputStream(); // accept, then close
                    } catch (Exception ignored) {
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            log("could not open port " + port + ": " + e.getMessage());
        }
    }

    static synchronized void log(String s) {
        System.out.println(new SimpleDateFormat("HH:mm:ss").format(new Date()) + "  " + s);
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(kv.substring(0, i), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    static void send(HttpExchange ex, int code, String type, byte[] b) throws java.io.IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    static String err(String cmd, int rsp, String detail) {
        return "{\"cmd\":\"" + cmd + "\",\"code\":1,\"error\":{\"detail\":\"" + detail + "\",\"rspCode\":" + rsp + "}}";
    }

    static String ok(String cmd, String value) {
        return "{\"cmd\":\"" + cmd + "\",\"code\":0,\"value\":" + value + "}";
    }

    /** People on lens 1 for 6 s every 45 s; plain motion on lens 2 for 4 s every 70 s; or forced. */
    static int state(int ch) {
        long now = System.currentTimeMillis();
        if (now < forcedUntil[ch]) return ch == 0 ? 3 : 1;
        if (manual) return 0;
        long t = (now - start) / 1000;
        if (ch == 0 && t % 45 >= 30 && t % 45 < 36) return 3; // motion + person
        if (ch == 1 && t % 70 >= 50 && t % 70 < 54) return 1; // motion only
        return 0;
    }

    static final int[] lastLogged = {0, 0};

    static void api(HttpExchange ex) throws java.io.IOException {
        Map<String, String> q = query(ex);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String cmd0 = q.getOrDefault("cmd", "");
        // Sign-in: token, or user/password on the request (old firmware).
        boolean authed = false;
        String how = "none";
        String tok = q.get("token");
        if (tok != null) {
            Long until = tokens.get(tok);
            authed = until != null && until > System.currentTimeMillis();
            how = authed ? "token" : "expired token";
        } else if (q.containsKey("user")) {
            authed = user.equals(q.get("user")) && pass.equals(q.get("password"));
            how = authed ? "user+password" : "wrong user/password";
        }
        if (cmd0.equals("Snap")) {
            if (!authed) {
                log("Snap refused (" + how + ")");
                send(ex, 200, "text/html", ("[" + err("Snap", tok != null ? -6 : -7, tok != null ? "please login first" : "login failed") + "]").getBytes());
                return;
            }
            int ch = Integer.parseInt(q.getOrDefault("channel", "0"));
            send(ex, 200, "image/jpeg", picture(ch));
            return;
        }
        List<String> cmds = new ArrayList<>();
        Matcher m = Pattern.compile("\"cmd\"\\s*:\\s*\"(\\w+)\"").matcher(body);
        while (m.find()) cmds.add(m.group(1));
        List<Integer> chans = new ArrayList<>();
        Matcher mc = Pattern.compile("\"channel\"\\s*:\\s*(\\d+)").matcher(body);
        while (mc.find()) chans.add(Integer.parseInt(mc.group(1)));
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < cmds.size(); i++) {
            String c = cmds.get(i);
            int ch = i < chans.size() ? Math.min(1, chans.get(i)) : 0;
            if (out.length() > 1) out.append(',');
            if (c.equals("Login")) {
                if (noToken) {
                    out.append(err("Login", -9, "not support"));
                    log("Login: not supported (old firmware mode)");
                    continue;
                }
                Matcher u = Pattern.compile("\"userName\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
                Matcher p = Pattern.compile("\"password\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
                if (u.find() && p.find() && u.group(1).equals(user) && p.group(1).equals(pass)) {
                    String t = Long.toHexString(System.nanoTime());
                    tokens.put(t, System.currentTimeMillis() + lease * 1000L);
                    out.append(ok("Login", "{\"Token\":{\"leaseTime\":" + lease + ",\"name\":\"" + t + "\"}}"));
                    log("Login OK, token for " + lease + " s");
                } else {
                    out.append(err("Login", -7, "login failed"));
                    log("Login refused (wrong user/password)");
                }
                continue;
            }
            if (!authed) {
                out.append(err(c, tok != null ? -6 : -7, tok != null ? "please login first" : "login failed"));
                log(c + " refused (" + how + ")");
                continue;
            }
            switch (c) {
                case "GetDevInfo":
                    out.append(ok(c, "{\"DevInfo\":{\"model\":\"FAKE Duo 2\",\"name\":\"Fake Cam\",\"channelNum\":2,"
                            + "\"firmVer\":\"v0.0.0-fake\",\"type\":\"IPC\"}}"));
                    log("GetDevInfo (" + how + ")");
                    break;
                case "GetMdState": {
                    int s = state(ch);
                    out.append(ok(c, "{\"state\":" + (s != 0 ? 1 : 0) + "}"));
                    if (s != lastLogged[ch]) {
                        lastLogged[ch] = s;
                        log("lens " + (ch + 1) + (s == 0 ? " quiet" : s == 3 ? " PERSON + motion" : " motion"));
                    }
                    break;
                }
                case "GetAiState": {
                    int p = (state(ch) & 2) != 0 ? 1 : 0;
                    out.append(ok(c, "{\"channel\":" + ch + ",\"dog_cat\":{\"alarm_state\":0,\"support\":1},"
                            + "\"face\":{\"alarm_state\":0,\"support\":0},\"people\":{\"alarm_state\":" + p + ",\"support\":1},"
                            + "\"vehicle\":{\"alarm_state\":0,\"support\":1}}"));
                    break;
                }
                case "GetWhiteLed":
                    if (led[ch] < 0) out.append(err(c, -9, "not support"));
                    else out.append(ok(c, "{\"WhiteLed\":{\"channel\":" + ch + ",\"mode\":1,\"bright\":80,\"state\":" + led[ch] + "}}"));
                    break;
                case "SetWhiteLed": {
                    Matcher st = Pattern.compile("\"state\"\\s*:\\s*(\\d)").matcher(body);
                    int v = st.find() ? Integer.parseInt(st.group(1)) : 0;
                    if (led[ch] < 0) out.append(err(c, -9, "not support"));
                    else {
                        led[ch] = v;
                        out.append(ok(c, "{\"rspCode\":200}"));
                        log("FLOODLIGHT lens " + (ch + 1) + " -> " + (v == 1 ? "ON" : "OFF"));
                    }
                    break;
                }
                default:
                    out.append(err(c, -9, "not support"));
                    log(c + ": not supported by the fake");
            }
        }
        out.append(']');
        send(ex, 200, "application/json", out.toString().getBytes(StandardCharsets.UTF_8));
    }

    static byte[] picture(int ch) throws java.io.IOException {
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, ch == 0 ? new Color(20, 60, 40) : new Color(30, 40, 80), 1280, 720, Color.BLACK));
        g.fillRect(0, 0, 1280, 720);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 72));
        g.drawString("FAKE LENS " + (ch + 1), 60, 140);
        g.setFont(new Font("SansSerif", Font.PLAIN, 56));
        g.drawString(new SimpleDateFormat("HH:mm:ss").format(new Date()), 60, 240);
        int s = state(ch);
        if (s != 0) {
            g.setColor(new Color(255, 90, 80));
            g.drawString(s == 3 ? "PERSON" : "MOTION", 60, 340);
        }
        if (led[ch] == 1) {
            g.setColor(new Color(255, 240, 160));
            g.drawString("FLOODLIGHT ON", 60, 440);
        }
        g.dispose();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", bo);
        return bo.toByteArray();
    }
}
