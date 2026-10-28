package org.communitycomms.app;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.UUID;

/**
 * Bluetooth for field use, e.g. the FalconClaw PTT, which shows up as two Bluetooth devices:
 * <ul>
 * <li>a headset ("FALCONCLAW"): while on air and a headset is connected, radio audio and the
 * microphone are switched to it (Bluetooth SCO, the phone-call link), like a call;</li>
 * <li>a PTT button ("BLU-PTT"): a Bluetooth Low Energy button. The app connects to the button
 * chosen in Settings, listens to every notification it sends and turns them into PTT down/up,
 * so it works with the screen off. Value rules: text with "P"/"R" or "press"/"release" (e.g.
 * "+PTT=P"), otherwise any non-zero byte = pressed, all zero = released.</li>
 * </ul>
 * Buttons that are a Bluetooth keyboard instead (send a key) are set with "Set PTT key" in Settings.
 */
public final class BtPtt {
    private static final String TAG = "BtPtt";
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public interface Ptt {
        void onPtt(boolean down);
    }

    /** Last raw value from the button, for the Settings test line. */
    public static volatile String lastEvent = "";
    public static volatile String buttonState = "not set";

    private final Context ctx;
    private final AudioManager am;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Ptt ptt;
    private boolean active;
    private boolean scoWanted;

    public BtPtt(Context c, Ptt ptt) {
        ctx = c.getApplicationContext();
        am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        this.ptt = ptt;
    }

