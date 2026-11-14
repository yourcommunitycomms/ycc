package org.communitycomms.app.power;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import java.util.UUID;

/**
 * One Bluetooth LE link to a Bluetti power station. Blocking calls, made from a worker thread (never
 * the main thread): connect(), read(), write(), close(). Handles the encrypted handshake by itself
 * when the unit asks for it. Android 4.3 or newer (BLE).
 */
@TargetApi(18)
@SuppressLint("MissingPermission")
public final class BluettiBle {
    public interface Log {
        void log(String s);
    }

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context ctx;
    private final String address;
    private final Log log;
    private final Object lock = new Object();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic wr;
    private volatile boolean connected, servicesOk, opDone, linkFailed;
    private volatile int mtu = 23;
    private final BluettiProtocol.Session session;
    private byte[] answer;
    /** Log every notification in hex (the probe). */
    public volatile boolean rawLog;
    public volatile String lastError = "";
    /** The unit started an encrypted handshake. */
    public volatile boolean sawChallenge;

    public BluettiBle(Context c, String address, final Log log) {
        ctx = c.getApplicationContext();
        this.address = address;
        this.log = log;
        session = new BluettiProtocol.Session(new BluettiProtocol.Session.Out() {
            @Override
            public void send(byte[] wire) {
                if (rawLog) log.log("send (handshake) " + BluettiProtocol.hex(wire));
                sawChallenge = true;
                // From the notify thread: hand off so the GATT callback thread is never blocked.
                final byte[] w = wire;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        writeRaw(w);
                    }
                }, "bluetti-kex").start();
            }

