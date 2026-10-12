# Architecture: Offline Wi-Fi Provisioning

## Overview

The prototype provisions an Ubuntu device from an Android phone without internet access or an existing shared Wi-Fi network. The phone sends Wi-Fi credentials over Bluetooth Low Energy (BLE), and the device uses NetworkManager (`nmcli`) to connect to the target network and reports the result over BLE.

In the current prototype, starting `device/ble_server.py` begins a five-minute advertising window, and the app displays nearby devices whose Bluetooth names start with `Teton-`. Each device has a four-character identifier in its name (e.g., `Teton-5FG2`), which the technician uses to identify the intended device. A physical setup button could be added in a product version to start this window intentionally.

## Why BLE

BLE is supported by Android and can establish a local communication channel before the device joins the facility network. Its GATT characteristics support the exchange of public keys, encrypted credentials, and connection status without requiring internet access.

Wi-Fi Direct is a viable alternative but requires establishing a peer network and handling IP connectivity and service discovery. NFC enables close-range interaction but requires compatible hardware and physical proximity. USB offers a reliable wired connection but requires cable access. BLE provides a convenient wireless workflow, at the cost of shorter range, lower throughput, and platform-specific GATT handling.

## Provisioning flow

1. The Ubuntu service advertises a BLE GATT service for up to five minutes after the server starts. A physical setup button could activate this window in a product deployment, but is not implemented in the prototype.
2. The Android app scans for devices with names beginning with `Teton-` and presents a selectable list.
3. The phone and device establish a session for exchanging credentials.
4. The technician enters the Wi-Fi SSID and password. The app serializes them as JSON, encrypts the payload, and writes it to the provisioning characteristic.
5. The device authenticates and decrypts the payload, then asks NetworkManager to connect to the network.
6. The device publishes a status such as `connecting`, `connected`, `ssid_not_found`, `password_check`, or `failed`. The app displays the result.

## Credential protection

The app and device use ephemeral X25519 Diffie–Hellman key agreement to derive a shared secret. HKDF-SHA256 derives a 32-byte session key using a 32-byte all-zero salt and `teton-provisioning-v1` as the `info` context. Both implementations use the same salt: Python passes `salt=None`, which the `cryptography` library treats as a zero-filled hash-length salt, while Kotlin explicitly provides 32 zero bytes. The session key remains unique to each exchange because it is derived from a fresh X25519 shared secret.

The device verifies the authentication tag before passing the credentials to NetworkManager. Credentials are therefore not transmitted as plaintext over BLE.

However, the prototype does not authenticate exchanged public keys. A nearby active attacker could intercept and replace them, establish separate sessions with the phone and device, and potentially obtain the credentials. Encryption protects against passive eavesdropping but does not prevent this man-in-the-middle attack.

### Authenticated pairing with a fixed QR code

For production, each device could have a persistent identity signing key. Its QR code would contain a fingerprint of the identity public key. During provisioning, the device would sign its ephemeral X25519 public key and the relevant key-exchange transcript. The app would verify the signature and compare the identity-key fingerprint with the scanned value. This authenticates the device while retaining fresh session keys.

The current prototype does not implement persistent identity keys, QR scanning, signatures, or fingerprint verification.

## Provisioning 200 devices

Provisioning 200 devices requires both reliable wireless operation and a workflow that non-technical staff can use safely. In practice, we will assume that multiple technicians provision different devices in parallel, with one active provisioning session per phone.

Each device should have a unique identifier. To simplify device selection, a production version of the app could offer a "Scan QR code" option. The QR code would contain the device's four-character identifier, allowing the app to filter the nearby Bluetooth device list to the matching entry instead of requiring the technician to search the full list manually. The current prototype does not implement QR scanning.

Devices should advertise only during a time-limited provisioning window, ideally activated by a physical setup button. This reduces ambiguity and limits unnecessary exposure of the provisioning service. The prototype now limits advertising to five minutes after the server starts, but does not implement a button gate. If the timeout expires during an active BLE connection, the current session is allowed to finish and the server exits when the phone disconnects.

The app should display per-device progress and actionable errors, avoid logging credentials, and allow technicians to retry failed devices independently.

## Current implementation boundaries

- BLE GATT carries the key exchange, encrypted credential payload, and connection status.
- The app uses a ten-second scan window. Separately, the Ubuntu server allows BLE advertising for up to five minutes after startup.
- When the five-minute timer expires without a connected phone, the server's event loop exits and Bluezero unregisters the advertisement. If a phone is connected, the server lets that GATT session finish and exits when the phone disconnects; it does not terminate the session when the timer expires.
- The app displays `Wi-Fi connected` after receiving the device's `connected` status; it does not stop the Ubuntu BLE server.
- The prototype does not implement physical-button activation, QR scanning, or authenticated pairing.
