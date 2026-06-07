package com.example.flapsuploader;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
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
    private int chunkSize = 180; // Default to safe value
    private boolean useWriteWithResponse = true;
    private boolean noReboot = false;
    private boolean mtuRequested = false;

    private int sentBytes = 0;
    private Callback callback;

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

        callback.onStatusUpdate("Connecting to " + device.getName() + "...");
        bluetoothGatt = device.connectGatt(context, false, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server.");
                callback.onStatusUpdate("Connected. Discovering services...");
                gatt.discoverServices();
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
                    // Request larger MTU for faster transfer
                    Log.i(TAG, "Requesting MTU...");
                    if (!gatt.requestMtu(247)) {
                        Log.w(TAG, "Failed to request MTU, starting with default.");
                        handler.post(() -> sendStartCommand());
                    }
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
                chunkSize = mtu - 3;
            } else {
                chunkSize = 20; // Fallback
            }
            // Start OTA process after MTU negotiation (success or fail)
            handler.post(() -> sendStartCommand());
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.getUuid().equals(OTA_CTRL_UUID)) {
                    byte[] val = characteristic.getValue();
                    if (val.length > 0 && val[0] == CMD_START) {
                        sendNextChunk();
                    } else if (val.length > 0 && val[0] == CMD_FINISH) {
                        callback.onStatusUpdate("Finished upload.");
                        if (targetMode == TARGET_APP_OTA && !noReboot) {
                            sendRebootCommand();
                        } else {
                            callback.onSuccess();
                        }
                    }
                } else if (characteristic.getUuid().equals(OTA_DATA_UUID)) {
                    sendNextChunk();
                }
            } else {
                callback.onError("Write failed: " + status);
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
        bluetoothGatt.writeCharacteristic(ctrlChar);
    }

    private void sendNextChunk() {
        if (sentBytes >= payload.length) {
            sendFinishCommand();
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

        boolean success = bluetoothGatt.writeCharacteristic(dataChar);
        if (success) {
            sentBytes += currentChunkSize;
            callback.onProgress(sentBytes, payload.length);
            if (!useWriteWithResponse) {
                // If no response, we can't rely on onCharacteristicWrite to trigger next chunk.
                // However, writing too fast might overflow buffers.
                // For simplicity in this implementation, if no response is used, 
                // we should probably still wait for a small delay or use a more robust flow control.
                // But Android's writeCharacteristic (NO_RESPONSE) usually returns immediately.
                // In many cases, it's better to wait for callback even for NO_RESPONSE,
                // although some Android versions don't call it for NO_RESPONSE.
                // Actually, Android DOES call onCharacteristicWrite for NO_RESPONSE too on most modern versions.
            }
        } else {
            callback.onError("Failed to write chunk.");
        }
    }

    private void sendFinishCommand() {
        callback.onStatusUpdate("Sending FINISH command...");
        ctrlChar.setValue(new byte[]{CMD_FINISH});
        ctrlChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        bluetoothGatt.writeCharacteristic(ctrlChar);
    }

    private void sendRebootCommand() {
        callback.onStatusUpdate("Sending REBOOT command...");
        ctrlChar.setValue(new byte[]{CMD_REBOOT});
        ctrlChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        try {
            bluetoothGatt.writeCharacteristic(ctrlChar);
            callback.onSuccess();
        } catch (Exception e) {
            // Might disconnect immediately
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
