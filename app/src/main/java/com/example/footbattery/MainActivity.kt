package com.example.footbattery

import android.Manifest
import android.bluetooth.BluetoothManager
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
import androidx.compose.foundation.Image
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Bg = Color(0xFF0A0E0D)
private val Panel = Color(0xFF121817)
private val Line = Color(0xFF24302E)
private val Ink = Color(0xFFE8EFED)
private val Muted = Color(0xFF7C8D89)
private val Warn = Color(0xFFF5B94A)
private val Crit = Color(0xFFF0604D)
private val RingTrack = Color(0xFF1A2422)

private val INTERVALS = listOf(15 to "15m", 30 to "30m", 60 to "1h", 120 to "2h")

private fun colorForLevel(level: Int?, normal: Color): Color = when {
    level == null -> Muted
    level <= 15 -> Crit
    level <= 35 -> Warn
    else -> normal
}

class MainActivity : ComponentActivity() {

    private var threshold by mutableStateOf(25)
    private var polling by mutableStateOf(false)
    private var intervalMin by mutableStateOf(60)
    private var pairingCode by mutableStateOf("")
    private var footNameInput by mutableStateOf("")
    private var footSetupFeedback by mutableStateOf<FootSetupFeedback>(FootSetupFeedback.Idle)
    private var showSettings by mutableStateOf(false)
    private var disconnectWarningText by mutableStateOf<String?>(null)
    private var bluetoothAvailable by mutableStateOf(false)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { bluetoothAvailable = canUseBluetooth() }

    private var pendingFootSearchName: String? = null
    private var footSearchJob: Job? = null
    private var footSearchGeneration = 0
    private val footScanner by lazy { AndroidFootScanner(applicationContext) }
    private val footVerifier by lazy { FootCandidateVerifier(applicationContext) }

