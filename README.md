# Teton Wi-Fi Provisioning Demo

This project implements an offline Wi-Fi provisioning flow for a Teton device. A technician uses an Android application to send Wi-Fi credentials to an Ubuntu device over Bluetooth Low Energy (BLE). The Ubuntu device decrypts the credentials, connects to the specified Wi-Fi network, and reports the result to the configurator.

## Features

- Offline provisioning without internet access or a pre-existing shared Wi-Fi network.
- BLE-based communication between the Android configurator and the Ubuntu device.
- Encrypted Wi-Fi credential transfer using a shared session key.
- Actual Wi-Fi connection using NetworkManager (`nmcli`).
- A graphical Android interface for device discovery, credential entry, and connection status.

## Architecture

The system consists of two components:

- **Configurator:** An Android application that discovers the Teton device, establishes a shared session key, encrypts the Wi-Fi credentials, and displays the connection result.
- **Teton device:** A Python BLE server running on Ubuntu 24.04. It advertises for up to five minutes after startup, receives and decrypts the credentials, configures Wi-Fi using `nmcli`, and reports the result over BLE.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the design decisions, provisioning flow, credential protection, and approach to provisioning 200 devices. Follow [Running the Demo](#running-the-demo) to reproduce the flow.

## Hardware Requirements

### Ubuntu device

- Ubuntu 24.04 LTS with a BLE capable Bluetooth adapter and a Wi-Fi adapter.
- BlueZ and NetworkManager (`nmcli`). NetworkManager must be running, and the user running the server must be permitted to use Bluetooth and NetworkManager.
- Python 3 and the Python packages `bluezero` and `cryptography`.

Install the system packages:

```bash
sudo apt update
sudo apt install bluez network-manager python3 python3-venv python3-pip python3-dbus python3-gi
```

Create the virtual environment from the repository root. `--system-site-packages` makes the Ubuntu D-Bus and GObject Python bindings available inside it, which Bluezero uses:

```bash
python3 -m venv --system-site-packages .venv
source .venv/bin/activate
python -m pip install bluezero cryptography
```

Check the system services and adapters:

```bash
bluetoothctl --version
bluetoothctl list
nmcli --version
nmcli device status
```

### Android configurator

- Android phone with Bluetooth LE support. The app requires Android 8.0 (API 26) or later.
- To build from source, use Android Studio with the Android SDK. The project includes a Gradle wrapper.


## Build or install the Android configurator

**Option A — Install the prebuilt APK**

1. [Download the Android APK](teton-app-debug.apk) from the project.
2. Transfer it to your Android phone and install it. You may need to allow installation from that source.
3. Open the application and grant the requested Bluetooth permissions.

**Option B — Build from source**

1. Install Android Studio and the Android SDK.
2. Open the Android project in Android Studio.
3. Connect an Android phone with USB debugging enabled, and run the application from Android Studio.

## Running the Demo

### 1. Start the Teton device

On the Ubuntu device, from the repository root, activate the virtual environment and start the BLE server:

```bash
source .venv/bin/activate
python device/ble_server.py
```

The server advertises the Teton BLE service for up to five minutes. If a phone connects before the timeout, the server lets that provisioning session finish and exits after the phone disconnects.

Keep this terminal open to observe diagnostic output.

### 2. Open the Android configurator

1. Enable Bluetooth on the Android phone.
2. Launch the Teton setup application.
3. Grant the requested Bluetooth permissions.

### 3. Discover and select the device

1. Tap **Scan for devices**.
2. Select the intended Teton device from the list.
3. Wait for the BLE connection and session-key setup to complete.

The device and phone must be within Bluetooth range. They do not need an existing shared Wi-Fi network. Internet access is not needed for provisioning, but may be needed to build the Android app and download its dependencies. The target Wi-Fi network must be in range of the Ubuntu device.

### 4. Send Wi-Fi credentials

1. Enter the target Wi-Fi network name (SSID).
2. Enter the corresponding password.
3. Tap **Send Wi-Fi credentials**.
4. Wait for the connection result.

The configurator should display **Wi-Fi connected** if provisioning succeeds.

Verify that the Ubuntu device has connected to the intended network.

## Security Considerations

Wi-Fi credentials are encrypted before transmission over BLE. The configurator and device exchange public keys and derive a shared session key. The Ubuntu device decrypts the credentials locally before configuring the Wi-Fi connection.

The app and server exchange ephemeral X25519 public keys and derive a 32-byte session key with HKDF-SHA256 (`teton-provisioning-v1`). Credentials are JSON encrypted with AES-256-GCM using a 12-byte nonce. The server communicates over the BLE GATT service defined in both app and device source.

The exchanged public keys are not authenticated, so this protocol does not prevent an active man-in-the-middle attack. Do not treat it as suitable for protecting credentials against a nearby active attacker.


## Testing

The end-to-end demo has been tested using:

- Ubuntu 24.04 on a laptop as the Teton device.
- A Nothing Phone (3a) Pro as the configurator.

You can find a video of the demo in the file [Final-demo.mp4](Final-demo.mp4). The video shows the full provisioning flow with 2 failure cases: a wrong SSID, and a password too short (Note that a wrong password isn't shown here because it triggers a pop-up on the Ubuntu machine to enter the password manually, but it has been tested on a non-graphical interface as well).
