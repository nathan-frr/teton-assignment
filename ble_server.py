from bluezero import adapter
from bluezero import peripheral
import subprocess

DEVICE_NAME = "Teton-5FG2"

SERVICE_UUID = "a5f1063d-0998-43be-9946-3eaacaf74ab1"
CHARACTERISTIC_UUID = "f62b8918-e9ef-4481-94b6-4e5c17f0a700"


def connect_to_wifi(ssid, password):
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
    data = bytes(value).decode("utf-8")

    ssid, password = data.split("/", 1)

    print(f"SSID: {ssid}")
    print(f"Password: {password}")

    connect_to_wifi(ssid, password)


def main():
    bluetooth_adapter = list(adapter.Adapter.available())[0]

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

    print(f"Advertising as {DEVICE_NAME}...")
    print("Waiting for Wi-Fi credentials...")

    device.publish()


if __name__ == "__main__":
    main()