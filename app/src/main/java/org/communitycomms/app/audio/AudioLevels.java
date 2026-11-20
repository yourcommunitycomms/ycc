package org.communitycomms.app.audio;

/**
 * App-level audio gains set on the Audio screen: rx boosts what is heard (on top of the phone's own
 * volume), mic scales what is sent. 1.0 = unchanged. Loaded from Prefs at start-up.
 */
public final class AudioLevels {
    public static volatile float rxGain = 1f;
    public static volatile float micGain = 1f;
    /** Stealth: nothing heard is played (it is saved to Chat instead). */
    public static volatile boolean muted;
    /** Audio page: left and right swapped in stereo listening (headphones worn the other way, or a wiring quirk). */
    public static volatile boolean swapLeftRight;
    /**
     * The stream voice is played on: the in-call stream on phones (echo cancelling, earpiece or speaker), the media
     * stream on a TV, which has no call path (the in-call stream there may sit at its lowest or go nowhere).
     */
    public static volatile int voiceStream = android.media.AudioManager.STREAM_VOICE_CALL;
    /** A TV: no in-call audio mode either. */
    public static volatile boolean tv;

    private AudioLevels() {
    }

    /** Scales a frame in place with clipping. */
    public static void apply(short[] pcm, int n, float g) {
        if (g == 1f) return;
        for (int i = 0; i < n; i++) {
            int v = (int) (pcm[i] * g);
            pcm[i] = (short) (v > 32767 ? 32767 : (v < -32768 ? -32768 : v));
        }
    }

    /** Peak level of a frame, 0..1, for meters. */
    public static float peak(short[] pcm, int n) {
        int m = 0;
        for (int i = 0; i < n; i++) m = Math.max(m, Math.abs((int) pcm[i]));
        return m / 32768f;
    }
}
