package org.communitycomms.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.communitycomms.app.camera.BcClient;
import org.communitycomms.app.camera.ReolinkApi;
import org.communitycomms.app.camera.ReolinkCam;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Watches the saved Reolink cameras while a video screen is open (Tactical videos, TV, Intercom,
 * a camera full screen), or in the background for the linked floodlights if that is switched on.
 * Each lens gets its own thread: about once a second it asks GetMdState + GetAiState, and while a
 * screen is open it fetches a Snap picture for the tiles (every second for the lens in front,
 * every 2 s for the others). A trigger brings that lens to the front (VideoHub.alert) and turns on
 * every linked floodlight; they go back off a set time after the last trigger.
 */
public final class ReolinkMonitor {
    private static final String TAG = "Reolink";
    /** Ignore triggers this long after the floodlights go off (the light changing sets off motion). */
    private static final long FLOOD_SETTLE_MS = 30000;

    private static final Set<String> holders = new HashSet<String>();
    private static final Map<String, Watcher> watchers = new HashMap<String, Watcher>();
    private static Context app;
    private static int loadedVersion = -1;
    private static Thread floodThread;
    /** The lens shown large (full screen): its pictures come every second. */
    public static volatile String focused;

    private ReolinkMonitor() {
    }

    /** who = "tactical", "intercom", "view", "bg"...; screens get pictures, "bg" only triggers. */
    public static synchronized void acquire(Context c, String who) {
        app = c.getApplicationContext();
        holders.add(who);
        refresh();
    }

    public static synchronized void release(String who) {
        holders.remove(who);
        refresh();
    }

    static synchronized boolean screens() {
        for (String h : holders) if (!h.equals("bg")) return true;
        return false;
    }

