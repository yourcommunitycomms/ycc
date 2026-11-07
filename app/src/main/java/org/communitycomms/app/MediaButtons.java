package org.communitycomms.app;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.view.KeyEvent;

/**
 * A media-style PTT button (wired headset button, play/pause on a media remote or Bluetooth headset) works
 * with the screen off and wakes a sleeping phone. Used only when the PTT button learned on the Audio page
 * is a media key, so music controls are left alone otherwise. Android 5+ uses a media session; older
 * phones register the receiver below.
 */
final class MediaButtons {
    private Object session; // android.media.session.MediaSession on 21+
    private boolean registered;

    static boolean isMediaKey(int k) {
        return k == KeyEvent.KEYCODE_HEADSETHOOK || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY
                || k == KeyEvent.KEYCODE_MEDIA_PAUSE || k == KeyEvent.KEYCODE_MEDIA_STOP || k == KeyEvent.KEYCODE_MEDIA_NEXT
                || k == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    }

    /** A key event from the button: true when it was the PTT key and has been used. */
    static boolean handle(KeyEvent ev) {
        RadioService s = RadioService.instance();
        if (ev == null || s == null || ev.getKeyCode() != new Prefs(s).pttKey()) return false;
        if (ev.getRepeatCount() == 0) s.hardwarePtt(ev.getAction() == KeyEvent.ACTION_DOWN);
        return true;
    }

    @SuppressWarnings("deprecation")
    void start(Context c) {
        stop(c);
        if (!isMediaKey(new Prefs(c).pttKey())) return;
        if (Build.VERSION.SDK_INT >= 21) {
            startSession(c);
        } else {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.registerMediaButtonEventReceiver(new ComponentName(c, Receiver.class));
                registered = true;
            }
        }
    }

    @SuppressWarnings("deprecation")
    void stop(Context c) {
        if (Build.VERSION.SDK_INT >= 21 && session != null) {
            ((android.media.session.MediaSession) session).release();
        }
        session = null;
        if (registered) {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.unregisterMediaButtonEventReceiver(new ComponentName(c, Receiver.class));
            registered = false;
        }
    }

    @TargetApi(21)
    private void startSession(Context c) {
        android.media.session.MediaSession s = new android.media.session.MediaSession(c, "YCC PTT");
        s.setFlags(android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        s.setCallback(new android.media.session.MediaSession.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent i) {
                KeyEvent ev = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                return handle(ev) || super.onMediaButtonEvent(i);
            }
        });
        s.setPlaybackState(new android.media.session.PlaybackState.Builder()
                .setActions(android.media.session.PlaybackState.ACTION_PLAY_PAUSE)
                .setState(android.media.session.PlaybackState.STATE_PLAYING, 0, 1f).build());
        s.setActive(true);
        session = s;
    }

    /** Android 4.1 to 4.4: media buttons arrive as a broadcast. */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!Intent.ACTION_MEDIA_BUTTON.equals(i.getAction())) return;
            if (handle((KeyEvent) i.getParcelableExtra(Intent.EXTRA_KEY_EVENT)) && isOrderedBroadcast()) abortBroadcast();
        }
    }
}