    private val findPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val name = pendingFootSearchName
        pendingFootSearchName = null
        if (name != null && hasFindPermissions()) {
            beginFootSearch(name)
        } else if (name != null) {
            footSetupFeedback = FootSetupFeedback.Error(
                "Permission is required to find a foot."
            )
        }
    }

    private var pendingCheck = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SelectedFootRepository.ensureInitialized(this)
        threshold = Prefs.threshold(this)
        polling = Prefs.polling(this)
        intervalMin = Prefs.intervalMin(this)
        pairingCode = Prefs.pairingCode(this)
        footNameInput = SelectedFootRepository.current(this)?.name.orEmpty()
        BatteryRepo.ensureInitialized(this)
        AnkleRepo.ensureInitialized(this)
        PresetRepository.ensureInitialized(this)
        bluetoothAvailable = canUseBluetooth()
        if (SelectedFootRepository.current(this) == null) {
            BatteryRepo.status.value = "Add a foot in Settings"
        } else if (!bluetoothAvailable) {
            BatteryRepo.status.value = bluetoothUnavailableStatus()
        }
        Alerts.ensureChannels(this)
        if (!LiveConnection.isMonitoringRequested()) {
            Alerts.cancelOngoing(this)
            if (polling) Alerts.updatePollStatus(this) else Alerts.cancelPollStatus(this)
        }

        handleIntent(intent)

        setContent {
            val footBatteryGreen = colorResource(R.color.footbattery_green_app)
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = footBatteryGreen,
                    background = Bg,
                    surface = Bg
                )
            ) {
                val level by BatteryRepo.level.collectAsState()
                val status by BatteryRepo.status.collectAsState()
                val running by BatteryRepo.running.collectAsState()
                val snapshot by BatteryRepo.snapshot.collectAsState()
                val standbyStatus by BatteryRepo.standbyStatus.collectAsState()
                val connectionState by BatteryRepo.connectionState.collectAsState()
                val coordination by BleOperationCoordinator.state.collectAsState()
                val ankleState by AnkleRepo.state.collectAsState()
                val presetState by PresetRepository.state.collectAsState()
                val selectedFoot by SelectedFootRepository.selected.collectAsState()
                val latestRunning by rememberUpdatedState(running)

                LaunchedEffect(ankleState.message) {
                    val message = ankleState.message ?: return@LaunchedEffect
                    delay(8_000L)
                    if (AnkleRepo.state.value.message == message &&
                        AnkleRepo.state.value.operation == AnkleOperation.IDLE
                    ) {
                        AnkleRepo.clearMessage()
                    }
                }

                LaunchedEffect(Unit) {
                    while (true) {
                        val available = canUseBluetooth()
                        bluetoothAvailable = available
                        val nextStatus = if (SelectedFootRepository.selected.value == null) {
                            "Add a foot in Settings"
                        } else {
                            bluetoothAvailabilityStatus(
                                currentStatus = BatteryRepo.status.value,
                                bluetoothAvailable = available,
                                monitoring = latestRunning,
                                unavailableStatus = bluetoothUnavailableStatus()
                            )
                        }
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
                        selectedFoot = selectedFoot,
                        footName = footNameInput,
                        footSetupFeedback = footSetupFeedback,
                        footSetupAvailability = footSetupActionAvailability(
                            monitoringActive = running,
                            bleOperationActive = coordination.isBusy,
                            ankleOperationActive = ankleState.operation != AnkleOperation.IDLE,
                            searching = footSetupFeedback is FootSetupFeedback.Finding
                        ),
                        onThreshold = { applyThreshold(it) },
                        onPolling = { applyPolling(it) },
                        onInterval = { applyInterval(it) },
                        onPairingCode = { applyPairingCode(it) },
                        onFootName = { applyFootNameInput(it) },
                        onFindFoot = ::requestFindFoot,
                        onRemoveFoot = ::removeSelectedFoot,
                        onBack = ::leaveSettings
                    )
                } else {
                    MainScreen(
                        level = level, status = status, running = running,
                        connectionState = connectionState,
                        snapshot = snapshot,
                        standbyStatus = standbyStatus,
                        ankleState = ankleState,
                        presetState = presetState,
                        operation = coordination.visibleOperation,
                        bluetoothAvailable = bluetoothAvailable,
                        selectedFoot = selectedFoot,
                        threshold = threshold,
                        pollingEnabled = polling,
                        onStayConnectedChange = { stayConnected ->
                            if (stayConnected) startMonitoring() else requestStopMonitoring()
                        },
                        onCheck = ::checkNow,
                        onStandby = ::changeStandby,
                        onFineAdjust = ::fineAdjust,
                        onPreset = ::selectPreset,
                        onSavePreset = ::savePreset,
                        onAutoAlign = ::autoAlign,
                        onSettings = {
                            cancelFootSearch(clearFeedback = true)
                            footNameInput = selectedFoot?.name.orEmpty()
                            showSettings = true
                        }
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
        if (on && SelectedFootRepository.current(this) == null) {
            polling = false
            Prefs.setPolling(this, false)
            BatteryRepo.status.value = "Add a foot in Settings"
            return
        }
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

    private fun applyFootNameInput(value: String) {
        footNameInput = value.take(64)
        if (footSetupFeedback is FootSetupFeedback.Error) {
            footSetupFeedback = FootSetupFeedback.Idle
        }
    }

    private fun requestFindFoot() {
        val name = footNameInput.trim()
        if (name.isEmpty()) {
            footSetupFeedback = FootSetupFeedback.Error("Enter a foot name first.")
            return
        }
        if (!SelectedFootRepository.canChangeNow()) {
            footSetupFeedback = FootSetupFeedback.Error("Disconnect before changing the foot.")
            return
        }
        val missing = neededFindPerms().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            pendingFootSearchName = name
            findPermLauncher.launch(missing.toTypedArray())
            return
        }
        beginFootSearch(name)
    }

    private fun beginFootSearch(name: String) {
        cancelFootSearch(clearFeedback = false)
        val generation = ++footSearchGeneration
        footSetupFeedback = FootSetupFeedback.Finding(name)
        footSearchJob = lifecycleScope.launch {
            val result = FootSetupSearchCoordinator(
                scanner = footScanner,
                verifier = footVerifier,
                committer = SelectedFootCommitter {
                    SelectedFootRepository.replace(this@MainActivity, it)
                }
            ).findAndSelect(name)
            if (generation != footSearchGeneration) return@launch
            when (result) {
                FootSetupSearchResult.EnterName ->
                    footSetupFeedback = FootSetupFeedback.Error("Enter a foot name first.")
                is FootSetupSearchResult.Selected -> {
                    if (result.targetChanged) polling = false
                    footNameInput = result.foot.name
                    footSetupFeedback = FootSetupFeedback.Idle
                }
                is FootSetupSearchResult.NotFound -> footSetupFeedback = FootSetupFeedback.Error(
                    "Couldn't find ${result.name}. Check the name and try again."
                )
                is FootSetupSearchResult.Incompatible -> footSetupFeedback = FootSetupFeedback.Error(
                    "${result.name} isn't a compatible foot."
                )
                FootSetupSearchResult.BluetoothDisabled -> footSetupFeedback =
                    FootSetupFeedback.Error("Turn on Bluetooth to find a foot.")
                FootSetupSearchResult.PermissionMissing -> footSetupFeedback =
                    FootSetupFeedback.Error("Permission is required to find a foot.")
                is FootSetupSearchResult.Blocked -> footSetupFeedback =
                    FootSetupFeedback.Error(result.message)
                is FootSetupSearchResult.Failed -> footSetupFeedback =
                    FootSetupFeedback.Error(result.message)
            }
            footSearchJob = null
        }
    }

    private fun removeSelectedFoot() {
        cancelFootSearch(clearFeedback = false)
        lifecycleScope.launch {
            when (val result = SelectedFootRepository.remove(this@MainActivity)) {
                is SelectedFootChangeResult.Changed -> {
                    polling = false
                    footNameInput = ""
                    footSetupFeedback = FootSetupFeedback.Idle
                }
                SelectedFootChangeResult.Unchanged -> footSetupFeedback = FootSetupFeedback.Idle
                is SelectedFootChangeResult.Blocked ->
                    footSetupFeedback = FootSetupFeedback.Error(result.message)
                is SelectedFootChangeResult.Failed ->
                    footSetupFeedback = FootSetupFeedback.Error(result.message)
            }
        }
    }

    private fun leaveSettings() {
        cancelFootSearch(clearFeedback = true)
        showSettings = false
    }

    private fun cancelFootSearch(clearFeedback: Boolean) {
        footSearchGeneration++
        pendingFootSearchName = null
        footSearchJob?.cancel()
        footSearchJob = null
        footScanner.cancel()
        if (clearFeedback) footSetupFeedback = FootSetupFeedback.Idle
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
        if (SelectedFootRepository.current(this) == null) {
            BatteryRepo.status.value = "Add a foot in Settings"
        } else if (!bluetoothAvailable) {
            BatteryRepo.status.value = bluetoothUnavailableStatus()
        }
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

    private fun neededFindPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasFindPermissions(): Boolean = neededFindPerms().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
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
        if (SelectedFootRepository.current(this) == null) {
            BatteryRepo.status.value = "Add a foot in Settings"
            return
        }
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

    private fun fineAdjust(adjustment: FineAdjustment) {
        if (!hasAll()) { permLauncher.launch(neededPerms()); return }
        Alerts.ensureChannels(this)
        FootOperations.launchFineAdjustment(applicationContext, adjustment)
    }

    private fun selectPreset(preset: FootwearPreset) {
        PresetRepository.select(preset)
        val target = PresetRepository.state.value.targets.target(preset)
        if (target == null) {
            val message = "${preset.displayName} has no saved angle; use Save preset to configure it"
            AnkleRepo.fail(message)
            Alerts.refreshApplicable(applicationContext, message)
            return
        }
        if (!hasAll()) { permLauncher.launch(neededPerms()); return }
        Alerts.ensureChannels(this)
        FootOperations.launchPreset(applicationContext, preset)
    }

    private fun savePreset() {
        val confirmed = AnkleRepo.state.value.confirmedMd
        if (confirmed == null) {
            val message = "A confirmed ankle angle is required before saving"
            AnkleRepo.fail(message)
            Alerts.refreshApplicable(applicationContext, message)
            return
        }
        val saved = PresetRepository.saveSelected(applicationContext, confirmed)
        val message = saved?.let {
            "${it.displayName} saved at ${AnkleProtocol.format(confirmed)}"
        } ?: "Select Barefoot, Running, Dress, or Boots before saving"
        AnkleRepo.fail(message)
        Alerts.refreshApplicable(applicationContext, message)
    }

    private fun autoAlign() {
        if (!hasAll()) { permLauncher.launch(neededPerms()); return }
        Alerts.ensureChannels(this)
        FootOperations.launchAutoAlign(applicationContext)
    }

    override fun onDestroy() {
        cancelFootSearch(clearFeedback = false)
        super.onDestroy()
    }

    override fun onStop() {
        // Verification may continue through Android's pairing UI, but an active scan never
        // remains running after the app leaves the foreground.
        if (footSearchJob != null && footScanner.cancel()) {
            footSearchGeneration++
            footSearchJob?.cancel()
            footSearchJob = null
            if (footSetupFeedback is FootSetupFeedback.Finding) {
                footSetupFeedback = FootSetupFeedback.Idle
            }
        }
        super.onStop()
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
    ankleState: AnkleState,
    presetState: PresetState,
    operation: BleOperationKind?,
    bluetoothAvailable: Boolean,
    selectedFoot: SelectedFoot?,
    threshold: Int,
    pollingEnabled: Boolean,
    onStayConnectedChange: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onStandby: (StandbyState) -> Unit,
    onFineAdjust: (FineAdjustment) -> Unit,
    onPreset: (FootwearPreset) -> Unit,
    onSavePreset: () -> Unit,
    onAutoAlign: () -> Unit,
    onSettings: () -> Unit
) {
    val accent = colorForLevel(level, MaterialTheme.colorScheme.primary)
    val ready = connectionState == LiveConnectionState.READY
    val busy = operation != null || ankleState.operation != AnkleOperation.IDLE
    val canUseConnection = !running || ready
    val hasSelectedFoot = selectedFoot != null
    val modePresentation = mainScreenModePresentation(
        running = running,
        connectionState = connectionState,
        pollingEnabled = pollingEnabled && hasSelectedFoot
    )
    val stayConnected = stayConnectedPresentation(
        running = running,
        busy = busy,
        bluetoothAvailable = bluetoothAvailable,
        footSelected = hasSelectedFoot,
        connectionState = connectionState
    )
    val display = SnapshotPresentation.create(snapshot)
    val presentation = if (hasSelectedFoot) {
        MainScreenPresentation.create(
            activeOperationText = mainScreenOperationText(operation),
            verificationMessage = ankleState.message ?: display.verificationMessage,
            standbyStatus = standbyStatus,
            generalStatus = status
        )
    } else {
        MainScreenPresentation.create(null, null, null, "Add a foot in Settings")
    }
    val controlsReady = hasSelectedFoot && bluetoothAvailable && !busy && canUseConnection
    val anklePresentation = AnklePresentation.create(
        state = ankleState,
        standby = display.standby,
        controlsReady = controlsReady
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
            MainHeader(presentation = modePresentation, onSettings = onSettings)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(layout.headerToGaugeGapDp.dp))
                BatteryGauge(
                    level = level,
                    accent = accent,
                    size = layout.gaugeSizeDp.dp,
                    valueFontSizeSp = layout.gaugeValueFontSizeSp,
                    percentFontSizeSp = layout.gaugePercentFontSizeSp
                )
                Spacer(Modifier.height(layout.gaugeToDeviceGapDp.dp))

                DeviceMetadata(
                    selectedFoot = selectedFoot,
                    threshold = threshold,
                    display = display,
                    deviceToThresholdGap = layout.deviceToThresholdGapDp.dp
                )
                Spacer(Modifier.height(layout.metadataToCardGapDp.dp))

                FootControlsCard(
                    stayConnected = stayConnected,
                    display = display,
                    minHeight = layout.footControlsMinHeightDp.dp,
                    standbyEnabled = controlsReady && snapshot.standby != StandbyState.UNKNOWN,
                    onStayConnectedChange = onStayConnectedChange,
                    onStandbyChange = onStandby
                )
                Spacer(Modifier.height(layout.cardToStatusGapDp.dp))
                AnkleAlignmentCard(
                    state = ankleState,
                    presentation = anklePresentation,
                    presets = presetState,
                    interactionReady = controlsReady,
                    standby = display.standby,
                    onFineAdjust = onFineAdjust,
                    onPreset = onPreset,
                    onSavePreset = onSavePreset,
                    onAutoAlign = onAutoAlign
                )
                Spacer(Modifier.height(layout.cardToStatusGapDp.dp))
                MainScreenStatusSlot(
                    presentation = presentation,
                    height = layout.statusSlotHeightDp.dp
                )
                Spacer(Modifier.height(8.dp))
            }
            CheckNowButton(
                enabled = hasSelectedFoot && bluetoothAvailable && !busy && canUseConnection,
                onClick = onCheck
            )
        }
    }
}

