package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.KeyPair

private const val DEVICE_NAME_PREFIX = "Teton-"
private const val SCAN_DURATION_MS = 10_000L
private const val REQUESTED_MTU = 517
internal data class DiscoveredDevice(
    val bluetoothDevice: BluetoothDevice,
    val name: String,
    val signalStrength: Int,
)

class MainActivity : ComponentActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private var status by mutableStateOf("Ready to scan")
    private var isScanning by mutableStateOf(false)
    private var devices by mutableStateOf<List<DiscoveredDevice>>(emptyList())

    private var isConnected by mutableStateOf(false)
    private var isProvisioningReady by mutableStateOf(false)
    private var wifiConnected by mutableStateOf(false)
    private var waitingForWifiConfirmation by mutableStateOf(false)

    private var ssid by mutableStateOf("")
    private var password by mutableStateOf("")
    private var isSendingCredentials by mutableStateOf(false)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopScanTask = Runnable { stopScan("Scan complete") }

    private var keyPair: KeyPair? = null
    private var sessionKey: ByteArray? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val permissionsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_SCAN] == true &&
                    permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else {
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        }

        if (permissionsGranted) {
            startScan()
        } else {
            status = "Bluetooth permissions are required to scan and connect"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager =
            getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        bluetoothAdapter = bluetoothManager.adapter

        setContent {
            MaterialTheme {
                TetonSetupScreen(
                    status = status,
                    isScanning = isScanning,
                    devices = devices,
                    isConnected = isConnected,
                    isProvisioningReady = isProvisioningReady,
                    wifiConnected = wifiConnected,
                    waitingForWifiConfirmation = waitingForWifiConfirmation,
                    ssid = ssid,
                    password = password,
                    isSendingCredentials = isSendingCredentials,
                    onScan = ::requestBluetoothPermissions,
                    onReturnToScanning = ::returnToScanning,
                    onSsidChange = { ssid = it },
                    onPasswordChange = { password = it },
                    onSendCredentials = ::sendWifiCredentials,
                    onDeviceSelected = { connectToDevice(it.bluetoothDevice) },
                )
            }
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        permissionLauncher.launch(permissions)
    }

    private fun startScan() {
        val scanner = bluetoothAdapter.bluetoothLeScanner

        if (!bluetoothAdapter.isEnabled || scanner == null) {
            status = "Turn on Bluetooth before scanning"
            return
        }

        bluetoothScanner = scanner
        devices = emptyList()
        isScanning = true
        status = "Looking for Teton devices…"

        try {
            scanner.startScan(scanCallback)

            mainHandler.removeCallbacks(stopScanTask)
            mainHandler.postDelayed(
                stopScanTask,
                SCAN_DURATION_MS
            )
        } catch (_: SecurityException) {
            isScanning = false
            status = "Bluetooth permission is required to scan"
        }
    }

    private fun stopScan(finalStatus: String? = null) {
        mainHandler.removeCallbacks(stopScanTask)

        if (isScanning) {
            try {
                bluetoothScanner?.stopScan(scanCallback)
            } catch (_: SecurityException) {
            }
        }

        isScanning = false

        finalStatus?.let {
            status = it
        }

        if (devices.isEmpty() && finalStatus == "Scan complete") {
            status = "No Teton devices found"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(
            callbackType: Int,
            result: ScanResult,
        ) {
            val name = result.scanRecord?.deviceName
                ?: runCatching {
                    result.device.name
                }.getOrNull()
                ?: return

            if (!name.startsWith(
                    DEVICE_NAME_PREFIX,
                    ignoreCase = true
                )
            ) {
                return
            }

            val discovered = DiscoveredDevice(
                result.device,
                name,
                result.rssi,
            )

            devices = (
                    devices.filterNot {
                        it.bluetoothDevice.address ==
                                result.device.address
                    } + discovered
                    ).sortedBy {
                    it.name.lowercase()
                }

            status = "Select a device to connect"
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            mainHandler.removeCallbacks(stopScanTask)
            status = "Scan failed (error $errorCode)"
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        stopScan()

        bluetoothGatt?.close()

        isConnected = false
        isProvisioningReady = false
        wifiConnected = false
        waitingForWifiConfirmation = false
        isSendingCredentials = false
        sessionKey = null
        keyPair = null

        status = "Connecting…"

        bluetoothGatt = try {
            device.connectGatt(
                this,
                false,
                gattCallback
            )
        } catch (_: SecurityException) {
            status = "Bluetooth permission is required to connect"
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun returnToScanning() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        isConnected = false
        isProvisioningReady = false
        wifiConnected = false
        waitingForWifiConfirmation = false
        isSendingCredentials = false
        sessionKey = null
        keyPair = null
        ssid = ""
        password = ""
        status = "Ready to scan"
    }

    @SuppressLint("MissingPermission")
    private fun sendConfiguratorPublicKey(
        gatt: BluetoothGatt,
        tetonPublicKey: ByteArray,
    ) {
        keyPair = CryptoUtils.generateX25519KeyPair()

        val publicKey =
            CryptoUtils.getRawPublicKey(keyPair!!)

        sessionKey =
            CryptoUtils.deriveSessionKey(
                keyPair = keyPair!!,
                peerRawPublicKey = tetonPublicKey,
            )

        val service =
            gatt.getService(BleConstants.SERVICE_UUID)

        val characteristic =
            service?.getCharacteristic(
                BleConstants.CONFIGURATOR_PUBLIC_KEY_CHARACTERISTIC_UUID
            )

        if (characteristic == null) {
            status =
                "Configurator public key characteristic not found"
            return
        }

        status = "Sending configurator public key..."

        val writeResult = gatt.writeCharacteristic(
            characteristic,
            publicKey,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )

        if (writeResult != BluetoothGatt.GATT_SUCCESS) {
            status =
                "Could not start configurator public-key write " +
                        "(error $writeResult)"
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendWifiCredentials() {
        val currentSessionKey = sessionKey

        if (currentSessionKey == null) {
            status = "Secure connection is not ready"
            return
        }

        val gatt = bluetoothGatt

        if (gatt == null) {
            status = "Device is not connected"
            return
        }

        if (ssid.isBlank() || password.isEmpty()) {
            status = "Enter both Wi-Fi fields"
            return
        }

        isSendingCredentials = true
        wifiConnected = false
        status = "Sending Credentials…"

        try {
            val encryptedPayload = CryptoUtils.encryptWifiCredentials(
                ssid = ssid,
                password = password,
                sessionKey = currentSessionKey,
            )

            val service =
                gatt.getService(BleConstants.SERVICE_UUID)

            val characteristic =
                service?.getCharacteristic(
                    BleConstants.PROVISIONING_CHARACTERISTIC_UUID
                )

            if (characteristic == null) {
                status =
                    "Wi-Fi provisioning characteristic not found"

                isSendingCredentials = false
                return
            }

            status = "Sending Credentials…"

            val writeResult = gatt.writeCharacteristic(
                characteristic,
                encryptedPayload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )

            if (writeResult != BluetoothGatt.GATT_SUCCESS) {
                status =
                    "Could not send credentials " +
                            "(error $writeResult)"

                isSendingCredentials = false
            }
        } catch (_: Exception) {
            status = "Could not encrypt Wi-Fi credentials"
            isSendingCredentials = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableWifiStatusNotifications(
        gatt: BluetoothGatt
    ): Boolean {
        val service = gatt.getService(BleConstants.SERVICE_UUID)

        val characteristic = service?.getCharacteristic(
            BleConstants.WIFI_STATUS_CHARACTERISTIC_UUID
        )

        if (characteristic == null) {
            return false
        }

        if (!gatt.setCharacteristicNotification(
                characteristic,
                true
            )
        ) {
            return false
        }

        val descriptor = characteristic.getDescriptor(
            java.util.UUID.fromString(
                "00002902-0000-1000-8000-00805f9b34fb"
            )
        )

        if (descriptor == null) {
            return false
        }

        val writeResult = gatt.writeDescriptor(
            descriptor,
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        )

        return writeResult == BluetoothGatt.GATT_SUCCESS
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            statusCode: Int,
            newState: Int,
        ) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                isConnected = false
                isProvisioningReady = false
                status = "Connection failed (error $statusCode)"

                gatt.close()
                return
            }

            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    isConnected = true

                    status =
                        "Connected; checking Teton service…"

                    try {
                        gatt.requestMtu(REQUESTED_MTU)
                    } catch (_: SecurityException) {
                    }

                    val discoveryStarted =
                        gatt.discoverServices()

                    if (!discoveryStarted) {
                        status =
                            "Could not start service discovery"
                    }
                }

                BluetoothGatt.STATE_DISCONNECTED -> {
                    isConnected = false
                    isProvisioningReady = false
                    sessionKey = null
                    keyPair = null

                    status = "Disconnected"
                }
            }
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            statusCode: Int,
        ) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status =
                    "Service discovery failed (error $statusCode)"
                return
            }

            val service =
                gatt.getService(BleConstants.SERVICE_UUID)

            if (service == null) {
                status = "Teton service not found"
                return
            }

            if (!enableWifiStatusNotifications(gatt)) {
                status = "Could not enable Wi-Fi status notifications"
                return
            }

            status = "Enabling Wi-Fi status notifications…"
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            statusCode: Int,
        ) {
            val clientConfigurationDescriptor = java.util.UUID.fromString(
                "00002902-0000-1000-8000-00805f9b34fb"
            )
            if (
                descriptor.uuid != clientConfigurationDescriptor ||
                descriptor.characteristic.uuid != BleConstants.WIFI_STATUS_CHARACTERISTIC_UUID
            ) {
                return
            }

            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status = "Could not enable Wi-Fi status notifications"
                return
            }

            readTetonPublicKey(gatt)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            statusCode: Int,
        ) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.uuid != BleConstants.WIFI_STATUS_CHARACTERISTIC_UUID) {
                    status = "Failed to read public key"
                }
                return
            }

            when (characteristic.uuid) {
                BleConstants.PUBLIC_KEY_CHARACTERISTIC_UUID -> {
                    val tetonPublicKey = characteristic.value
                    status = "Teton public key received"
                    sendConfiguratorPublicKey(gatt, tetonPublicKey)
                }

                BleConstants.WIFI_STATUS_CHARACTERISTIC_UUID -> {
                    val wifiStatus = characteristic.value.toString(Charsets.UTF_8)
                    applyWifiStatus(wifiStatus)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            statusCode: Int,
        ) {
            if (
                characteristic.uuid ==
                BleConstants.CONFIGURATOR_PUBLIC_KEY_CHARACTERISTIC_UUID
            ) {
                if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                    status =
                        "Secure connection established"

                    isProvisioningReady = true
                } else {
                    status =
                        "Failed to send configurator public key"
                }

                return
            }

            if (
                characteristic.uuid ==
                BleConstants.PROVISIONING_CHARACTERISTIC_UUID
            ) {
                if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                    if (!wifiConnected) {
                        waitingForWifiConfirmation = true
                        status = "Waiting for Wi-Fi connection…"
                    }
                    readWifiStatus(gatt)
                } else {
                    status = "Failed to send Wi-Fi credentials"
                    isSendingCredentials = false
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handleCharacteristicChanged(characteristic, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristicChanged(characteristic, value)
        }

        private fun handleCharacteristicChanged(
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (
                characteristic.uuid ==
                BleConstants.WIFI_STATUS_CHARACTERISTIC_UUID
            ) {
                val wifiStatus = value.toString(Charsets.UTF_8)

                applyWifiStatus(wifiStatus)
            }
        }
    }

    private fun applyWifiStatus(wifiStatus: String) {
        when (wifiStatus) {
            "connecting" -> {
                waitingForWifiConfirmation = true
                wifiConnected = false
                status = "Waiting for Wi-Fi connection…"
            }

            "connected" -> {
                waitingForWifiConfirmation = false
                wifiConnected = true
                isSendingCredentials = false
                status = "Wi-Fi connected"
            }

            "failed" -> {
                waitingForWifiConfirmation = false
                wifiConnected = false
                isSendingCredentials = false
                status = "Could not connect to Wi-Fi"
            }

            else -> Unit
        }
    }

    @SuppressLint("MissingPermission")
    private fun readWifiStatus(gatt: BluetoothGatt) {
        val characteristic = gatt
            .getService(BleConstants.SERVICE_UUID)
            ?.getCharacteristic(BleConstants.WIFI_STATUS_CHARACTERISTIC_UUID)

        if (characteristic == null) {
            return
        }

        gatt.readCharacteristic(characteristic)
    }

    @SuppressLint("MissingPermission")
    private fun readTetonPublicKey(gatt: BluetoothGatt) {
        val characteristic = gatt
            .getService(BleConstants.SERVICE_UUID)
            ?.getCharacteristic(BleConstants.PUBLIC_KEY_CHARACTERISTIC_UUID)

        if (characteristic == null) {
            status = "Public key characteristic not found"
            return
        }

        status = "Reading Teton public key…"
        if (!gatt.readCharacteristic(characteristic)) {
            status = "Could not start public-key read"
        }
    }

    override fun onDestroy() {
        stopScan()

        bluetoothGatt?.close()
        bluetoothGatt = null

        isConnected = false
        isProvisioningReady = false
        sessionKey = null
        keyPair = null

        super.onDestroy()
    }
}
