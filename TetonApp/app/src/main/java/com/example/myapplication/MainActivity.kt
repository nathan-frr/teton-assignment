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
import android.util.Log
import android.util.Log.d
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.security.KeyPair
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject


private const val DEVICE_NAME_PREFIX = "Teton-"
private const val SCAN_DURATION_MS = 10_000L
private const val TAG = "TetonBLE"

private const val REQUESTED_MTU = 517
private const val GCM_NONCE_LENGTH = 12
private const val GCM_TAG_LENGTH = 128


private data class DiscoveredDevice(
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
            Log.w(TAG, "Bluetooth permissions denied: $permissions")
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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 520.dp)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "Teton setup",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = if (isProvisioningReady) {
                                "Enter the Wi-Fi credentials for this device."
                            } else {
                                "Choose an advertising Teton device to connect."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        Spacer(Modifier.height(20.dp))

                        if (!isProvisioningReady) {
                            Button(
                                onClick = ::requestBluetoothPermissions,
                                enabled = !isScanning && !isConnected,
                            ) {
                                Text(
                                    if (isScanning) {
                                        "Scanning…"
                                    } else {
                                        "Scan for devices"
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .width(18.dp)
                                        .height(18.dp),
                                    strokeWidth = 2.dp,
                                )

                                Spacer(Modifier.width(8.dp))
                            }

                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Spacer(Modifier.height(20.dp))

                        if (isProvisioningReady) {

                            Button(
                                onClick = ::returnToScanning,
                                enabled = !isSendingCredentials,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Back to device list")
                            }

                            Spacer(Modifier.height(12.dp))

                            OutlinedTextField(
                                value = ssid,
                                onValueChange = { ssid = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = {
                                    Text("Wi-Fi network name (SSID)")
                                },
                                singleLine = true,
                                enabled = !isSendingCredentials,
                            )

                            Spacer(Modifier.height(12.dp))

                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = {
                                    Text("Wi-Fi password")
                                },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                enabled = !isSendingCredentials,
                            )

                            Spacer(Modifier.height(16.dp))

                            if (wifiConnected) {

                                Text(
                                    text = "Wi-Fi connected ✓",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )

                            } else if (waitingForWifiConfirmation) {

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .width(20.dp)
                                            .height(20.dp),
                                        strokeWidth = 2.dp,
                                    )

                                    Spacer(Modifier.width(10.dp))

                                    Text(
                                        text = "Waiting for Wi-Fi connection…",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }

                            } else {

                                Button(
                                    onClick = ::sendWifiCredentials,
                                    enabled = ssid.isNotBlank() &&
                                            password.isNotEmpty() &&
                                            !isSendingCredentials,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        if (isSendingCredentials) {
                                            "Sending Credentials…"
                                        } else {
                                            "Send Wi-Fi credentials"
                                        }
                                    )
                                }
                            }

                        } else if (devices.isNotEmpty()) {

                            Text(
                                text = "Available devices",
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )

                            Spacer(Modifier.height(8.dp))

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(
                                    devices,
                                    key = { it.bluetoothDevice.address },
                                ) { device ->

                                    Card(
                                        onClick = {
                                            connectToDevice(
                                                device.bluetoothDevice
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(
                                            Modifier.padding(16.dp)
                                        ) {
                                            Text(
                                                text = device.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                            )

                                            Spacer(Modifier.height(4.dp))

                                            Text(
                                                text = "Signal strength: ${device.signalStrength} dBm",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }

                        } else if (!isScanning) {

                            Text(
                                text = "Tap scan to find nearby devices.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
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
            Log.w(
                TAG,
                "Cannot scan: Bluetooth enabled=${bluetoothAdapter.isEnabled}, " +
                        "scannerAvailable=${scanner != null}"
            )

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

        } catch (exception: SecurityException) {
            Log.e(
                TAG,
                "BLE scan could not start because permission is missing",
                exception
            )

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
                Log.w(
                    TAG,
                    "Could not stop BLE scan because permission was revoked"
                )
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
            Log.e(
                TAG,
                "BLE scan failed with errorCode=$errorCode"
            )

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
        } catch (exception: SecurityException) {
            Log.e(
                TAG,
                "Could not connect because Bluetooth permission is missing",
                exception
            )

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


    private fun ByteArray.toHex(): String =
        joinToString("") {
            "%02x".format(it)
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

        d(
            TAG,
            "Android session key: ${sessionKey!!.toHex()}"
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
            Log.e(
                TAG,
                "Could not start configurator public-key write; " +
                        "status=$writeResult"
            )

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
            /*
             * Create a compact JSON payload.
             *
             * Example:
             * {"ssid":"MyWifi","password":"secret123"}
             */
            val credentialsJson = JSONObject()
                .put("ssid", ssid)
                .put("password", password)
                .toString()

            val encryptedPayload = encryptCredentials(
                credentialsJson.toByteArray(Charsets.UTF_8),
                currentSessionKey,
            )

            Log.d(
                TAG,
                "Encrypted credential payload length: " +
                        "${encryptedPayload.size} bytes"
            )

            val service =
                gatt.getService(BleConstants.SERVICE_UUID)

            val characteristic =
                service?.getCharacteristic(
                    BleConstants.PROVISIONING_CHARACTERISTIC_UUID
                )

            if (characteristic == null) {
                Log.e(
                    TAG,
                    "Wi-Fi provisioning characteristic not found"
                )

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

            Log.d(TAG, "Credential write queued; result=$writeResult")

            if (writeResult != BluetoothGatt.GATT_SUCCESS) {
                Log.e(
                    TAG,
                    "Could not start credential write; " +
                            "status=$writeResult"
                )

                status =
                    "Could not send credentials " +
                            "(error $writeResult)"

                isSendingCredentials = false
            }

        } catch (exception: Exception) {
            Log.e(
                TAG,
                "Could not encrypt/send Wi-Fi credentials",
                exception
            )

            status = "Could not encrypt Wi-Fi credentials"
            isSendingCredentials = false
        }
    }


    private fun encryptCredentials(
        plaintext: ByteArray,
        sessionKey: ByteArray,
    ): ByteArray {

        require(sessionKey.size == 32) {
            "AES-256 session key must be 32 bytes"
        }

        /*
         * AES-GCM uses a fresh random 12-byte nonce for
         * every encryption operation.
         */
        val nonce = ByteArray(GCM_NONCE_LENGTH)

        SecureRandom().nextBytes(nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        val key = SecretKeySpec(
            sessionKey,
            "AES"
        )

        val parameterSpec = GCMParameterSpec(
            GCM_TAG_LENGTH,
            nonce
        )

        cipher.init(
            Cipher.ENCRYPT_MODE,
            key,
            parameterSpec
        )

        val ciphertext = cipher.doFinal(plaintext)

        /*
         * Wire format:
         *
         * [12-byte nonce][ciphertext + 16-byte GCM tag]
         *
         * The nonce does not need to be secret.
         */
        return nonce + ciphertext
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
            Log.e(TAG, "Wi-Fi status characteristic not found")
            return false
        }

        if (!gatt.setCharacteristicNotification(
                characteristic,
                true
            )
        ) {
            Log.e(TAG, "Could not enable local Wi-Fi notifications")
            return false
        }

        val descriptor = characteristic.getDescriptor(
            java.util.UUID.fromString(
                "00002902-0000-1000-8000-00805f9b34fb"
            )
        )

        if (descriptor == null) {
            Log.e(TAG, "CCCD descriptor not found")
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
                Log.e(
                    TAG,
                    "GATT connection failed with status=$statusCode"
                )

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

                    /*
                     * Ask for a larger MTU so the encrypted credential
                     * payload can fit into a single GATT write.
                     */
                    try {
                        val mtuRequested =
                            gatt.requestMtu(REQUESTED_MTU)

                        if (!mtuRequested) {
                            Log.w(
                                TAG,
                                "Could not request larger BLE MTU"
                            )
                        }
                    } catch (exception: SecurityException) {
                        Log.w(
                            TAG,
                            "Could not request BLE MTU",
                            exception
                        )
                    }

                    val discoveryStarted =
                        gatt.discoverServices()

                    if (!discoveryStarted) {
                        Log.e(
                            TAG,
                            "Could not start GATT service discovery"
                        )

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


        override fun onMtuChanged(
            gatt: BluetoothGatt,
            mtu: Int,
            statusCode: Int,
        ) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                Log.d(
                    TAG,
                    "BLE MTU negotiated: $mtu"
                )
            } else {
                Log.w(
                    TAG,
                    "BLE MTU request failed: status=$statusCode"
                )
            }
        }


        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            statusCode: Int,
        ) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                Log.e(
                    TAG,
                    "Service discovery failed with status=$statusCode"
                )

                status =
                    "Service discovery failed (error $statusCode)"
                return
            }

            val serviceUuids =
                gatt.services.map { it.uuid }

            val service =
                gatt.getService(BleConstants.SERVICE_UUID)

            if (service == null) {
                Log.e(
                    TAG,
                    "Expected Teton service " +
                            "${BleConstants.SERVICE_UUID} was not found; " +
                            "discovered=$serviceUuids"
                )

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

            Log.d(TAG, "Wi-Fi status CCCD write completed; status=$statusCode")

            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Could not enable Wi-Fi status notifications; status=$statusCode")
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
            Log.d(
                TAG,
                "Characteristic read completed: ${characteristic.uuid}, status=$statusCode"
            )
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                Log.e(
                    TAG,
                    "Characteristic ${characteristic.uuid} read failed with status=$statusCode"
                )

                if (characteristic.uuid == BleConstants.WIFI_STATUS_CHARACTERISTIC_UUID) {
                    // A transient status-read error is not a Wi-Fi failure.
                    // Keep waiting for the status notification; only Teton's
                    // explicit "failed" value should show a connection error.
                    Log.w(TAG, "Wi-Fi status read failed; waiting for notification")
                } else {
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
                    Log.d(TAG, "Wi-Fi status read from Teton: $wifiStatus")
                    applyWifiStatus(wifiStatus)
                }
            }
        }


        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            statusCode: Int,
        ) {
            Log.d(
                TAG,
                "Characteristic write completed: ${characteristic.uuid}, status=$statusCode"
            )
            if (
                characteristic.uuid ==
                BleConstants.CONFIGURATOR_PUBLIC_KEY_CHARACTERISTIC_UUID
            ) {
                if (statusCode == BluetoothGatt.GATT_SUCCESS) {

                    status =
                        "Secure connection established"

                    isProvisioningReady = true

                    d(
                        TAG,
                        "Configurator public key sent"
                    )

                } else {

                    Log.e(
                        TAG,
                        "Configurator public-key write failed; " +
                                "status=$statusCode"
                    )

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
                    Log.d(TAG, "Credential write acknowledged; reading Wi-Fi status")
                    if (!wifiConnected) {
                        waitingForWifiConfirmation = true
                        status = "Waiting for Wi-Fi connection…"
                    }
                    readWifiStatus(gatt)

                } else {

                    Log.e(
                        TAG,
                        "Credential write failed; status=$statusCode"
                    )

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
            Log.d(TAG, "Characteristic changed callback: ${characteristic.uuid}")
            if (
                characteristic.uuid ==
                BleConstants.WIFI_STATUS_CHARACTERISTIC_UUID
            ) {
                val wifiStatus = value.toString(Charsets.UTF_8)

                Log.d(
                    TAG,
                    "Wi-Fi status notification received: $wifiStatus"
                )

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

            else -> Log.w(TAG, "Unrecognized Wi-Fi status from Teton: '$wifiStatus'")
        }
    }


    @SuppressLint("MissingPermission")
    private fun readWifiStatus(gatt: BluetoothGatt) {
        val characteristic = gatt
            .getService(BleConstants.SERVICE_UUID)
            ?.getCharacteristic(BleConstants.WIFI_STATUS_CHARACTERISTIC_UUID)

        if (characteristic == null) {
            Log.e(TAG, "Cannot read Wi-Fi status: characteristic not found")
            return
        }

        val started = gatt.readCharacteristic(characteristic)
        Log.d(TAG, "Requested Wi-Fi status read; started=$started")
    }


    @SuppressLint("MissingPermission")
    private fun readTetonPublicKey(gatt: BluetoothGatt) {
        val characteristic = gatt
            .getService(BleConstants.SERVICE_UUID)
            ?.getCharacteristic(BleConstants.PUBLIC_KEY_CHARACTERISTIC_UUID)

        if (characteristic == null) {
            Log.e(TAG, "Public-key characteristic ${BleConstants.PUBLIC_KEY_CHARACTERISTIC_UUID} was not found")
            status = "Public key characteristic not found"
            return
        }

        status = "Reading Teton public key…"
        if (!gatt.readCharacteristic(characteristic)) {
            Log.e(TAG, "Could not start public-key characteristic read")
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
