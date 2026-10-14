package com.pcremote.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pcremote.app.model.CustomRemoteButton
import com.pcremote.app.network.ConnectionState
import com.pcremote.app.network.PcRemoteClient
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcRemoteAppUI(client: PcRemoteClient) {
    val connState by client.connectionState.collectAsState()
    val statusMsg by client.statusMessage.collectAsState()
    val pendingConfirm by client.pendingConfirmation.collectAsState()
    var darkTheme by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val colorScheme = if (darkTheme) {
        darkColorScheme(primary = Color(0xFF38BDF8), surface = Color(0xFF0F172A), background = Color(0xFF020617))
    } else lightColorScheme()

    MaterialTheme(colorScheme = colorScheme) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("PC Remote", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(statusMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    actions = {
                        IconButton(onClick = { darkTheme = !darkTheme }) {
                            Icon(if (darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode, "Theme")
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    val tabs = listOf(
                        "Connect" to Icons.Default.Computer,
                        "Touchpad" to Icons.Default.TouchApp,
                        "Media/Pres" to Icons.Default.PlayCircle,
                        "Files" to Icons.Default.Folder,
                        "System" to Icons.Default.Memory,
                        "Screen" to Icons.Default.CastConnected
                    )
                    tabs.forEachIndexed { index, pair ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = {
                                if (selectedTab == 5 && index != 5) client.setScreenStream(false)
                                selectedTab = index
                                if (index == 5) client.setScreenStream(true)
                            },
                            icon = { Icon(pair.second, contentDescription = pair.first) },
                            label = { Text(pair.first, fontSize = 10.sp) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                when (selectedTab) {
                    0 -> ConnectAndPairScreen(client, connState)
                    1 -> TouchpadAndKeyboardScreen(client)
                    2 -> MediaPresentationCustomScreen(client)
                    3 -> FileManagerScreen(client)
                    4 -> SystemControlScreen(client)
                    5 -> LiveScreenStreamView(client)
                }

                if (pendingConfirm != null) {
                    AlertDialog(
                        onDismissRequest = { client.dismissPendingConfirmation() },
                        title = { Text("Confirm Sensitive Action") },
                        text = { Text("Execute ${pendingConfirm?.first} (${pendingConfirm?.second ?: ""}) on remote PC?") },
                        confirmButton = {
                            Button(
                                onClick = { client.confirmPendingAction() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                            ) { Text("Confirm") }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { client.dismissPendingConfirmation() }) { Text("Cancel") }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectAndPairScreen(client: PcRemoteClient, connState: ConnectionState) {
    val discovered by client.discoveredServers.collectAsState()
    val saved by client.savedProfiles.collectAsState()
    var manualIp by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("45991") }
    var pinInput by remember { mutableStateOf("") }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (connState == ConnectionState.PAIRING_REQUIRED) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Device Pairing Required", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = pinInput, onValueChange = { pinInput = it }, label = { Text("6-Digit Server PIN") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { client.submitPairingPin(pinInput) }, modifier = Modifier.fillMaxWidth()) { Text("Verify PIN & Pair") }
                    }
                }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Discovered on Wi-Fi LAN", fontWeight = FontWeight.Bold)
                Button(onClick = { client.discoverServersOnLan() }) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Scan LAN")
                }
            }
        }
        items(discovered) { srv ->
            Card(modifier = Modifier.fillMaxWidth().clickable { client.connect(srv.hostAddress, srv.port, srv.udpPort, srv.certFingerprint) }) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("${srv.serverName} (${srv.hostAddress}:${srv.port})", fontWeight = FontWeight.Bold)
                    Text("TLS SHA-256: ${srv.certFingerprint.take(24)}...", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        item { Text("Paired Computers", fontWeight = FontWeight.Bold) }
        items(saved) { prof ->
            Card(modifier = Modifier.fillMaxWidth().clickable { client.connect(prof.host, prof.port, prof.udpPort, prof.certFingerprint) }) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("${prof.serverName} (${prof.host}:${prof.port})", fontWeight = FontWeight.SemiBold)
                    Text("Pinned Cert: ${prof.certFingerprint.take(20)}...", fontSize = 11.sp)
                }
            }
        }
        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Manual IP / Port Connection", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = manualIp, onValueChange = { manualIp = it }, label = { Text("IP Address") }, modifier = Modifier.weight(2f))
                OutlinedTextField(value = manualPort, onValueChange = { manualPort = it }, label = { Text("Port") }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { client.connect(manualIp.trim(), manualPort.toIntOrNull() ?: 45991) }, modifier = Modifier.fillMaxWidth()) { Text("Connect Manually") }
        }
    }
}

@Composable
fun TouchpadAndKeyboardScreen(client: PcRemoteClient) {
    var sensitivity by remember { mutableFloatStateOf(1.8f) }
    var textInput by remember { mutableStateOf("") }
    var ctrlHeld by remember { mutableStateOf(false) }
    var altHeld by remember { mutableStateOf(false) }
    var shiftHeld by remember { mutableStateOf(false) }
    var winHeld by remember { mutableStateOf(false) }

    fun activeModifiers(): List<String> = buildList {
        if (ctrlHeld) add("CTRL"); if (altHeld) add("ALT"); if (shiftHeld) add("SHIFT"); if (winHeld) add("WIN")
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFF1E293B), RoundedCornerShape(16.dp))
                    .pointerInput(sensitivity) {
                        detectTapGestures(
                            onTap = { client.sendMouseClick("LEFT", "CLICK") },
                            onDoubleTap = { client.sendMouseClick("LEFT", "DOUBLE_CLICK") },
                            onLongPress = { client.sendMouseClick("RIGHT", "CLICK") }
                        )
                    }
                    .pointerInput(sensitivity) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            client.sendMouseDelta((dragAmount.x * sensitivity).roundToInt(), (dragAmount.y * sensitivity).roundToInt())
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Text("TOUCHPAD\nTap: Left | Long-Press: Right | Double-Tap: Double Click", color = Color(0xFF64748B), fontSize = 12.sp) }

            Box(
                modifier = Modifier.width(44.dp).fillMaxHeight().background(Color(0xFF334155), RoundedCornerShape(16.dp))
                    .pointerInput(sensitivity) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            client.sendMouseDelta(0, 0, 0, (-dragAmount.y * 2.5f).roundToInt())
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Text("S\nC\nR\nO\nL\nL", fontSize = 10.sp, color = Color.White) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { client.sendMouseClick("LEFT", "CLICK") }, modifier = Modifier.weight(1f)) { Text("Left") }
            Button(onClick = { client.sendMouseClick("MIDDLE", "CLICK") }, modifier = Modifier.weight(0.7f)) { Text("Mid") }
            Button(onClick = { client.sendMouseClick("RIGHT", "CLICK") }, modifier = Modifier.weight(1f)) { Text("Right") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = ctrlHeld, onClick = { ctrlHeld = !ctrlHeld }, label = { Text("Ctrl") })
            FilterChip(selected = altHeld, onClick = { altHeld = !altHeld }, label = { Text("Alt") })
            FilterChip(selected = shiftHeld, onClick = { shiftHeld = !shiftHeld }, label = { Text("Shift") })
            FilterChip(selected = winHeld, onClick = { winHeld = !winHeld }, label = { Text("Win") })
            OutlinedButton(onClick = { client.sendKey("ESC", activeModifiers()) }) { Text("Esc") }
            OutlinedButton(onClick = { client.sendKey("TAB", activeModifiers()) }) { Text("Tab") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("LEFT", "UP", "DOWN", "RIGHT", "BACKSPACE", "ENTER").forEach { k ->
                FilledTonalButton(onClick = { client.sendKey(k, activeModifiers()) }, contentPadding = PaddingValues(6.dp)) { Text(k.take(4), fontSize = 11.sp) }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = textInput, onValueChange = { textInput = it }, label = { Text("Type text or shortcut key") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = {
                if (textInput.isNotEmpty()) {
                    if (ctrlHeld || altHeld || winHeld) client.sendKey(textInput.take(1), activeModifiers())
                    else client.sendText(textInput)
                    textInput = ""
                }
            }) { Text("Send") }
        }
    }
}

@Composable
fun MediaPresentationCustomScreen(client: PcRemoteClient) {
    val customButtons by client.customButtons.collectAsState()
    var newLabel by remember { mutableStateOf("") }
    var newKey by remember { mutableStateOf("") }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Multimedia & Volume Remote", fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { client.sendSystemCommand("MEDIA_PREV") }) { Icon(Icons.Default.SkipPrevious, null) }
                Button(onClick = { client.sendSystemCommand("MEDIA_PLAY_PAUSE") }) { Icon(Icons.Default.PlayArrow, null) }
                Button(onClick = { client.sendSystemCommand("MEDIA_STOP") }) { Icon(Icons.Default.Stop, null) }
                Button(onClick = { client.sendSystemCommand("MEDIA_NEXT") }) { Icon(Icons.Default.SkipNext, null) }
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                FilledTonalButton(onClick = { client.sendSystemCommand("VOLUME_DOWN") }) { Text("Vol -") }
                FilledTonalButton(onClick = { client.sendSystemCommand("MUTE") }) { Text("Mute") }
                FilledTonalButton(onClick = { client.sendSystemCommand("VOLUME_UP") }) { Text("Vol +") }
            }
        }
        item {
            HorizontalDivider()
            Text("Presentation Remote", fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { client.sendKey("F5") }) { Text("Start (F5)") }
                Button(onClick = { client.sendKey("LEFT") }) { Text("Prev") }
                Button(onClick = { client.sendKey("RIGHT") }) { Text("Next") }
                OutlinedButton(onClick = { client.sendKey("ESC") }) { Text("Exit") }
            }
        }
        item {
            HorizontalDivider()
            Text("Custom Macro Buttons", fontWeight = FontWeight.Bold)
            LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.height(160.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(customButtons) { btn ->
                    ElevatedButton(onClick = {
                        if (btn.commandType == "KEY") client.sendKey(btn.primaryArg, btn.modifiers)
                        else client.sendSystemCommand(btn.commandType, btn.primaryArg)
                    }) { Text(btn.label) }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = newLabel, onValueChange = { newLabel = it }, label = { Text("Label") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = newKey, onValueChange = { newKey = it }, label = { Text("Key (e.g. F11)") }, modifier = Modifier.weight(1f))
            }
            Button(onClick = {
                if (newLabel.isNotBlank() && newKey.isNotBlank()) {
                    client.saveCustomButtons(customButtons + CustomRemoteButton(System.currentTimeMillis().toString(), newLabel.trim(), "KEY", newKey.trim().uppercase()))
                    newLabel = ""; newKey = ""
                }
            }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Save Custom Button") }
        }
    }
}

