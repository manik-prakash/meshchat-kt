package com.meshchat.app.ui.screen

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshchat.app.permission.PermissionHelper
import com.meshchat.app.ui.theme.*
import com.meshchat.app.ui.viewmodel.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val context = LocalContext.current
    val identity by vm.identity.collectAsState()
    val saved by vm.saved.collectAsState()
    val runtimeStatus by vm.runtimeStatus.collectAsState()
    val pendingRelayEnable by vm.pendingRelayEnable.collectAsState()

    var nameInput by remember(identity?.displayName) { mutableStateOf(identity?.displayName ?: "") }

    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.onBgLocationResult(granted) }

    LaunchedEffect(saved) {
        if (saved) {
            kotlinx.coroutines.delay(1500)
            vm.clearSaved()
        }
    }

    // Background location rationale dialog
    if (pendingRelayEnable) {
        AlertDialog(
            onDismissRequest = { vm.dismissBgLocationPrompt() },
            containerColor   = Surface,
            title = {
                Text("background location", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Primary)
            },
            text = {
                Text(
                    "Persistent relay keeps the mesh service alive when the app is closed. " +
                    "Android requires background location permission for this.\n\n" +
                    "Without it, relay runs only while the app is in the foreground.",
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }) {
                    Text("[GRANT]", fontFamily = FontFamily.Monospace, color = Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.dismissBgLocationPrompt()
                    vm.onBgLocationResult(false)
                }) {
                    Text("[SKIP — FOREGROUND ONLY]", fontFamily = FontFamily.Monospace, color = TextMuted)
                }
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            "SETTINGS",
            fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = Primary,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        HorizontalDivider(color = Divider)
        Spacer(Modifier.height(16.dp))

        identity?.let { id ->
            SettingsRow("fingerprint", id.publicKeyFingerprint.ifEmpty { "generating..." })
            SettingsRow("created", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(id.createdAt)))
            Spacer(Modifier.height(20.dp))

            Text("> display name:", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Accent)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value          = nameInput,
                onValueChange  = { if (it.length <= 24) nameInput = it },
                singleLine     = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.saveDisplayName(nameInput) }),
                placeholder    = { Text(id.displayName, fontFamily = FontFamily.Monospace, color = TextMuted) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = Primary,
                    unfocusedBorderColor = TextMuted,
                    focusedTextColor     = TextPrimary,
                    unfocusedTextColor   = TextPrimary,
                    cursorColor          = Primary,
                ),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text("${nameInput.length}/24", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextMuted)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.saveDisplayName(nameInput) },
                colors  = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Background),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (saved) "[SAVED]" else "[SAVE]", fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = Divider)
            Spacer(Modifier.height(16.dp))

            // Persistent mesh toggle with background location gate
            val hasBgLocation = PermissionHelper.hasBackgroundLocation(context)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("persistent mesh", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Accent)
                    Text(
                        "keep the relay service running across app closes and reboots",
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextMuted
                    )
                    if (runtimeStatus.persistentMeshEnabled && !hasBgLocation) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "[~] foreground only — background location not granted",
                            fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = WarningColor
                        )
                    }
                }
                Switch(
                    checked = runtimeStatus.persistentMeshEnabled,
                    onCheckedChange = { enabled ->
                        vm.setPersistentMeshEnabled(enabled, hasBgLocation)
                    }
                )
            }

            Spacer(Modifier.height(16.dp))
            SettingsRow("mesh",      if (runtimeStatus.meshActive) "active" else "stopped")
            SettingsRow("neighbors", runtimeStatus.neighborCount.toString())
            SettingsRow("queued",    runtimeStatus.queuedPacketCount.toString())
            SettingsRow("route",     runtimeStatus.lastRoutingStatus)
        }

        Spacer(Modifier.height(32.dp))
        HorizontalDivider(color = Divider)
        Spacer(Modifier.height(16.dp))

        Text("app info", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted)
        Spacer(Modifier.height(8.dp))
        SettingsRow("version",  "0.1.0-alpha")
        SettingsRow("protocol", "BLE mesh relay")
        SettingsRow("storage",  "Room/SQLite")
        SettingsRow("identity", "EC P-256 / Keystore")
        SettingsRow("min sdk",  "API 29 (Android 10+)")
    }
}

@Composable
private fun SettingsRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            "$key: ",
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted,
            modifier = Modifier.width(100.dp)
        )
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextPrimary)
    }
}
