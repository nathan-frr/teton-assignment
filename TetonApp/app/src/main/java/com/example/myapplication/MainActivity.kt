package com.example.myapplication

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val DEVICE_NAME_PREFIX = "Teton"
private const val SCAN_DURATION_MS = 10_000L

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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopScanTask = Runnable { stopScan("Scan complete") }

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

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
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
                            text = "Choose an advertising Teton device to connect.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(20.dp))

                        Button(
                            onClick = ::requestBluetoothPermissions,
                            enabled = !isScanning,
                        ) {
                            Text(if (isScanning) "Scanning…" else "Scan for devices")
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.width(18.dp).height(18.dp),
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
                        if (devices.isNotEmpty()) {
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
                                items(devices, key = { it.bluetoothDevice.address }) { device ->
                                    Card(
                                        onClick = { connectToDevice(device.bluetoothDevice) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(Modifier.padding(16.dp)) {
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
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(
            permissions,
        )
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
            mainHandler.postDelayed(stopScanTask, SCAN_DURATION_MS)
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
                // Permissions may have been revoked while scanning.
            }
        }
        isScanning = false
        finalStatus?.let { status = it }
        if (devices.isEmpty() && finalStatus == "Scan complete") {
            status = "No Teton devices found"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName
                ?: runCatching { result.device.name }.getOrNull()
                ?: return
            if (!name.startsWith(DEVICE_NAME_PREFIX, ignoreCase = true)) return

            val discovered = DiscoveredDevice(result.device, name, result.rssi)
            devices = (
                    devices.filterNot { it.bluetoothDevice.address == result.device.address } + discovered
                    )
                .sortedBy { it.name.lowercase() }
            status = "Select a device to connect"
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            mainHandler.removeCallbacks(stopScanTask)
            status = "Scan failed (error $errorCode)"
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        stopScan()
        bluetoothGatt?.close()
        status = "Connecting…"
        bluetoothGatt = try {
            device.connectGatt(this, false, gattCallback)
        } catch (_: SecurityException) {
            status = "Bluetooth permission is required to connect"
            null
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            statusCode: Int,
            newState: Int,
        ) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status = "Connection failed (error $statusCode)"
                gatt.close()
                return
            }

            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    status = "Connected; checking Teton service…"
                    gatt.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> status = "Disconnected"
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, statusCode: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status = "Service discovery failed"
                return
            }

            val service = gatt.getService(BleConstants.SERVICE_UUID)
            if (service == null) {
                status = "Teton service not found"
                return
            }

            val publicKeyCharacteristic = service.getCharacteristic(
                BleConstants.PUBLIC_KEY_CHARACTERISTIC_UUID,
            )
            if (publicKeyCharacteristic == null) {
                status = "Public key characteristic not found"
                return
            }

            status = "Reading public key…"
            gatt.readCharacteristic(publicKeyCharacteristic)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            statusCode: Int,
        ) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status = "Failed to read public key"
                return
            }
            if (characteristic.uuid == BleConstants.PUBLIC_KEY_CHARACTERISTIC_UUID) {
                val publicKeyHex = characteristic.value.joinToString(" ") { "%02x".format(it) }
                status = "Public key received: $publicKeyHex"
            }
        }
    }

    override fun onDestroy() {
        stopScan()
        bluetoothGatt?.close()
        bluetoothGatt = null
        super.onDestroy()
    }
}