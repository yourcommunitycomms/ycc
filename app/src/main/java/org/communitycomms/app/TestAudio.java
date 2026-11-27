package org.communitycomms.app;

import android.annotation.TargetApi;
import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import org.json.JSONArray;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Mesh testing: Play audio to channel. An internet radio stream (plain HTTP/HTTPS MP3 or AAC, Icecast/Shoutcast,
 * .m3u/.pls) or local audio files are decoded with MediaCodec to PCM, resampled to the channel's voice format and
 * sent as live voice from this phone, as if PTT were held: YCC channels get the normal encrypted 8 kHz voice
 * frames (RadioEngine inject), the OpenMANET / TAK talk group the standard Opus 48 kHz 20 ms RTP stream.
 * The sending phone plays nothing unless the monitor is ticked (off by default).
 */
@TargetApi(16)
public final class TestAudio {
    private static final String TAG = "TestAudio";
    private static volatile Player cur;
    private static volatile String nowPlaying = "", lastStatus = "";

    private TestAudio() {
    }

    public static boolean running() {
        return cur != null;
    }

    public static String nowPlaying() {
        return nowPlaying;
    }

    public static String lastStatus() {
        return lastStatus;
    }

    // ---- settings (meshtest prefs) ----

    public static boolean useFiles() {
        return "files".equals(MeshTest.sp().getString("ta_src", "url"));
    }

    /** Source: whatever other apps play on this phone (Android 10+ playback capture). */
    public static boolean useSpeaker() {
        return "speaker".equals(MeshTest.sp().getString("ta_src", "url")) && CaptureService.supported();
    }

    static final String PHONE_AUDIO = "phone audio (other apps)";

    /** Checks before asking Android for capture permission. Returns an error to show, or null. */
    public static String precheck() {
        if (cur != null) return "Already playing";
        RadioService rs = RadioService.instance();
        if (rs == null || (rs.engine() == null && rs.compat() == null)) return "Join a channel first";
        if (RadioEngine.radioSilent) return "Radio silent is on: nothing can be sent";
        if (MeshTest.onPublicChannel()) return PRIVATE_ONLY;
        RadioEngine e = rs.compat() != null ? null : rs.engine();
        if (e != null && e.listenOnly()) return "This channel is listen-only on this phone";
        return null;
    }

    /** Play audio is never allowed on Public, the cloud group or OM/TAK (no setting can allow it). */
    public static final String PRIVATE_ONLY = "Play audio works on private channels only";

    /** CaptureService has the capture running: start sending it. */
    static void captureReady() {
        String err = precheck();
        if (err == null) {
            RadioService rs = RadioService.instance();
            List<String> src = new ArrayList<String>();
            src.add(PHONE_AUDIO);
            Player p = new Player(rs.compat(), rs.compat() != null ? null : rs.engine(), src, false);
            p.capture = true;
            err = p.begin();
            if (err == null) cur = p;
        }
        if (err != null) captureFailed(err);
    }

