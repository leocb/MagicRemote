package com.leobottaro.magicremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leobottaro.magicremote.ui.screens.ConnectionListScreen
import com.leobottaro.magicremote.ui.screens.DiscoveryScreen
import com.leobottaro.magicremote.ui.screens.PairingScreen
import com.leobottaro.magicremote.ui.screens.RemoteControlScreen
import com.leobottaro.magicremote.ui.theme.MagicRemoteTheme
import com.leobottaro.magicremote.viewmodel.RemoteViewModel
import com.leobottaro.magicremote.viewmodel.Screen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isDebug = try {
            applicationContext.packageManager.getApplicationInfo(packageName, 0).flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        } catch (_: Exception) { false }

        enableEdgeToEdge()
        setContent {
            MagicRemoteTheme {
                val viewModel: RemoteViewModel = viewModel()
                val state by viewModel.state.collectAsState()

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) viewModel.onLocationPermissionGranted()
                    else viewModel.onLocationPermissionDenied()
                }

                Content(
                    state = state,
                    viewModel = viewModel,
                    onRequestLocationPermission = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                    isDebug = isDebug
                )
            }
        }
    }
}

@Composable
private fun Content(
    state: com.leobottaro.magicremote.viewmodel.RemoteUiState,
    viewModel: RemoteViewModel,
    onRequestLocationPermission: () -> Unit,
    isDebug: Boolean = false
) {
    when (val currentScreen = state.screen) {
        is Screen.ConnectionList -> ConnectionListScreen(
            connections = state.savedConnections,
            error = state.error,
            pairingMessage = state.pairingMessage,
            showTestButton = isDebug,
            onConnect = { viewModel.connectToSaved(it) },
            onRename = { conn, name -> viewModel.renameConnection(conn, name) },
            onDelete = { viewModel.deleteConnection(it) },
            onAddNew = { viewModel.goToDiscovery() },
            onTestMode = { viewModel.enterTestMode() },
            onClearError = { viewModel.clearError() }
        )

        is Screen.Discovery -> DiscoveryScreen(
            devices = state.devices,
            isScanning = state.isScanning,
            needsLocationPermission = state.needsLocationPermission,
            showManualIpEntry = state.showManualIpEntry,
            error = state.error,
            onDeviceSelected = { viewModel.selectDevice(it) },
            onStartDiscovery = { viewModel.startDiscovery() },
            onRequestPermission = onRequestLocationPermission,
            onDismissPermission = { viewModel.onLocationPermissionDenied() },
            onToggleManualIpEntry = { viewModel.toggleManualIpEntry() },
            onConnectToIp = { ip -> viewModel.connectToManualIp(ip) },
            onClearError = { viewModel.clearError() }
        )

        is Screen.Pairing -> PairingScreen(
            device = currentScreen.device,
            pairingMessage = state.pairingMessage,
            error = state.error,
            onSubmitPin = { pin -> viewModel.submitPin(currentScreen.device, pin) },
            onBack = { viewModel.cancelPairing() },
            onClearError = { viewModel.clearError() }
        )

        is Screen.Remote -> RemoteControlScreen(
            device = currentScreen.device,
            connected = state.connected,
            onUp = { viewModel.pressUp() },
            onDown = { viewModel.pressDown() },
            onLeft = { viewModel.pressLeft() },
            onRight = { viewModel.pressRight() },
            onEnter = { viewModel.pressEnter() },
            onHome = { viewModel.pressHome() },
            onBack = { viewModel.pressBack() },
            onVolumeUp = { viewModel.volumeUp() },
            onVolumeDown = { viewModel.volumeDown() },
            onPower = { viewModel.pressPower() },
            onDisconnect = { viewModel.goToConnectionList() },
            onRelativeEvent = { dx, dy -> viewModel.sendRelativeEvent(dx, dy) },
            onButtonPress = { viewModel.onButtonPress() },
            onButtonRelease = { viewModel.onButtonRelease() },
            onButtonRepeat = { viewModel.onButtonRepeat() }
        )
    }
}
