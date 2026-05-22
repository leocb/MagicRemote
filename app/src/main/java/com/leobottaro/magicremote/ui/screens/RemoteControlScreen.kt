package com.leobottaro.magicremote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
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
    onRelativeEvent: (dx: Int, dy: Int) -> Unit = { _, _ -> },
    onButtonPress: () -> Unit = {},
    onButtonRelease: () -> Unit = {}
) {
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDisconnect) { Text("Disconnect", fontSize = 14.sp) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(device.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(if (connected) "Connected" else "Disconnected", fontSize = 12.sp, color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = {}) { Text("", fontSize = 14.sp) }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
            Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Volume", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    VolBtn("-", onVolumeUp, onButtonPress, onButtonRelease, Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    VolBtn("+", onVolumeDown, onButtonPress, onButtonRelease, Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            NavBtn("Home", onHome, onButtonPress, onButtonRelease)
            NavBtn("Power", onPower, onButtonPress, onButtonRelease)
            NavBtn("Back", onBack, onButtonPress, onButtonRelease)
        }
        Spacer(Modifier.weight(1f))
        Card(Modifier.fillMaxWidth().height(200.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(); down.consume()
                    var accDx = 0f; var accDy = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        val dx = ((c.position.x - down.position.x) - accDx).roundToInt().coerceIn(-127, 127)
                        val dy = ((c.position.y - down.position.y) - accDy).roundToInt().coerceIn(-127, 127)
                        accDx += dx; accDy += dy
                        if (dx != 0 || dy != 0) onRelativeEvent(dx, dy)
                        c.consume()
                    }
                }
            }, contentAlignment = Alignment.Center) { Text("Touchpad", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
        Spacer(Modifier.height(12.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PadBtn("▲", onUp, onButtonPress, onButtonRelease)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PadBtn("◄", onLeft, onButtonPress, onButtonRelease)
                Spacer(Modifier.width(6.dp))
                OkBtn(onEnter, onButtonPress, onButtonRelease)
                Spacer(Modifier.width(6.dp))
                PadBtn("►", onRight, onButtonPress, onButtonRelease)
            }
            Spacer(Modifier.height(6.dp))
            PadBtn("▼", onDown, onButtonPress, onButtonRelease)
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun Modifier.gesture(onClick: () -> Unit, onPress: () -> Unit, onRelease: () -> Unit) = this.pointerInput(onClick) {
    awaitEachGesture {
        awaitFirstDown()
        onPress()
        val up = waitForUpOrCancellation()
        onRelease()
        if (up != null) onClick()
    }
}

@Composable
private fun PadBtn(l: String, oc: () -> Unit, op: () -> Unit, or: () -> Unit) {
    Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).gesture(oc, op, or),
        contentAlignment = Alignment.Center) { Text(l, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun OkBtn(oc: () -> Unit, op: () -> Unit, or: () -> Unit) {
    Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).gesture(oc, op, or),
        contentAlignment = Alignment.Center) { Text("OK", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary) }
}

@Composable
private fun VolBtn(l: String, oc: () -> Unit, op: () -> Unit, or: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.height(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).gesture(oc, op, or),
        contentAlignment = Alignment.Center) { Text(l, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun NavBtn(l: String, oc: () -> Unit, op: () -> Unit, or: () -> Unit) {
    Box(Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).gesture(oc, op, or),
        contentAlignment = Alignment.Center) { Text(l, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}
