package org.communitycomms.app;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.Surface;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Share my camera, as a foreground service: it keeps sending pictures (and audio, if ticked) when the
 * screen is left, the Home button is pressed or the screen is blacked out, with a notification (red dot)
 * while it runs and a Stop button there. The phone camera runs with no screen at all (an off-screen
 * preview texture); the Share my camera screen shows the pictures as they are sent. Torch on the back camera.
 */
@SuppressWarnings("deprecation")
public final class CameraShareService extends Service implements Camera.PreviewCallback {
    static final String ACTION_START = "start", ACTION_STOP = "stop";
    static final String EXTRA_SOURCE = "source", EXTRA_WIDTH = "width", EXTRA_FPS = "fps", EXTRA_URL = "url",
            EXTRA_AUDIO = "audio", EXTRA_TELEMETRY = "telemetry";
    private static final int NOTIF_ID = 4720;

    private static volatile CameraShareService instance;
    /** The last picture sent (for the screen), and a line about how it is going. */
    static volatile Bitmap lastPicture;
    static volatile String statusLine = "";
    /** Called (any thread) after each picture or a change, while the screen is open. */
    static volatile Runnable onChange;

    private volatile boolean sharing, encoding;
    private int source, width, fps;
    private String url;
    private Camera camera;
    private SurfaceTexture texture;
    private int previewW, previewH, camRotation;
    private boolean torch;
    private long lastFrameMs, sentBytes;
    private int sent;
    private Thread netThread;
    private VideoTelemetry telemetry;
    private org.communitycomms.app.audio.Capture mic;
    private final org.communitycomms.app.audio.ImaAdpcm adpcm = new org.communitycomms.app.audio.ImaAdpcm();
    private byte[] audioBuf;
    private int audioFill, audioSeq;
    private PowerManager.WakeLock wake;

    public static boolean running() {
        CameraShareService s = instance;
        return s != null && s.sharing;
    }

