from bluezero import adapter
from bluezero import async_tools
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
WIFI_STATUS_CHARACTERISTIC_UUID = "2277b4bb-a728-4852-9502-4edcccd5f249"

GCM_NONCE_LENGTH = 12
ADVERTISING_TIMEOUT_SECONDS = 300

teton_private_key = None
session_key = None

wifi_status_characteristic = None


def read_wifi_status():
    return list(wifi_connection_status.encode("utf-8"))


wifi_connection_status = "not_connected"


def update_wifi_status(status):
    global wifi_connection_status

    wifi_connection_status = status

    print(f"Wi-Fi status: {status}")

    if wifi_status_characteristic is not None:
        wifi_status_characteristic.set_value(list(status.encode("utf-8")))


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
        return "connected"

    print("Wi-Fi connection failed.")
    error_text = f"{result.stdout}\n{result.stderr}".strip()
    print(error_text)
    normalized_error = error_text.casefold()

    if "no network with ssid" in normalized_error:
        return "ssid_not_found"

    if (
        "secrets were required, but not provided" in normalized_error
        or (
            "802-11-wireless-security.psk" in normalized_error
            and "property is invalid" in normalized_error
        )
    ):
        return "password_check"

    return "failed"


def on_write(value, options):
    global session_key

    if session_key is None:
        print("No secure session established.")
        update_wifi_status("failed")
        return

    encrypted_data = bytes(value)

    if len(encrypted_data) <= GCM_NONCE_LENGTH:
        update_wifi_status("failed")
        return

    try:
        nonce = encrypted_data[:GCM_NONCE_LENGTH]
        ciphertext = encrypted_data[GCM_NONCE_LENGTH:]

        credentials = decrypt_credentials(
            session_key,
            nonce,
            ciphertext,
        )

        ssid = credentials["ssid"].strip()
        password = credentials["password"]

        update_wifi_status("connecting")

        connection_status = connect_to_wifi(
            ssid,
            password,
        )

        update_wifi_status(connection_status)

    except Exception:
        update_wifi_status("failed")


def on_configurator_public_key(value, options):
    global teton_private_key
    global session_key

    configurator_public_key = bytes(value)

    if len(configurator_public_key) != 32:
        return

    session_key = derive_session_key_from_peer(
        teton_private_key,
        configurator_public_key,
    )


def main():
    global teton_private_key
    global wifi_status_characteristic

    ble_connected = False
    advertising_timeout_elapsed = False

    def on_ble_connect(*_args):
        nonlocal ble_connected
        ble_connected = True

    def on_ble_disconnect(*_args):
        nonlocal ble_connected
        ble_connected = False

        if advertising_timeout_elapsed:
            device.mainloop.quit()

    def on_advertising_timeout():
        nonlocal advertising_timeout_elapsed
        advertising_timeout_elapsed = True
        print("BLE advertising window expired.")

        if not ble_connected:
            device.mainloop.quit()

        return False

    bluetooth_adapter = list(adapter.Adapter.available())[0]

    teton_private_key, public_key = generate_key_pair()

    public_key_bytes = public_key.public_bytes_raw()

    device = peripheral.Peripheral(
        bluetooth_adapter.address,
        local_name=DEVICE_NAME,
    )
    device.on_connect = on_ble_connect
    device.on_disconnect = on_ble_disconnect

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
        flags=[
            "write",
            "write-without-response",
        ],
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
        flags=[
            "write",
            "write-without-response",
        ],
        write_callback=on_configurator_public_key,
    )

    device.add_characteristic(
        srv_id=1,
        chr_id=4,
        uuid=WIFI_STATUS_CHARACTERISTIC_UUID,
        value=list(
            wifi_connection_status.encode("utf-8")
        ),
        notifying=True,
        flags=[
            "read",
            "notify",
        ],
        read_callback=read_wifi_status,
    )
    wifi_status_characteristic = device.characteristics[-1]

    async_tools.add_timer_seconds(
        ADVERTISING_TIMEOUT_SECONDS,
        on_advertising_timeout,
    )
    device.publish()


if __name__ == "__main__":
    main()