            @Override
            public void log(String s) {
                log.log(s);
            }
        });
    }

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 18;
    }

    public boolean encrypted() {
        return session.encrypted();
    }

    public boolean secure() {
        return session.secure();
    }

    public boolean isConnected() {
        return connected && servicesOk;
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            synchronized (lock) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connected = true;
                    g.discoverServices();
                } else {
                    if (connected || status != 0) log.log("link down (status " + status + ")");
                    connected = false;
                    servicesOk = false;
                    linkFailed = true;
                }
                lock.notifyAll();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            synchronized (lock) {
                servicesOk = status == BluetoothGatt.GATT_SUCCESS;
                lock.notifyAll();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int m, int status) {
            synchronized (lock) {
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = m;
                opDone = true;
                lock.notifyAll();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            synchronized (lock) {
                opDone = true;
                lock.notifyAll();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            synchronized (lock) {
                opDone = true;
                lock.notifyAll();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v == null) return;
            if (rawLog) log.log("notify " + BluettiProtocol.hex(v));
            if (BluettiProtocol.isKex(v, v.length)) sawChallenge = true;
            synchronized (lock) {
                if (session.onNotify(v)) answer = session.answer;
                lock.notifyAll(); // also wakes connect() when the handshake moves on
            }
        }
    };

    private boolean waitFor(long ms, java.util.concurrent.Callable<Boolean> cond) {
        long end = SystemClock.elapsedRealtime() + ms;
        synchronized (lock) {
            try {
                while (!cond.call()) {
                    long left = end - SystemClock.elapsedRealtime();
                    if (left <= 0) return false;
                    lock.wait(left);
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /** Connects, finds ff01/ff02, turns on notifications and waits out a handshake if the unit wants one. */
    public boolean connect() {
        lastError = "";
        if (!supported()) {
            lastError = "needs Android 4.3 or newer";
            return false;
        }
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null || !a.isEnabled()) {
            lastError = "Bluetooth is off";
            return false;
        }
        try {
            BluetoothDevice d = a.getRemoteDevice(address);
            gatt = Build.VERSION.SDK_INT >= 23 ? d.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE) : d.connectGatt(ctx, false, cb);
        } catch (RuntimeException e) {
            lastError = "could not connect: " + e.getMessage();
            return false;
        }
        if (gatt == null) {
            lastError = "could not connect";
            return false;
        }
        if (!waitFor(20000, new java.util.concurrent.Callable<Boolean>() {
            @Override
            public Boolean call() {
                return servicesOk || linkFailed;
            }
        }) || !servicesOk) {
            lastError = connected ? "no answer after connecting" : "not in range, or another phone (the Bluetti app?) is connected";
            close();
            return false;
        }
        BluetoothGattService s = gatt.getService(UUID.fromString(BluettiProtocol.SERVICE));
        BluetoothGattCharacteristic n = null;
        if (s != null) {
            n = s.getCharacteristic(UUID.fromString(BluettiProtocol.NOTIFY));
            wr = s.getCharacteristic(UUID.fromString(BluettiProtocol.WRITE));
        }
        if (n == null || wr == null) { // look in every service
            for (BluetoothGattService x : gatt.getServices()) {
                if (n == null) n = x.getCharacteristic(UUID.fromString(BluettiProtocol.NOTIFY));
                if (wr == null) wr = x.getCharacteristic(UUID.fromString(BluettiProtocol.WRITE));
            }
        }
        if (rawLog) for (BluetoothGattService x : gatt.getServices()) {
            StringBuilder b = new StringBuilder("service " + x.getUuid());
            for (BluetoothGattCharacteristic c : x.getCharacteristics()) b.append("\n  char ").append(c.getUuid()).append(" props 0x").append(Integer.toHexString(c.getProperties()));
            log.log(b.toString());
        }
        if (n == null || wr == null) {
            lastError = "this is not a Bluetti power station (no ff01/ff02)";
            close();
            return false;
        }
        if (Build.VERSION.SDK_INT >= 21) {
            opDone = false;
            if (gatt.requestMtu(247)) waitDone(3000);
            if (rawLog) log.log("MTU " + mtu);
        }
        gatt.setCharacteristicNotification(n, true);
        BluetoothGattDescriptor desc = n.getDescriptor(CCCD);
        if (desc != null) {
            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            opDone = false;
            gatt.writeDescriptor(desc);
            waitDone(3000);
        }
        // A unit that wants encryption sends its challenge now; give it a moment, then wait out the handshake.
        waitFor(2500, new java.util.concurrent.Callable<Boolean>() {
            @Override
            public Boolean call() {
                return sawChallenge;
            }
        });
        if (sawChallenge || session.encrypted()) {
            boolean ok = waitFor(15000, new java.util.concurrent.Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return session.state == BluettiProtocol.Session.SECURE || session.state == BluettiProtocol.Session.FAILED || !connected;
                }
            });
            if (!ok || !session.secure()) {
                lastError = "the encrypted handshake did not finish";
                close();
                return false;
            }
        }
        return true;
    }

    private void waitDone(long ms) {
        waitFor(ms, new java.util.concurrent.Callable<Boolean>() {
            @Override
            public Boolean call() {
                return opDone;
            }
        });
    }

    private synchronized boolean writeRaw(byte[] w) {
        BluetoothGatt g = gatt;
        if (g == null || wr == null) return false;
        int max = Math.max(20, mtu - 3);
        boolean noResp = (wr.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0;
        for (int off = 0; off < w.length; off += max) {
            byte[] part = java.util.Arrays.copyOfRange(w, off, Math.min(w.length, off + max));
            wr.setWriteType(noResp ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            wr.setValue(part);
            opDone = false;
            boolean ok = false;
            for (int tries = 0; tries < 5 && !ok; tries++) {
                ok = g.writeCharacteristic(wr);
                if (!ok) SystemClock.sleep(50);
            }
            if (!ok) return false;
            waitDone(2000);
        }
        return true;
    }

    /** Sends a Modbus frame, waits for its answer (raw Modbus, decrypted). Null on timeout. */
    public byte[] request(byte[] modbus, long timeoutMs) {
        if (!isConnected()) return null;
        try {
            synchronized (lock) {
                answer = null;
                session.reset();
            }
            if (rawLog) log.log("send " + BluettiProtocol.hex(modbus));
            if (!writeRaw(session.wrap(modbus))) {
                lastError = "write failed";
                return null;
            }
            boolean ok = waitFor(timeoutMs, new java.util.concurrent.Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return answer != null || !connected;
                }
            });
            if (!ok || answer == null) {
                lastError = connected ? "no answer" : "link lost";
                return null;
            }
            if (rawLog) log.log("answer " + BluettiProtocol.hex(answer));
            return answer;
        } catch (Exception e) {
            lastError = e.toString();
            return null;
        }
    }

    /** Holding registers, or null (no answer, refused, bad CRC). */
    public int[] read(int start, int count) {
        byte[] a = request(BluettiProtocol.read(start, count), 5000);
        if (a == null) return null;
        if (a.length >= 2 && (a[1] & 0x80) != 0) {
            lastError = "refused (Modbus exception " + (a.length > 2 ? a[2] & 0xFF : 0) + ")";
            return null;
        }
        if (!BluettiProtocol.crcOk(a, a.length)) {
            lastError = "bad CRC";
            return null;
        }
        return BluettiProtocol.registers(a, a.length);
    }

    // No write(): the app is read-only for every battery (since 2026-11-14). Use the Bluetti app to switch outputs.

    public void close() {
        BluetoothGatt g;
        synchronized (lock) {
            g = gatt;
            gatt = null;
            connected = false;
            servicesOk = false;
            lock.notifyAll();
        }
        if (g != null) {
            try {
                g.disconnect();
                g.close();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
