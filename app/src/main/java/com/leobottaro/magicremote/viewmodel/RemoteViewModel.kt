package com.leobottaro.magicremote.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leobottaro.magicremote.data.certificate.CertificateManager
import com.leobottaro.magicremote.data.discovery.TvDevice
import com.leobottaro.magicremote.data.discovery.TvDiscoveryManager
import com.leobottaro.magicremote.data.network.PairingClient
import com.leobottaro.magicremote.data.network.RemoteClient
import com.leobottaro.magicremote.data.protocol.KeyCodes
import com.leobottaro.magicremote.data.storage.ConnectionRepository
import com.leobottaro.magicremote.data.storage.SavedConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.InetAddress

sealed class Screen {
    object ConnectionList : Screen()
    object Discovery : Screen()
    data class Pairing(val device: TvDevice, val serverName: String? = null) : Screen()
    data class Remote(val device: TvDevice) : Screen()
}

data class RemoteUiState(
    val screen: Screen = Screen.ConnectionList,
    val devices: List<TvDevice> = emptyList(),
    val savedConnections: List<SavedConnection> = emptyList(),
    val isScanning: Boolean = false,
    val error: String? = null,
    val pairingMessage: String? = null,
    val connected: Boolean = false,
    val needsLocationPermission: Boolean = false,
    val showManualIpEntry: Boolean = false
)

class RemoteViewModel(application: Application) : AndroidViewModel(application) {

    private val certificateManager = CertificateManager(application)
    private val discoveryManager = TvDiscoveryManager(application)
    private val pairingClient = PairingClient(certificateManager)
    private val remoteClient = RemoteClient(certificateManager)
    private val connectionRepo = ConnectionRepository(application)
    private var pingJob: Job? = null

    private val _state = MutableStateFlow(RemoteUiState())
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()

    init {
        val connections = connectionRepo.list()
        if (connections.isEmpty() && certificateManager.isPaired()) {
            _state.update {
                it.copy(
                    savedConnections = connections,
                    screen = Screen.Discovery,
                    error = "Previously paired TV found — select it to save the connection."
                )
            }
        } else {
            _state.update { it.copy(savedConnections = connections, screen = Screen.ConnectionList) }
        }
    }

    fun goToConnectionList() {
        pingJob?.cancel()
        pingJob = null
        remoteClient.disconnect()
        pairingClient.disconnect()
        _state.update {
            it.copy(
                screen = Screen.ConnectionList,
                savedConnections = connectionRepo.list(),
                pairingMessage = null,
                error = null
            )
        }
    }

    fun goToDiscovery() {
        _state.update { it.copy(screen = Screen.Discovery) }
    }

    fun connectToSaved(connection: SavedConnection) {
        _state.update { it.copy(pairingMessage = "Connecting to ${connection.name}...", error = null) }
        viewModelScope.launch {
            try {
                val host = InetAddress.getByName(connection.host)
                connectToRemote(TvDevice(name = connection.name, host = host, port = 6466))
            } catch (e: Exception) {
                _state.update { it.copy(pairingMessage = null, error = "Cannot reach ${connection.host}") }
            }
        }
    }

    fun renameConnection(connection: SavedConnection, newName: String) {
        connectionRepo.update(connection.copy(name = newName))
        _state.update { it.copy(savedConnections = connectionRepo.list()) }
    }

    fun deleteConnection(connection: SavedConnection) {
        connectionRepo.delete(connection.id)
        if (connectionRepo.count() == 0) certificateManager.clearPairing()
        val remaining = connectionRepo.list()
        _state.update { it.copy(savedConnections = remaining) }
        if (remaining.isEmpty()) _state.update { it.copy(screen = Screen.Discovery) }
    }

    private fun saveConnection(host: String, name: String) {
        if (connectionRepo.list().none { it.host == host }) {
            connectionRepo.add(SavedConnection(name = name, host = host))
            _state.update { it.copy(savedConnections = connectionRepo.list()) }
        }
    }

