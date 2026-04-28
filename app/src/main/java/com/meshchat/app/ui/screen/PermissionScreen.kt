package com.meshchat.app.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshchat.app.permission.PermissionHelper
import com.meshchat.app.ui.theme.*
import com.meshchat.app.ui.viewmodel.MeshCapability
import com.meshchat.app.ui.viewmodel.PermissionViewModel

private enum class PermStep { BLE, LOCATION, NOTIFY, BATTERY, DONE }

@Composable
fun PermissionScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val vm: PermissionViewModel = viewModel()

    LaunchedEffect(Unit) { vm.refresh(context) }
    val capability by vm.capability.collectAsState()

    var step by remember { mutableStateOf(PermStep.BLE) }

    // Build the combined BLE permission array once
    val blePerms = remember {
        buildList {
            addAll(PermissionHelper.coreBlePermissions())
            PermissionHelper.advertisingPermission()?.let { add(it) }
        }.toTypedArray()
    }

    val bleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refresh(context); step = PermStep.LOCATION }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.refresh(context); step = stepAfterLocation() }

    val notifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.refresh(context); step = PermStep.BATTERY }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("MESHCHAT", fontFamily = FontFamily.Monospace, fontSize = 20.sp, color = Primary)
        Text("setup", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted)
        Spacer(Modifier.height(32.dp))

        when (step) {
            PermStep.BLE -> BleStep(
                hasBleHardware = capability.hasHardware,
                onRequest      = { bleLauncher.launch(blePerms) },
                onSkip         = { step = PermStep.LOCATION }
            )
            PermStep.LOCATION -> LocationStep(
                onRequest = { locationLauncher.launch(PermissionHelper.LOCATION) },
                onSkip    = { step = stepAfterLocation() }
            )
            PermStep.NOTIFY -> NotifyStep(
                onRequest = {
                    PermissionHelper.notificationPermission()
                        ?.let { notifyLauncher.launch(it) }
                        ?: run { step = PermStep.BATTERY }
                },
                onSkip = { step = PermStep.BATTERY }
            )
            PermStep.BATTERY -> BatteryStep(
                onContinue = { step = PermStep.DONE }
            )
            PermStep.DONE -> DoneStep(
                capability  = capability,
                onContinue  = onDone
            )
        }
    }
}

private fun stepAfterLocation(): PermStep =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) PermStep.NOTIFY else PermStep.BATTERY

// ── Steps ─────────────────────────────────────────────────────────────────────

@Composable
private fun BleStep(hasBleHardware: Boolean, onRequest: () -> Unit, onSkip: () -> Unit) {
    StepHeader("[1/4]", "bluetooth")
    Spacer(Modifier.height(16.dp))

    if (!hasBleHardware) {
        Text(
            "[!] this device has no Bluetooth LE hardware. MeshChat cannot function.",
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = ErrorColor
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton("[CONTINUE ANYWAY]", onSkip)
        return
    }

    StepBody(
        "MeshChat forms a local radio mesh over Bluetooth LE.\n\n" +
        "  scan      — discover nearby peers\n" +
        "  connect   — exchange messages directly\n" +
        "  advertise — let others find you passively"
    )
    Spacer(Modifier.height(24.dp))
    PrimaryButton("[GRANT BLUETOOTH ACCESS]", onRequest)
    Spacer(Modifier.height(8.dp))
    SkipButton("[skip — receiving only]", onSkip)
}

@Composable
private fun LocationStep(onRequest: () -> Unit, onSkip: () -> Unit) {
    StepHeader("[2/4]", "location")
    Spacer(Modifier.height(16.dp))
    StepBody(
        "Fine location lets MeshChat embed a geohash in your beacon " +
        "so messages can be routed toward you across multiple hops.\n\n" +
        "Without it: messages still deliver when devices are close, " +
        "but multi-hop geo-routing is disabled."
    )
    Spacer(Modifier.height(24.dp))
    PrimaryButton("[GRANT LOCATION]", onRequest)
    Spacer(Modifier.height(8.dp))
    SkipButton("[skip — direct-only routing]", onSkip)
}

@Composable
private fun NotifyStep(onRequest: () -> Unit, onSkip: () -> Unit) {
    StepHeader("[3/4]", "notifications")
    Spacer(Modifier.height(16.dp))
    StepBody(
        "Allow notifications so MeshChat can alert you to new messages " +
        "when the app is in the background."
    )
    Spacer(Modifier.height(24.dp))
    PrimaryButton("[GRANT NOTIFICATIONS]", onRequest)
    Spacer(Modifier.height(8.dp))
    SkipButton("[skip]", onSkip)
}

@Composable
private fun BatteryStep(onContinue: () -> Unit) {
    val context = LocalContext.current
    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    val alreadyExempt = pm.isIgnoringBatteryOptimizations(context.packageName)

    StepHeader("[4/4]", "battery")
    Spacer(Modifier.height(16.dp))

    if (alreadyExempt) {
        StepBody("Battery optimization is already disabled for MeshChat. You're all set.")
    } else {
        StepBody(
            "Android may suspend the relay service to conserve battery.\n\n" +
            "If you plan to use persistent relay, disable battery optimization " +
            "for MeshChat. You can also do this later in Settings > Apps."
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${context.packageName}"))
                )
            },
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
            border   = androidx.compose.foundation.BorderStroke(1.dp, Accent)
        ) {
            Text("[OPEN BATTERY SETTINGS]", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
    }
    PrimaryButton("[CONTINUE]", onContinue)
}

@Composable
private fun DoneStep(capability: MeshCapability, onContinue: () -> Unit) {
    Text("ready", fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = Primary)
    Spacer(Modifier.height(20.dp))

    CapRow("bluetooth scan/connect", capability.canScan)
    CapRow("bluetooth advertise",    capability.canAdvertise)
    CapRow("location / geo-routing", capability.hasLocation)
    CapRow("notifications",          capability.hasNotifications)

    Spacer(Modifier.height(16.dp))

    when {
        capability.meshUnavailable -> WarningBox(
            "[!] bluetooth access is required for MeshChat to work.\n" +
            "Go to App Settings and grant Bluetooth permissions, then re-open the app.",
            error = true
        )
        capability.advertisingDegraded -> WarningBox(
            "[~] advertising unavailable — you can receive messages but " +
            "other devices cannot discover you passively via scan."
        )
        !capability.hasLocation -> WarningBox(
            "[~] location not granted — messages route by flood instead of geohash. " +
            "Multi-hop delivery still works but is less efficient."
        )
    }

    Spacer(Modifier.height(24.dp))
    PrimaryButton("[ENTER MESHCHAT]", onContinue)
}

// ── Shared components ─────────────────────────────────────────────────────────

@Composable
private fun CapRow(label: String, granted: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (granted) "[+]" else "[-]",
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            color = if (granted) Primary else TextMuted
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            color = if (granted) TextPrimary else TextMuted
        )
    }
}

@Composable
private fun WarningBox(text: String, error: Boolean = false) {
    Spacer(Modifier.height(4.dp))
    Text(
        text,
        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        color = if (error) ErrorColor else WarningColor,
        lineHeight = 16.sp
    )
}

@Composable
private fun StepHeader(step: String, title: String) {
    Text("$step $title", fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = Primary)
}

@Composable
private fun StepBody(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted, lineHeight = 18.sp)
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick  = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors   = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Background)
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

@Composable
private fun SkipButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted)
    }
}
