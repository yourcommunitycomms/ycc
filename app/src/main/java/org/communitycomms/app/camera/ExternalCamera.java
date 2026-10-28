package org.communitycomms.app.camera;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Size;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * UNTESTED (*): one still picture from a USB (UVC) camera through Android's own camera system.
 * Only phones whose camera service lists USB cameras ("external", mostly Android 9+) can do this;
 * night vision and thermal cameras with a USB video output show up the same way when they do.
 * No preview: a few frames are skipped so exposure settles, then one is saved as JPEG.
 */
@TargetApi(21)
public final class ExternalCamera {
    public interface Done {
        /** On the main thread: the saved picture, or null and why. */
        void onDone(File f, String problem);
    }

    private static final String TAG = "ExternalCamera";
    /** CameraCharacteristics.LENS_FACING_EXTERNAL (API 23); USB cameras report this. */
    private static final int LENS_FACING_EXTERNAL = 2;
    private static final int SKIP_FRAMES = 8;
    private static final long TIMEOUT_MS = 12000;

    private ExternalCamera() {
    }

    /** Ids of USB / external cameras the phone lists (empty on most phones). */
    public static List<String> externalIds(Context c) {
        List<String> out = new ArrayList<String>();
        try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return out;
            for (String id : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == LENS_FACING_EXTERNAL) out.add(id);
            }
        } catch (Exception e) {
            Log.w(TAG, "camera list", e);
        }
        return out;
    }

    public static void capture(Context ctx, final File out, final Done done) {
        final Handler main = new Handler(Looper.getMainLooper());
        final Context c = ctx.getApplicationContext();
        List<String> ids = externalIds(c);
        if (ids.isEmpty()) {
            done.onDone(null, "this phone lists no USB camera. Android only offers USB cameras on some phones; "
                    + "try \"Choose a camera app each time\" with a USB camera app instead.");
            return;
        }
        final String id = ids.get(0);
        final CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        final HandlerThread ht = new HandlerThread("usb-camera");
        ht.start();
        final Handler bg = new Handler(ht.getLooper());
        final boolean[] finished = {false};
        final CameraDevice[] dev = {null};
        final ImageReader[] reader = {null};

        final Runnable[] finish = new Runnable[1];
        final String[] problem = {null};
        finish[0] = new Runnable() {
            @Override
            public void run() {
                synchronized (finished) {
                    if (finished[0]) return;
                    finished[0] = true;
                }
                try {
                    if (dev[0] != null) dev[0].close();
                } catch (RuntimeException ignored) {
                }
                if (reader[0] != null) reader[0].close();
                ht.quitSafely();
                final String p = problem[0];
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        done.onDone(p == null ? out : null, p);
                    }
                });
            }
        };
        bg.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (problem[0] == null && !out.exists()) problem[0] = "no picture came from the camera in time";
                finish[0].run();
            }
        }, TIMEOUT_MS);

        try {
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            int format = ImageFormat.JPEG;
            Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) {
                format = ImageFormat.YUV_420_888;
                sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
            }
            if (sizes == null || sizes.length == 0) {
                problem[0] = "the camera offers no picture format this app can read";
                finish[0].run();
                return;
            }
            Size best = pick(sizes);
            final int fmt = format;
            reader[0] = ImageReader.newInstance(best.getWidth(), best.getHeight(), format, 2);
            final int[] seen = {0};
            reader[0].setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try {
                        img = r.acquireLatestImage();
                        if (img == null || ++seen[0] <= SKIP_FRAMES) return;
                        save(img, fmt, out);
                    } catch (Exception e) {
                        problem[0] = "could not save the picture: " + e.getMessage();
                    } finally {
                        if (img != null) img.close();
                    }
                    if (seen[0] > SKIP_FRAMES) finish[0].run();
                }
            }, bg);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(final CameraDevice d) {
                    dev[0] = d;
                    try {
                        d.createCaptureSession(Collections.singletonList(reader[0].getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession s) {
                                        try {
                                            CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                            b.addTarget(reader[0].getSurface());
                                            s.setRepeatingRequest(b.build(), null, bg);
                                        } catch (Exception e) {
                                            problem[0] = "the camera would not start: " + e.getMessage();
                                            finish[0].run();
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession s) {
                                        problem[0] = "the camera refused the picture size";
                                        finish[0].run();
                                    }
                                }, bg);
                    } catch (CameraAccessException e) {
                        problem[0] = "the camera would not start: " + e.getMessage();
                        finish[0].run();
                    }
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    dev[0] = d;
                    if (problem[0] == null) problem[0] = "the camera was unplugged";
                    finish[0].run();
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    dev[0] = d;
                    problem[0] = "camera error " + error;
                    finish[0].run();
                }
            }, bg);
        } catch (SecurityException e) {
            problem[0] = "camera permission is needed (Settings > Permissions)";
            finish[0].run();
        } catch (Exception e) {
            problem[0] = "could not open the camera: " + e.getMessage();
            finish[0].run();
        }
    }

    /** Largest size up to about 2 megapixels (plenty to send over the radio, and quick). */
    private static Size pick(Size[] sizes) {
        Size best = null;
        for (Size s : sizes) {
            long a = (long) s.getWidth() * s.getHeight();
            if (a > 2100000L) continue;
            if (best == null || a > (long) best.getWidth() * best.getHeight()) best = s;
        }
        if (best != null) return best;
        best = sizes[0];
        for (Size s : sizes) if ((long) s.getWidth() * s.getHeight() < (long) best.getWidth() * best.getHeight()) best = s;
        return best;
    }

    private static void save(Image img, int fmt, File out) throws Exception {
        FileOutputStream os = new FileOutputStream(out);
        try {
            if (fmt == ImageFormat.JPEG) {
                ByteBuffer b = img.getPlanes()[0].getBuffer();
                byte[] data = new byte[b.remaining()];
                b.get(data);
                os.write(data);
            } else {
                int w = img.getWidth(), h = img.getHeight();
                new YuvImage(toNv21(img), ImageFormat.NV21, w, h, null).compressToJpeg(new Rect(0, 0, w, h), 85, os);
            }
        } finally {
            os.close();
        }
    }

    /** YUV_420_888 (any strides) to NV21: Y plane, then interleaved V/U at quarter size. */
    private static byte[] toNv21(Image img) {
        int w = img.getWidth(), h = img.getHeight();
        byte[] out = new byte[w * h * 3 / 2];
        Image.Plane[] p = img.getPlanes();
        ByteBuffer y = p[0].getBuffer();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int pos = 0;
        for (int r = 0; r < h; r++) {
            for (int col = 0; col < w; col++) out[pos++] = y.get(r * yRow + col * yPix);
        }
        ByteBuffer u = p[1].getBuffer(), v = p[2].getBuffer();
        int uvRow = p[1].getRowStride(), uvPix = p[1].getPixelStride();
        for (int r = 0; r < h / 2; r++) {
            for (int col = 0; col < w / 2; col++) {
                int i = r * uvRow + col * uvPix;
                out[pos++] = v.get(i);
                out[pos++] = u.get(i);
            }
        }
        return out;
    }
}
