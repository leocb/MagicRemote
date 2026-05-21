package com.leobottaro.magicremote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leobottaro.magicremote.data.discovery.TvDevice
import kotlin.math.roundToInt

@Composable
fun RemoteControlScreen(
    device: TvDevice,
    connected: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onEnter: () -> Unit,
    onHome: () -> Unit,
    onBack: () -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onPower: () -> Unit,
    onDisconnect: () -> Unit,
    onRelativeEvent: (dx: Int, dy: Int) -> Unit = { _, _ -> }
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Top bar ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDisconnect) { Text("Disconnect", fontSize = 14.sp) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(device.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (connected) "Connected" else "Disconnected",
                    fontSize = 12.sp,
                    color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            TextButton(onClick = {}) { Text("", fontSize = 14.sp) }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Volume controls ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Volume", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    VolumeButton("-", onVolumeDown, Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    VolumeButton("+", onVolumeUp, Modifier.weight(1f))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Navigation: Home | Power | Back ──
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            NavButton("Home", onHome)
            NavButton("Power", onPower)
            NavButton("Back", onBack)
        }

        Spacer(modifier = Modifier.weight(1f))

        // ── Touchpad ──
        Card(
            modifier = Modifier.fillMaxWidth().height(200.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val dx = (dragAmount.x / 8).roundToInt().coerceIn(-127, 127)
                            val dy = (dragAmount.y / 8).roundToInt().coerceIn(-127, 127)
                            if (dx != 0 || dy != 0) onRelativeEvent(dx, dy)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🖱", fontSize = 28.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Touchpad", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── D-Pad ──
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DPadButton("▲", onUp)
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DPadButton("◄", onLeft)
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onEnter),
                    contentAlignment = Alignment.Center
                ) { Text("OK", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary) }
                Spacer(modifier = Modifier.width(6.dp))
                DPadButton("►", onRight)
            }
            Spacer(modifier = Modifier.height(6.dp))
            DPadButton("▼", onDown)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun DPadButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VolumeButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.height(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NavButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(12.dp), modifier = Modifier.height(40.dp)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
