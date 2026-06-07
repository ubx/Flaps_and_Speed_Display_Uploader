package com.example.flapsuploader;

import android.bluetooth.BluetoothAdapter;
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.CRC32;

public class BleOtaManager {
    private static final String TAG = "BleOtaManager";

    public static final UUID OTA_CTRL_UUID = UUID.fromString("2ae4d630-7d4b-2dae-8b4f-14310bb05d39");
    public static final UUID OTA_DATA_UUID = UUID.fromString("2ae4d630-7d4b-2dae-8b4f-14310cb05d39");

    public static final byte CMD_START = 0x01;
    public static final byte CMD_FINISH = 0x02;
    public static final byte CMD_REBOOT = 0x04;

    public static final byte TARGET_APP_OTA = 0x00;
    public static final byte TARGET_SPIFFS_FILE = 0x02;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic ctrlChar;
    private BluetoothGattCharacteristic dataChar;

    private byte[] payload;
    private int targetMode;
    private String remotePath;
    private int chunkSize = 20; // Default to safe value for BLE
    private boolean useWriteWithResponse = true;
    private boolean noReboot = false;
    private boolean mtuRequested = false;

    private int sentBytes = 0;
    private boolean writeInProgress = false;
    private Callback callback;

    private int otaState = 0;
    private static final int STATE_IDLE = 0;
    private static final int STATE_STARTING = 1;
    private static final int STATE_DATA = 2;
    private static final int STATE_FINISHING = 3;
    private static final int STATE_REBOOTING = 4;

    public interface Callback {
        void onProgress(int sent, int total);
        void onSuccess();
        void onError(String message);
        void onStatusUpdate(String status);
    }

    public BleOtaManager(Context context) {
        this.context = context;
    }