@Composable
fun FileManagerScreen(client: PcRemoteClient) {
    val entries by client.fileEntries.collectAsState()
    val currentPath by client.currentPath.collectAsState()
    var newFolderName by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Path: $currentPath", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { client.listFiles("ROOTS") }) { Text("Roots") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            OutlinedTextField(value = newFolderName, onValueChange = { newFolderName = it }, label = { Text("New Folder") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = {
                if (newFolderName.isNotBlank() && currentPath != "ROOTS") {
                    client.sendFileAction("MKDIR", "$currentPath\\${newFolderName.trim()}")
                    newFolderName = ""
                    client.listFiles(currentPath)
                }
            }) { Text("Create") }
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(entries) { item ->
                Card(modifier = Modifier.fillMaxWidth().clickable { if (item.isDirectory) client.listFiles(item.fullPath) }) {
                    Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (item.isDirectory) "[DIR] ${item.name}" else item.name, fontWeight = FontWeight.Medium)
                            if (!item.isDirectory) Text("${item.sizeBytes / 1024} KB", fontSize = 11.sp, color = Color.Gray)
                        }
                        if (currentPath != "ROOTS") {
                            IconButton(onClick = { client.sendFileAction("DELETE", item.fullPath) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SystemControlScreen(client: PcRemoteClient) {
    val telemetry by client.telemetry.collectAsState()
    var launchTarget by remember { mutableStateOf("") }
    var clipboardSync by remember { mutableStateOf("") }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("System Telemetry", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { client.requestTelemetry() }) { Text("Refresh") }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("CPU Load: ${telemetry.cpuUsagePercent}%")
                    Text("RAM: ${telemetry.ramUsedMb} MB / ${telemetry.ramTotalMb} MB")
                    Text("Battery: ${telemetry.batteryPercent}% (${if (telemetry.isCharging) "AC Online" else "Discharging"})")
                }
            }
        }
        item {
            Text("Power Actions", fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = { client.sendSystemCommand("LOCK") }) { Text("Lock") }
                Button(onClick = { client.sendSystemCommand("SLEEP") }) { Text("Sleep") }
                Button(onClick = { client.sendSystemCommand("MONITOR_OFF") }) { Text("Screen Off") }
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { client.sendSystemCommand("RESTART") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706))) { Text("Restart PC") }
                Button(onClick = { client.sendSystemCommand("SHUTDOWN") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))) { Text("Shutdown PC") }
            }
        }
        item {
            Text("Launch App / URL & Clipboard", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = launchTarget, onValueChange = { launchTarget = it }, label = { Text("App or https:// URL") }, modifier = Modifier.weight(1f))
                Button(onClick = { client.sendSystemCommand("OPEN_URL", launchTarget) }) { Text("Open") }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = clipboardSync, onValueChange = { clipboardSync = it }, label = { Text("Clipboard to PC") }, modifier = Modifier.weight(1f))
                Button(onClick = { client.sendSystemCommand("SET_CLIPBOARD", clipboardSync) }) { Text("Sync") }
            }
        }
        item { Text("Running Window Processes", fontWeight = FontWeight.Bold) }
        items(telemetry.processes) { proc ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${proc.name} (PID ${proc.pid})", fontWeight = FontWeight.SemiBold)
                        Text("${proc.title} - ${proc.memoryMb} MB", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { client.sendSystemCommand("KILL_PROCESS", proc.pid.toString()) }) { Text("End") }
                }
            }
        }
    }
}

