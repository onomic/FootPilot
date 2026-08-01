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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

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
            .any { it.address == FootConfig.TARGET_ADDRESS }
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
    private var disconnectWarningText by mutableStateOf<String?>(null)
    private var bluetoothAvailable by mutableStateOf(false)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { bluetoothAvailable = canUseBluetooth() }

    private var pendingCheck = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        threshold = Prefs.threshold(this)
        polling = Prefs.polling(this)
        intervalMin = Prefs.intervalMin(this)
        pairingCode = Prefs.pairingCode(this)
        BatteryRepo.ensureInitialized(this)
        bluetoothAvailable = canUseBluetooth()
        if (!bluetoothAvailable) BatteryRepo.status.value = bluetoothUnavailableStatus()
        Alerts.ensureChannels(this)
        if (!LiveConnection.isMonitoringRequested()) {
            Alerts.cancelOngoing(this)
            if (polling) Alerts.updatePollStatus(this) else Alerts.cancelPollStatus(this)
        }

        handleIntent(intent)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
                val level by BatteryRepo.level.collectAsState()
                val status by BatteryRepo.status.collectAsState()
                val running by BatteryRepo.running.collectAsState()
                val snapshot by BatteryRepo.snapshot.collectAsState()
                val standbyStatus by BatteryRepo.standbyStatus.collectAsState()
                val connectionState by BatteryRepo.connectionState.collectAsState()
                val coordination by BleOperationCoordinator.state.collectAsState()
                val latestRunning by rememberUpdatedState(running)

                LaunchedEffect(Unit) {
                    while (true) {
                        val available = canUseBluetooth()
                        bluetoothAvailable = available
                        val nextStatus = bluetoothAvailabilityStatus(
                            currentStatus = BatteryRepo.status.value,
                            bluetoothAvailable = available,
                            monitoring = latestRunning,
                            unavailableStatus = bluetoothUnavailableStatus()
                        )
                        if (nextStatus != BatteryRepo.status.value) {
                            BatteryRepo.status.value = nextStatus
                        }
                        delay(1_000L)
                    }
                }

                disconnectWarningText?.let { warning ->
                    AlertDialog(
                        onDismissRequest = { disconnectWarningText = null },
                        title = { Text("Disconnect monitoring?") },
                        text = { Text(warning) },
                        confirmButton = {
                            TextButton(onClick = {
                                disconnectWarningText = null
                                LiveConnection.stop()
                            }) { Text("Disconnect") }
                        },
                        dismissButton = {
                            TextButton(onClick = { disconnectWarningText = null }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

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
                        connectionState = connectionState,
                        snapshot = snapshot,
                        standbyStatus = standbyStatus,
                        operation = coordination.visibleOperation,
                        bluetoothAvailable = bluetoothAvailable,
                        threshold = threshold,
                        onStart = ::startMonitoring,
                        onStop = ::requestStopMonitoring,
                        onCheck = ::checkNow,
                        onStandby = ::changeStandby,
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
        bluetoothAvailable = canUseBluetooth()
        if (!bluetoothAvailable) BatteryRepo.status.value = bluetoothUnavailableStatus()
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

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun canUseBluetooth(): Boolean = try {
        hasBluetoothPermission() &&
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true
    } catch (_: Exception) {
        false
    }

    private fun bluetoothUnavailableStatus(): String =
        if (hasBluetoothPermission()) "Turn on Bluetooth" else "Bluetooth permission is required"

    // ---- Actions ----
    private fun startMonitoring() {
        if (!hasBluetoothPermission()) {
            BatteryRepo.status.value = "Bluetooth permission is required"
            permLauncher.launch(neededPerms())
            return
        }
        if (!canUseBluetooth()) {
            bluetoothAvailable = false
            BatteryRepo.status.value = "Turn on Bluetooth"
            return
        }
        if (!hasAll()) {
            BatteryRepo.status.value = "Notification permission is required"
            permLauncher.launch(neededPerms())
            return
        }
        if (BleOperationCoordinator.isBusy()) return
        LiveConnection.start(this)
    }

    private fun requestStopMonitoring() {
        if (BleOperationCoordinator.isBusy()) return
        val warning = disconnectStandbyWarning(BatteryRepo.snapshot.value)
        if (warning != null) {
            disconnectWarningText = warning
        } else {
            LiveConnection.stop()
        }
    }

    private fun checkNow() {
        if (!hasAll()) { permLauncher.launch(neededPerms()); return }
        Alerts.ensureChannels(this)
        FootOperations.launchManualCheck(applicationContext)
    }

    private fun changeStandby(requested: StandbyState) {
        if (!hasAll()) { permLauncher.launch(neededPerms()); return }
        Alerts.ensureChannels(this)
        FootOperations.launchStandbyChange(applicationContext, requested)
    }
}

// ---------- Main screen ----------

@Composable
private fun MainScreen(
    level: Int?,
    status: String,
    running: Boolean,
    connectionState: LiveConnectionState,
    snapshot: SnapshotState,
    standbyStatus: String,
    operation: BleOperationKind?,
    bluetoothAvailable: Boolean,
    threshold: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCheck: () -> Unit,
    onStandby: (StandbyState) -> Unit,
    onSettings: () -> Unit
) {
    val accent = colorForLevel(level)
    val ready = connectionState == LiveConnectionState.READY
    val busy = operation != null
    val canUseConnection = !running || ready
    val display = SnapshotPresentation.create(snapshot)
    val presentation = MainScreenPresentation.create(
        activeOperationText = mainScreenOperationText(operation),
        verificationMessage = display.verificationMessage,
        standbyStatus = standbyStatus,
        generalStatus = status
    )
    val fontScale = LocalDensity.current.fontScale

    BoxWithConstraints(Modifier.fillMaxSize().background(Bg)) {
        val layout = mainScreenLayoutSpec(
            availableHeightDp = maxHeight.value,
            fontScale = fontScale
        )

        Column(
            Modifier.fillMaxSize().padding(
                horizontal = layout.horizontalPaddingDp.dp,
                vertical = layout.verticalPaddingDp.dp
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MainHeader(ready = ready, onSettings = onSettings)

            Spacer(Modifier.height(layout.headerToGaugeGapDp.dp))
            BatteryGauge(
                level = level,
                accent = accent,
                size = layout.gaugeSizeDp.dp,
                valueFontSizeSp = layout.gaugeValueFontSizeSp,
                percentFontSizeSp = layout.gaugePercentFontSizeSp
            )
            Spacer(Modifier.height(layout.gaugeToMetadataGapDp.dp))

            DeviceMetadataRow(threshold = threshold)

            Spacer(Modifier.weight(1f))

            StandbyCard(
                display = display,
                minHeight = layout.cardMinHeightDp.dp,
                enabled = bluetoothAvailable && !busy && canUseConnection &&
                    snapshot.standby != StandbyState.UNKNOWN,
                onChange = onStandby
            )
            Spacer(Modifier.height(layout.cardToStatusGapDp.dp))
            MainScreenStatusSlot(
                presentation = presentation,
                height = layout.statusSlotHeightDp.dp
            )
            Spacer(Modifier.height(layout.statusToActionsGapDp.dp))
            ContextualActionRow(
                accent = accent,
                running = running,
                busy = busy,
                bluetoothAvailable = bluetoothAvailable,
                canUseConnection = canUseConnection,
                gap = layout.actionGapDp.dp,
                onStart = onStart,
                onStop = onStop,
                onCheck = onCheck
            )
        }
    }
}

@Composable
private fun MainHeader(
    ready: Boolean,
    onSettings: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row {
                Text("Foot ", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text("Battery", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
            Text("BLE · 0x180F", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        StatusPill(ready)
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onSettings) { Text("\u2699", color = Muted, fontSize = 22.sp) }
    }
}

@Composable
private fun DeviceMetadataRow(threshold: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            FootConfig.TARGET_NAME,
            color = Ink,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "Alerts below $threshold%",
            color = Muted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MainScreenStatusSlot(
    presentation: MainScreenPresentation,
    height: Dp
) {
    Box(
        Modifier.fillMaxWidth().height(height),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = presentation.statusText,
            color = if (
                presentation.statusKind == MainScreenStatusKind.VERIFICATION_WARNING
            ) Warn else Muted,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ContextualActionRow(
    accent: Color,
    running: Boolean,
    busy: Boolean,
    bluetoothAvailable: Boolean,
    canUseConnection: Boolean,
    gap: Dp,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCheck: () -> Unit
) {
    val contextualAction = mainScreenContextualAction(running, busy, bluetoothAvailable)
    val contextualOnClick = when (contextualAction.type) {
        MainScreenContextualActionType.START -> onStart
        MainScreenContextualActionType.DISCONNECT -> onStop
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        OutlinedButton(
            onClick = onCheck,
            enabled = bluetoothAvailable && !busy && canUseConnection,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            border = BorderStroke(1.dp, accent),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = accent,
                disabledContentColor = Muted
            )
        ) { Text("Check now", fontWeight = FontWeight.Bold) }

        when (contextualAction.type) {
            MainScreenContextualActionType.START -> Button(
                onClick = contextualOnClick,
                enabled = contextualAction.enabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color(0xFF03140E),
                    disabledContainerColor = Panel,
                    disabledContentColor = Muted
                )
            ) { Text(contextualAction.label, fontWeight = FontWeight.Bold, maxLines = 1) }
            MainScreenContextualActionType.DISCONNECT -> OutlinedButton(
                onClick = contextualOnClick,
                enabled = contextualAction.enabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                border = BorderStroke(1.dp, Line),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Ink,
                    disabledContentColor = Muted
                )
            ) { Text(contextualAction.label, fontWeight = FontWeight.Bold, maxLines = 1) }
        }
    }
}

@Composable
private fun StandbyCard(
    display: SnapshotDisplayState,
    minHeight: Dp,
    enabled: Boolean,
    onChange: (StandbyState) -> Unit
) {
    val isOn = display.standby == StandbyState.ON
    val stateText = when {
        display.standbyAmbiguousAfterCommand -> "Not confirmed"
        display.standby == StandbyState.ON -> "On"
        display.standby == StandbyState.OFF -> "Off"
        else -> "Not checked"
    }
    val checkedText = display.checkedLine(
        display.lastChecked.takeIf { it > 0L }?.let {
            Alerts.clockTime(LocalContext.current, it)
        }
    )
    val borderColor = if (isOn) Warn else Line

    Row(
        Modifier.fillMaxWidth().heightIn(min = minHeight)
            .clip(RoundedCornerShape(14.dp)).background(Panel)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "STANDBY",
                color = if (isOn) Warn else Muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stateText,
                color = if (isOn) Warn else Ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                checkedText,
                color = Muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(12.dp))
        Switch(
            checked = isOn,
            enabled = enabled,
            onCheckedChange = { checked ->
                onChange(if (checked) StandbyState.ON else StandbyState.OFF)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF2A1900),
                checkedTrackColor = Warn,
                uncheckedThumbColor = Muted,
                uncheckedTrackColor = Panel,
                uncheckedBorderColor = Line,
                disabledCheckedTrackColor = Warn.copy(alpha = 0.45f),
                disabledUncheckedTrackColor = Panel
            )
        )
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
private fun BatteryGauge(
    level: Int?,
    accent: Color,
    size: Dp,
    valueFontSizeSp: Float,
    percentFontSizeSp: Float
) {
    val sweep by animateFloatAsState(
        targetValue = (level ?: 0).toFloat(), animationSpec = tween(900), label = "sweep"
    )
    val ringWidth = size * (16f / 248f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = ringWidth.toPx()
            val glow = base * 1.7f
            val inset = glow / 2f
            val arcSize = Size(this.size.width - glow, this.size.height - glow)
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
                fontSize = valueFontSizeSp.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "%",
                color = Muted,
                fontSize = percentFontSizeSp.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = (percentFontSizeSp * 0.55f).dp)
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
