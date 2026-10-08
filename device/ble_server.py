from bluezero import adapter
from bluezero import peripheral
import subprocess

from crypto_utils import (
    generate_key_pair,
    derive_session_key_from_peer,
    decrypt_credentials,
    )


DEVICE_NAME = "Teton-5FG2"

SERVICE_UUID = "a5f1063d-0998-43be-9946-3eaacaf74ab1"
CHARACTERISTIC_UUID = "f62b8918-e9ef-4481-94b6-4e5c17f0a700"
PUBLIC_KEY_CHARACTERISTIC_UUID = "75a65872-af14-4fde-9ab7-b68e3df74d30"
CONFIGURATOR_PUBLIC_KEY_CHARACTERISTIC_UUID = "c0ad84ed-5c63-496e-ab9b-5eb2a5315bd2"

GCM_NONCE_LENGTH = 12

teton_private_key = None
session_key = None


def connect_to_wifi(ssid, password):
    print(f"Connecting to Wi-Fi network: {ssid}")

    result = subprocess.run(
        [
            "nmcli",
            "device",
            "wifi",
            "connect",
            ssid,
            "password",
            password,
        ],
        capture_output=True,
        text=True,
    )

    if result.returncode == 0:
        print("Wi-Fi connection successful.")
        print(result.stdout.strip())
    else:
        print("Wi-Fi connection failed.")
        print(result.stderr.strip())


def on_write(value, options):
    global session_key

    if session_key is None:
        print("No secure session established.")
        return

    encrypted_data = bytes(value)

    print(
        f"Encrypted Wi-Fi credentials received "
        f"({len(encrypted_data)} bytes)."
    )

    if len(encrypted_data) <= GCM_NONCE_LENGTH:
        print("Invalid encrypted payload.")
        return

    try:
        # Android sends:
        # [12-byte nonce][ciphertext + GCM authentication tag]
        nonce = encrypted_data[:GCM_NONCE_LENGTH]
        ciphertext = encrypted_data[GCM_NONCE_LENGTH:]

        # Decrypt the credentials
        credentials = decrypt_credentials(
            session_key,
            nonce,
            ciphertext,
        )

        ssid = credentials["ssid"].strip()
        password = credentials["password"]

        print(f"Received Wi-Fi credentials for SSID: {ssid}")

        # Do not print the password.
        connect_to_wifi(ssid, password)

    except Exception as exception:
        print("Failed to decrypt Wi-Fi credentials.")
        print(f"Error: {type(exception).__name__}: {exception}")


def on_configurator_public_key(value, options):
    global teton_private_key
    global session_key

    configurator_public_key = bytes(value)

    print(
        f"Configurator public key received: "
        f"{configurator_public_key.hex()}"
    )

    if len(configurator_public_key) != 32:
        print("Invalid configurator public key.")
        return

    # Derive the shared session key using X25519 + HKDF-SHA256
    session_key = derive_session_key_from_peer(
        teton_private_key,
        configurator_public_key,
    )

    print(f"Teton session key: {session_key.hex()}")
    print("Secure provisioning session established.")


def main():
    global teton_private_key

    bluetooth_adapter = list(adapter.Adapter.available())[0]

    teton_private_key, public_key = generate_key_pair()

    public_key_bytes = public_key.public_bytes_raw()

    device = peripheral.Peripheral(
        bluetooth_adapter.address,
        local_name=DEVICE_NAME,
    )

    device.add_service(
        srv_id=1,
        uuid=SERVICE_UUID,
        primary=True,
    )

    device.add_characteristic(
        srv_id=1,
        chr_id=1,
        uuid=CHARACTERISTIC_UUID,
        value=[],
        notifying=False,
        flags=["write", "write-without-response"],
        write_callback=on_write,
    )

    device.add_characteristic(
        srv_id=1,
        chr_id=2,
        uuid=PUBLIC_KEY_CHARACTERISTIC_UUID,
        value=list(public_key_bytes),
        notifying=False,
        flags=["read"],
    )

    device.add_characteristic(
        srv_id=1,
        chr_id=3,
        uuid=CONFIGURATOR_PUBLIC_KEY_CHARACTERISTIC_UUID,
        value=[],
        notifying=False,
        flags=["write", "write-without-response"],
        write_callback=on_configurator_public_key,
    )

    print(f"Advertising as {DEVICE_NAME}...")
    print("Waiting for Wi-Fi credentials...")

    device.publish()


if __name__ == "__main__":
    main()