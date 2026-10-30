package org.communitycomms.app.audio;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;

import org.communitycomms.app.RadioService;
import org.communitycomms.app.net.FileTransfer;

import java.io.File;
import java.util.ArrayDeque;

/**
 * Burst voice: hold PTT to record, let go to send the recording as one small file, which plays by
 * itself on every phone when it has fully arrived. Unlike live voice it survives a weak or patchy
 * link (lost pieces are sent again), and the quality can be chosen: from very small AMR to clear AAC.
 */
public final class BurstVoice {
    private BurstVoice() {
    }

    public static final String PREFIX = "burst-";
    public static final String[] QUALITY_NAMES = {
            "Smallest (AMR 4.75 kbit/s)", "Phone (AMR 12.2 kbit/s)", "Wideband (AMR-WB 23.85 kbit/s)", "Clear (AAC 48 kbit/s)"};

    private static MediaRecorder rec;
    private static File file;
    private static String mime;
    private static long startedMs;

    public static boolean recording() {
        return rec != null;
    }

    /** Starts recording at the chosen quality (0 smallest … 3 clearest). Returns false if the mic is busy. */
    public static synchronized boolean start(Context c, int quality) {
        stopQuietly();
        try {
            File dir = new File(c.getCacheDir(), "burst");
            dir.mkdirs();
            String ext;
            MediaRecorder r = new MediaRecorder();
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            if (quality >= 3) {
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                r.setAudioSamplingRate(22050);
                r.setAudioEncodingBitRate(48000);
                ext = ".m4a";
                mime = "audio/mp4";
            } else if (quality == 2) {
                r.setOutputFormat(MediaRecorder.OutputFormat.AMR_WB);
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_WB);
                r.setAudioSamplingRate(16000);
                r.setAudioEncodingBitRate(23850);
                ext = ".awb";
                mime = "audio/amr-wb";
            } else {
                r.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB);
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                r.setAudioSamplingRate(8000);
                r.setAudioEncodingBitRate(quality == 0 ? 4750 : 12200);
                ext = ".amr";
                mime = "audio/amr";
            }
            file = new File(dir, PREFIX + System.currentTimeMillis() + ext);
            r.setOutputFile(file.getPath());
            r.prepare();
            r.start();
            rec = r;
            startedMs = System.currentTimeMillis();
            return true;
        } catch (Exception e) {
            stopQuietly();
            return false;
        }
    }

    private static long recordedMs;

    /** Stops recording. False if there is nothing worth keeping. */
    private static boolean finish() {
        MediaRecorder r = rec;
        rec = null;
        recordedMs = System.currentTimeMillis() - startedMs;
        try {
            r.stop();
        } catch (RuntimeException e) {
            // Released too quickly: nothing was recorded.
            r.release();
            if (file != null) file.delete();
            return false;
        }
        r.release();
        return recordedMs >= 400 && file != null && file.length() > 0;
    }

    // ---- Offline: recordings wait in a folder until someone is heard ----

    private static volatile int waiting = -1;

    private static File outbox(Context c) {
        File d = new File(c.getFilesDir(), "offline-voice");
        d.mkdirs();
        return d;
    }

    /** Recordings waiting to be sent. */
    public static int waiting(Context c) {
        if (waiting < 0) {
            String[] names = outbox(c).list();
            waiting = names == null ? 0 : names.length;
        }
        return waiting;
    }

    /** Stops and keeps the recording, sending it straight away if someone is around. */
    public static synchronized String stopAndKeep(Context c) {
        if (rec == null) return null;
        if (!finish()) return "Too short: hold PTT while you speak";
        File kept = new File(outbox(c), file.getName());
        if (!file.renameTo(kept)) return "Could not save the message";
        waiting = -1;
        return "Saved (" + Math.max(1, recordedMs / 1000) + " s). It sends when someone is heard.";
    }

    /** Someone is around: sends what was recorded, oldest first. */
    public static synchronized void sendWaiting(Context c) {
        if (waiting(c) == 0) return;
        FileTransfer ft = RadioService.files();
        File[] all = outbox(c).listFiles();
        if (ft == null || all == null) return;
        java.util.Arrays.sort(all);
        File dir = new File(c.getCacheDir(), "burst");
        dir.mkdirs();
        for (File f : all) {
            String n = f.getName();
            String type = n.endsWith(".m4a") ? "audio/mp4" : n.endsWith(".awb") ? "audio/amr-wb" : "audio/amr";
            File out = new File(dir, n);
            if (!f.renameTo(out)) continue;
            try {
                RadioService.logFileSent(c, ft.send(out, n, type), type, out);
            } catch (Exception e) {
                out.renameTo(f); // try again next time
                break;
            }
        }
        waiting = -1;
    }

    /** Stops and sends. Returns a short result for the screen. */
    public static synchronized String stopAndSend(Context c) {
        if (rec == null) return null;
        if (!finish()) return "Too short: hold PTT while you speak";
        long ms = recordedMs;
        FileTransfer ft = RadioService.files();
        if (ft == null) return "Not on a channel: not sent";
        try {
            FileTransfer.Outgoing o = ft.send(file, file.getName(), mime);
            RadioService.logFileSent(c, o, mime, file);
            return "Burst sent: " + (ms / 1000) + " s, " + Math.max(1, file.length() / 1024) + " KB";
        } catch (Exception e) {
            return "Could not send: " + e.getMessage();
        }
    }

    private static void stopQuietly() {
        if (rec != null) {
            try {
                rec.stop();
            } catch (RuntimeException ignored) {
            }
            rec.release();
            rec = null;
        }
    }

    // ---- playing received bursts, one after another ----

    private static final ArrayDeque<File> queue = new ArrayDeque<File>();
    private static MediaPlayer player;

    public static boolean isBurst(String name, String mime) {
        return name != null && name.startsWith(PREFIX) && mime != null && mime.startsWith("audio/");
    }

    /** Audio page: longest wait (seconds) for the channel to clear before a burst plays anyway; 0 = never wait. */
    public static volatile int waitClearSec = 60;
    /** Audio page: a short call-waiting beep when a burst arrives while the channel is busy. */
    public static volatile boolean waitTone = true;
    private static final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    /** When the burst at the head of the queue started waiting for a clear channel (0 = not waiting). */
    private static long waitingSince;

    /**
     * A received burst: queued, and played when nothing else is: no live voice heard for a second, this
     * phone not on air, no other burst playing. After waitClearSec it plays anyway. In order received.
     */
    public static void play(final File f) {
        if (org.communitycomms.app.RadioEngine.cwMode) return; // CW mode: nothing plays but CW (it stays in Chat)
        if (!BurstCleanup.enabled) {
            queueToPlay(f);
            return;
        }
        // Audio cleanup: decode and clean on one worker thread (keeps the order), then queue the clean copy.
        cleaner.execute(new Runnable() {
            @Override
            public void run() {
                File c = BurstCleanup.clean(f);
                final File use = c != null ? c : f;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        queueToPlay(use);
                    }
                });
            }
        });
    }

    private static final java.util.concurrent.ExecutorService cleaner = java.util.concurrent.Executors.newSingleThreadExecutor();

    private static synchronized void queueToPlay(File f) {
        queue.add(f);
        if (player == null) {
            if (busy()) {
                if (waitTone) callWaitingTone();
                if (waitingSince == 0) waitingSince = System.currentTimeMillis();
                main.postDelayed(retry, 500);
            } else {
                next();
            }
        } else if (waitTone) {
            callWaitingTone(); // another burst is playing: this one is next
        }
    }

    private static boolean busy() {
        RadioService rs = RadioService.instance();
        org.communitycomms.app.RadioEngine e = rs == null ? null : rs.engine();
        return e != null && (e.channelBusy(1000) || recording());
    }

    private static final Runnable retry = new Runnable() {
        @Override
        public void run() {
            synchronized (BurstVoice.class) {
                if (player != null || queue.isEmpty()) return;
                boolean timedOut = System.currentTimeMillis() - waitingSince >= waitClearSec * 1000L;
                if (busy() && !timedOut) {
                    main.postDelayed(this, 500);
                    return;
                }
                waitingSince = 0;
                next();
            }
        }
    };

    /** Two short high beeps, like an old phone's call waiting (on the call stream, quietly). */
    private static void callWaitingTone() {
        try {
            final android.media.ToneGenerator tg = new android.media.ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60);
            tg.startTone(android.media.ToneGenerator.TONE_SUP_CALL_WAITING, 600);
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    tg.release();
                }
            }, 800);
        } catch (RuntimeException ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private static synchronized void next() {
        File f = queue.poll();
        if (f == null) {
            player = null;
            return;
        }
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_VOICE_CALL); // same path as live voice (headset, earpiece…)
            mp.setDataSource(f.getPath());
            final File played = f;
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    m.release();
                    if (played.getName().endsWith(".clean.wav")) played.delete(); // the original stays for Chat
                    synchronized (BurstVoice.class) {
                        player = null;
                    }
                    // The next one waits again if someone started talking meanwhile.
                    waitingSince = System.currentTimeMillis();
                    main.postDelayed(retry, 300);
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    m.release();
                    next();
                    return true;
                }
            });
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception e) {
            player = null;
            next();
        }
    }
}
