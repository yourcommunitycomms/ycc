package org.communitycomms.app;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Mesh testing: "Phone speaker output" for Play audio to channel (Android 10+). Holds the screen/audio capture
 * permission the user just granted (Android needs a foreground service of type mediaProjection for it), records
 * what other apps play (media, games, unknown; never YCC's own sound, so nothing loops) and hands it to TestAudio,
 * which sends it as live voice. Stopping test audio stops this service and the capture.
 */
@TargetApi(29)
public final class CaptureService extends Service {
    private static final String TAG = "CaptureService";
    static final String EXTRA_CODE = "code", EXTRA_DATA = "data";
    public static final int RATE = 48000;
    private static final int NOTIF = 7341;

    private static volatile CaptureService current;
    private MediaProjection projection;
    private volatile AudioRecord record;

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 29;
    }

    /** The capture recorder while running, or null. */
    static AudioRecord record() {
        CaptureService s = current;
        return s == null ? null : s.record;
    }

    static void stopNow(Context c) {
        c.stopService(new Intent(c, CaptureService.class));
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (i == null || !supported()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground();
        String err = begin(i.getIntExtra(EXTRA_CODE, 0), (Intent) i.getParcelableExtra(EXTRA_DATA));
        if (err != null) {
            TestAudio.captureFailed(err);
            stopSelf();
        } else {
            current = this;
            TestAudio.captureReady();
        }
        return START_NOT_STICKY;
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("meshcapture", "Sharing phone audio", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 73, new Intent(this, MeshTestingActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "meshcapture")
                .setContentTitle("Sending this phone's audio to the channel")
                .setContentText("Mesh testing: Play audio to channel. Tap to stop.")
                .setSmallIcon(R.drawable.ic_stat_radio)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        startForeground(NOTIF, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    private String begin(int code, Intent data) {
        if (data == null) return "No permission to capture the phone's audio";
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "Microphone permission is needed to capture audio";
        }
        try {
            MediaProjectionManager m = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = m.getMediaProjection(code, data);
            if (projection == null) return "Android refused the capture";
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.i(TAG, "capture permission ended");
                    TestAudio.stop("Android ended the capture");
                }
            }, null);
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .excludeUid(android.os.Process.myUid()) // never YCC's own sound (monitor, alerts): no loops
                    .build();
            AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord r = new AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(Math.max(min, RATE / 5 * 2))
                    .setAudioPlaybackCaptureConfig(cfg).build();
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return "Could not start capturing the phone's audio";
            }
            r.startRecording();
            record = r;
            Log.i(TAG, "capturing other apps' audio at " + RATE + " Hz");
            return null;
        } catch (Exception e) {
            Log.w(TAG, "capture", e);
            return "Could not capture the phone's audio: " + e.getMessage();
        }
    }

    @Override
    public void onDestroy() {
        if (current == this) current = null;
        AudioRecord r = record;
        record = null;
        if (r != null) {
            try {
                r.stop();
            } catch (RuntimeException ignored) {
            }
            r.release();
        }
        if (projection != null) projection.stop();
        projection = null;
        Log.i(TAG, "stopped");
        super.onDestroy();
    }
}