    static void captureFailed(final String err) {
        lastStatus = err;
        Log.w(TAG, "capture: " + err);
        if (MeshTest.app != null) {
            CaptureService.stopNow(MeshTest.app);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    android.widget.Toast.makeText(MeshTest.app, err, android.widget.Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    public static String url() {
        return MeshTest.sp().getString("ta_url", "");
    }

    public static List<String> files() {
        List<String> out = new ArrayList<String>();
        try {
            JSONArray a = new JSONArray(MeshTest.sp().getString("ta_files", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void setFiles(List<String> l) {
        MeshTest.sp().edit().putString("ta_files", new JSONArray(l).toString()).commit();
    }

    public static boolean loop() {
        return MeshTest.sp().getBoolean("ta_loop", false);
    }

    public static int gainPct() {
        return MeshTest.sp().getInt("ta_gain", 100);
    }

    public static int autoStopMin() {
        return MeshTest.sp().getInt("ta_auto", 10);
    }

    public static boolean monitor() {
        return MeshTest.sp().getBoolean("ta_monitor", false);
    }

    // ---- start / stop ----

    /** Starts from the settings, after the public channel check. Returns an error to show, or null. */
    public static String start(Activity a) {
        String pre = precheck();
        if (pre != null) return pre;
        RadioService rs = RadioService.instance();
        RadioEngine e = rs.compat() != null ? null : rs.engine();
        List<String> src = new ArrayList<String>();
        if (useFiles()) src.addAll(files());
        else if (url().trim().length() > 0) src.add(url().trim());
        if (src.isEmpty()) return useFiles() ? "Add one or more audio files first" : "Type a stream address first";
        Player p = new Player(rs.compat(), e, src, !useFiles());
        String err = p.begin();
        if (err != null) return err;
        cur = p;
        return null;
    }

    public static void stop(String why) {
        Player p = cur;
        cur = null;
        if (p != null) p.end(why);
    }

    // ---- the player ----

    private static final class Player {
        final CompatVoice compat;
        final RadioEngine engine;
        final List<String> sources;
        final boolean stream;
        final int rate, frame, frameMs;
        final ArrayBlockingQueue<short[]> queue;
        volatile boolean stopped, decodeDone;
        final long startedAt = System.currentTimeMillis();
        final long autoStopMs = autoStopMin() * 60000L;
        final float gain = gainPct() / 100f;
        AudioTrack mon;
        Thread dec, snd;
        volatile InputStream openStream;
        volatile int underruns, framesSent;
        volatile double energy;
        boolean capture;
        int publicTick;

        Player(CompatVoice c, RadioEngine e, List<String> src, boolean stream) {
            compat = c;
            engine = e;
            sources = src;
            this.stream = stream;
            rate = c != null ? 48000 : RadioEngine.SAMPLE_RATE;
            frame = c != null ? 960 : RadioEngine.FRAME_SAMPLES;
            frameMs = frame * 1000 / rate;
            queue = new ArrayBlockingQueue<short[]>(4000 / frameMs); // 4 s ahead at most
        }

        String begin() {
            boolean ok = compat != null ? compat.testStart() : engine.injectStart(null);
            if (!ok) return compat != null ? "The talk group is busy, or this phone is talking" : "This phone is talking on the channel: release PTT first";
            if (monitor()) {
                try {
                    int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    mon = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(min, frame * 8), AudioTrack.MODE_STREAM);
                    mon.play();
                } catch (RuntimeException ex) {
                    mon = null;
                }
            }
            lastStatus = "Starting…";
            nowPlaying = stream ? "Connecting to " + shortUrl(sources.get(0)) : "";
            dec = new Thread(new Runnable() {
                @Override
                public void run() {
                    decodeAll();
                }
            }, "testaudio-dec");
            snd = new Thread(new Runnable() {
                @Override
                public void run() {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                    sendLoop();
                }
            }, "testaudio-tx");
            dec.start();
            snd.start();
            Log.i(TAG, "start " + (compat != null ? "talk group Opus 48k" : "channel " + MeshTest.label(engine) + " 8k") + " " + sources);
            return null;
        }

        void end(String why) {
            if (stopped) return;
            stopped = true;
            InputStream s = openStream;
            if (s != null) try {
                s.close();
            } catch (IOException ignored) {
            }
            if (dec != null) dec.interrupt();
            if (capture && MeshTest.app != null) CaptureService.stopNow(MeshTest.app);
            if (snd != null && Thread.currentThread() != snd) {
                try {
                    snd.join(500);
                } catch (InterruptedException ignored) {
                }
            }
            if (compat != null) compat.testEnd();
            else engine.injectEnd();
            if (mon != null) {
                try {
                    mon.stop();
                    mon.release();
                } catch (RuntimeException ignored) {
                }
            }
            lastStatus = "Stopped: " + why;
            Log.i(TAG, "stop: " + why + " after " + (System.currentTimeMillis() - startedAt) / 1000 + " s, " + framesSent + " audio frames sent (RMS "
                    + (framesSent == 0 ? 0 : Math.round(Math.sqrt(energy / framesSent))) + "), " + underruns + " underruns");
        }

        /** Paced sender: one frame every 40 ms (YCC) or 20 ms (talk group); silence while the source catches up. */
        void sendLoop() {
            long next = System.currentTimeMillis();
            short[] silence = new short[frame];
            int sent = 0;
            // Buffer a second before the first frame (and half a second after running dry), so a stream that only
            // just keeps up does not break into many tiny gaps. Silence goes out meanwhile: the channel stays held.
            int prime = 1000 / frameMs;
            boolean buffering = true;
            while (!stopped) {
                RadioService rs = RadioService.instance();
                if (rs == null || (compat != null ? rs.compat() != compat : rs.engine() != engine)) {
                    finish("the channel changed");
                    return;
                }
                if (++publicTick % 25 == 0 && MeshTest.onPublicChannel()) { // about once a second
                    finish("public channel: play audio works on private channels only");
                    return;
                }
                if (autoStopMs > 0 && System.currentTimeMillis() - startedAt > autoStopMs) {
                    finish("auto-stop after " + autoStopMin() + " min");
                    return;
                }
                if (buffering && (queue.size() >= prime || decodeDone)) buffering = false;
                short[] f = buffering ? null : queue.poll();
                if (f == null) {
                    if (decodeDone && queue.isEmpty()) {
                        finish("finished");
                        return;
                    }
                    f = silence;
                    if (sent > 0 && !buffering) {
                        underruns++;
                        buffering = true;
                        prime = 500 / frameMs;
                    }
                } else {
                    sent++;
                    framesSent = sent;
                    long e = 0;
                    for (short v : f) e += (long) v * v;
                    energy += e / (double) frame;
                    lastStatus = "On air";
                }
                if (compat != null) compat.testAudio(f, frame);
                else engine.injectFrame(f);
                if (mon != null) mon.write(f, 0, frame);
                next += frameMs;
                long wait = next - System.currentTimeMillis();
                if (wait < -500) next = System.currentTimeMillis(); // fell far behind (phone busy): start the clock again
                else if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }

        void finish(final String why) {
            if (cur == this) cur = null;
            end(why);
        }

        /**
         * Phone audio: read what other apps play (CaptureService) in real time. Silence for 8 s means nothing is
         * playing, or the playing app blocks capture (allowAudioPlaybackCapture=false, protected/DRM audio).
         */
        void captureLoop() throws InterruptedException {
            short[] buf = new short[CaptureService.RATE / 50]; // 20 ms
            long loudAt = System.currentTimeMillis();
            boolean warned = false;
            nowPlaying = "Phone audio";
            while (!stopped) {
                android.media.AudioRecord r = CaptureService.record();
                if (r == null) {
                    lastStatus = "Phone audio capture ended";
                    return;
                }
                int n = r.read(buf, 0, buf.length);
                if (n <= 0) {
                    Thread.sleep(20);
                    continue;
                }
                int peak = 0;
                for (int i = 0; i < n; i++) peak = Math.max(peak, Math.abs(buf[i]));
                long now = System.currentTimeMillis();
                if (peak > 64) {
                    loudAt = now;
                    if (warned) {
                        warned = false;
                        nowPlaying = "Phone audio";
                        Log.i(TAG, "phone audio is coming through again");
                    }
                } else if (!warned && now - loudAt > 8000) {
                    warned = true;
                    nowPlaying = "No audio captured: nothing is playing, or the playing app blocks sharing";
                    Log.i(TAG, "no phone audio for 8 s");
                }
                pcm(buf, n, CaptureService.RATE, 1);
            }
        }

        void decodeAll() {
            if (capture) {
                try {
                    captureLoop();
                } catch (InterruptedException ignored) {
                } finally {
                    decodeDone = true;
                }
                return;
            }
            try {
                do {
                    for (int i = 0; i < sources.size() && !stopped; i++) {
                        String s = sources.get(i);
                        try {
                            if (stream) playStream(s);
                            else {
                                nowPlaying = new java.io.File(s).getName() + (sources.size() > 1 ? "  (" + (i + 1) + " of " + sources.size() + ")" : "");
                                playFile(s);
                            }
                        } catch (InterruptedException e) {
                            return;
                        } catch (Exception e) {
                            Log.w(TAG, "source " + s + ": " + e);
                            lastStatus = "Could not play " + (stream ? shortUrl(s) : new java.io.File(s).getName()) + ": " + e.getMessage();
                            if (stream || sources.size() == 1) {
                                decodeDone = true;
                                return;
                            }
                        }
                    }
                } while (loop() && !stopped && !stream);
            } finally {
                decodeDone = true;
            }
        }

        // ---- PCM out of the decoder: mono, resampled, gain, cut into frames ----

        Resampler rs;
        int rsFrom = -1;
        short[] acc;
        int accN;

        void pcm(short[] s, int n, int srcRate, int ch) throws InterruptedException {
            if (srcRate <= 0 || ch <= 0) return;
            if (rs == null || rsFrom != srcRate) {
                rs = new Resampler(srcRate, rate);
                rsFrom = srcRate;
            }
            if (acc == null) acc = new short[frame];
            for (int i = 0; i + ch <= n; i += ch) {
                int m = 0;
                for (int k = 0; k < ch; k++) m += s[i + k];
                rs.push(m / (float) ch);
                while (rs.ready()) {
                    float v = rs.take() * gain;
                    acc[accN++] = (short) (v > 32767 ? 32767 : v < -32768 ? -32768 : v);
                    if (accN == frame) {
                        while (!stopped && !queue.offer(acc, 200, TimeUnit.MILLISECONDS)) {
                            // the sender is behind real time: wait
                        }
                        acc = new short[frame];
                        accN = 0;
                    }
                }
                if (stopped) throw new InterruptedException();
            }
        }

        // ---- local files: MediaExtractor + MediaCodec ----

        void playFile(String path) throws Exception {
            MediaExtractor ex = new MediaExtractor();
            try {
                ex.setDataSource(path);
                int track = -1;
                MediaFormat fmt = null;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    String m = f.getString(MediaFormat.KEY_MIME);
                    if (m != null && m.startsWith("audio/")) {
                        track = i;
                        fmt = f;
                        break;
                    }
                }
                if (track < 0) throw new IOException("no audio in the file");
                ex.selectTrack(track);
                Dec d = new Dec(fmt);
                try {
                    ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
                    byte[] tmp = new byte[256 * 1024];
                    while (!stopped) {
                        buf.clear();
                        int n = ex.readSampleData(buf, 0);
                        if (n < 0) break;
                        buf.position(0);
                        buf.get(tmp, 0, n);
                        d.feed(tmp, 0, n, ex.getSampleTime());
                        ex.advance();
                    }
                    d.finish();
                } finally {
                    d.release();
                }
            } finally {
                ex.release();
            }
        }

        /** One MediaCodec decoder, fed whole compressed frames, giving PCM to pcm(). */
        final class Dec {
            final MediaCodec codec;
            ByteBuffer[] ins, outs;
            int srcRate, ch;
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean eos;

            @SuppressWarnings("deprecation")
            Dec(MediaFormat fmt) throws IOException {
                codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
                codec.configure(fmt, null, null, 0);
                codec.start();
                ins = codec.getInputBuffers();
                outs = codec.getOutputBuffers();
                srcRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
                ch = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            }

            void feed(byte[] b, int off, int len, long ptsUs) throws InterruptedException {
                while (!stopped) {
                    int i = codec.dequeueInputBuffer(10000);
                    if (i >= 0) {
                        ByteBuffer in = ins[i];
                        in.clear();
                        int n = Math.min(len, in.capacity());
                        in.put(b, off, n);
                        codec.queueInputBuffer(i, 0, n, Math.max(0, ptsUs), 0);
                        break;
                    }
                    drain(0);
                }
                drain(0);
            }

            void finish() throws InterruptedException {
                for (int t = 0; t < 50 && !stopped; t++) {
                    int i = codec.dequeueInputBuffer(10000);
                    if (i >= 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        break;
                    }
                    drain(0);
                }
                for (int t = 0; t < 200 && !eos && !stopped; t++) drain(10000);
            }

            @SuppressWarnings("deprecation")
            void drain(long timeoutUs) throws InterruptedException {
                while (!stopped) {
                    int o = codec.dequeueOutputBuffer(info, timeoutUs);
                    if (o >= 0) {
                        if (info.size > 0) {
                            ByteBuffer out = outs[o];
                            out.position(info.offset);
                            out.limit(info.offset + info.size);
                            short[] s = new short[info.size / 2];
                            out.order(ByteOrder.nativeOrder()).asShortBuffer().get(s);
                            codec.releaseOutputBuffer(o, false);
                            pcm(s, s.length, srcRate, ch);
                        } else {
                            codec.releaseOutputBuffer(o, false);
                        }
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            eos = true;
                            return;
                        }
                    } else if (o == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        outs = codec.getOutputBuffers();
                    } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat f = codec.getOutputFormat();
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    } else {
                        return; // nothing ready
                    }
                    timeoutUs = 0;
                }
            }

            void release() {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                }
                codec.release();
            }
        }

        // ---- streams: our own HTTP (Shoutcast answers "ICY 200 OK"), ICY titles, MP3 / ADTS AAC frames ----

        void playStream(String url) throws Exception {
            int tries = 0;
            while (!stopped) {
                try {
                    playStreamOnce(resolvePlaylist(url, 0));
                    return; // the server ended the stream
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    if (stopped || ++tries > 3) throw e;
                    lastStatus = "Stream dropped (" + e.getMessage() + "), reconnecting…";
                    Thread.sleep(2000L * tries);
                }
            }
        }

        void playStreamOnce(String url) throws Exception {
            Conn c = Conn.open(url, 0);
            openStream = c.in;
            try {
                if (c.isPlaylist()) throw new IOException("playlist inside a playlist");
                String ct = c.type == null ? "" : c.type.toLowerCase(java.util.Locale.US);
                if (ct.contains("ogg") || ct.contains("opus") || ct.contains("flac")) throw new IOException("Ogg/Opus/FLAC streams are not supported (MP3 or AAC only)");
                if (ct.contains("mpegurl") && url.toLowerCase(java.util.Locale.US).contains(".m3u8")) throw new IOException("HLS (.m3u8) streams are not supported");
                nowPlaying = c.name != null && c.name.length() > 0 ? c.name : shortUrl(url);
                lastStatus = "Buffering…";
                InputStream in = c.metaint > 0 ? new IcyStream(c.in, c.metaint) : c.in;
                FrameReader fr = new FrameReader(in);
                Dec d = null;
                String fmtKey = null;
                try {
                    long pts = 0;
                    while (!stopped) {
                        byte[] f = fr.next();
                        if (f == null) break;
                        if (d == null || !fr.key.equals(fmtKey)) {
                            if (d != null) d.release();
                            d = new Dec(fr.format());
                            fmtKey = fr.key;
                        }
                        d.feed(f, fr.payloadOff, f.length - fr.payloadOff, pts);
                        pts += fr.frameUs;
                    }
                } finally {
                    if (d != null) d.release();
                }
            } finally {
                openStream = null;
                try {
                    c.in.close();
                } catch (IOException ignored) {
                }
                if (c.socket != null) try {
                    c.socket.close();
                } catch (IOException ignored) {
                }
            }
        }

        /** .m3u / .pls (by name or content type): the first stream address in it. */
        String resolvePlaylist(String url, int depth) throws Exception {
            String low = url.toLowerCase(java.util.Locale.US);
            boolean byName = low.matches(".*\\.(m3u|pls)(\\?.*)?$");
            if (!byName && depth == 0) {
                // Look at the content type: some playlist links have no extension.
                Conn c = Conn.open(url, 0);
                boolean pl = c.isPlaylist();
                String text = pl ? readText(c.in) : null;
                try {
                    c.in.close();
                } catch (IOException ignored) {
                }
                if (c.socket != null) c.socket.close();
                if (!pl) return url;
                return firstUrl(text, url, depth);
            }
            if (!byName) return url;
            Conn c = Conn.open(url, 0);
            String text = readText(c.in);
            try {
                c.in.close();
            } catch (IOException ignored) {
            }
            if (c.socket != null) c.socket.close();
            return firstUrl(text, url, depth);
        }

        String firstUrl(String text, String from, int depth) throws Exception {
            for (String line : text.split("[\r\n]+")) {
                String l = line.trim();
                int eq = l.indexOf('=');
                if (l.toLowerCase(java.util.Locale.US).startsWith("file") && eq > 0) l = l.substring(eq + 1).trim();
                if (l.startsWith("http://") || l.startsWith("https://")) {
                    return depth < 2 ? resolvePlaylist(l, depth + 1) : l;
                }
            }
            throw new IOException("no stream address in the playlist " + shortUrl(from));
        }
    }

    static String readText(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while (bo.size() < 65536 && (n = in.read(b)) > 0) bo.write(b, 0, n);
        return new String(bo.toByteArray(), "UTF-8");
    }

    static String shortUrl(String u) {
        String s = u.replaceFirst("^https?://", "");
        return s.length() > 48 ? s.substring(0, 45) + "…" : s;
    }

    /** An open HTTP(S) response. Plain http uses a socket of our own, so Shoutcast's "ICY 200 OK" works on every Android. */
    static final class Conn {
        InputStream in;
        Socket socket;
        String type, name;
        int metaint;

        boolean isPlaylist() {
            String t = type == null ? "" : type.toLowerCase(java.util.Locale.US);
            return t.contains("mpegurl") || t.contains("scpls") || t.contains("x-pls") || t.startsWith("text/");
        }

        static Conn open(String url, int redirects) throws IOException {
            if (redirects > 5) throw new IOException("too many redirects");
            URL u = new URL(url);
            Conn c = new Conn();
            if ("https".equalsIgnoreCase(u.getProtocol())) {
                java.net.HttpURLConnection h = org.communitycomms.app.net.LanNet.open(u);
                org.communitycomms.app.geo.Tls12SocketFactory.apply(h);
                h.setConnectTimeout(10000);
                h.setReadTimeout(15000);
                h.setRequestProperty("Icy-MetaData", "1");
                h.setRequestProperty("User-Agent", "YCC");
                int code = h.getResponseCode();
                if (code >= 400) throw new IOException("server answered " + code);
                c.in = new BufferedInputStream(h.getInputStream(), 16384);
                c.type = h.getContentType();
                c.name = h.getHeaderField("icy-name");
                try {
                    c.metaint = Integer.parseInt(h.getHeaderField("icy-metaint").trim());
                } catch (Exception ignored) {
                }
                return c;
            }
            int port = u.getPort() > 0 ? u.getPort() : 80;
            Socket s = new Socket();
            s.connect(new InetSocketAddress(u.getHost(), port), 10000);
            s.setSoTimeout(15000);
            String path = u.getFile().length() == 0 ? "/" : u.getFile();
            OutputStream os = s.getOutputStream();
            os.write(("GET " + path + " HTTP/1.0\r\nHost: " + u.getHost() + (u.getPort() > 0 ? ":" + port : "")
                    + "\r\nUser-Agent: YCC\r\nAccept: */*\r\nIcy-MetaData: 1\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
            os.flush();
            InputStream in = new BufferedInputStream(s.getInputStream(), 16384);
            String status = line(in);
            String[] parts = status.split(" ");
            int code = parts.length > 1 ? parseInt(parts[1]) : 0;
            String location = null;
            for (String h = line(in); h.length() > 0; h = line(in)) {
                int i = h.indexOf(':');
                if (i <= 0) continue;
                String k = h.substring(0, i).trim().toLowerCase(java.util.Locale.US), v = h.substring(i + 1).trim();
                if (k.equals("content-type")) c.type = v;
                else if (k.equals("icy-metaint")) c.metaint = parseInt(v);
                else if (k.equals("icy-name")) c.name = v;
                else if (k.equals("location")) location = v;
            }
            if (code >= 300 && code < 400 && location != null) {
                s.close();
                return open(new URL(u, location).toString(), redirects + 1);
            }
            if (code != 200) {
                s.close();
                throw new IOException("server answered " + (status.length() > 40 ? status.substring(0, 40) : status));
            }
            c.in = in;
            c.socket = s;
            return c;
        }

        static int parseInt(String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        static String line(InputStream in) throws IOException {
            StringBuilder b = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0 && c != '\n') {
                if (c != '\r') b.append((char) c);
                if (b.length() > 4096) break;
            }
            if (c < 0 && b.length() == 0) throw new IOException("connection closed");
            return b.toString();
        }
    }

    /** Takes the ICY metadata blocks out of the audio, keeping the StreamTitle for the now playing line. */
    static final class IcyStream extends InputStream {
        final InputStream in;
        final int metaint;
        int left;

        IcyStream(InputStream in, int metaint) {
            this.in = in;
            this.metaint = metaint;
            left = metaint;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n <= 0 ? -1 : b[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left == 0) {
                int ml = in.read();
                if (ml < 0) return -1;
                ml *= 16;
                byte[] m = new byte[ml];
                int got = 0;
                while (got < ml) {
                    int n = in.read(m, got, ml - got);
                    if (n < 0) return -1;
                    got += n;
                }
                if (ml > 0) {
                    String meta = new String(m, "UTF-8");
                    int i = meta.indexOf("StreamTitle='");
                    if (i >= 0) {
                        int j = meta.indexOf("';", i + 13);
                        String t = meta.substring(i + 13, j > 0 ? j : meta.length()).trim();
                        if (t.length() > 0) nowPlaying = t;
                    }
                }
                left = metaint;
            }
            int n = in.read(b, off, Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** Finds MP3 (MPEG audio layer II/III) or ADTS AAC frames in a raw stream. */
    static final class FrameReader {
        static final int[] BR_V1_L3 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
        static final int[] BR_V1_L2 = {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384};
        static final int[] BR_V2 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
        static final int[] SR_V1 = {44100, 48000, 32000};
        static final int[] AAC_SR = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

        final InputStream in;
        final byte[] buf = new byte[65536];
        int pos, end;
        String key = "";
        int payloadOff;
        long frameUs;
        // the current format
        boolean aac;
        int sr, ch, profile, srIdx, layer;

        FrameReader(InputStream in) {
            this.in = in;
        }

        private boolean fill(int need) throws IOException {
            if (end - pos >= need) return true;
            if (pos > 0) {
                System.arraycopy(buf, pos, buf, 0, end - pos);
                end -= pos;
                pos = 0;
            }
            while (end - pos < need) {
                int n = in.read(buf, end, buf.length - end);
                if (n <= 0) return false;
                end += n;
            }
            return true;
        }

        /** Frame length at pos, or 0 when there is no valid header there. Sets the format fields. */
        private int header(int p) {
            int b1 = buf[p + 1] & 0xff, b2 = buf[p + 2] & 0xff, b3 = buf[p + 3] & 0xff;
            if ((buf[p] & 0xff) != 0xff) return 0;
            if ((b1 & 0xf6) == 0xf0) { // ADTS
                int b4 = buf[p + 4] & 0xff, b5 = buf[p + 5] & 0xff;
                int si = (b2 >> 2) & 0xf;
                if (si >= AAC_SR.length) return 0;
                int len = ((b3 & 3) << 11) | (b4 << 3) | ((b5 >> 5) & 7);
                int hdr = (b1 & 1) == 1 ? 7 : 9;
                if (len <= hdr) return 0;
                aac = true;
                profile = (b2 >> 6) & 3;
                srIdx = si;
                sr = AAC_SR[si];
                ch = Math.max(1, ((b2 & 1) << 2) | ((b3 >> 6) & 3));
                payloadOff = hdr;
                frameUs = 1024L * 1000000 / sr;
                key = "aac" + profile + "/" + srIdx + "/" + ch;
                return len;
            }
            if ((b1 & 0xe0) != 0xe0) return 0;
            int ver = (b1 >> 3) & 3, lay = (b1 >> 1) & 3, bri = (b2 >> 4) & 0xf, sri = (b2 >> 2) & 3, pad = (b2 >> 1) & 1;
            if (ver == 1 || lay == 0 || lay == 3 || bri == 0 || bri == 15 || sri == 3) return 0; // reserved, layer I, free format
            int rate = SR_V1[sri] >> (ver == 3 ? 0 : ver == 2 ? 1 : 2);
            int br = ver == 3 ? (lay == 1 ? BR_V1_L3[bri] : BR_V1_L2[bri]) : BR_V2[bri];
            int len = (ver == 3 || lay == 2 ? 144 : 72) * br * 1000 / rate + pad;
            if (len < 24) return 0;
            aac = false;
            layer = lay == 1 ? 3 : 2;
            sr = rate;
            ch = ((b3 >> 6) & 3) == 3 ? 1 : 2;
            payloadOff = 0;
            frameUs = (ver == 3 || lay == 2 ? 1152L : 576L) * 1000000 / rate;
            key = "mp" + layer + "/" + sr + "/" + ch;
            return len;
        }

        private boolean locked;

        /** The next whole frame (header included), or null at the end of the stream. */
        byte[] next() throws IOException {
            int skipped = 0;
            while (true) {
                if (!fill(10)) return null;
                int len = header(pos);
                if (len > 0 && len < 8192 && fill(len + (locked ? 0 : 10))) {
                    // Before the first frame: check the next header too (a stray 0xFF in the data looks like one).
                    if (!locked) {
                        String k = key;
                        boolean wasAac = aac;
                        int savedOff = payloadOff;
                        long savedUs = frameUs;
                        int nextLen = header(pos + len);
                        boolean ok = nextLen > 0 && wasAac == aac;
                        header(pos); // restore the fields for this frame
                        if (!ok) {
                            pos++;
                            continue;
                        }
                        key = k;
                        payloadOff = savedOff;
                        frameUs = savedUs;
                        locked = true;
                    }
                    byte[] f = new byte[len];
                    System.arraycopy(buf, pos, f, 0, len);
                    pos += len;
                    return f;
                }
                if (len > 0 && !fill(len)) return null;
                pos++;
                locked = false;
                if (++skipped > 512 * 1024) throw new IOException("not an MP3 or AAC stream");
            }
        }

        MediaFormat format() {
            if (!aac) {
                return MediaFormat.createAudioFormat(layer == 3 ? "audio/mpeg" : "audio/mpeg-L2", sr, ch);
            }
            MediaFormat f = MediaFormat.createAudioFormat("audio/mp4a-latm", sr, ch);
            int asc = ((profile + 1) << 11) | (srIdx << 7) | (ch << 3);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[]{(byte) (asc >> 8), (byte) asc}));
            return f;
        }
    }

    /** Mono resampler: windowed-sinc low-pass (when going down) then linear interpolation. */
    static final class Resampler {
        final double step; // source samples per output sample
        final float[] taps;
        final float[] hist;
        int hp;
        double nextOut;
        long index = -1;
        float prev;
        final float[] out = new float[64];
        int outR, outW;

        Resampler(int src, int dst) {
            step = src / (double) dst;
            if (src > dst) {
                int n = 31;
                taps = new float[n];
                double fc = 0.45 * dst / src; // cut-off as a fraction of the source rate
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    double m = i - (n - 1) / 2.0;
                    double sinc = m == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * m) / (Math.PI * m);
                    double w = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (n - 1));
                    taps[i] = (float) (sinc * w);
                    sum += taps[i];
                }
                for (int i = 0; i < n; i++) taps[i] /= sum;
                hist = new float[n];
            } else {
                taps = null;
                hist = null;
            }
        }

        void push(float x) {
            if (taps != null) {
                hist[hp] = x;
                hp = (hp + 1) % hist.length;
                float y = 0;
                for (int i = 0, k = hp; i < taps.length; i++) {
                    y += taps[i] * hist[k];
                    if (++k == hist.length) k = 0;
                }
                x = y;
            }
            index++;
            while (nextOut <= index) {
                double frac = nextOut - (index - 1);
                float v = (float) (prev + (x - prev) * frac);
                out[outW] = v;
                outW = (outW + 1) % out.length;
                nextOut += step;
            }
            prev = x;
        }

        boolean ready() {
            return outR != outW;
        }

        float take() {
            float v = out[outR];
            outR = (outR + 1) % out.length;
            return v;
        }
    }
}
