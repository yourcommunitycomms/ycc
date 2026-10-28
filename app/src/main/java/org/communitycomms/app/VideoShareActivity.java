package org.communitycomms.app;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Share my camera: small, low-rate pictures (1 to 5 a second, or up to 15 when Tiny) to everyone on
 * the channel, for the Tactical videos screen, with optional audio (off by default). The source is this phone's back or front camera, or a network camera
 * on the MANET (a "float cam", dash cam or IP camera) that gives a still JPEG at a web address.
 * Sharing runs only while this screen is open, with the screen kept on.
 */
@SuppressWarnings("deprecation")
public final class VideoShareActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private static final int REQ_CAMERA = 3;
    private static final String[] SOURCES = {"Back camera", "Front camera", "Network camera (web address)"};
    private static final String[] SIZES = {"Tiny (160 wide)", "Small (240 wide)", "Medium (320 wide)"};
    private static final int[] WIDTHS = {160, 240, 320};
    private static final String[] RATES = {"1 a second", "2 a second", "5 a second", "10 a second (Tiny only)",
            "15 a second (Tiny only)"};
    private static final int[] FPS = {1, 2, 5, 10, 15};
    /** Above this many a second only Tiny pictures are sent, which keeps the channel usable. */
    private static final int MAX_FPS_LARGER = 5;

    private SharedPreferences sp;
    private Spinner source, size, rate;
    private android.widget.CheckBox audio;
    private org.communitycomms.app.audio.Capture mic;
    private final org.communitycomms.app.audio.ImaAdpcm adpcm = new org.communitycomms.app.audio.ImaAdpcm();
    private byte[] audioBuf;
    private int audioFill, audioSeq;
    private EditText url;
    private Button go;
    private TextView status;
    private SurfaceView surface;
    private ImageView netPreview;
    private Camera camera;
    private int camId = -1, previewW, previewH, camRotation;
    private volatile boolean sharing, encoding;
    private long lastFrameMs;
    private int sent;
    private long sentBytes;
    private Thread netThread;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Share my camera");
        sp = getSharedPreferences("video", MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        source = spinner(root, "Camera", SOURCES, sp.getInt("source", 0));
        url = new EditText(this);
        url.setSingleLine(true);
        url.setHint("http://10.41.x.x/snapshot.jpg");
        url.setText(sp.getString("url", ""));
        root.addView(url);
        size = spinner(root, "Picture size", SIZES, sp.getInt("size", 1));
        rate = spinner(root, "Pictures", RATES, sp.getInt("rate", 2));
        audio = new android.widget.CheckBox(this);
        audio.setText("Send audio too (only heard by people on the Tactical videos screen)");
        audio.setChecked(sp.getBoolean("audio", false));
        root.addView(audio);

        go = new Button(this);
        go.setText("Start sharing");
        root.addView(go);
        status = new TextView(this);
        status.setText("Pictures go to everyone on your current channel. Others see them under Tactical videos.");
        root.addView(status);

        FrameLayout frame = new FrameLayout(this);
        surface = new SurfaceView(this);
        frame.addView(surface);
        netPreview = new ImageView(this);
        netPreview.setVisibility(View.GONE);
        frame.addView(netPreview);
        root.addView(frame, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        surface.getHolder().addCallback(this);
        surface.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        source.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                sp.edit().putInt("source", pos).commit();
                url.setVisibility(pos == 2 ? View.VISIBLE : View.GONE);
                stopSharing();
                stopCamera();
                if (pos < 2 && surface.getHolder().getSurface() != null && surface.getHolder().getSurface().isValid()) {
                    startCamera(surface.getHolder());
                }
                surface.setVisibility(pos < 2 ? View.VISIBLE : View.GONE);
                netPreview.setVisibility(pos < 2 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> a) {
            }
        });
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sharing) stopSharing();
                else startSharing();
            }
        });
    }

    private Spinner spinner(LinearLayout root, String label, String[] items, int sel) {
        TextView t = new TextView(this);
        t.setText(label);
        root.addView(t);
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(Math.min(sel, items.length - 1));
        root.addView(s);
        return s;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 23 && !hasCamera()) askCamera();
    }

    @Override
    protected void onPause() {
        stopSharing();
        stopCamera();
        super.onPause();
    }

    @TargetApi(23)
    private boolean hasCamera() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @TargetApi(23)
    private void askCamera() {
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code == REQ_CAMERA && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            if (surface.getHolder().getSurface() != null && surface.getHolder().getSurface().isValid()) {
                startCamera(surface.getHolder());
            }
        }
    }

    private void startSharing() {
        RadioService svc = RadioService.instance();
        RadioEngine e = svc == null ? null : svc.engine();
        if (e == null) {
            Toast.makeText(this, "Join a channel first", Toast.LENGTH_LONG).show();
            return;
        }
        if (e.listenOnly()) {
            Toast.makeText(this, "This channel is listen only", Toast.LENGTH_LONG).show();
            return;
        }
        if (FPS[rate.getSelectedItemPosition()] > MAX_FPS_LARGER && size.getSelectedItemPosition() != 0) {
            size.setSelection(0);
            Toast.makeText(this, "More than " + MAX_FPS_LARGER + " a second sends Tiny pictures", Toast.LENGTH_LONG).show();
        }
        sp.edit().putInt("size", size.getSelectedItemPosition()).putInt("rate", rate.getSelectedItemPosition())
                .putString("url", url.getText().toString().trim()).putBoolean("audio", audio.isChecked()).commit();
        sharing = true;
        sent = 0;
        sentBytes = 0;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        go.setText("Stop sharing");
        if (source.getSelectedItemPosition() == 2) startNetwork();
        else if (camera == null) status.setText("Camera not open");
        if (audio.isChecked()) startMic();
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

    private void stopMic() {
        org.communitycomms.app.audio.Capture m = mic;
        mic = null;
        if (m != null) m.stop();
    }

    private void stopSharing() {
        sharing = false;
        stopMic();
        if (netThread != null) {
            netThread.interrupt();
            netThread = null;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (go != null) go.setText("Start sharing");
    }

    private int targetWidth() {
        return WIDTHS[Math.max(0, size.getSelectedItemPosition())];
    }

    private long frameGapMs() {
        return 1000 / FPS[Math.max(0, rate.getSelectedItemPosition())];
    }

    /** Shrinks, turns upright and squeezes a picture to a small JPEG (under about 12 KB). */
    private static byte[] squeeze(Bitmap src, int width, int rotation) {
        float scale = (float) width / Math.max(src.getWidth(), src.getHeight());
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        if (rotation != 0) m.postRotate(rotation);
        Bitmap small = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int q = 50;
        small.compress(Bitmap.CompressFormat.JPEG, q, out);
        while (out.size() > 12000 && q > 15) {
            q -= 15;
            out.reset();
            small.compress(Bitmap.CompressFormat.JPEG, q, out);
        }
        if (small != src) small.recycle();
        return out.toByteArray();
    }

    private void sendFrame(byte[] jpeg) {
        RadioService svc = RadioService.instance();
        RadioEngine e = svc == null ? null : svc.engine();
        if (e == null || !sharing) return;
        e.sendVideoFrame(jpeg);
        sent++;
        sentBytes += jpeg.length;
        final String s = "Sharing: " + sent + " pictures, last " + (jpeg.length / 1024) + " KB, about "
                + (sentBytes / Math.max(1, sent) * 1000 / frameGapMs() / 1024) + " KB a second";
        main.post(new Runnable() {
            @Override
            public void run() {
                status.setText(s);
            }
        });
    }

    // ---- phone camera ----

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        if (source.getSelectedItemPosition() < 2 && (Build.VERSION.SDK_INT < 23 || hasCamera())) startCamera(h);
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        stopCamera();
    }

    private void startCamera(SurfaceHolder h) {
        if (camera != null) return;
        boolean front = source.getSelectedItemPosition() == 1;
        try {
            camId = -1;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                boolean isFront = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
                if (isFront == front) {
                    camId = i;
                    break;
                }
            }
            if (camId < 0 && Camera.getNumberOfCameras() > 0) camId = 0;
            if (camId < 0) throw new RuntimeException("no camera");
            camera = Camera.open(camId);
            Camera.Parameters p = camera.getParameters();
            // Smallest preview at least 320 wide keeps the phone cool and the encoder quick.
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
            int orient;
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                orient = (360 - (info.orientation + display) % 360) % 360;
                camRotation = (info.orientation + display) % 360;
            } else {
                orient = (info.orientation - display + 360) % 360;
                camRotation = orient;
            }
            camera.setDisplayOrientation(orient);
            camera.setPreviewDisplay(h);
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Exception e) {
            status.setText("Camera unavailable: " + e.getMessage());
            stopCamera();
        }
    }

    private int displayRotation() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
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
            camera.setPreviewCallback(null);
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }

    @Override
    public void onPreviewFrame(final byte[] data, Camera cam) {
        if (!sharing || encoding) return;
        long now = System.currentTimeMillis();
        if (now - lastFrameMs < frameGapMs()) return;
        lastFrameMs = now;
        encoding = true;
        final int w = previewW, h = previewH, rot = camRotation, tw = targetWidth();
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
                        sendFrame(squeeze(bm, tw, rot));
                        bm.recycle();
                    }
                } catch (Throwable ignored) {
                    // A dropped picture is fine; the next one follows shortly.
                } finally {
                    encoding = false;
                }
            }
        }, "video-encode").start();
    }

    // ---- network camera ----

    private void startNetwork() {
        final String address = url.getText().toString().trim();
        if (!address.startsWith("http://") && !address.startsWith("https://")) {
            status.setText("Enter the camera's still picture address, starting http://");
            stopSharing();
            return;
        }
        final int tw = targetWidth();
        final long gap = frameGapMs();
        netThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (sharing && !Thread.currentThread().isInterrupted()) {
                    long start = System.currentTimeMillis();
                    try {
                        final Bitmap bm = fetch(address, tw);
                        if (bm != null) {
                            sendFrame(squeeze(bm, tw, 0));
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    netPreview.setImageBitmap(bm);
                                }
                            });
                        }
                    } catch (final Exception e) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                status.setText("Camera not answering: " + e.getMessage());
                            }
                        });
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
}
