package com.meshchat.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshchat.app.ui.theme.*
import com.meshchat.app.ui.viewmodel.OnboardingViewModel
import kotlinx.coroutines.delay

private val BOOT_LINES = listOf(
    "MeshChat v0.1.0-alpha",
    "protocol  : BLE direct",
    "storage   : Room/SQLite",
    "encryption: planned v0.2",
    "──────────────────────────",
    "initializing identity...",
)

@Composable
fun OnboardingScreen(vm: OnboardingViewModel, onDone: () -> Unit) {
    val identity by vm.identity.collectAsState()
    val done     by vm.done.collectAsState()

    var visibleLines by remember { mutableIntStateOf(0) }
    var nameInput    by remember { mutableStateOf("") }
    var bootFinished by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        for (i in BOOT_LINES.indices) {
            delay(120)
            visibleLines = i + 1
        }
        bootFinished = true
    }

    LaunchedEffect(done) {
        if (done) onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        BOOT_LINES.take(visibleLines).forEach { line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Primary)
        }

        if (bootFinished && identity != null) {
            Spacer(Modifier.height(24.dp))
            Text("device_id : ${identity!!.deviceId.take(8)}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted)
            Spacer(Modifier.height(16.dp))
            Text("> enter display name (max 24):", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Accent)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = nameInput,
                onValueChange = { if (it.length <= 24) nameInput = it },
                placeholder = { Text(identity!!.displayName, fontFamily = FontFamily.Monospace, color = TextMuted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.confirmName(nameInput.ifBlank { identity!!.displayName }) }),
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
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.confirmName(nameInput.ifBlank { identity!!.displayName }) },
                colors  = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Background),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("[BOOT]", fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
        }
    }
}