    static boolean permitted(Context c) {
        return Build.VERSION.SDK_INT < 31
                || c.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** On join. */
    public void start() {
        if (active) return;
        active = true;
        IntentFilter f = new IntentFilter();
        f.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        f.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        ctx.registerReceiver(receiver, f);
        headsetAudio();
        connectButton();
    }

    /** On leave. */
    public void stop() {
        if (!active) return;
        active = false;
        try {
            ctx.unregisterReceiver(receiver);
        } catch (RuntimeException ignored) {
        }
        main.removeCallbacksAndMessages(null);
        stopSco();
        disconnectButton();
    }

    // ---- headset audio ----

    private boolean headsetConnected() {
        if (!permitted(ctx)) return false;
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            return a != null && a.isEnabled() && a.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void headsetAudio() {
        if (!active || !new Prefs(ctx).btHeadset() || am == null) return;
        if (headsetConnected() && am.isBluetoothScoAvailableOffCall()) {
            if (!scoWanted) {
                Log.i(TAG, "Bluetooth headset connected: starting SCO");
                scoWanted = true;
                try {
                    am.startBluetoothSco();
                } catch (RuntimeException e) {
                    Log.w(TAG, "startBluetoothSco", e);
                }
            }
        } else if (scoWanted) {
            stopSco();
        }
    }

    private void stopSco() {
        if (!scoWanted || am == null) return;
        scoWanted = false;
        try {
            am.setBluetoothScoOn(false);
            am.stopBluetoothSco();
            // Back to the loudspeaker (as RadioService sets it) unless a wired headset is in.
            if (active) am.setSpeakerphoneOn(org.communitycomms.app.audio.AudioRoute.wantSpeaker(ctx, am, am.isWiredHeadsetOn()));
        } catch (RuntimeException ignored) {
        }
    }

    public boolean scoOn() {
        return scoWanted && am != null && am.isBluetoothScoOn();
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED.equals(i.getAction())) {
                int s = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                Log.i(TAG, "SCO state " + s);
                if (s == AudioManager.SCO_AUDIO_STATE_CONNECTED && scoWanted) {
                    am.setSpeakerphoneOn(false);
                    am.setBluetoothScoOn(true);
                }
            } else {
                // Headset came or went: re-check a moment later, once the profile has settled.
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        headsetAudio();
                    }
                }, 1500);
            }
        }
    };

    // ---- BLE PTT button ----

    private BluetoothGatt gatt;
    private boolean pressed;

    @SuppressLint("MissingPermission")
    private void connectButton() {
        String addr = new Prefs(ctx).btButton();
        if (addr.length() == 0) {
            buttonState = "not set";
            return;
        }
        if (Build.VERSION.SDK_INT < 18) {
            buttonState = "needs Android 4.3 or newer";
            return;
        }
        if (!permitted(ctx)) {
            buttonState = "Bluetooth permission not given";
            return;
        }
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null || !a.isEnabled()) {
            buttonState = "Bluetooth is off";
            return;
        }
        try {
            BluetoothDevice d = a.getRemoteDevice(addr);
            buttonState = "connecting to " + name(d);
            // autoConnect: Android reconnects by itself whenever the button is in range again.
            gatt = Build.VERSION.SDK_INT >= 23 ? d.connectGatt(ctx, true, callback(), BluetoothDevice.TRANSPORT_LE)
                    : d.connectGatt(ctx, true, callback());
        } catch (RuntimeException e) {
            buttonState = "could not connect: " + e.getMessage();
        }
    }

    @SuppressLint("MissingPermission")
    private void disconnectButton() {
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) {
            try {
                g.disconnect();
                g.close();
            } catch (RuntimeException ignored) {
            }
        }
        if (pressed) release();
    }

    @SuppressLint("MissingPermission")
    static String name(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null || n.length() == 0 ? d.getAddress() : n;
        } catch (RuntimeException e) {
            return d.getAddress();
        }
    }

    private void release() {
        pressed = false;
        main.post(new Runnable() {
            @Override
            public void run() {
                ptt.onPtt(false);
            }
        });
    }

    /** Made only on Android 4.3+ (the class does not exist before, and 4.1 would crash loading it). */
    private BluetoothGattCallback callback;

    @TargetApi(18)
    private BluetoothGattCallback callback() {
        if (callback == null) callback = makeCallback();
        return callback;
    }

    @TargetApi(18)
    private BluetoothGattCallback makeCallback() {
        return new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                buttonState = "connected: " + name(g.getDevice());
                g.discoverServices();
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                buttonState = "out of range (reconnects by itself)";
                if (pressed) release(); // never leave the radio stuck transmitting
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            // Listen to everything the button can notify; the next subscription goes out when the
            // previous descriptor write finishes (GATT allows one at a time).
            pending.clear();
            for (BluetoothGattService s : g.getServices()) {
                for (BluetoothGattCharacteristic ch : s.getCharacteristics()) {
                    int p = ch.getProperties();
                    if ((p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) == 0) continue;
                    // Skip the standard battery / device info services (0x180F, 0x180A).
                    String su = s.getUuid().toString();
                    if (su.startsWith("0000180f") || su.startsWith("0000180a")) continue;
                    pending.add(ch);
                }
            }
            Log.i(TAG, "button characteristics to watch: " + pending.size());
            subscribeNext(g);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            subscribeNext(g);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            byte[] v = ch.getValue();
            if (v == null) return;
            Boolean down = decode(v);
            StringBuilder hex = new StringBuilder();
            for (byte b : v) hex.append(String.format("%02x", b & 0xff));
            lastEvent = hex + (down == null ? " (ignored)" : down ? " = pressed" : " = released");
            Log.i(TAG, "button " + ch.getUuid() + ": " + lastEvent);
            if (down == null || down == pressed) return;
            pressed = down;
            final boolean dn = down;
            main.post(new Runnable() {
                @Override
                public void run() {
                    ptt.onPtt(dn);
                }
            });
        }
        };
    }

    private final java.util.ArrayDeque<BluetoothGattCharacteristic> pending = new java.util.ArrayDeque<BluetoothGattCharacteristic>();

    @SuppressLint("MissingPermission")
    @TargetApi(18)
    private void subscribeNext(BluetoothGatt g) {
        BluetoothGattCharacteristic ch = pending.poll();
        if (ch == null) {
            buttonState = "ready: " + name(g.getDevice());
            return;
        }
        g.setCharacteristicNotification(ch, true);
        BluetoothGattDescriptor d = ch.getDescriptor(CCCD);
        if (d == null) {
            subscribeNext(g);
            return;
        }
        boolean indicate = (ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
        d.setValue(indicate ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        if (!g.writeDescriptor(d)) subscribeNext(g);
    }

    /** true = pressed, false = released, null = not a button event. */
    static Boolean decode(byte[] v) {
        if (v.length == 0) return null;
        String s = new String(v, java.nio.charset.Charset.forName("ISO-8859-1")).trim().toUpperCase(java.util.Locale.US);
        boolean text = true;
        for (byte b : v) if ((b & 0xff) < 0x20 && b != '\r' && b != '\n') text = false;
        if (text && s.length() > 0) {
            if (s.contains("RELEASE") || s.endsWith("=R") || s.equals("R") || s.contains("UP")) return false;
            if (s.contains("PRESS") || s.endsWith("=P") || s.equals("P") || s.contains("DOWN")) return true;
            if (s.equals("0")) return false;
            if (s.equals("1")) return true;
        }
        for (byte b : v) if (b != 0) return true;
        return false;
    }
}
