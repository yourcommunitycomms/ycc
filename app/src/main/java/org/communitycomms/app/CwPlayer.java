package org.communitycomms.app;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayDeque;

/**
 * CW waiting to go out by itself (the CW relay): played one after another to the radio on the media
 * sound, only in CW mode, and not while a CW message is being received (the radio is half duplex).
 */
final class CwPlayer {
    private CwPlayer() {
    }

    private static final ArrayDeque<File> queue = new ArrayDeque<File>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static MediaPlayer player;

    static synchronized void queue(Context c, File f) {
        queue.add(f);
        main.post(next);
    }

    private static final Runnable next = new Runnable() {
        @Override
        public void run() {
            synchronized (CwPlayer.class) {
                if (player != null || queue.isEmpty()) return;
                if (!RadioEngine.cwMode) {
                    queue.clear(); // CW mode ended: nothing goes out
                    return;
                }
                if (CwReceiver.hearing) {
                    main.postDelayed(this, 1000); // someone is sending: wait for the clear air
                    return;
                }
                start(queue.poll());
            }
        }
    };

    @SuppressWarnings("deprecation")
    private static void start(File f) {
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(f.getPath());
            mp.prepare();
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    m.release();
                    synchronized (CwPlayer.class) {
                        player = null;
                    }
                    main.postDelayed(next, 1000);
                }
            });
            mp.start();
            player = mp;
        } catch (Exception e) {
            player = null;
            main.post(next);
        }
    }
}
