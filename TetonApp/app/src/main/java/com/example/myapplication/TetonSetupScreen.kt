package com.example.myapplication

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun TetonSetupScreen(
    status: String,
    isScanning: Boolean,
    devices: List<DiscoveredDevice>,
    isConnected: Boolean,
    isProvisioningReady: Boolean,
    wifiConnected: Boolean,
    waitingForWifiConfirmation: Boolean,
    ssid: String,
    password: String,
    isSendingCredentials: Boolean,
    onScan: () -> Unit,
    onReturnToScanning: () -> Unit,
    onSsidChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSendCredentials: () -> Unit,
    onDeviceSelected: (DiscoveredDevice) -> Unit,
) {
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
                    onClick = onScan,
                    enabled = !isScanning && !isConnected,
                ) {
                    Text(if (isScanning) "Scanning…" else "Scan for devices")
                }
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

            if (isProvisioningReady) {
                Button(
                    onClick = onReturnToScanning,
                    enabled = !isSendingCredentials,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Back to device list") }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = ssid,
                    onValueChange = onSsidChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Wi-Fi network name (SSID)") },
                    singleLine = true,
                    enabled = !isSendingCredentials,
                )

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Wi-Fi password") },
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
                            modifier = Modifier.width(20.dp).height(20.dp),
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
                        onClick = onSendCredentials,
                        enabled = ssid.isNotBlank() && password.isNotEmpty() && !isSendingCredentials,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (isSendingCredentials) "Sending Credentials…" else "Send Wi-Fi credentials")
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
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(devices, key = { it.bluetoothDevice.address }) { device ->
                        Card(
                            onClick = { onDeviceSelected(device) },
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
