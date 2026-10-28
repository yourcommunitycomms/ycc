package org.communitycomms.app.audio;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * Where radio audio, the microphone and the Call ring go (Audio page, cog buttons).
 * <ul>
 * <li>Sound: Automatic (loudspeaker unless a headset is in), Loudspeaker always, or Earpiece /
 * headset (never the loudspeaker).</li>
 * <li>Microphone: Automatic, or one input device picked by type (Android 6+).</li>
 * <li>Ringer: Automatic (follows media audio), or Loudspeaker always (Android 6+), so a Call is
 * heard even with headphones in.</li>
 * </ul>
 */
public final class AudioRoute {
    public static final int OUT_AUTO = 0, OUT_SPEAKER = 1, OUT_EARPIECE = 2;
    public static final String[] OUT_NAMES = {"Automatic (loudspeaker unless a headset is plugged in)",
            "Loudspeaker always", "Earpiece or headset (never the loudspeaker)"};

    private AudioRoute() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static int out(Context c) {
        return sp(c).getInt("audioOut", OUT_AUTO);
    }

    public static void setOut(Context c, int v) {
        sp(c).edit().putInt("audioOut", v).commit();
    }

    /** 0 = automatic, else an AudioDeviceInfo type. Read by Capture on every start. */
    public static volatile int micType;
    public static volatile boolean ringSpeaker;

    public static void load(Context c) {
        micType = sp(c).getInt("micType", 0);
        ringSpeaker = sp(c).getBoolean("ringSpeaker", false);
    }

    public static void setMicType(Context c, int t) {
        micType = t;
        sp(c).edit().putInt("micType", t).commit();
    }

    public static void setRingSpeaker(Context c, boolean on) {
        ringSpeaker = on;
        sp(c).edit().putBoolean("ringSpeaker", on).commit();
    }

    /** Loudspeaker on or off for radio audio, given the setting and what is plugged in. */
    @SuppressWarnings("deprecation")
    public static boolean wantSpeaker(Context c, AudioManager am, boolean wiredIn) {
        int o = out(c);
        if (o == OUT_SPEAKER) return true;
        if (o == OUT_EARPIECE) return false;
        return !wiredIn && !am.isBluetoothScoOn();
    }

    @SuppressWarnings("deprecation")
    public static String describeOut(Context c, AudioManager am) {
        if (am.isBluetoothScoOn()) return "Bluetooth headset";
        boolean wired = am.isWiredHeadsetOn();
        if (wantSpeaker(c, am, wired)) return "Loudspeaker";
        return wired ? "Wired headset" : "Earpiece";
    }

    @SuppressWarnings("deprecation")
    public static String describeMic(Context c, AudioManager am) {
        if (micType != 0) return typeName(micType) + " (chosen)";
        if (am.isBluetoothScoOn()) return "Bluetooth headset microphone";
        if (Build.VERSION.SDK_INT >= 23) {
            for (AudioDeviceInfo d : inputs(am)) if (d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET) return "Headset microphone";
        } else if (am.isWiredHeadsetOn()) {
            return "Headset microphone (if the headset has one)";
        }
        return "Phone microphone";
    }

    @SuppressWarnings("deprecation")
    public static String describeRing(AudioManager am) {
        if (ringSpeaker && Build.VERSION.SDK_INT >= 23) return "Loudspeaker (always)";
        if (am.isWiredHeadsetOn()) return "Wired headset";
        if (am.isBluetoothA2dpOn()) return "Bluetooth audio";
        return "Loudspeaker";
    }

    @TargetApi(23)
    public static List<AudioDeviceInfo> inputs(AudioManager am) {
        List<AudioDeviceInfo> out = new ArrayList<AudioDeviceInfo>();
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            boolean dup = false;
            for (AudioDeviceInfo o : out) if (o.getType() == d.getType()) dup = true;
            // Only real microphones (not tuners, telephony or the system's internal "submix").
            boolean mic = false;
            for (int t : MIC_TYPES) if (t == d.getType()) mic = true;
            if (!dup && mic) out.add(d);
        }
        return out;
    }

    /** Built-in mic, wired headset, line in, Bluetooth SCO, USB device, USB headset, Bluetooth LE headset. */
    private static final int[] MIC_TYPES = {15, 3, 5, 7, 11, 22, 26};

    public static String typeName(int t) {
        switch (t) {
            case 5: return "Line in";
            case 26: return "Bluetooth LE headset microphone";
            case 15: return "Phone microphone";          // TYPE_BUILTIN_MIC
            case 3: return "Wired headset microphone";   // TYPE_WIRED_HEADSET
            case 7: return "Bluetooth headset microphone"; // TYPE_BLUETOOTH_SCO
            case 11: return "USB microphone";            // TYPE_USB_DEVICE
            case 22: return "USB headset microphone";    // TYPE_USB_HEADSET
            default: return "Input type " + t;
        }
    }

    /** Capture: point the recording at the chosen microphone, when there is one. */
    @TargetApi(23)
    public static void applyMic(android.media.AudioRecord r, Context c) {
        if (micType == 0 || Build.VERSION.SDK_INT < 23) return;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (d.getType() == micType) {
                r.setPreferredDevice(d);
                return;
            }
        }
    }

    /** CallRinger: the ring on the loudspeaker even with a headset in, when chosen. */
    @TargetApi(23)
    public static void applyRing(android.media.AudioTrack t, Context c) {
        if (!ringSpeaker || Build.VERSION.SDK_INT < 23) return;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                t.setPreferredDevice(d);
                return;
            }
        }
    }
}