@Composable
private fun MainHeader(
    presentation: MainScreenModePresentation,
    onSettings: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row {
                Text("Foot", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text(
                    "Pilot",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Text("BLE · 0x180F", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        StatusPill(presentation)
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onSettings) { Text("\u2699", color = Muted, fontSize = 22.sp) }
    }
}

@Composable
private fun DeviceMetadata(
    selectedFoot: SelectedFoot?,
    threshold: Int,
    display: SnapshotDisplayState,
    deviceToThresholdGap: Dp
) {
    val checkedText = display.checkedLine(
        display.lastChecked.takeIf { it > 0L }?.let {
            Alerts.clockTime(LocalContext.current, it)
        }
    )
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = selectedFoot?.name ?: "No foot selected",
            color = Ink,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(deviceToThresholdGap))
        Text(
            text = "Alerts below $threshold%",
            color = Muted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = checkedText,
            color = Muted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
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
private fun CheckNowButton(
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color(0xFF03140E),
            disabledContainerColor = Panel,
            disabledContentColor = Muted
        )
    ) { Text("Check now", fontWeight = FontWeight.Bold) }
}

@Composable
private fun FootControlsCard(
    stayConnected: StayConnectedPresentation,
    display: SnapshotDisplayState,
    minHeight: Dp,
    standbyEnabled: Boolean,
    onStayConnectedChange: (Boolean) -> Unit,
    onStandbyChange: (StandbyState) -> Unit
) {
    val standbyOn = display.standby == StandbyState.ON
    val standbySecondary = when {
        display.standbyAmbiguousAfterCommand -> "Not confirmed"
        display.standby == StandbyState.UNKNOWN -> "Not checked"
        else -> null
    }
    val standbyStateDescription = standbySecondary ?: if (standbyOn) "On" else "Off"

    Column(
        Modifier.fillMaxWidth().heightIn(min = minHeight)
            .clip(RoundedCornerShape(14.dp)).background(Panel)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            "FOOT CONTROLS",
            color = Muted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Stay connected",
                color = Ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = stayConnected.checked,
                enabled = stayConnected.enabled,
                onCheckedChange = onStayConnectedChange,
                modifier = Modifier.semantics {
                    contentDescription = "Stay connected"
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF03140E),
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = Muted,
                    uncheckedTrackColor = Panel,
                    uncheckedBorderColor = Line,
                    disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    disabledUncheckedTrackColor = Panel
                )
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))

        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Standby",
                    color = if (standbyOn) Warn else Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                standbySecondary?.let { status ->
                    Text(
                        status,
                        color = Muted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = standbyOn,
                enabled = standbyEnabled,
                onCheckedChange = { checked ->
                    onStandbyChange(if (checked) StandbyState.ON else StandbyState.OFF)
                },
                modifier = Modifier.semantics {
                    contentDescription = "Standby"
                    stateDescription = standbyStateDescription
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
}

@Composable
private fun AnkleAlignmentCard(
    state: AnkleState,
    presentation: AnkleValuePresentation,
    presets: PresetState,
    interactionReady: Boolean,
    standby: StandbyState,
    onFineAdjust: (FineAdjustment) -> Unit,
    onPreset: (FootwearPreset) -> Unit,
    onSavePreset: () -> Unit,
    onAutoAlign: () -> Unit,
    calibration: ShoeHeightCalibration = UnconfiguredShoeHeightCalibration
) {
    var showAnkleInfo by remember { mutableStateOf(false) }
    val autoRunning = state.operation in setOf(
        AnkleOperation.AUTO_STARTING,
        AnkleOperation.AUTO_RUNNING,
        AnkleOperation.VERIFYING
    )
    val currentConfirmedMd = state.confirmedMd.takeIf { standby == StandbyState.OFF }
    val activeMatches = presets.targets.activeMatches(currentConfirmedMd)
    val summary = summaryPreset(presets, currentConfirmedMd) ?: presets.selected
    val presetSelectionReady = state.operation == AnkleOperation.IDLE

    if (showAnkleInfo) {
        AlertDialog(
            onDismissRequest = { showAnkleInfo = false },
            title = { Text("Ankle Alignment") },
            text = {
                Text(
                    "Adjusts the ankle angle for comfortable posture with different heel heights.\n\n" +
                        "• Sit down before adjusting.\n" +
                        "• Place the whole foot flat on the floor, heel to toe.\n" +
                        "• Tap Auto align. Keep the foot flat until the second beep, then lift " +
                        "the foot to allow the ankle to adapt.\n" +
                        "• Use Fine Adjust for small manual changes."
                )
            },
            confirmButton = {
                TextButton(onClick = { showAnkleInfo = false }) { Text("Done") }
            }
        )
    }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Panel)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "ANKLE ALIGNMENT",
                color = Muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onAutoAlign,
                enabled = presentation.movementEnabled && !autoRunning,
                modifier = Modifier.semantics {
                    contentDescription = if (presentation.movementEnabled) {
                        "Start automatic ankle alignment"
                    } else {
                        "Automatic ankle alignment unavailable until standby is off and angle is verified"
                    }
                }
            ) {
                Text("Auto align", fontWeight = FontWeight.Bold)
            }
            IconButton(
                onClick = { showAnkleInfo = true },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_info_outline),
                    contentDescription = "About ankle alignment",
                    tint = Muted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (autoRunning) {
            AutoAlignmentCardBody(state.operation)
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (summary != null) {
                Image(
                    painter = painterResource(presetDrawableRes(summary)),
                    contentDescription = "${summary.displayName} footwear artwork",
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 72.dp, height = 50.dp)
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    summary?.summaryName ?: "Current alignment",
                    color = Ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (state.operation == AnkleOperation.SETTING) {
                        state.message ?: "Adjusting ankle..."
                    } else {
                        presentation.statusText
                    },
                    color = if (presentation.isCurrentConfirmed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Muted
                    },
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                presentation.historicalText?.let {
                    Text(it, color = Muted, fontSize = 11.sp, maxLines = 2)
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FootwearPreset.fixedOrder.forEach { preset ->
                val configured = presets.targets.target(preset) != null
                PresetCell(
                    preset = preset,
                    configured = configured,
                    selectedForSave = presets.selected == preset,
                    physicallyActive = preset in activeMatches,
                    enabled = interactionReady && presetSelectionReady &&
                        (!configured || presentation.movementEnabled),
                    onClick = { onPreset(preset) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (quickAdjustVisible(calibration)) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            Spacer(Modifier.height(10.dp))
            Text(
                "QUICK ADJUST",
                color = Muted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ShoeHeightChange.APPROVED_V1.forEach { change ->
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.weight(1f).heightIn(min = 42.dp).semantics {
                            contentDescription = "${change.label}, unavailable"
                        },
                        border = BorderStroke(1.dp, Line),
                        colors = ButtonDefaults.outlinedButtonColors(
                            disabledContentColor = Muted.copy(alpha = 0.65f)
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 4.dp,
                            vertical = 4.dp
                        )
                    ) {
                        Text(change.label, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Spacer(Modifier.height(10.dp))
        Text(
            "FINE ADJUST (DEGREES)",
            color = Muted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(5.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FineAdjustButton(
                label = "−",
                enabled = presentation.minusEnabled,
                description = "Decrease ankle angle by 0.1 degrees",
                onClick = { onFineAdjust(FineAdjustment.MINUS) }
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    presentation.angleText,
                    color = if (presentation.isCurrentConfirmed) Ink else Muted,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
                Text("Fine tune alignment", color = Muted, fontSize = 11.sp)
            }
            FineAdjustButton(
                label = "+",
                enabled = presentation.plusEnabled,
                description = "Increase ankle angle by 0.1 degrees",
                onClick = { onFineAdjust(FineAdjustment.PLUS) }
            )
        }

        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        TextButton(
            onClick = onSavePreset,
            enabled = state.confirmedMd != null && presets.selected != null &&
                state.operation == AnkleOperation.IDLE,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = if (presets.selected == null) {
                    "Save preset unavailable. Select a footwear preset first"
                } else if (state.confirmedMd == null) {
                    "Save preset unavailable. Confirm ankle angle first"
                } else {
                    "Save current confirmed angle to ${presets.selected.displayName}"
                }
            }
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_bookmark_outline),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("Save preset", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AutoAlignmentCardBody(operation: AnkleOperation) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 190.dp).padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            trackColor = RingTrack,
            modifier = Modifier.size(42.dp).semantics {
                contentDescription = "Automatic alignment in progress"
            }
        )
        Spacer(Modifier.height(14.dp))
        Text(
            if (operation == AnkleOperation.VERIFYING) {
                "Verifying alignment"
            } else {
                "Automatic alignment"
            },
            color = Ink,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(7.dp))
        Text(
            "Keep foot flat until the second beep, then lift your foot.",
            color = Muted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PresetCell(
    preset: FootwearPreset,
    configured: Boolean,
    selectedForSave: Boolean,
    physicallyActive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val border = when {
        physicallyActive -> accent
        selectedForSave -> accent.copy(alpha = 0.75f)
        else -> Line
    }
    val description = buildString {
        append(preset.displayName)
        append(" preset")
        if (!configured) append(", no saved angle")
        if (selectedForSave) append(", selected for saving")
        if (physicallyActive) append(", matches confirmed foot angle")
        if (!enabled) append(", unavailable")
    }
    Column(
        modifier.heightIn(min = 86.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (physicallyActive) Bg else Panel)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selectedForSave || physicallyActive
            }
            .padding(horizontal = 3.dp, vertical = 5.dp)
            .alpha(
                when {
                    !enabled -> 0.45f
                    configured -> 1f
                    else -> 0.55f
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(presetDrawableRes(preset)),
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (physicallyActive) accent else Muted),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(46.dp)
        )
        Text(
            preset.displayName,
            color = if (physicallyActive) accent else Ink,
            fontSize = 10.sp,
            fontWeight = if (selectedForSave || physicallyActive) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FineAdjustButton(
    label: String,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(50.dp).semantics {
            contentDescription = if (enabled) description else "$description, unavailable"
        },
        shape = CircleShape,
        border = BorderStroke(1.dp, if (enabled) Line else Line.copy(alpha = 0.5f)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Ink,
            disabledContentColor = Muted.copy(alpha = 0.5f)
        )
    ) {
        Text(label, fontSize = 24.sp)
    }
}

private fun presetDrawableRes(preset: FootwearPreset): Int = when (preset) {
    FootwearPreset.BAREFOOT -> R.drawable.preset_barefoot
    FootwearPreset.RUNNING -> R.drawable.preset_running
    FootwearPreset.DRESS -> R.drawable.preset_dress
    FootwearPreset.BOOTS -> R.drawable.preset_boots
}

@Composable
private fun StatusPill(presentation: MainScreenModePresentation) {
    val dotAlpha = if (presentation.pulses) {
        val infinite = rememberInfiniteTransition(label = "pulse")
        val alpha by infinite.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "dot"
        )
        alpha
    } else {
        1f
    }
    val dotColor = if (presentation.usesActiveColor) {
        MaterialTheme.colorScheme.primary
    } else {
        Muted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(8.dp).clip(CircleShape)
                .background(dotColor.copy(alpha = dotAlpha))
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = presentation.label,
            color = Muted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
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
    selectedFoot: SelectedFoot?,
    footName: String,
    footSetupFeedback: FootSetupFeedback,
    footSetupAvailability: FootSetupActionAvailability,
    onThreshold: (Int) -> Unit, onPolling: (Boolean) -> Unit, onInterval: (Int) -> Unit,
    onPairingCode: (String) -> Unit,
    onFootName: (String) -> Unit,
    onFindFoot: () -> Unit,
    onRemoveFoot: () -> Unit,
    onBack: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val finding = footSetupFeedback is FootSetupFeedback.Finding
    val setupStatus = footSetupStatusPresentation(selectedFoot, footSetupFeedback)
    val setupStatusColor = when (setupStatus.tone) {
        FootSetupStatusTone.MUTED -> Muted
        FootSetupStatusTone.SUCCESS -> accent
        FootSetupStatusTone.WARNING -> Warn
    }
    Box(Modifier.fillMaxSize().background(Bg).padding(horizontal = 24.dp, vertical = 28.dp)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Text("\u2190", color = Ink, fontSize = 24.sp) }
                Spacer(Modifier.width(4.dp))
                Text("Settings", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }

            Spacer(Modifier.height(24.dp))

            Text("FOOT SETUP", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel)
                    .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(16.dp)
            ) {
                Text("Pairing code", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = onPairingCode,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    singleLine = true,
                    placeholder = { Text("Pairing code", color = Muted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink,
                        focusedBorderColor = accent,
                        unfocusedBorderColor = Line,
                        cursorColor = accent,
                        focusedContainerColor = Panel,
                        unfocusedContainerColor = Panel
                    )
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Used automatically when the foot asks to pair. You can find the code on the back of the foot.",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                Spacer(Modifier.height(16.dp))

                Text("Bluetooth foot", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = footName,
                    onValueChange = onFootName,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    singleLine = true,
                    enabled = footSetupAvailability.canChange,
                    placeholder = { Text("Foot name", color = Muted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink,
                        disabledTextColor = Muted,
                        focusedBorderColor = accent,
                        unfocusedBorderColor = Line,
                        disabledBorderColor = Line.copy(alpha = 0.65f),
                        cursorColor = accent,
                        focusedContainerColor = Panel,
                        unfocusedContainerColor = Panel,
                        disabledContainerColor = Panel
                    )
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    footSetupAvailability.helperText,
                    color = Muted,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onFindFoot,
                    enabled = footSetupAvailability.canChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, if (footSetupAvailability.canChange) accent else Line),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = accent,
                        disabledContentColor = Muted
                    )
                ) {
                    if (finding) {
                        CircularProgressIndicator(
                            color = Muted,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (finding) "Finding…" else "Find foot", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        setupStatus.text,
                        color = setupStatusColor,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (setupStatus.showRemove) {
                        TextButton(
                            onClick = onRemoveFoot,
                            enabled = footSetupAvailability.canChange,
                            modifier = Modifier.heightIn(min = 44.dp)
                        ) {
                            Text("Remove", color = if (footSetupAvailability.canChange) Muted else Line)
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            Text("FOOT MODES", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(10.dp))
            FootModesCard()

            Spacer(Modifier.height(28.dp))

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
                        checked = polling,
                        onCheckedChange = onPolling,
                        enabled = selectedFoot != null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF03140E),
                            checkedTrackColor = accent,
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
                    val intervalsEnabled = polling && selectedFoot != null
                    val selected = intervalMin == min && intervalsEnabled
                    val chipMod = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (selected) accent else Panel)
                        .border(1.dp, if (selected) accent else Line, RoundedCornerShape(12.dp))
                        .let { if (intervalsEnabled) it.clickable { onInterval(min) } else it }
                        .padding(vertical = 14.dp)
                    Box(chipMod, contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            color = when {
                                !intervalsEnabled -> Muted.copy(alpha = 0.5f)
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
            Spacer(Modifier.height(20.dp))
            Text(
                "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                color = Muted.copy(alpha = 0.72f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FootModesCard() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        DisabledFootModeRow("Chair Exit Mode")
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        DisabledFootModeRow("Relax Mode")
    }
}

@Composable
private fun DisabledFootModeRow(name: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            color = Ink,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "Disabled",
            color = Muted,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
            .background(Bg).border(1.dp, Line, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
