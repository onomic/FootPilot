package com.example.footbattery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Bg = Color(0xFF0A0E0D)
private val Panel = Color(0xFF121817)
private val Line = Color(0xFF24302E)
private val Ink = Color(0xFFE8EFED)
private val Muted = Color(0xFF7C8D89)
private val Accent = Color(0xFF34E0A1)
private val Warn = Color(0xFFF5B94A)
private val Crit = Color(0xFFF0604D)
private val RingTrack = Color(0xFF1A2422)

private val INTERVALS = listOf(15 to "15m", 30 to "30m", 60 to "1h", 120 to "2h")

private fun colorForLevel(level: Int?): Color = when {
    level == null -> Muted
    level <= 15 -> Crit
    level <= 35 -> Warn
    else -> Accent
}

/** Reports the foot's actual system-level GATT connection state, for diagnostics. */
@SuppressLint("MissingPermission")
private fun systemLinkLabel(ctx: Context): String {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) return ""
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val linked = bm.getConnectedDevices(BluetoothProfile.GATT)
            .any { it.address == BatteryService.TARGET_ADDRESS }
        val n = BleRegistry.count()
        (if (linked) "System: foot CONNECTED" else "System: foot not connected") + "  ·  app clients: $n"
    } catch (e: Exception) { "" }
}

class MainActivity : ComponentActivity() {

    private var threshold by mutableStateOf(25)
    private var polling by mutableStateOf(false)
    private var intervalMin by mutableStateOf(60)
    private var pairingCode by mutableStateOf("")
    private var showSettings by mutableStateOf(false)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* user grants; they tap again */ }

    private var pendingCheck = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        threshold = Prefs.threshold(this)
        polling = Prefs.polling(this)
        intervalMin = Prefs.intervalMin(this)
        pairingCode = Prefs.pairingCode(this)

        handleIntent(intent)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
                val level by BatteryRepo.level.collectAsState()
                val status by BatteryRepo.status.collectAsState()
                val running by BatteryRepo.running.collectAsState()

                if (showSettings) {
                    SettingsScreen(
                        threshold = threshold,
                        polling = polling,
                        intervalMin = intervalMin,
                        pairingCode = pairingCode,
                        onThreshold = { applyThreshold(it) },
                        onPolling = { applyPolling(it) },
                        onInterval = { applyInterval(it) },
                        onPairingCode = { applyPairingCode(it) },
                        onBack = { showSettings = false }
                    )
                } else {
                    MainScreen(
                        level = level, status = status, running = running,
                        threshold = threshold,
                        onStart = ::startMonitoring,
                        onStop = ::stopMonitoring,
                        onCheck = ::checkNow,
                        onSettings = { showSettings = true }
                    )
                }
            }
        }
    }

    // ---- Settings changes ----
    private fun applyThreshold(v: Int) {
        threshold = v.coerceIn(5, 50)
        Prefs.setThreshold(this, threshold)
    }

    private fun applyPolling(on: Boolean) {
        polling = on
        Prefs.setPolling(this, on)
        if (on && !hasAll()) permLauncher.launch(neededPerms())
        PollScheduler.apply(this, on, intervalMin)
        if (on) {
            Alerts.ensureChannels(this)
            // Show the status notification immediately (battery may be "—" until first check).
            if (!BatteryRepo.running.value) Alerts.updatePollStatus(this)
        } else {
            Alerts.cancelPollStatus(this)
        }
    }

    private fun applyInterval(min: Int) {
        intervalMin = min
        Prefs.setIntervalMin(this, min)
        if (polling) PollScheduler.apply(this, true, min)
    }

    private fun applyPairingCode(code: String) {
        // The foot uses a numeric PIN — keep digits only, cap at 8 (covers 4/6-digit PINs).
        val clean = code.filter { it.isDigit() }.take(8)
        pairingCode = clean
        Prefs.setPairingCode(this, clean)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Marks a Check-now request; the actual read runs in onResume when the UI is live. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Alerts.ACTION_CHECK_NOW) {
            pendingCheck = true
            // Clear the action so it doesn't re-fire on rotation/recreate.
            intent.action = null
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingCheck) {
            pendingCheck = false
            checkNow()
        }
    }

    // ---- Permissions ----
    private fun neededPerms(): Array<String> {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            p += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            p += Manifest.permission.POST_NOTIFICATIONS
        return p.toTypedArray()
    }

    private fun hasAll() = neededPerms().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    // ---- Actions ----
    private fun startMonitoring() {
        if (!hasAll()) { permLauncher.launch(neededPerms()); return }
        LiveConnection.start(this)
    }

    private fun stopMonitoring() {
        LiveConnection.stop()
    }

    private fun checkNow() {
        if (!hasAll()) { permLauncher.launch(neededPerms()); return }
        // If a live connection is up, just re-read on it.
        if (LiveConnection.isConnected()) {
            BatteryRepo.status.value = "Checking…"
            LiveConnection.refresh()
            return
        }
        // Ignore taps while a check is already running.
        if (BleReader.isBusy()) {
            BatteryRepo.status.value = "Checking…"
            return
        }
        BatteryRepo.status.value = "Checking…"
        lifecycleScope.launch {
            Alerts.ensureChannels(this@MainActivity)
            val pct = BleReader.readOnce(applicationContext)
            if (pct != null) {
                BatteryRepo.level.value = pct
                BatteryRepo.status.value = "Checked"
                Alerts.recordReading(applicationContext, pct)
            } else {
                BatteryRepo.status.value = "Check failed — is the foot in range?"
            }
        }
    }
}

// ---------- Main screen ----------

