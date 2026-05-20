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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.InetAddress

sealed class Screen {
    object Discovery : Screen()
    data class Pairing(val device: TvDevice, val serverName: String? = null) : Screen()
    data class Remote(val device: TvDevice) : Screen()
}

data class RemoteUiState(
    val screen: Screen = Screen.Discovery,
    val devices: List<TvDevice> = emptyList(),
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

    private val _state = MutableStateFlow(RemoteUiState())
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()

    init {
        if (certificateManager.isPaired()) {
            _state.update { it.copy(screen = Screen.Discovery) }
        }
    }

    fun startDiscovery() {
        val ctx = getApplication<Application>()

        // Android 9-12 requires ACCESS_FINE_LOCATION for WiFi scanning/mDNS
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

    fun onLocationPermissionGranted() {
        _state.update { it.copy(needsLocationPermission = false) }
        beginScan()
    }

    fun onLocationPermissionDenied() {
        _state.update {
            it.copy(
                needsLocationPermission = false,
                error = "Location permission is required to discover TVs."
            )
        }
    }

    private fun beginScan() {
        _state.update { it.copy(isScanning = true, error = null, devices = emptyList()) }

        viewModelScope.launch {
            discoveryManager.discoverTvs().collect { device ->
                val existing = _state.value.devices.any { it.host == device.host }
                if (!existing) {
                    _state.update { state ->
                        state.copy(devices = state.devices + device, isScanning = false)
                    }
                }
            }
        }
    }

    // ── Manual IP entry ──

    fun toggleManualIpEntry() {
        _state.update { it.copy(showManualIpEntry = !it.showManualIpEntry) }
    }

    fun connectToManualIp(ip: String) {
        try {
            val address = InetAddress.getByName(ip.trim())
            val device = TvDevice(name = ip.trim(), host = address, port = 6466)
            selectDevice(device)
        } catch (e: Exception) {
            _state.update { it.copy(error = "Invalid IP address. Enter a valid IPv4 address.") }
        }
    }

    // ── Device selection ──

    fun selectDevice(device: TvDevice) {
        if (certificateManager.isPaired()) {
            connectToRemote(device)
        } else {
            _state.update { it.copy(screen = Screen.Pairing(device)) }
        }
    }

    fun connectToRemote(device: TvDevice) {
        _state.update { it.copy(pairingMessage = "Connecting...", error = null) }
        viewModelScope.launch {
            val connected = remoteClient.connect(device.host)
            if (connected) {
                _state.update {
                    it.copy(
                        screen = Screen.Remote(device),
                        pairingMessage = null,
                        connected = true,
                        error = null
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        screen = Screen.Pairing(device),
                        pairingMessage = null,
                        error = "Connection failed. Please pair again."
                    )
                }
                certificateManager.clearPairing()
            }
        }
    }

    // ── Pairing ──

    fun submitPin(device: TvDevice, pin: String) {
        if (pin.length < 4) return

        _state.update { it.copy(pairingMessage = "Pairing...", error = null) }

        viewModelScope.launch {
            val result = pairingClient.performPairing(device.host, pin)
            if (result.success) {
                _state.update {
                    it.copy(pairingMessage = "Pairing successful! Connecting...", error = null)
                }
                connectToRemote(device)
            } else {
                _state.update {
                    it.copy(
                        pairingMessage = null,
                        error = "Pairing failed. Please check the PIN and try again."
                    )
                }
            }
        }
    }

    fun goBackToDiscovery() {
        remoteClient.disconnect()
        _state.update {
            RemoteUiState(
                devices = it.devices,
                isScanning = true
            )
        }
        beginScan()
    }

    // ── Remote control actions ──

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

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    override fun onCleared() {
        remoteClient.disconnect()
        pairingClient.disconnect()
        super.onCleared()
    }
}
