package ch.luethi.flapsuploader;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.annotation.SuppressLint;

import android.bluetooth.BluetoothStatusCodes;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;

@SuppressLint("MissingPermission")
public class BleOtaManager {

    private static final String TAG = "BleOtaManager";

    public static final UUID OTA_CTRL_UUID =
            UUID.fromString("2ae4d630-7d4b-2dae-8b4f-14310bb05d39");

    public static final UUID OTA_DATA_UUID =
            UUID.fromString("2ae4d630-7d4b-2dae-8b4f-14310cb05d39");

    public static final byte CMD_START = 0x01;
    public static final byte CMD_FINISH = 0x02;
    public static final byte CMD_REBOOT = 0x04;

    public static final byte TARGET_APP_OTA = 0x00;
    public static final byte TARGET_SPIFFS_FILE = 0x02;

    private static final int STATE_IDLE = 0;
    private static final int STATE_START = 1;
    private static final int STATE_DATA = 2;
    private static final int STATE_FINISH = 3;
    private static final int STATE_REBOOT = 4;

    private static final int MAX_RETRIES = 30;
    private static final long WRITE_TIMEOUT_MS = 8000;
    private static final long INTER_CHUNK_DELAY_MS = 10;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic ctrlChar;
    private BluetoothGattCharacteristic dataChar;

    private byte[] payload;
    private int targetMode;
    private String remotePath;

    private Callback callback;

    private int state = STATE_IDLE;
    private int offset = 0;
    private int mtu = 23;
    private int chunkSize = 20;

    private boolean writeInProgress = false;
    private Runnable timeoutRunnable;

    private final Queue<WriteOp> queue = new ArrayDeque<>();

    private static class WriteOp {
        BluetoothGattCharacteristic ch;
        byte[] data;
        int writeType;
        int retries = 0;

        WriteOp(BluetoothGattCharacteristic ch, byte[] data, int writeType) {
            this.ch = ch;
            this.data = data;
            this.writeType = writeType;
        }
    }

    public interface Callback {
        void onProgress(int sent, int total);
        void onStatus(String msg);
        void onSuccess();
        void onError(String msg);
    }

    public BleOtaManager(Context context) {
        this.context = context;
    }

    // ------------------------------------------------------------
    // PUBLIC START
    // ------------------------------------------------------------

    public void startOta(BluetoothDevice device,
                         byte[] payload,
                         int targetMode,
                         String remotePath,
                         Callback cb) {

        this.payload = payload;
        this.targetMode = targetMode;
        this.remotePath = remotePath;
        this.callback = cb;

        this.offset = 0;
        this.state = STATE_IDLE;

        cb.onStatus("Connecting...");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            gatt = device.connectGatt(context, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } else {
            gatt = device.connectGatt(context, false, gattCallback);
        }
    }

    // ------------------------------------------------------------
    // GATT CALLBACK
    // ------------------------------------------------------------

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            handler.post(() -> {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    callback.onStatus("Connected. Discovering...");
                    g.discoverServices();

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        g.requestConnectionPriority(
                                BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    }

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    callback.onStatus("Disconnected");
                    if (state != STATE_IDLE && state != STATE_REBOOT) {
                        callback.onError("Disconnected during update");
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            handler.post(() -> {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    callback.onError("Service discovery failed");
                    return;
                }

                for (BluetoothGattService s : g.getServices()) {
                    for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                        if (OTA_CTRL_UUID.equals(c.getUuid())) ctrlChar = c;
                        if (OTA_DATA_UUID.equals(c.getUuid())) dataChar = c;
                    }
                }

                if (ctrlChar == null || dataChar == null) {
                    callback.onError("OTA characteristics missing");
                    return;
                }

                requestMtu();
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtuValue, int status) {
            handler.post(() -> {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    mtu = mtuValue;
                    chunkSize = Math.max(20, mtu - 3);
                } else {
                    chunkSize = 20;
                }

                callback.onStatus("MTU=" + mtu + " chunk=" + chunkSize);

                // Small delay to let the stack settle
                handler.postDelayed(BleOtaManager.this::startStartCommand, 100);
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g,
                                          BluetoothGattCharacteristic c,
                                          int status) {

            handler.post(() -> {
                if (timeoutRunnable == null) {
                    Log.w(TAG, "Ignoring callback for " + c.getUuid() + " (already handled by timeout or failure)");
                    return;
                }
                cancelTimeout();
                writeInProgress = false;

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Write failed status=" + status + " for " + c.getUuid());
                    handleRetryOrFail(c);
                    return;
                }

                synchronized (queue) {
                    queue.poll();
                }

                if (c.getUuid().equals(OTA_CTRL_UUID)) {
                    handleControlFlow();
                } else if (c.getUuid().equals(OTA_DATA_UUID)) {
                    if (state == STATE_DATA) {
                        callback.onProgress(offset, payload.length);
                        handler.postDelayed(BleOtaManager.this::sendNextChunk, INTER_CHUNK_DELAY_MS);
                    }
                }
            });
        }
    };

    // ------------------------------------------------------------
    // QUEUE SYSTEM
    // ------------------------------------------------------------

    private void enqueue(BluetoothGattCharacteristic ch, byte[] data, int type) {
        synchronized (queue) {
            queue.add(new WriteOp(ch, data, type));
        }
        handler.post(this::processQueue);
    }

    private void processQueue() {
        if (writeInProgress) return;

        WriteOp op;
        synchronized (queue) {
            op = queue.peek();
        }
        if (op == null) return;

        if (gatt == null) {
            callback.onError("GATT disconnected");
            return;
        }

        writeInProgress = true;

        boolean ok;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ok = gatt.writeCharacteristic(op.ch, op.data, op.writeType) == BluetoothStatusCodes.SUCCESS;
            } else {
                op.ch.setWriteType(op.writeType);
                op.ch.setValue(op.data);
                ok = gatt.writeCharacteristic(op.ch);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception during write", e);
            ok = false;
        }

        if (!ok) {
            writeInProgress = false;
            handleRetryOrFail(op.ch);
            return;
        }

        startTimeout();
    }