    fun startDiscovery() {
        val ctx = getApplication<Application>()
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.P..Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                _state.update { it.copy(needsLocationPermission = true) }
                return
            }
        }
        beginScan()
    }

    fun onLocationPermissionGranted() { _state.update { it.copy(needsLocationPermission = false) }; beginScan() }
    fun onLocationPermissionDenied() { _state.update { it.copy(needsLocationPermission = false, error = "Location permission required.") } }

    private fun beginScan() {
        _state.update { it.copy(isScanning = true, error = null, devices = emptyList()) }
        viewModelScope.launch {
            discoveryManager.discoverTvs().collect { device ->
                if (_state.value.devices.none { it.host == device.host }) {
                    _state.update { it.copy(devices = it.devices + device, isScanning = false) }
                }
            }
        }
    }

    fun toggleManualIpEntry() { _state.update { it.copy(showManualIpEntry = !it.showManualIpEntry) } }

    fun connectToManualIp(ip: String) {
        try {
            val address = InetAddress.getByName(ip.trim())
            selectDevice(TvDevice(name = ip.trim(), host = address, port = 6466))
        } catch (e: Exception) {
            _state.update { it.copy(error = "Invalid IP address.") }
        }
    }

    fun selectDevice(device: TvDevice) {
        if (certificateManager.isPaired()) {
            connectToRemote(device)
        } else {
            _state.update { it.copy(pairingMessage = "Connecting to TV...") }
            viewModelScope.launch {
                val result = pairingClient.initiatePairing(device.host)
                if (result.success) {
                    _state.update { it.copy(screen = Screen.Pairing(device), pairingMessage = null) }
                } else {
                    _state.update { it.copy(pairingMessage = null, error = result.errorMessage ?: "Pairing failed") }
                }
            }
        }
    }

    fun connectToRemote(device: TvDevice) {
        _state.update { it.copy(pairingMessage = "Connecting...", error = null) }
        viewModelScope.launch {
            if (remoteClient.connect(device.host)) {
                val host = device.host.hostAddress ?: device.name
                saveConnection(host, device.name)
                _state.update {
                    it.copy(
                        screen = Screen.Remote(device),
                        savedConnections = connectionRepo.list(),
                        pairingMessage = null,
                        connected = true,
                        error = null
                    )
                }
                // Start background ping loop to keep connection alive
                pingJob?.cancel()
                pingJob = viewModelScope.launch(Dispatchers.IO) {
                    remoteClient.runPingLoop()
                }
            } else {
                _state.update {
                    it.copy(
                        screen = Screen.Pairing(device),
                        pairingMessage = null,
                        error = "Connection failed."
                    )
                }
            }
        }
    }

    fun submitPin(device: TvDevice, pin: String) {
        if (pin.length < 6) return
        _state.update { it.copy(pairingMessage = "Pairing...", error = null) }
        viewModelScope.launch {
            val result = pairingClient.completePairing(pin)
            if (result.success) {
                _state.update { it.copy(pairingMessage = "Pairing successful! Connecting...", error = null) }
                connectToRemote(device)
            } else {
                _state.update { it.copy(pairingMessage = null, error = result.errorMessage ?: "Pairing failed.") }
            }
        }
    }

    fun cancelPairing() { pairingClient.disconnect(); goToConnectionList() }

    fun sendKeyEvent(keyCode: Int) {
        viewModelScope.launch {
            remoteClient.sendKeyPress(keyCode)
        }
    }

    fun pressUp() = sendKeyEvent(KeyCodes.KEYCODE_DPAD_UP)
    fun pressDown() = sendKeyEvent(KeyCodes.KEYCODE_DPAD_DOWN)
    fun pressLeft() = sendKeyEvent(KeyCodes.KEYCODE_DPAD_LEFT)
    fun pressRight() = sendKeyEvent(KeyCodes.KEYCODE_DPAD_RIGHT)
    fun pressEnter() = sendKeyEvent(KeyCodes.KEYCODE_DPAD_CENTER)
    fun pressHome() = sendKeyEvent(KeyCodes.KEYCODE_HOME)
    fun pressBack() = sendKeyEvent(KeyCodes.KEYCODE_BACK)
    fun volumeUp() = sendKeyEvent(KeyCodes.KEYCODE_VOLUME_UP)
    fun volumeDown() = sendKeyEvent(KeyCodes.KEYCODE_VOLUME_DOWN)
    fun pressPower() = sendKeyEvent(KeyCodes.KEYCODE_POWER)

    fun clearError() { _state.update { it.copy(error = null) } }

    override fun onCleared() {
        pingJob?.cancel()
        remoteClient.disconnect()
        pairingClient.disconnect()
        super.onCleared()
    }
}