    public void startOta(BluetoothDevice device, byte[] payload, int targetMode, String remotePath, Callback callback) {
        this.payload = payload;
        this.targetMode = targetMode;
        this.remotePath = remotePath;
        this.callback = callback;
        this.sentBytes = 0;
        this.otaState = STATE_IDLE;

        callback.onStatusUpdate("Connecting to " + device.getName() + "...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            bluetoothGatt = device.connectGatt(context, false, gattCallback);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server.");
                callback.onStatusUpdate("Connected. Discovering services...");
                gatt.discoverServices();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Log.i(TAG, "Requesting high connection priority...");
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.");
                if (sentBytes >= (payload != null ? payload.length : 0) && !noReboot) {
                     // Likely reboot disconnect
                     callback.onStatusUpdate("Device disconnected (rebooted).");
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered.");
                findCharacteristics(gatt);
                if (ctrlChar != null && dataChar != null) {
                    // Small delay before MTU request to let some stacks settle
                    handler.postDelayed(() -> {
                        if (bluetoothGatt == null) return;
                        Log.i(TAG, "Requesting MTU...");
                        if (!bluetoothGatt.requestMtu(247)) {
                            Log.w(TAG, "Failed to request MTU, starting with default.");
                            chunkSize = 20;
                            handler.postDelayed(() -> sendStartCommand(), 500);
                        }
                    }, 500);
                } else {
                    callback.onError("OTA characteristics not found.");
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
                callback.onError("Service discovery failed: " + status);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.i(TAG, "MTU changed to " + mtu + ", status: " + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                chunkSize = Math.min(mtu - 3, 128); // Limit to safe value
                Log.i(TAG, "Set chunkSize to " + chunkSize);
            } else {
                chunkSize = 20; // Fallback
            }
            // Start OTA process after a short delay to let the stack settle
            handler.postDelayed(() -> sendStartCommand(), 500);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "onCharacteristicWrite: " + characteristic.getUuid().toString() + " status: " + status + " state: " + otaState);
            writeInProgress = false;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handler.post(() -> {
                    if (characteristic.getUuid().equals(OTA_CTRL_UUID)) {
                        if (otaState == STATE_STARTING) {
                            otaState = STATE_DATA;
                            //sendNextChunk();
                            handler.post(() -> sendNextChunk());
                        } else if (otaState == STATE_FINISHING) {
                            callback.onStatusUpdate("Finished upload.");
                            if (targetMode == TARGET_APP_OTA && !noReboot) {
                                handler.postDelayed(() -> sendRebootCommand(), 500);
                            } else {
                                otaState = STATE_IDLE;
                                callback.onSuccess();
                            }
                        } else if (otaState == STATE_REBOOTING) {
                            otaState = STATE_IDLE;
                            callback.onSuccess();
                        }
                    } else if (characteristic.getUuid().equals(OTA_DATA_UUID)) {
                        sentBytes += characteristic.getValue().length;
                        callback.onProgress(sentBytes, payload.length);
                        //sendNextChunk();
                        handler.post(() -> sendNextChunk());
                    }
                });
            } else {
                Log.e(TAG, "Write failed with status " + status + " at state " + otaState);
                callback.onError("Write failed: " + status + " (State: " + otaState + ")");
            }
        }
    };

    private void findCharacteristics(BluetoothGatt gatt) {
        for (BluetoothGattService service : gatt.getServices()) {
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                if (characteristic.getUuid().equals(OTA_CTRL_UUID)) {
                    ctrlChar = characteristic;
                } else if (characteristic.getUuid().equals(OTA_DATA_UUID)) {
                    dataChar = characteristic;
                }
            }
        }
    }

    private void sendStartCommand() {
        if (bluetoothGatt == null || ctrlChar == null) return;
        otaState = STATE_STARTING;
        callback.onStatusUpdate("Sending START command...");
        CRC32 crc = new CRC32();
        crc.update(payload);
        long crcValue = crc.getValue();

        int startPacketSize = 1 + 4 + 4; // CMD + size + CRC
        if (targetMode == TARGET_SPIFFS_FILE) {
            byte[] remotePathBytes = remotePath.getBytes(StandardCharsets.UTF_8);
            startPacketSize += 2 + remotePathBytes.length;
        }

        ByteBuffer buffer = ByteBuffer.allocate(startPacketSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(CMD_START);
        buffer.putInt(payload.length);
        buffer.putInt((int) crcValue);

        if (targetMode == TARGET_SPIFFS_FILE) {
            byte[] remotePathBytes = remotePath.getBytes(StandardCharsets.UTF_8);
            buffer.put(TARGET_SPIFFS_FILE);
            buffer.put((byte) remotePathBytes.length);
            buffer.put(remotePathBytes);
        }

        ctrlChar.setValue(buffer.array());
        ctrlChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        Log.i(TAG, "Sending START command, size: " + startPacketSize);
        if (!bluetoothGatt.writeCharacteristic(ctrlChar)) {
            Log.e(TAG, "writeCharacteristic failed for START command");
            callback.onError("Failed to send START command.");
        }
    }

    private void sendNextChunk() {
        if (writeInProgress) return;
        writeInProgress = true;

        if (sentBytes >= payload.length) {
            callback.onStatusUpdate("Wait before finishing...");
            handler.postDelayed(() -> sendFinishCommand(), 500);
            return;
        }

        int remaining = payload.length - sentBytes;
        int currentChunkSize = Math.min(remaining, chunkSize);
        byte[] chunk = Arrays.copyOfRange(payload, sentBytes, sentBytes + currentChunkSize);

        dataChar.setValue(chunk);
        if (useWriteWithResponse) {
            dataChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        } else {
            dataChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        }

        Log.d(TAG, "Writing chunk: " + sentBytes + "/" + payload.length + " (size: " + currentChunkSize + ")");
        boolean success = bluetoothGatt.writeCharacteristic(dataChar);
        if (success) {
            // Wait for onCharacteristicWrite to update sentBytes and trigger next chunk
        } else {
            writeInProgress = false;
            callback.onError("Failed to write chunk.");
        }
    }

    private void sendFinishCommand() {
        if (bluetoothGatt == null || ctrlChar == null) {
            writeInProgress = false;
            return;
        }
        otaState = STATE_FINISHING;
        callback.onStatusUpdate("Sending FINISH command...");
        ctrlChar.setValue(new byte[]{CMD_FINISH});
        ctrlChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        if (!bluetoothGatt.writeCharacteristic(ctrlChar)) {
            writeInProgress = false;
            callback.onError("Failed to send FINISH command.");
        }
    }

    private void sendRebootCommand() {
        otaState = STATE_REBOOTING;
        callback.onStatusUpdate("Sending REBOOT command...");
        ctrlChar.setValue(new byte[]{CMD_REBOOT});
        ctrlChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        try {
            if (!bluetoothGatt.writeCharacteristic(ctrlChar)) {
                otaState = STATE_IDLE;
                callback.onSuccess(); // Assume success if we can't write, likely disconnected
            }
        } catch (Exception e) {
            // Might disconnect immediately
            otaState = STATE_IDLE;
            callback.onSuccess();
        }
    }

    public void disconnect() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }
}
