package org.communitycomms.app;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.communitycomms.app.dryad.DryadSheet;
import org.communitycomms.app.dryad.SheetStore;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Camera QR scanner (old android.hardware.Camera API, so it runs on Android 4.1). Collects
 * DRYAD sheets as they are scanned, so a batch shown on another phone can be read one after
 * another, then imports them. A channel QR offers to set channel A or B.
 */
@SuppressWarnings("deprecation")
public final class QrScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private static final int REQ_CAMERA = 2;

    private Camera camera;
    private SurfaceView surface;
    private TextView status;
    private final Map<String, DryadSheet> scanned = new LinkedHashMap<String, DryadSheet>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean decoding;
    private volatile boolean paused;
    private Thread worker;
    private int previewW, previewH;
    private final QRCodeReader reader = new QRCodeReader();
    private final Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Scan QR");
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        FrameLayout frame = new FrameLayout(this);
        surface = new SurfaceView(this);
        frame.addView(surface);
        root.addView(frame, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(16);
        status.setText("Point the camera at a QR code from this app");
        root.addView(status);
        Button done = new Button(this);
        done.setText("Import scanned sheets");
        root.addView(done);
        setContentView(root);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importScanned();
            }
        });
        surface.getHolder().addCallback(this);
        surface.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS); // needed before API 11
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 23 && !hasCamera()) {
            askCamera();
        }
    }

    @Override
    protected void onPause() {
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
        } else {
            Toast.makeText(this, "Camera permission is needed to scan", Toast.LENGTH_LONG).show();
        }
    }

    // ---- camera ----

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        if (Build.VERSION.SDK_INT < 23 || hasCamera()) startCamera(h);
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
        try {
            camera = Camera.open(); // back camera
            if (camera == null && Camera.getNumberOfCameras() > 0) camera = Camera.open(0);
            if (camera == null) throw new RuntimeException("no camera");
            Camera.Parameters p = camera.getParameters();
            Camera.Size best = null;
            for (Camera.Size s : p.getSupportedPreviewSizes()) {
                if (s.width * s.height > 1920 * 1080) continue;
                if (best == null || s.width * s.height > best.width * best.height) best = s;
            }
            if (best != null) p.setPreviewSize(best.width, best.height);
            List<String> focus = p.getSupportedFocusModes();
            if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            camera.setParameters(p);
            Camera.Size ps = camera.getParameters().getPreviewSize();
            previewW = ps.width;
            previewH = ps.height;
            camera.setDisplayOrientation(displayRotation());
            camera.setPreviewDisplay(h);
            camera.setPreviewCallback(this);
            camera.startPreview();
            if (Camera.Parameters.FOCUS_MODE_AUTO.equals(p.getFocusMode())) refocus();
        } catch (Exception e) {
            status.setText("Camera unavailable: " + e.getMessage());
            stopCamera();
        }
    }

    private void refocus() {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (camera == null) return;
                try {
                    camera.autoFocus(null);
                } catch (RuntimeException ignored) {
                }
                refocus();
            }
        }, 2000);
    }

    private int displayRotation() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(0, info);
        int degrees;
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
            default:
                degrees = 0;
        }
        return (info.orientation - degrees + 360) % 360;
    }

    private void stopCamera() {
        main.removeCallbacksAndMessages(null);
        if (camera != null) {
            camera.setPreviewCallback(null);
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }

    @Override
    public void onPreviewFrame(final byte[] data, Camera cam) {
        if (decoding || paused) return;
        decoding = true;
        final int w = previewW, h = previewH;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                String text = null;
                try {
                    PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false);
                    Result r = reader.decode(new BinaryBitmap(new HybridBinarizer(src)), hints);
                    text = r.getText();
                } catch (Exception ignored) {
                    // No code in this frame.
                } finally {
                    reader.reset();
                }
                final String found = text;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (found != null) onScanned(found);
                        decoding = false;
                    }
                });
            }
        }, "qr-decode");
        worker.start();
    }

    // ---- results ----

    /** Started for a result (DRYAD Enigma): hand back the first Enigma QR seen. */
    public static final String EXTRA_RESULT = "result";
    /** Started by the setup wizard: only phone setup codes are taken. */
    public static final String EXTRA_SETUP_ONLY = "setupOnly";

    private void onScanned(String text) {
        if (paused) return;
        if (SetupQr.is(text)) { // from Settings, or the setup wizard (started for a result)
            paused = true;
            SetupQr.offer(this, text, new Runnable() {
                @Override
                public void run() {
                    paused = false;
                }
            }, new Runnable() {
                @Override
                public void run() {
                    setResult(RESULT_OK);
                    finish();
                }
            });
            return;
        }
        if (getIntent().getBooleanExtra(EXTRA_SETUP_ONLY, false)) {
            status.setText("That is not a phone setup QR code");
            return;
        }
        if (text.startsWith(EnigmaActivity.QR_PREFIX)) {
            if (getCallingActivity() != null) {
                setResult(RESULT_OK, new android.content.Intent().putExtra(EXTRA_RESULT, text));
                finish();
            } else {
                // Scanned from Settings: open Enigma with it.
                android.content.Intent i = new android.content.Intent(this, EnigmaActivity.class);
                i.putExtra(EnigmaActivity.EXTRA_QR, text);
                startActivity(i);
                finish();
            }
            return;
        }
        if (getCallingActivity() != null) {
            status.setText("That is not a DRYAD Enigma QR code");
            return;
        }
        if (DryadSheet.isQr(text)) {
            try {
                DryadSheet s = DryadSheet.fromQr(text);
                if (scanned.put(s.fingerprint(), s) == null) {
                    status.setText("Scanned " + scanned.size() + " sheet(s), latest: " + s.name
                            + "\nKeep scanning, or tap Import.");
                }
            } catch (IllegalArgumentException e) {
                status.setText(e.getMessage());
            }
        } else if (text.startsWith(MainActivity.CHANNEL_QR_PREFIX)) {
            offerChannel(text);
        } else if (org.communitycomms.app.crypto.ListenKey.isListenKey(text)) {
            final String lk = text.trim();
            paused = true;
            new AlertDialog.Builder(this)
                    .setTitle("Public listen key (read only)")
                    .setMessage("Use this as your Public key? You will be able to hear and read that Public channel, but not send on it.")
                    .setPositiveButton("Use it", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            new Prefs(QrScanActivity.this).setPublicKey(lk);
                            Toast.makeText(QrScanActivity.this, "Public listen key set", Toast.LENGTH_LONG).show();
                            finish();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            paused = false;
                        }
                    })
                    .show();
        } else {
            status.setText("That QR code is not from this app");
        }
    }

    private void offerChannel(String text) {
        String[] f = text.substring(MainActivity.CHANNEL_QR_PREFIX.length()).split("\\|", 2);
        if (f.length != 2 || f[1].length() == 0) return;
        final String name = f[1];
        paused = true;
        new AlertDialog.Builder(this)
                .setTitle("Channel QR scanned")
                .setMessage("Use this channel name as channel A or B?")
                .setPositiveButton("Set A", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        setChannel(Prefs.SLOT_A, name);
                    }
                })
                .setNeutralButton("Set B", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        setChannel(Prefs.SLOT_B, name);
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        paused = false;
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void setChannel(int slot, String name) {
        new Prefs(this).setChannel(slot, name);
        Toast.makeText(this, "Channel " + (slot == Prefs.SLOT_A ? "A" : "B") + " set. Switch to it on the radio screen.",
                Toast.LENGTH_LONG).show();
        finish();
    }

    private void importScanned() {
        if (scanned.isEmpty()) {
            finish();
            return;
        }
        try {
            int added = new SheetStore(this).importAll(new java.util.ArrayList<DryadSheet>(scanned.values()));
            int dup = scanned.size() - added;
            Toast.makeText(this, "Imported " + added + " sheet(s)" + (dup > 0 ? ", " + dup + " already held" : ""),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
