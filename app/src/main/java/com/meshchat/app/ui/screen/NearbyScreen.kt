package com.meshchat.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshchat.app.ble.BleState
import com.meshchat.app.data.db.entity.NeighborEntity
import com.meshchat.app.domain.Conversation
import com.meshchat.app.domain.Peer
import com.meshchat.app.permission.PermissionHelper
import com.meshchat.app.runtime.MeshRuntimeStatus
import com.meshchat.app.ui.theme.*
import com.meshchat.app.ui.viewmodel.NearbyViewModel
import kotlinx.coroutines.launch

@Composable
fun NearbyScreen(vm: NearbyViewModel, onOpenChat: (Conversation) -> Unit) {
    val context = LocalContext.current
    val uiState by vm.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    // Re-check capability each time the screen is shown (permissions may have changed in Settings).
    val canAdvertise = remember { PermissionHelper.hasAdvertising(context) }
    val hasLocation  = remember { PermissionHelper.hasLocation(context) }
    val hasBleHw     = remember { PermissionHelper.hasBluetoothHardware(context) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        item {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "NEARBY",
                    fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = Primary,
                    modifier = Modifier.weight(1f)
                )
                StateBadge(uiState.runtimeStatus)
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick  = { vm.startScan() },
                    enabled  = uiState.runtimeStatus.bleState != BleState.SCANNING,
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                    border   = androidx.compose.foundation.BorderStroke(1.dp, Accent)
                ) {
                    Text("[SCAN]", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }

        // ── Capability warnings ───────────────────────────────────────────────
        if (!hasBleHw) {
            item {
                CapabilityBanner(
                    "[!] no bluetooth LE hardware — MeshChat cannot function on this device.",
                    error = true
                )
            }
        } else {
            if (!canAdvertise) {
                item {
                    CapabilityBanner(
                        "[~] advertising unavailable — nearby devices cannot discover you passively. " +
                        "You can still receive messages from connected peers."
                    )
                }
            }
            if (!hasLocation) {
                item {
                    CapabilityBanner(
                        "[~] location not granted — geo-routing disabled. " +
                        "Direct and relay delivery still work when nearby nodes can forward."
                    )
                }
            }
        }

        if (uiState.runtimeStatus.relayActive) {
            item {
                CapabilityBanner(
                    "[relay] forwarding ${uiState.runtimeStatus.relaySummary ?: "mesh traffic now"}"
                )
            }
        }

        item { HorizontalDivider(color = Divider) }

        // ── BLE scan results ──────────────────────────────────────────────────
        if (uiState.peers.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (uiState.runtimeStatus.bleState == BleState.SCANNING)
                            "scanning for MC_ devices..."
                        else
                            "no peers found. tap [SCAN] to search.",
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted
                    )
                }
            }
        } else {
            items(uiState.peers, key = { "peer_${it.displayName}" }) { peer ->
                PeerRow(
                    peer       = peer,
                    connecting = uiState.runtimeStatus.bleState == BleState.CONNECTING,
                    onClick    = {
                        scope.launch {
                            val conv = vm.connectAndGetConversation(peer)
                            if (conv != null) onOpenChat(conv)
                        }
                    }
                )
            }
        }

        // ── Neighbor table (beacon-discovered) ────────────────────────────────
        item {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "NEIGHBORS",
                    fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "(${uiState.neighbors.size})",
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted
                )
            }
            HorizontalDivider(color = Divider)
        }

        if (uiState.neighbors.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "no beacons received yet",
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted
                    )
                }
            }
        } else {
            items(uiState.neighbors, key = { "nbr_${it.neighborPublicKey}" }) { neighbor ->
                NeighborRow(neighbor)
            }
        }
    }
}

// ── List rows ─────────────────────────────────────────────────────────────────

@Composable
private fun CapabilityBanner(message: String, error: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (error) ErrorColor.copy(alpha = 0.12f) else WarningColor.copy(alpha = 0.10f))
            .padding(10.dp)
    ) {
        Text(
            message,
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            color = if (error) ErrorColor else WarningColor,
            lineHeight = 16.sp
        )
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
            val routeHint = if (peer.bleId == null) "  via relay" else ""
            Text(peer.displayName, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Primary)
            Text(
                "id:${peer.deviceId.take(8)}  ${formatLastSeen(peer.lastSeen)}$routeHint",
                fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextMuted
            )
        }
        peer.rssi?.let { rssi ->
            Text(rssiBar(rssi), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Accent)
        }
    }
}

@Composable
private fun NeighborRow(neighbor: NeighborEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(neighbor.displayName, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Primary)
                if (neighbor.relayCapable) {
                    Spacer(Modifier.width(6.dp))
                    Text("[relay]", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Accent)
                }
            }
            val geoStr = neighbor.geohash?.let { "geo:$it" } ?: "geo:none"
            Text(
                "id:${neighbor.neighborPublicKey.take(8)}  ${formatLastSeen(neighbor.lastSeen)}  $geoStr",
                fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextMuted
            )
        }
        neighbor.rssi?.let { rssi ->
            Text(rssiBar(rssi), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Accent)
        }
    }
}

// ── State badge ───────────────────────────────────────────────────────────────

@Composable
private fun StateBadge(status: MeshRuntimeStatus) {
    val (text, color) = when {
        status.relayActive                      -> "RELAYING"   to Accent
        !status.meshActive                     -> "STOPPED"    to TextMuted
        status.bleState == BleState.SCANNING   -> "SCANNING"   to Accent
        status.bleState == BleState.CONNECTING -> "CONNECTING" to WarningColor
        status.bleState == BleState.CONNECTED  -> "CONNECTED"  to Primary
        status.bleState == BleState.ERROR      -> "ERROR"      to ErrorColor
        else                                   -> "ACTIVE"     to Primary
    }
    Text("[$text]", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = color)
}

// ── Utilities ─────────────────────────────────────────────────────────────────

private fun rssiBar(rssi: Int): String {
    val level = when {
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else        -> 0
    }
    return "${"█".repeat(level)}${"░".repeat(4 - level)} ${rssi}dBm"
}

private fun formatLastSeen(ts: Long): String {
    val delta = (System.currentTimeMillis() - ts) / 1000
    return when {
        delta < 5    -> "now"
        delta < 60   -> "${delta}s ago"
        delta < 3600 -> "${delta / 60}m ago"
        else         -> "${delta / 3600}h ago"
    }
}
