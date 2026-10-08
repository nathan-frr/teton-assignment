from bluezero import adapter
from bluezero import peripheral

DEVICE_NAME = "Teton-5FG2"

SERVICE_UUID = "12345678-1234-5678-1234-56789abcdef0"
CHARACTERISTIC_UUID = "12345678-1234-5678-1234-56789abcdef1"


def on_write(value, options):
    data = bytes(value).decode("utf-8")

    ssid, password = data.split("/", 1)

    print(f"SSID: {ssid}")
    print(f"Password: {password}")


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