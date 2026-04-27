package com.meshchat.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshchat.app.ui.theme.*
import com.meshchat.app.ui.viewmodel.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val identity by vm.identity.collectAsState()
    val saved    by vm.saved.collectAsState()

    var nameInput by remember(identity?.displayName) { mutableStateOf(identity?.displayName ?: "") }

    LaunchedEffect(saved) {
        if (saved) {
            kotlinx.coroutines.delay(1500)
            vm.clearSaved()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text("SETTINGS", fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = Primary, modifier = Modifier.padding(vertical = 12.dp))
        HorizontalDivider(color = Divider)
        Spacer(Modifier.height(16.dp))

        identity?.let { id ->
            SettingsRow("fingerprint", id.publicKeyFingerprint.ifEmpty { "generating..." })
            SettingsRow("created",   SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(id.createdAt)))
            Spacer(Modifier.height(20.dp))

            Text("> display name:", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Accent)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = nameInput,
                onValueChange = { if (it.length <= 24) nameInput = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.saveDisplayName(nameInput) }),
                placeholder = { Text(id.displayName, fontFamily = FontFamily.Monospace, color = TextMuted) },
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
                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Background),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (saved) "[SAVED]" else "[SAVE]", fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(32.dp))
        HorizontalDivider(color = Divider)
        Spacer(Modifier.height(16.dp))

        Text("app info", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted)
        Spacer(Modifier.height(8.dp))
        SettingsRow("version",   "0.1.0-alpha")
        SettingsRow("protocol",  "BLE direct")
        SettingsRow("storage",   "Room/SQLite")
        SettingsRow("identity",  "EC P-256 / Keystore")
    }
}

@Composable
private fun SettingsRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("$key: ", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted, modifier = Modifier.width(100.dp))
        Text(value,   fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextPrimary)
    }
}