@Composable
private fun MainScreen(
    level: Int?, status: String, running: Boolean, threshold: Int,
    onStart: () -> Unit, onStop: () -> Unit, onCheck: () -> Unit, onSettings: () -> Unit
) {
    val accent = colorForLevel(level)
    Box(Modifier.fillMaxSize().background(Bg).padding(horizontal = 24.dp, vertical = 28.dp)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row {
                        Text("Foot ", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Black)
                        Text("Battery", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    }
                    Text("BLE · 0x180F", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                StatusPill(running && level != null)
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onSettings) { Text("\u2699", color = Muted, fontSize = 22.sp) }
            }

            Spacer(Modifier.height(32.dp))
            BatteryGauge(level, accent)
            Spacer(Modifier.height(24.dp))

            Text(BatteryService.TARGET_NAME, color = Ink, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            Text("Alerts below $threshold%", color = Muted, fontSize = 12.sp)

            Spacer(Modifier.weight(1f))

            OutlinedButton(
                onClick = onCheck, modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, accent),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)
            ) { Text("Check now", fontWeight = FontWeight.Bold) }

            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onStart, enabled = !running, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent, contentColor = Color(0xFF03140E),
                        disabledContainerColor = Panel, disabledContentColor = Muted
                    )
                ) { Text(if (running) "Monitoring" else "Start", fontWeight = FontWeight.Bold) }

                OutlinedButton(
                    onClick = onStop, enabled = running, modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, Line),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink, disabledContentColor = Muted)
                ) { Text("Disconnect", fontWeight = FontWeight.Bold) }
            }

            Spacer(Modifier.height(10.dp))
            Text(status, color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun StatusPill(live: Boolean) {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val alpha by infinite.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot"
    )
    val dotColor = if (live) Accent else Muted
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(8.dp).clip(CircleShape)
                .background(dotColor.copy(alpha = if (live) alpha else 1f))
        )
        Spacer(Modifier.width(6.dp))
        Text(if (live) "LIVE" else "IDLE", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun BatteryGauge(level: Int?, accent: Color) {
    val sweep by animateFloatAsState(
        targetValue = (level ?: 0).toFloat(), animationSpec = tween(900), label = "sweep"
    )
    Box(Modifier.size(248.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = 16.dp.toPx()
            val glow = base * 1.7f
            val inset = glow / 2f
            val arcSize = Size(size.width - glow, size.height - glow)
            val topLeft = Offset(inset, inset)
            val sweepAngle = 360f * (sweep / 100f)

            drawArc(
                color = RingTrack, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(width = base)
            )
            if (sweepAngle > 0f) {
                drawArc(
                    color = accent.copy(alpha = 0.22f), startAngle = -90f, sweepAngle = sweepAngle,
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = glow, cap = StrokeCap.Round)
                )
                drawArc(
                    color = accent, startAngle = -90f, sweepAngle = sweepAngle, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(width = base, cap = StrokeCap.Round)
                )
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = level?.toString() ?: "\u2014",
                color = if (level == null) Muted else Ink,
                fontSize = 68.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
            )
            Text(
                "%", color = Muted, fontSize = 22.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

// ---------- Settings screen ----------

@Composable
private fun SettingsScreen(
    threshold: Int, polling: Boolean, intervalMin: Int, pairingCode: String,
    onThreshold: (Int) -> Unit, onPolling: (Boolean) -> Unit, onInterval: (Int) -> Unit,
    onPairingCode: (String) -> Unit,
    onBack: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Bg).padding(horizontal = 24.dp, vertical = 28.dp)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Text("\u2190", color = Ink, fontSize = 24.sp) }
                Spacer(Modifier.width(4.dp))
                Text("Settings", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }

            Spacer(Modifier.height(24.dp))

            Text("ALERT THRESHOLD", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel)
                    .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(16.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    StepButton("\u2212") { onThreshold(threshold - 5) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$threshold%", color = Ink, fontSize = 30.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text("alert when below this", color = Muted, fontSize = 11.sp)
                    }
                    StepButton("+") { onThreshold(threshold + 5) }
                }
            }

            Spacer(Modifier.height(28.dp))

            Text("PAIRING CODE", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = pairingCode,
                onValueChange = onPairingCode,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("6-digit PIN", color = Muted) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Ink, unfocusedTextColor = Ink,
                    focusedBorderColor = Accent, unfocusedBorderColor = Line,
                    cursorColor = Accent,
                    focusedContainerColor = Panel, unfocusedContainerColor = Panel
                )
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Saved and entered automatically when the foot asks to pair, so you don't have to type it on each reconnect. Leave blank if no code is needed.",
                color = Muted, fontSize = 12.sp
            )

            Spacer(Modifier.height(28.dp))

            Text("BACKGROUND POLLING", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel)
                    .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Check in background", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Briefly connects on a schedule, then disconnects.",
                            color = Muted, fontSize = 12.sp)
                    }
                    Switch(
                        checked = polling, onCheckedChange = onPolling,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF03140E),
                            checkedTrackColor = Accent,
                            uncheckedThumbColor = Muted,
                            uncheckedTrackColor = Panel,
                            uncheckedBorderColor = Line
                        )
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text("CHECK EVERY", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                INTERVALS.forEach { (min, label) ->
                    val selected = intervalMin == min && polling
                    val chipMod = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Accent else Panel)
                        .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(12.dp))
                        .let { if (polling) it.clickable { onInterval(min) } else it }
                        .padding(vertical = 14.dp)
                    Box(chipMod, contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            color = when {
                                !polling -> Muted.copy(alpha = 0.5f)
                                selected -> Color(0xFF03140E)
                                else -> Ink
                            },
                            fontSize = 14.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                "Tip: for reliable background alerts, set this app's battery usage to Unrestricted in Android settings.",
                color = Muted, fontSize = 12.sp
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
            .background(Bg).border(1.dp, Line, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Accent, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
}