    // ------------------------------------------------------------
    // OTA FLOW
    // ------------------------------------------------------------

    private void startStartCommand() {
        state = STATE_START;

        CRC32 crc = new CRC32();
        crc.update(payload);
        Log.i(TAG,
                String.format("CRC32 = 0x%08X",
                        crc.getValue()));
        ByteBuffer buf;

        if (targetMode == TARGET_SPIFFS_FILE) {

            byte[] path = remotePath.getBytes(StandardCharsets.UTF_8);

            buf = ByteBuffer.allocate(1 + 4 + 4 + 2 + path.length)
                    .order(ByteOrder.LITTLE_ENDIAN);

            buf.put(CMD_START);
            buf.putInt(payload.length);
            buf.putInt((int) crc.getValue());
            buf.put(TARGET_SPIFFS_FILE);
            buf.put((byte) path.length);
            buf.put(path);

        } else {

            buf = ByteBuffer.allocate(1 + 4 + 4)
                    .order(ByteOrder.LITTLE_ENDIAN);

            buf.put(CMD_START);
            buf.putInt(payload.length);
            buf.putInt((int) crc.getValue());
        }

        enqueue(ctrlChar, buf.array(),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
    }

    private void handleControlFlow() {

        if (state == STATE_START) {
            state = STATE_DATA;
            sendNextChunk();
        }

        else if (state == STATE_FINISH) {
            if (targetMode == TARGET_APP_OTA) {
                sendReboot();
            } else {
                callback.onSuccess();
            }
        }

        else if (state == STATE_REBOOT) {
            callback.onSuccess();
        }
    }

    private void sendNextChunk() {

        if (offset >= payload.length) {
            sendFinish();
            return;
        }

        int len = Math.min(chunkSize, payload.length - offset);

        byte[] chunk = Arrays.copyOfRange(payload, offset, offset + len);

        offset += len;

        enqueue(dataChar, chunk,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
    }

    private void sendFinish() {
        state = STATE_FINISH;

        enqueue(ctrlChar, new byte[]{CMD_FINISH},
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
    }

    private void sendReboot() {
        state = STATE_REBOOT;

        enqueue(ctrlChar, new byte[]{CMD_REBOOT},
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
    }

    // ------------------------------------------------------------
    // MTU
    // ------------------------------------------------------------

    private void requestMtu() {
        gatt.requestMtu(247);
    }

    // ------------------------------------------------------------
    // TIMEOUT + RETRY
    // ------------------------------------------------------------

    private void startTimeout() {
        cancelTimeout();

        timeoutRunnable = () -> {
            timeoutRunnable = null;
            WriteOp op;
            synchronized (queue) {
                op = queue.peek();
            }
            if (op == null) return;

            if (op.retries++ < MAX_RETRIES) {
                Log.i(TAG, "Timeout, retrying... attempt " + op.retries);
                writeInProgress = false;
                processQueue();
            } else {
                callback.onError("Write timeout");
            }
        };

        handler.postDelayed(timeoutRunnable, WRITE_TIMEOUT_MS);
    }

    private void cancelTimeout() {
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private void handleRetryOrFail(BluetoothGattCharacteristic c) {
        WriteOp op;
        synchronized (queue) {
            op = queue.peek();
        }

        if (op != null && op.retries++ < MAX_RETRIES) {
            Log.i(TAG, "Retrying write... attempt " + op.retries + " for " + (c != null ? c.getUuid() : "unknown"));
            writeInProgress = false;
            handler.postDelayed(this::processQueue, 1000);
        } else {
            callback.onError("Write failed after max retries");
        }
    }

    // ------------------------------------------------------------
    // CLEANUP
    // ------------------------------------------------------------

    public void disconnect() {
        cancelTimeout();
        synchronized (queue) {
            queue.clear();
        }
        writeInProgress = false;
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
    }
}