    /** Starts/stops lens threads to match the saved list and who is watching. */
    public static synchronized void refresh() {
        if (app == null) return;
        boolean any = screens() || (holders.contains("bg") && ReolinkCam.backgroundWatch(app));
        List<ReolinkCam> cams = any ? ReolinkCam.load(app) : new ArrayList<ReolinkCam>();
        loadedVersion = ReolinkCam.version();
        Map<String, ReolinkCam> want = new HashMap<String, ReolinkCam>();
        boolean onScreen = screens();
        for (ReolinkCam c : cams) {
            // In the background only the cameras that work the floodlights or tell the channel are watched.
            if (c.enabled && c.host.length() > 0 && (onScreen || c.linkedFlood || c.sendAlert || c.offlineAlert)) want.put(c.key() + "|" + c.feedName(), c);
        }
        for (String k : new ArrayList<String>(watchers.keySet())) {
            Watcher w = watchers.get(k);
            ReolinkCam n = want.get(k);
            if (n == null || !same(n, w.cam)) {
                w.stop = true;
                watchers.remove(k);
            }
        }
        for (Map.Entry<String, ReolinkCam> e : want.entrySet()) {
            if (watchers.containsKey(e.getKey())) continue;
            Watcher w = new Watcher(e.getValue());
            watchers.put(e.getKey(), w);
            w.start();
        }
        if (!screens()) VideoHub.removeLocal(null);
        else {
            Set<String> keep = new HashSet<String>();
            for (ReolinkCam c : want.values()) keep.add(c.feedName());
            VideoHub.removeLocal(keep);
        }
        if (!watchers.isEmpty() && !startupChecked) {
            startupChecked = true;
            offLeftOn();
        }
        if (!watchers.isEmpty() && floodThread == null) {
            floodThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    floodLoop();
                }
            }, "reolink-flood");
            floodThread.start();
        }
    }

    private static boolean same(ReolinkCam a, ReolinkCam b) {
        return a.key().equals(b.key()) && a.user.equals(b.user) && a.pass.equals(b.pass) && a.https == b.https
                && a.showOnTrigger == b.showOnTrigger && a.linkedFlood == b.linkedFlood && a.hasFlood == b.hasFlood
                && a.sendAlert == b.sendAlert && a.announce == b.announce && a.attachPicture == b.attachPicture
                && a.offlineAlert == b.offlineAlert;
    }

    static synchronized List<ReolinkCam> watched() {
        List<ReolinkCam> l = new ArrayList<ReolinkCam>();
        for (Watcher w : watchers.values()) l.add(w.cam);
        return l;
    }

    /** The saved camera for a tile, or null. */
    public static ReolinkCam camFor(Context c, String feedName) {
        for (ReolinkCam cam : ReolinkCam.load(c)) if (cam.feedName().equals(feedName)) return cam;
        return null;
    }

    // ---- one lens ----

    private static final class Watcher extends Thread {
        final ReolinkCam cam;
        volatile boolean stop;
        private int lastState;
        private long lastTrigger, lastSnap, lastAlertSent;
        private boolean heldSinceLights;
        /** This camera ignores the size asked for and sends its own (see run()). */
        private boolean ownSize;
        private int fails;
        /** When this lens started failing (0 = answering). */
        private long failingSince;

        Watcher(ReolinkCam cam) {
            super("reolink-" + cam.channel);
            this.cam = cam;
        }

        @Override
        public void run() {
            if (cam.bcOnly) {
                bcLoop();
                return;
            }
            ReolinkApi api = ReolinkApi.of(cam);
            String name = cam.feedName();
            while (!stop) {
                long start = System.currentTimeMillis();
                if (loadedVersion != ReolinkCam.version()) refresh();
                String problem = null;
                try {
                    if (app != null && (ReolinkCam.useMotion(app) || ReolinkCam.useAi(app))) {
                        int s = api.triggerState(cam.channel, ReolinkCam.useMotion(app), ReolinkCam.useAi(app));
                        onState(name, s < 0 ? 0 : s);
                    }
                    long gap = name.equals(focused) ? 400 : name.equals(VideoHub.alerting()) ? 900 : 1900; // full screen: about 2 pictures a second
                    if (screens() && start - lastSnap >= gap) {
                        lastSnap = start;
                        // Some cameras pick their own size (a Duo Floodlight PoE answers 640x360 with 1536x576, and
                        // 1280x720 with its full 4608x1728, 5 MB): for those, full screen asks the small size too.
                        boolean big = name.equals(focused) && !ownSize;
                        byte[] jpg = big ? api.snap(cam.channel, 1280, 720) : api.snap(cam.channel, 640, 360);
                        int w = jpegWidth(jpg);
                        if (w > (big ? 1920 : 960)) ownSize = true;
                        Bitmap bm = decode(jpg, big ? 1280 : 720);
                        jpg = null;
                        if (!stop) VideoHub.putLocal(name, bm, null);
                    }
                    fails = 0;
                    failingSince = 0;
                    if (cam.offlineAlert) offlineCheck(cam, name, true, start);
                } catch (OutOfMemoryError e) {
                    // A huge picture must not end this lens's thread (it would stay blank until the app restarts).
                    ownSize = true;
                    problem = "picture too big for this phone's memory";
                    fails++;
                } catch (Exception e) {
                    problem = e.getMessage();
                    fails++;
                }
                if (problem != null && !stop && cam.offlineAlert && !(problem + "").startsWith("picture too big")) {
                    if (failingSince == 0) failingSince = start;
                    else if (start - failingSince >= OFFLINE_AFTER_MS) offlineCheck(cam, name, false, start);
                }
                if (problem != null && screens() && !stop) {
                    VideoHub.Feed f = VideoHub.feed(name);
                    VideoHub.putLocal(name, null, "offline: " + problem);
                    if (f == null) Log.i(TAG, "lens " + cam.channel + " not answering"); // never the address or sign-in
                }
                long wait = (fails > 3 ? 5000 : name.equals(focused) ? 450 : 1000) - (System.currentTimeMillis() - start);
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }

        /** Motion / AI bits from the last pushed alarm event (port 9000 cameras). */
        private volatile int bcState;

        /**
         * Port-9000-only camera (Reolink's own protocol, e.g. the E1): one connection that signs in, takes
         * still pictures for the tiles, and gets motion / AI events pushed. Offline = it cannot be reached
         * or signed in to. A refused password waits a minute before trying again (cameras lock out).
         */
        private void bcLoop() {
            String name = cam.feedName();
            while (!stop) {
                long start = System.currentTimeMillis();
                if (loadedVersion != ReolinkCam.version()) refresh();
                BcClient c = new BcClient();
                String problem;
                boolean loginRefused = false;
                try {
                    c.connect(cam.host, cam.bcPort, 5000);
                    c.login(cam.user, cam.pass, 10000);
                    c.setEvents(new BcClient.Events() {
                        @Override
                        public void onEvent(String xml) {
                            bcState = bcParse(xml);
                        }

                        @Override
                        public void onClosed(String why) {
                        }
                    });
                    try {
                        c.subscribe(8000);
                    } catch (java.io.IOException e) {
                        Log.i(TAG, "no events from this camera: " + e.getMessage());
                    }
                    failingSince = 0;
                    if (cam.offlineAlert) offlineCheck(cam, name, true, start);
                    long lastKeep = System.currentTimeMillis();
                    while (!stop && c.isOpen()) {
                        long now = System.currentTimeMillis();
                        if (loadedVersion != ReolinkCam.version()) refresh();
                        if (app != null && (ReolinkCam.useMotion(app) || ReolinkCam.useAi(app))) {
                            int s = bcState;
                            if (!ReolinkCam.useMotion(app)) s &= ~8;
                            if (!ReolinkCam.useAi(app)) s &= 8;
                            onState(name, s);
                        }
                        long gap = name.equals(focused) ? 400 : name.equals(VideoHub.alerting()) ? 900 : 1900;
                        if (screens() && now - lastSnap >= gap) {
                            lastSnap = now;
                            byte[] jpg = c.snapshot(cam.channel, name.equals(focused) ? "main" : "sub", 15000);
                            Bitmap bm = decode(jpg, name.equals(focused) ? 1280 : 720);
                            if (!stop) VideoHub.putLocal(name, bm, null);
                        }
                        if (now - lastKeep > 30000) {
                            lastKeep = now;
                            c.keepalive(8000);
                        }
                        long wait = (name.equals(focused) ? 450 : 1000) - (System.currentTimeMillis() - now);
                        if (wait > 0) Thread.sleep(wait);
                    }
                    problem = "the connection to the camera ended";
                } catch (BcClient.LoginFailed e) {
                    problem = e.getMessage();
                    loginRefused = true;
                } catch (InterruptedException e) {
                    c.close();
                    return;
                } catch (OutOfMemoryError e) {
                    problem = "picture too big for this phone's memory";
                } catch (Exception e) {
                    problem = e.getMessage();
                } finally {
                    if (stop) c.logout();
                    c.close();
                }
                if (stop) return;
                bcState = 0;
                long now = System.currentTimeMillis();
                if (cam.offlineAlert && !loginRefused) {
                    if (failingSince == 0) failingSince = now;
                    else if (now - failingSince >= OFFLINE_AFTER_MS) offlineCheck(cam, name, false, now);
                }
                if (screens()) VideoHub.putLocal(name, null, "offline: " + problem);
                try {
                    Thread.sleep(loginRefused ? 60000 : 5000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
        private void onState(String name, int s) {
            long now = System.currentTimeMillis();
            // Our own floodlights going off look like motion, and real cameras hold that alarm for a while:
            // ignore it during the settle time, and after it until the alarm has dropped once (only fresh
            // movement counts). Seen on real cameras: without this the lights came back on every minute or so.
            if (cam.linkedFlood && s != 0 && (now < settleUntil || heldSinceLights)) {
                heldSinceLights = true;
                s = 0;
            } else if (s == 0) {
                heldSinceLights = false;
            }
            if (s != 0) {
                String what = (s & ReolinkApi.AI_PEOPLE) != 0 ? "Person" : (s & ReolinkApi.AI_VEHICLE) != 0 ? "Vehicle"
                        : (s & ReolinkApi.AI_ANIMAL) != 0 ? "Animal" : "Motion";
                lastTrigger = now;
                long quiet = ReolinkCam.quietSeconds(app) * 1000L;
                if (cam.showOnTrigger) VideoHub.alert(name, what, now + quiet);
                else {
                    VideoHub.Feed f = VideoHub.feed(name);
                    if (f != null) f.alert = what;
                }
                if (cam.linkedFlood) floodTrigger(now);
                // At most one channel alert per camera per hold time.
                if (cam.sendAlert && now - lastAlertSent >= quiet) {
                    lastAlertSent = now;
                    sendAlert(cam, name, what);
                }
            } else if (lastState != 0 && now - lastTrigger > 3000) {
                VideoHub.clearAlert(name);
            }
            if (s != 0 || now - lastTrigger > 3000) lastState = s;
        }
    }

    // ---- telling the channel ----

    private static final long OFFLINE_AFTER_MS = 120000;
    /** Cameras (address) the channel has been told are offline: one message per camera, not per lens. */
    private static final Set<String> toldOffline = new HashSet<String>();

    private static boolean toldLoaded;

    /** Kept on disk, so "back online" is still sent after the app or the tablet restarts. */
    private static void loadToldOffline() {
        if (toldLoaded || app == null) return;
        toldLoaded = true;
        for (String k : ReolinkCam.sp(app).getString("toldOffline", "").split("\n")) if (k.length() > 0) toldOffline.add(k);
    }

    private static void saveToldOffline() {
        if (app == null) return;
        StringBuilder b = new StringBuilder();
        for (String k : toldOffline) b.append(k).append('\n');
        ReolinkCam.sp(app).edit().putString("toldOffline", b.toString()).commit();
    }

    /** "Camera offline: Garage" after 2 minutes without an answer, "Camera back online: Garage" when it answers again. */
    private static void offlineCheck(final ReolinkCam cam, final String name, final boolean online, long now) {
        final String host = cam.host + ":" + cam.httpPort;
        synchronized (toldOffline) {
            loadToldOffline();
            if (online != toldOffline.contains(host)) return; // nothing new to say
        }
        alertPool.execute(new Runnable() {
            @Override
            public void run() {
                RadioService s = RadioService.instance();
                if (s == null || s.messages() == null || s.engine() == null) return; // not on a channel: try again later
                String label = cam.channels > 1 ? name.replaceAll(" lens \\d+$", "") : name;
                String text = (online ? "Camera back online: " : "Camera offline: ") + label;
                synchronized (toldOffline) {
                    if (online != toldOffline.contains(host)) return;
                    if (!s.messages().sendText(cam.announce ? RadioService.SAY + text : text)) return;
                    if (online) toldOffline.remove(host);
                    else toldOffline.add(host);
                    saveToldOffline();
                }
            }
        });
    }

    private static final ExecutorService alertPool = Executors.newSingleThreadExecutor();

    /**
     * "Motion: Garage (person)" as a normal chat message on the channel this phone is on (so it also
     * crosses the internet relay), optionally read aloud on the other phones (text as voice), and
     * optionally one small picture. Never live video. Skipped when not on a channel.
     */
    private static void sendAlert(final ReolinkCam cam, final String name, final String what) {
        alertPool.execute(new Runnable() {
            @Override
            public void run() {
                RadioService s = RadioService.instance();
                if (s == null || s.messages() == null || s.engine() == null) return;
                String text = "Motion: " + name + " (" + what.toLowerCase(java.util.Locale.US) + ")";
                s.messages().sendText(cam.announce ? RadioService.SAY + text : text);
                if (!cam.attachPicture || app == null) return;
                try {
                    Bitmap bm = decode(ReolinkApi.of(cam).snap(cam.channel, 640, 360), 640);
                    if (bm == null) return;
                    if (bm.getWidth() > 640) { // e.g. a Duo Floodlight sends 1536x576 whatever size is asked for
                        Bitmap small = Bitmap.createScaledBitmap(bm, 640, Math.max(1, bm.getHeight() * 640 / bm.getWidth()), true);
                        bm.recycle();
                        bm = small;
                    }
                    java.io.File dir = new java.io.File(app.getCacheDir(), "outgoing");
                    dir.mkdirs();
                    String fname = "motion-" + name.replaceAll("[^A-Za-z0-9]+", "-") + "-"
                            + new java.text.SimpleDateFormat("HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".jpg";
                    java.io.File f = new java.io.File(dir, fname);
                    java.io.FileOutputStream os = new java.io.FileOutputStream(f);
                    try {
                        bm.compress(Bitmap.CompressFormat.JPEG, 60, os); // about 30-50 KB: kind to the radio
                    } finally {
                        os.close();
                    }
                    org.communitycomms.app.net.FileTransfer ft = RadioService.files();
                    if (ft != null) RadioService.logFileSent(app, ft.send(f, fname, "image/jpeg"), "image/jpeg", f);
                } catch (Exception e) {
                    Log.i(TAG, "motion picture not sent: " + e.getMessage());
                }
            }
        });
    }

    /**
     * A pushed AlarmEvent: status "MD" = motion; AItype lists people / vehicle / dog_cat ("other" counts as
     * motion). "none" clears it. Returns the same bits as ReolinkApi.triggerState.
     */
    static int bcParse(String xml) {
        if (xml == null || xml.indexOf("AlarmEvent") < 0) return 0;
        int s = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<AlarmEvent(?:\\s[^>]*)?>(.*?)</AlarmEvent>",
                java.util.regex.Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            String ev = m.group(1);
            String status = BcClient.tag(ev, "status"), ai = BcClient.tag(ev, "AItype");
            if (status != null && status.contains("MD")) s |= 8;
            if (ai != null) {
                if (ai.contains("people")) s |= ReolinkApi.AI_PEOPLE;
                if (ai.contains("vehicle")) s |= ReolinkApi.AI_VEHICLE;
                if (ai.contains("dog_cat")) s |= ReolinkApi.AI_ANIMAL;
                if (ai.contains("other") && (s & 8) == 0) s |= 8;
            }
            String smart = BcClient.tag(ev, "type"); // smartAiTypeList entries
            if (smart != null) {
                if (smart.contains("people")) s |= ReolinkApi.AI_PEOPLE;
                if (smart.contains("vehicle")) s |= ReolinkApi.AI_VEHICLE;
                if (smart.contains("dog_cat")) s |= ReolinkApi.AI_ANIMAL;
            }
        }
        return s;
    }

    /** Width of a JPEG without decoding it. */
    static int jpegWidth(byte[] jpg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        return o.outWidth;
    }

    /** Decodes at about maxW wide: Reolink pictures are 4-8 megapixels, too big for old phones' memory. */
    static Bitmap decode(byte[] jpg, int maxW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        int sample = 1;
        while (o.outWidth / sample > maxW * 3 / 2) sample *= 2; // e.g. 4608 wide for 1280 → quarter size, 1152
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(jpg, 0, jpg.length, d);
    }

    // ---- linked floodlights ----

    private static final Object floodLock = new Object();
    private static volatile long floodOffAt, settleUntil;
    private static boolean floodOn;
    /** Lenses we switched on, and whether each was already on before (then we leave it on). */
    private static final Map<String, Integer> floodBefore = new HashMap<String, Integer>();
    private static final ExecutorService floodPool = Executors.newSingleThreadExecutor();

    static void floodTrigger(long now) {
        synchronized (floodLock) {
            floodOffAt = now + ReolinkCam.floodSeconds(app) * 1000L;
            if (floodOn) return; // already on: just keeps them on longer (no repeated commands)
            floodOn = true;
        }
        floodPool.execute(new Runnable() {
            @Override
            public void run() {
                for (ReolinkCam c : watched()) {
                    if (!c.linkedFlood || !c.hasFlood) continue;
                    try {
                        ReolinkApi api = ReolinkApi.of(c);
                        Integer before = api.whiteLed(c.channel);
                        if (before == null) continue;
                        synchronized (floodLock) {
                            floodBefore.put(c.key(), before);
                        }
                        if (before != 1) {
                            owned(c.key(), true);
                            api.setWhiteLed(c.channel, 1);
                        }
                    } catch (Exception e) {
                        Log.i(TAG, "floodlight on failed on lens " + c.channel + ": " + e.getMessage());
                    }
                }
            }
        });
    }

    /** Turns this camera's floodlight on or off by hand (full-screen camera view). */
    public static void manualFlood(final ReolinkCam c, final boolean on) {
        floodPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ReolinkApi.of(c).setWhiteLed(c.channel, on ? 1 : 0);
                } catch (Exception e) {
                    Log.i(TAG, "floodlight failed: " + e.getMessage());
                }
            }
        });
    }

    private static boolean startupChecked;

    /** Remembers (on disk) which floodlights this app has switched on, so a crash or reboot cannot leave them on. */
    private static synchronized void owned(String key, boolean on) {
        android.content.SharedPreferences sp = ReolinkCam.sp(app);
        Set<String> s = new HashSet<String>();
        for (String k : sp.getString("floodOwned", "").split("\n")) if (k.length() > 0) s.add(k);
        if (on) s.add(key);
        else s.remove(key);
        StringBuilder b = new StringBuilder();
        for (String k : s) b.append(k).append('\n');
        sp.edit().putString("floodOwned", b.toString()).commit();
    }

    /** First start after the app was stopped with floodlights on: turn those off. */
    private static void offLeftOn() {
        final String left = ReolinkCam.sp(app).getString("floodOwned", "");
        if (left.length() == 0) return;
        floodPool.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (floodLock) {
                    if (floodOn) return; // a new trigger has them on now: the normal timer turns them off
                }
                for (ReolinkCam c : ReolinkCam.load(app)) {
                    if (!left.contains(c.key() + "\n")) continue;
                    try {
                        ReolinkApi.of(c).setWhiteLed(c.channel, 0);
                        owned(c.key(), false);
                    } catch (Exception e) {
                        Log.i(TAG, "floodlight off (left on) failed on lens " + c.channel + ": " + e.getMessage());
                    }
                }
            }
        });
    }

    private static void floodLoop() {
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            final Map<String, Integer> restore;
            synchronized (floodLock) {
                if (!floodOn || System.currentTimeMillis() < floodOffAt) continue;
                floodOn = false;
                settleUntil = System.currentTimeMillis() + FLOOD_SETTLE_MS;
                restore = new HashMap<String, Integer>(floodBefore);
                floodBefore.clear();
            }
            floodPool.execute(new Runnable() {
                @Override
                public void run() {
                    List<ReolinkCam> all = app == null ? watched() : ReolinkCam.load(app);
                    for (ReolinkCam c : all) {
                        Integer before = restore.get(c.key());
                        if (before == null || before == 1) continue; // it was on before: leave it as it was
                        try {
                            ReolinkApi.of(c).setWhiteLed(c.channel, 0);
                            owned(c.key(), false);
                        } catch (Exception e) {
                            Log.i(TAG, "floodlight off failed on lens " + c.channel + ": " + e.getMessage());
                        }
                    }
                }
            });
        }
    }
}
