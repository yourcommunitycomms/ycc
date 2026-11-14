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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A plain Bluetooth LE link for the streaming batteries (Anker SOLIX, EcoFlow): finds a notify and a
 * write characteristic, queues every notification for the reader's thread, writes in MTU-sized
 * parts. Blocking calls from a worker thread. Android 4.3+.
 */
@TargetApi(18)
@SuppressLint("MissingPermission")
public final class BleLink {
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context ctx;
    private final String address;
    private final BatteryReader.Log log;
    private final Object lock = new Object();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic wr;
    private volatile boolean connected, servicesOk, opDone, linkFailed;
    private volatile int mtu = 23;
    private final LinkedBlockingQueue<byte[]> in = new LinkedBlockingQueue<byte[]>(400);
    public volatile boolean rawLog;
    public volatile String lastError = "";

    public BleLink(Context c, String address, BatteryReader.Log log) {
        ctx = c.getApplicationContext();
        this.address = address;
        this.log = log;
    }

    public boolean isConnected() {
        return connected && servicesOk && gatt != null;
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
            v = v.clone();
            if (rawLog) log.log("notify " + BatteryReader.hex(v));
            if (!in.offer(v)) {
                in.poll();
                in.offer(v);
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

    private void waitDone(long ms) {
        waitFor(ms, new java.util.concurrent.Callable<Boolean>() {
            @Override
            public Boolean call() {
                return opDone;
            }
        });
    }

    /** Connects and turns on notifications on the first notify / write UUID pair the device has. */
    public boolean connect(String[] notifyUuids, String[] writeUuids, String what) {
        lastError = "";
        if (Build.VERSION.SDK_INT < 18) {
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
            lastError = connected ? "no answer after connecting" : "not in range, or another phone (the maker's app?) is connected";
            close();
            return false;
        }
        BluetoothGattCharacteristic n = null;
        for (int i = 0; i < notifyUuids.length && (n == null || wr == null); i++) {
            n = null;
            wr = null;
            UUID nu = UUID.fromString(notifyUuids[i]), wu = UUID.fromString(writeUuids[i]);
            for (BluetoothGattService x : gatt.getServices()) {
                if (n == null) n = x.getCharacteristic(nu);
                if (wr == null) wr = x.getCharacteristic(wu);
            }
        }
        if (rawLog) for (BluetoothGattService x : gatt.getServices()) {
            StringBuilder b = new StringBuilder("service " + x.getUuid());
            for (BluetoothGattCharacteristic c : x.getCharacteristics()) b.append("\n  char ").append(c.getUuid()).append(" props 0x").append(Integer.toHexString(c.getProperties()));
            log.log(b.toString());
        }
        if (n == null || wr == null) {
            lastError = "this is not " + what + " (its Bluetooth parts were not found)";
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
        in.clear();
        return true;
    }

    /** The next notification, or null after ms. */
    public byte[] next(long ms) {
        try {
            return in.poll(Math.max(1, ms), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return null;
        }
    }

    public synchronized boolean write(byte[] w) {
        BluetoothGatt g = gatt;
        if (g == null || wr == null) return false;
        if (rawLog) log.log("send " + BatteryReader.hex(w));
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
            if (!ok) {
                lastError = "write failed";
                return false;
            }
            waitDone(2000);
        }
        return true;
    }

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
