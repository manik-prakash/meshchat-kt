package com.meshchat.app.ui.screen

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshchat.app.ble.BleState
import com.meshchat.app.domain.Conversation
import com.meshchat.app.domain.Peer
import com.meshchat.app.ui.theme.*
import com.meshchat.app.ui.viewmodel.NearbyViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun NearbyScreen(vm: NearbyViewModel, onOpenChat: (Conversation) -> Unit) {
    val uiState by vm.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) vm.startScan()
    }

    LaunchedEffect(Unit) {
        val perms = buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permLauncher.launch(perms.toTypedArray())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("NEARBY", fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = Primary, modifier = Modifier.weight(1f))
            StateBadge(uiState.bleState)
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { vm.startScan() },
                enabled = uiState.bleState != BleState.SCANNING,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                border = androidx.compose.foundation.BorderStroke(1.dp, Accent)
            ) {
                Text("[SCAN]", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        HorizontalDivider(color = Divider)
        Spacer(Modifier.height(8.dp))

        if (uiState.peers.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (uiState.bleState == BleState.SCANNING) "scanning for MC_ devices..."
                    else "no peers found. tap [SCAN] to search.",
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uiState.peers, key = { it.displayName }) { peer ->
                    PeerRow(
                        peer = peer,
                        connecting = uiState.bleState == BleState.CONNECTING,
                        onClick = {
                            scope.launch {
                                val conv = vm.connectAndGetConversation(peer)
                                if (conv != null) onOpenChat(conv)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PeerRow(peer: Peer, connecting: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .clickable(enabled = !connecting) { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(peer.displayName, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Primary)
            Text(
                "id:${peer.deviceId.take(8)}  ${formatLastSeen(peer.lastSeen)}",
                fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextMuted
            )
        }
        peer.rssi?.let { rssi ->
            Text(rssiBar(rssi), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Accent)
        }
    }
}

@Composable
private fun StateBadge(state: BleState) {
    val (text, color) = when (state) {
        BleState.IDLE       -> "IDLE"       to TextMuted
        BleState.SCANNING   -> "SCANNING"   to Accent
        BleState.CONNECTING -> "CONNECTING" to WarningColor
        BleState.CONNECTED  -> "CONNECTED"  to Primary
        BleState.ERROR      -> "ERROR"      to ErrorColor
    }
    Text("[$text]", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = color)
}

private fun rssiBar(rssi: Int): String {
    val level = when {
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else        -> 0
    }
    val filled = "█".repeat(level)
    val empty  = "░".repeat(4 - level)
    return "$filled$empty ${rssi}dBm"
}

private fun formatLastSeen(ts: Long): String {
    val delta = (System.currentTimeMillis() - ts) / 1000
    return when {
        delta < 5  -> "now"
        delta < 60 -> "${delta}s ago"
        delta < 3600 -> "${delta / 60}m ago"
        else       -> "${delta / 3600}h ago"
    }
}