    static void start(Context c, int source, int width, int fps, String url, boolean audio, boolean telemetry) {
        Intent i = new Intent(c, CameraShareService.class).setAction(ACTION_START).putExtra(EXTRA_SOURCE, source)
                .putExtra(EXTRA_WIDTH, width).putExtra(EXTRA_FPS, fps).putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_AUDIO, audio).putExtra(EXTRA_TELEMETRY, telemetry);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        c.startService(new Intent(c, CameraShareService.class).setAction(ACTION_STOP));
    }

    /** Back camera torch on or off; false when this camera has none. */
    static boolean setTorch(boolean on) {
        CameraShareService s = instance;
        return s != null && s.torch(on);
    }

    static boolean torchOn() {
        CameraShareService s = instance;
        return s != null && s.torch;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        instance = this;
        if (i == null || ACTION_STOP.equals(i.getAction())) {
            stopSharing();
            stopSelf();
            return START_NOT_STICKY;
        }
        stopSharing();
        source = i.getIntExtra(EXTRA_SOURCE, 0);
        width = i.getIntExtra(EXTRA_WIDTH, 240);
        fps = Math.max(1, i.getIntExtra(EXTRA_FPS, 2));
        url = i.getStringExtra(EXTRA_URL);
        boolean audio = i.getBooleanExtra(EXTRA_AUDIO, false);
        goForeground(audio);
        sharing = true;
        sent = 0;
        sentBytes = 0;
        lastPicture = null;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "communitycomms:camera");
            wake.setReferenceCounted(false);
            wake.acquire();
        }
        if (i.getBooleanExtra(EXTRA_TELEMETRY, true)) {
            telemetry = new VideoTelemetry(this);
            telemetry.start();
        }
        if (source == 2) startNetwork();
        else startCamera();
        if (audio) startMic();
        changed("Sharing started");
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopSharing();
        instance = null;
        super.onDestroy();
    }

    // ---- notification: a red dot while the camera is shared ----

    private void goForeground(boolean audio) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("camera", "Camera sharing", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, "camera");
        } else {
            b = new Notification.Builder(this);
        }
        int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 47, new Intent(this, VideoShareActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), fl);
        PendingIntent stop = PendingIntent.getService(this, 48, new Intent(this, CameraShareService.class).setAction(ACTION_STOP), fl);
        b.setContentTitle("● Camera live: sharing to the channel")
                .setContentText((source == 2 ? "Network camera" : source == 1 ? "Front camera" : "Back camera")
                        + (audio ? " with audio" : "") + ". Tap to open.")
                .setSmallIcon(R.drawable.ic_stat_camera_live)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(0, "Stop", stop);
        if (Build.VERSION.SDK_INT >= 21) b.setColor(0xffff1744);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 30) startTyped(n, audio);
        else startForeground(NOTIF_ID, n);
    }

    @TargetApi(30)
    private void startTyped(Notification n, boolean audio) {
        int type = source == 2 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        if (audio) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        try {
            startForeground(NOTIF_ID, n, type);
        } catch (RuntimeException e) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
    }

    private void stopSharing() {
        sharing = false;
        org.communitycomms.app.audio.Capture m = mic;
        mic = null;
        if (m != null) m.stop();
        if (telemetry != null) telemetry.stop();
        telemetry = null;
        if (netThread != null) netThread.interrupt();
        netThread = null;
        stopCamera();
        if (wake != null) wake.release();
        wake = null;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        changed("Not sharing");
    }

    private void changed(String s) {
        statusLine = s;
        Runnable r = onChange;
        if (r != null) r.run();
    }

    // ---- phone camera, with no screen ----

    private void startCamera() {
        boolean front = source == 1;
        try {
            int camId = -1;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int k = 0; k < Camera.getNumberOfCameras(); k++) {
                Camera.getCameraInfo(k, info);
                if ((info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) == front) {
                    camId = k;
                    break;
                }
            }
            if (camId < 0 && Camera.getNumberOfCameras() > 0) camId = 0;
            if (camId < 0) throw new RuntimeException("no camera");
            camera = Camera.open(camId);
            Camera.Parameters p = camera.getParameters();
            Camera.Size best = null;
            for (Camera.Size s : p.getSupportedPreviewSizes()) {
                if (s.width < 320) continue;
                if (best == null || s.width * s.height < best.width * best.height) best = s;
            }
            if (best != null) p.setPreviewSize(best.width, best.height);
            java.util.List<String> focus = p.getSupportedFocusModes();
            if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            camera.setParameters(p);
            Camera.Size ps = camera.getParameters().getPreviewSize();
            previewW = ps.width;
            previewH = ps.height;
            int display = displayRotation();
            Camera.getCameraInfo(camId, info);
            camRotation = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? (info.orientation + display) % 360
                    : (info.orientation - display + 360) % 360;
            texture = new SurfaceTexture(10); // off-screen: the camera runs with the screen left or off
            camera.setPreviewTexture(texture);
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Exception e) {
            changed("Camera unavailable: " + e.getMessage());
            stopCamera();
        }
    }

    private int displayRotation() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return 0;
        switch (wm.getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    private void stopCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
            } catch (RuntimeException ignored) {
            }
            camera.release();
            camera = null;
        }
        torch = false;
        if (texture != null) texture.release();
        texture = null;
    }

    private boolean torch(boolean on) {
        Camera c = camera;
        if (c == null) return false;
        try {
            Camera.Parameters p = c.getParameters();
            java.util.List<String> modes = p.getSupportedFlashModes();
            String want = on ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF;
            if (modes == null || !modes.contains(want)) return false;
            p.setFlashMode(want);
            c.setParameters(p);
            torch = on;
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void onPreviewFrame(final byte[] data, Camera cam) {
        if (!sharing || encoding) return;
        long now = System.currentTimeMillis();
        if (now - lastFrameMs < 1000 / fps) return;
        lastFrameMs = now;
        encoding = true;
        final int w = previewW, h = previewH, rot = camRotation;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    YuvImage yuv = new YuvImage(data, ImageFormat.NV21, w, h, null);
                    ByteArrayOutputStream raw = new ByteArrayOutputStream();
                    yuv.compressToJpeg(new Rect(0, 0, w, h), 70, raw);
                    byte[] r = raw.toByteArray();
                    Bitmap bm = BitmapFactory.decodeByteArray(r, 0, r.length);
                    if (bm != null) {
                        send(bm, rot);
                        bm.recycle();
                    }
                } catch (Throwable ignored) {
                    // a dropped picture is fine; the next follows shortly
                } finally {
                    encoding = false;
                }
            }
        }, "video-encode").start();
    }

    /** Shrinks, turns upright, adds the telemetry, squeezes to a small JPEG (under about 12 KB) and sends it. */
    private void send(Bitmap src, int rotation) {
        float scale = (float) width / Math.max(src.getWidth(), src.getHeight());
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        if (rotation != 0) m.postRotate(rotation);
        Bitmap small = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        VideoTelemetry tm = telemetry;
        if (tm != null) {
            Bitmap drawn = tm.draw(small);
            if (drawn != small && small != src) small.recycle();
            small = drawn;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int q = 50;
        small.compress(Bitmap.CompressFormat.JPEG, q, out);
        while (out.size() > 12000 && q > 15) {
            q -= 15;
            out.reset();
            small.compress(Bitmap.CompressFormat.JPEG, q, out);
        }
        byte[] jpeg = out.toByteArray();
        Bitmap shown = small == src ? small.copy(Bitmap.Config.ARGB_8888, false) : small;
        lastPicture = shown; // the screen shows it; the previous one is left to the garbage collector
        RadioService svc = RadioService.instance();
        RadioEngine e = svc == null ? null : svc.engine();
        if (e == null || !sharing) {
            changed("Not on a channel: pictures are not going out");
            return;
        }
        e.sendVideoFrame(jpeg);
        sent++;
        sentBytes += jpeg.length;
        changed("Sharing: " + sent + " pictures, last " + (jpeg.length / 1024) + " KB, about "
                + (sentBytes / Math.max(1, sent) * fps / 1024) + " KB a second");
    }

    // ---- network camera ----

    private void startNetwork() {
        final String address = url == null ? "" : url.trim();
        if (!address.startsWith("http://") && !address.startsWith("https://")) {
            changed("Enter the camera's still picture address, starting http://");
            return;
        }
        final long gap = 1000 / fps;
        netThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (sharing && !Thread.currentThread().isInterrupted()) {
                    long start = System.currentTimeMillis();
                    try {
                        Bitmap bm = fetch(address, width);
                        if (bm != null) send(bm, 0);
                    } catch (Exception e) {
                        changed("Camera not answering: " + e.getMessage());
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ie) {
                            return;
                        }
                    }
                    long wait = gap - (System.currentTimeMillis() - start);
                    if (wait > 0) {
                        try {
                            Thread.sleep(wait);
                        } catch (InterruptedException ie) {
                            return;
                        }
                    }
                }
            }
        }, "video-net");
        netThread.start();
    }

    private static Bitmap fetch(String address, int width) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(4000);
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 4 * 1024 * 1024) throw new Exception("picture too big");
            }
            byte[] d = out.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(d, 0, d.length, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= width) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(d, 0, d.length, o);
        } finally {
            c.disconnect();
        }
    }

    // ---- audio (optional): 80 ms of IMA ADPCM per packet, played only on the Tactical videos screen ----

    private void startMic() {
        final int frame = RadioEngine.FRAME_SAMPLES, per = org.communitycomms.app.audio.ImaAdpcm.encodedSize(frame);
        audioBuf = new byte[2 * per];
        audioFill = 0;
        mic = new org.communitycomms.app.audio.Capture(frame, new org.communitycomms.app.audio.Capture.FrameSink() {
            @Override
            public void onFrame(short[] pcm, int samples) {
                if (!sharing) return;
                audioFill += adpcm.encode(pcm, samples, audioBuf, audioFill);
                if (audioFill < audioBuf.length) return;
                audioFill = 0;
                RadioService svc = RadioService.instance();
                RadioEngine e = svc == null ? null : svc.engine();
                if (e != null) e.sendVideoAudio(audioBuf.clone(), audioSeq++);
            }
        });
        mic.start();
    }
}
