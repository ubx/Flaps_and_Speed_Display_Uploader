# Flaps and Speed Display Uploader

An Android application designed to perform Over-The-Air (OTA) updates for the "Flaps and Speed Display" hardware via Bluetooth Low Energy (BLE).

## Features

- **Bluetooth Low Energy (BLE) OTA:** Wirelessly update your device without physical connections.
- **Dual Update Modes:**
    - **Firmware Update:** Upload new firmware binaries to the device.
    - **SPIFFS File Upload:** Upload specific data files (e.g., `config.json`) to the device's SPIFFS filesystem.
- **Real-time Progress:** Track the upload progress with a progress bar and status messages.
- **Permission Management:** Handles necessary Android permissions for BLE scanning and file access.

## Prerequisites

- Android device with Bluetooth support.
- Android 6.0 (API level 23) or higher (for runtime permissions).
- Location services enabled (required for BLE scanning on some Android versions).

## How to Use

1. **Launch the App:** Open the Flaps Uploader app on your Android device.
2. **Select Update Type:**
    - Choose **Firmware** if you are updating the device's main software.
    - Choose **SPIFFS File** if you are uploading a specific file. If selected, provide the **Remote Path** where the file should be stored on the device (e.g., `/spiffs/config.json`).
3. **Select File:** Click the **Select File** button to choose the `.bin` or data file from your phone's storage.
4. **Upload:** Click the **Upload** button. The app will scan for the target device and begin the OTA process.
5. **Monitor Progress:** Watch the progress bar and status text. Do not close the app or move away from the device until the process is complete.

## Technical Details

- **BLE Communication:** The app uses a custom BLE protocol implemented in `BleOtaManager.java` to handle data chunking, flow control, and error recovery during the OTA process.
- **File Handling:** Uses Android's Storage Access Framework to securely select files for upload.

## Development

The project is built using Gradle and follows standard Android project structure.

- **Main Activity:** `app/src/main/java/com/example/flapsuploader/MainActivity.java`
- **OTA Logic:** `app/src/main/java/com/example/flapsuploader/BleOtaManager.java`
- **Layout:** `app/src/main/res/layout/activity_main.xml`