@Composable
fun LiveScreenStreamView(client: PcRemoteClient) {
    val bmp by client.liveScreenBitmap.collectAsState()
    var laserMode by remember { mutableStateOf(false) }
    var laserPos by remember { mutableStateOf<Offset?>(null) }
    var boxSize by remember { mutableStateOf(IntSize(1, 1)) }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Live Desktop Stream", fontWeight = FontWeight.Bold)
            FilterChip(selected = laserMode, onClick = { laserMode = !laserMode }, label = { Text("Laser Pointer") })
        }
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black, RoundedCornerShape(12.dp))
                .onSizeChanged { boxSize = it }
                .pointerInput(laserMode, boxSize) {
                    detectTapGestures { offset ->
                        val nx = (offset.x / boxSize.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val ny = (offset.y / boxSize.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        laserPos = offset
                        client.setScreenStream(true, nx, ny, if (laserMode) null else "LEFT")
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(bitmap = bmp!!.asImageBitmap(), contentDescription = "Remote PC Screen", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            } else {
                Text("Waiting for live frames from Windows Server...", color = Color.Gray)
            }
            if (laserMode && laserPos != null) {
                Canvas(modifier = Modifier.fillMaxSize()) { drawCircle(color = Color.Red, radius = 14f, center = laserPos!!) }
            }
        }
    }
}
