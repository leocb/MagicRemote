package com.leobottaro.magicremote.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leobottaro.magicremote.data.storage.SavedConnection

@Composable
fun ConnectionListScreen(
    connections: List<SavedConnection>,
    error: String?,
    pairingMessage: String?,
    showTestButton: Boolean = false,
    onConnect: (SavedConnection) -> Unit,
    onRename: (SavedConnection, String) -> Unit,
    onDelete: (SavedConnection) -> Unit,
    onAddNew: () -> Unit,
    onTestMode: () -> Unit = {},
    onClearError: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(48.dp))
            Text("Magic Remote", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Saved TVs", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(24.dp))

            if (connections.isEmpty()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No saved TVs yet", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap below to discover and pair a new TV", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }

            pairingMessage?.let {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(connections) { conn ->
                    ConnectionCard(conn, onConnect = { onConnect(conn) }, onRename = { onRename(conn, it) }, onDelete = { onDelete(conn) })
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onAddNew) { Text("Add new TV") }

            if (showTestButton) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onTestMode) { Text("Test Remote (debug)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        error?.let { Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp), action = { TextButton(onClick = onClearError) { Text("Dismiss") } }) { Text(it) } }
    }
}

@Composable
private fun ConnectionCard(connection: SavedConnection, onConnect: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onConnect), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(connection.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(connection.host, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row {
                IconButton(onClick = { showRename = true }) { Icon(Icons.Default.Edit, contentDescription = "Rename") }
                IconButton(onClick = { showDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            }
        }
    }

    if (showRename) RenameDialog(connection.name, onConfirm = { onRename(it); showRename = false }, onDismiss = { showRename = false })
    if (showDelete) AlertDialog(onDismissRequest = { showDelete = false }, title = { Text("Remove TV") }, text = { Text("Remove \"${connection.name}\"?") },
        confirmButton = { TextButton(onClick = { onDelete(); showDelete = false }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } })
}

@Composable
private fun RenameDialog(currentName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Rename TV") }, text = {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("TV name") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onConfirm(name) }))
    }, confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
