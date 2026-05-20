package com.leobottaro.magicremote.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leobottaro.magicremote.data.discovery.TvDevice

@Composable
fun DiscoveryScreen(
    devices: List<TvDevice>,
    isScanning: Boolean,
    needsLocationPermission: Boolean,
    showManualIpEntry: Boolean,
    error: String?,
    onDeviceSelected: (TvDevice) -> Unit,
    onStartDiscovery: () -> Unit,
    onRequestPermission: () -> Unit,
    onDismissPermission: () -> Unit,
    onToggleManualIpEntry: () -> Unit,
    onConnectToIp: (String) -> Unit,
    onClearError: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Magic Remote",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Find your Android TV",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Permission request card ──
            if (needsLocationPermission) {
                PermissionCard(onRequestPermission = onRequestPermission, onDismiss = onDismissPermission)
                return@Box
            }

            // ── Scanning state ──
            if (isScanning && devices.isEmpty()) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Scanning for TVs...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── Device list ──
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices) { device ->
                    TvDeviceCard(device = device, onClick = { onDeviceSelected(device) })
                }
            }

            if (devices.isEmpty() && !isScanning && error == null && !needsLocationPermission) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No TVs found",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Make sure your TV is powered on and on the same Wi-Fi network",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Scan / Refresh button ──
            if (!isScanning) {
                Button(onClick = onStartDiscovery) {
                    Text(if (devices.isEmpty()) "Scan" else "Refresh")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Manual IP entry toggle ──
            TextButton(onClick = onToggleManualIpEntry) {
                Text(
                    if (showManualIpEntry) "Hide manual entry" else "Enter IP manually",
                    fontSize = 14.sp
                )
            }

            // ── Manual IP entry form ──
            if (showManualIpEntry) {
                ManualIpEntryForm(onConnect = onConnectToIp)
            }

            // ── Error or status when empty and error ──
            if (devices.isEmpty() && !isScanning && error != null && !needsLocationPermission) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }

        // ── Error snackbar ──
        error?.let { msg ->
            if (!needsLocationPermission) {
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = onClearError) { Text("Dismiss") }
                    }
                ) { Text(msg) }
            }
        }
    }
}

@Composable
private fun PermissionCard(onRequestPermission: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Location Permission Required", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "To discover TVs on your network, the app needs location permission.",
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequestPermission) { Text("Grant Permission") }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onDismiss) { Text("Not Now") }
        }
    }
}

@Composable
private fun ManualIpEntryForm(onConnect: (String) -> Unit) {
    var ipAddress by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Enter TV IP address",
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = ipAddress,
                onValueChange = { ipAddress = it },
                placeholder = { Text("e.g. 192.168.1.100") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = {
                    if (ipAddress.isNotBlank()) onConnect(ipAddress)
                }),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onConnect(ipAddress) },
                enabled = ipAddress.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Connect")
            }
        }
    }
}

@Composable
private fun TvDeviceCard(device: TvDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    device.host.hostAddress ?: "",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("Connect", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
    }
}
