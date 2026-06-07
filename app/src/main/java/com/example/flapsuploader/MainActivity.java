package com.example.flapsuploader;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int PICK_FILE_REQUEST = 1;
    private static final int PERMISSION_REQUEST_CODE = 2;

    private RadioGroup targetGroup;
    private EditText editRemotePath;
    private Button btnSelectFile;
    private TextView textSelectedFile;
    private Button btnUpload;
    private ProgressBar progressBar;
    private TextView textStatus;

    private byte[] selectedFileBytes;
    private String selectedFileName;
    private BleOtaManager otaManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        targetGroup = findViewById(R.id.targetGroup);
        editRemotePath = findViewById(R.id.editRemotePath);
        btnSelectFile = findViewById(R.id.btnSelectFile);
        textSelectedFile = findViewById(R.id.textSelectedFile);
        btnUpload = findViewById(R.id.btnUpload);
        progressBar = findViewById(R.id.progressBar);
        textStatus = findViewById(R.id.textStatus);

        otaManager = new BleOtaManager(this);

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        targetGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radioSpiffs) {
                editRemotePath.setVisibility(View.VISIBLE);
            } else {
                editRemotePath.setVisibility(View.GONE);
            }
        });

        btnSelectFile.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            startActivityForResult(intent, PICK_FILE_REQUEST);
        });

        btnUpload.setOnClickListener(v -> {
            if (checkPermissions()) {
                startScanAndUpload();
            } else {
                requestPermissions();
            }
        });
    }

    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, PERMISSION_REQUEST_CODE);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    selectedFileBytes = readBytes(uri);
                    selectedFileName = uri.getLastPathSegment();
                    textSelectedFile.setText("Selected: " + selectedFileName + " (" + selectedFileBytes.length + " bytes)");
                    btnUpload.setEnabled(true);
                    
                    if (targetGroup.getCheckedRadioButtonId() == R.id.radioSpiffs) {
                         editRemotePath.setText("/spiffs/" + selectedFileName);
                    }
                } catch (IOException e) {
                    Toast.makeText(this, "Failed to read file", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private byte[] readBytes(Uri uri) throws IOException {
        InputStream inputStream = getContentResolver().openInputStream(uri);
        ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
        int bufferSize = 1024;
        byte[] buffer = new byte[bufferSize];
        int len;
        while ((len = inputStream.read(buffer)) != -1) {
            byteBuffer.write(buffer, 0, len);
        }
        return byteBuffer.toByteArray();
    }

    private void startScanAndUpload() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show();
            return;
        }

        scanner = bluetoothAdapter.getBluetoothLeScanner();
        textStatus.setText("Status: Scanning for Flaps-OTA...");
        btnUpload.setEnabled(false);

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        
        ScanFilter filter = new ScanFilter.Builder()
                .setDeviceName("Flaps-OTA")
                .build();

        scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        
        // Timeout after 10 seconds
        new android.os.Handler().postDelayed(() -> {
            if (scanner != null) {
                scanner.stopScan(scanCallback);
                if (textStatus.getText().toString().contains("Scanning")) {
                    textStatus.setText("Status: Scan timeout");
                    btnUpload.setEnabled(true);
                }
            }
        }, 10000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if ("Flaps-OTA".equals(device.getName()) || "Flaps-OTA".equals(result.getScanRecord().getDeviceName())) {
                scanner.stopScan(scanCallback);
                scanner = null;
                performUpload(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            textStatus.setText("Status: Scan failed (" + errorCode + ")");
            btnUpload.setEnabled(true);
        }
    };

    private void performUpload(BluetoothDevice device) {
        int targetMode = targetGroup.getCheckedRadioButtonId() == R.id.radioFirmware ? 
                BleOtaManager.TARGET_APP_OTA : BleOtaManager.TARGET_SPIFFS_FILE;
        String remotePath = editRemotePath.getText().toString();

        otaManager.startOta(device, selectedFileBytes, targetMode, remotePath, new BleOtaManager.Callback() {
            @Override
            public void onProgress(int sent, int total) {
                runOnUiThread(() -> {
                    int progress = (int) ((sent / (float) total) * 100);
                    progressBar.setProgress(progress);
                    textStatus.setText("Status: Uploading " + sent + "/" + total + " bytes");
                });
            }

            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    textStatus.setText("Status: Upload successful!");
                    btnUpload.setEnabled(true);
                    Toast.makeText(MainActivity.this, "Upload successful", Toast.LENGTH_SHORT).show();
                    otaManager.disconnect();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    textStatus.setText("Status: Error - " + message);
                    btnUpload.setEnabled(true);
                    Toast.makeText(MainActivity.this, "Error: " + message, Toast.LENGTH_SHORT).show();
                    otaManager.disconnect();
                });
            }

            @Override
            public void onStatusUpdate(String status) {
                runOnUiThread(() -> textStatus.setText("Status: " + status));
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (otaManager != null) {
            otaManager.disconnect();
        }
    }
}
