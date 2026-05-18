package com.leobottaro.magicremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leobottaro.magicremote.ui.screens.DiscoveryScreen
import com.leobottaro.magicremote.ui.screens.PairingScreen
import com.leobottaro.magicremote.ui.screens.RemoteControlScreen
import com.leobottaro.magicremote.ui.theme.MagicRemoteTheme
import com.leobottaro.magicremote.viewmodel.RemoteViewModel
import com.leobottaro.magicremote.viewmodel.Screen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MagicRemoteTheme {
                val viewModel: RemoteViewModel = viewModel()
                val state by viewModel.state.collectAsState()

                Scaffold(modifier = androidx.compose.ui.Modifier.fillMaxSize()) { innerPadding ->
                    when (val currentScreen = state.screen) {
                        is Screen.Discovery -> {
                            DiscoveryScreen(
                                devices = state.devices,
                                isScanning = state.isScanning,
                                error = state.error,
                                onDeviceSelected = { viewModel.selectDevice(it) },
                                onStartDiscovery = { viewModel.startDiscovery() },
                                onClearError = { viewModel.clearError() }
                            )
                        }

                        is Screen.Pairing -> {
                            PairingScreen(
                                device = currentScreen.device,
                                pairingMessage = state.pairingMessage,
                                error = state.error,
                                onSubmitPin = { pin ->
                                    viewModel.submitPin(currentScreen.device, pin)
                                },
                                onBack = { viewModel.goBackToDiscovery() },
                                onClearError = { viewModel.clearError() }
                            )
                        }

                        is Screen.Remote -> {
                            RemoteControlScreen(
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
                                onDisconnect = { viewModel.goBackToDiscovery() }
                            )
                        }
                    }
                }
            }
        }
    }
}
