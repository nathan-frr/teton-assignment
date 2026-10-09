# Teton Wi-Fi Provisioning Demo

An Android app provisions Wi-Fi credentials to an Ubuntu device over Bluetooth Low Energy (BLE). The device decrypts the credentials, connects using NetworkManager, and reports the result to the app.

## Components and requirements

### Ubuntu device

- Ubuntu 24.04 LTS with a BLE capable Bluetooth adapter and a Wi-Fi adapter.
- BlueZ and NetworkManager (`nmcli`). NetworkManager must be running, and the user running the server must be permitted to use Bluetooth and NetworkManager.
- Python 3 and the Python packages `bluezero` and `cryptography`.

The server uses Python's standard library for subprocess handling, JSON, and Base64. There are no other third-party Python imports in `device/`.

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

- Android phone with Bluetooth LE support; app minimum Android version is API 26 (Android 8.0).
- To build from source: Android Studio with Android SDK Platform 37, and JDK 25. The Gradle wrapper downloads the configured Gradle 9.6 distribution and may need internet access on the build machine.
- The app uses Android's built-in Bluetooth and cryptography APIs. Gradle declares AndroidX Compose, Activity, Core KTX, Lifecycle, and test libraries; no separate BLE or crypto package is used.

## Build and install the Android app

From the repository root:

```bash
cd TetonApp
./gradlew assembleDebug
```

The APK is written to `TetonApp/app/build/outputs/apk/debug/app-debug.apk`. Install it on the phone, open the app, and grant the Bluetooth permissions it requests. Android Studio can also open the `TetonApp/` directory and build/run the `app` configuration.

## Run the demo

On the Ubuntu device, from the repository root, activate the virtual environment and start the BLE server:

```bash
source .venv/bin/activate
python device/ble_server.py
```

Keep the server running. On the phone, enable Bluetooth, open the app, scan for the advertised device, select it, enter the target Wi-Fi SSID and password, and send the credentials. The app reports the connection status.

Check the device's Wi-Fi status with:

```bash
nmcli device status
nmcli connection show --active
```

The device and phone must be within Bluetooth range. They do not need an existing shared Wi-Fi network or internet access for provisioning. The target Wi-Fi network must be in range of the Ubuntu device.

## Protocol and security

The app and server exchange ephemeral X25519 public keys and derive a 32-byte session key with HKDF-SHA256 (`teton-provisioning-v1`). Credentials are JSON encrypted with AES-256-GCM using a 12-byte nonce. The server communicates over the BLE GATT service defined in both app and device source.

The exchanged public keys are not authenticated, so this protocol does not prevent an active man-in-the-middle attack. Do not treat it as suitable for protecting credentials against a nearby active attacker.

## Repository layout

- `device/ble_server.py` — BLE GATT server and NetworkManager connection.
- `device/crypto_utils.py` — key agreement, key derivation, and credential decryption.
- `TetonApp/` — Android configurator and Gradle build.
