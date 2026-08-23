package com.example.footbattery

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.EnumMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class CheckOrigin {
    MANUAL,
    NOTIFICATION,
    SCHEDULED,
    LIVE_INITIAL,
    LIVE_RECONNECT
}

sealed interface FootOperationResult {
    data class Complete(val snapshot: SnapshotState) : FootOperationResult
    data class ControlComplete(val message: String, val confirmedMd: Int? = null) : FootOperationResult
    data class Partial(val message: String) : FootOperationResult
    data class Failed(val message: String) : FootOperationResult
    data object Busy : FootOperationResult
    data object Skipped : FootOperationResult
}

data class FullSnapshotApplicationDecision(
    val reduction: SnapshotReduction,
    val result: FootOperationResult
)

/** Classifies snapshot completeness separately from whether an ankle query was intentionally skipped. */
fun classifyFullSnapshotRead(
    previous: SnapshotState,
    read: FullSnapshotRead,
    checkedAt: Long
): FullSnapshotApplicationDecision {
    val reduction = SnapshotReducer.reduce(
        previous,
        SnapshotEvent.NormalCheck(
            batteryLevel = read.batteryLevel,
            standby = read.standby,
            checkedAt = checkedAt
        )
    )
    val result = when {
        !reduction.completeSnapshotSaved ->
            FootOperationResult.Partial(partialCheckMessage(read))
        read.ankleDisposition == AnkleSnapshotDisposition.QUERIED && read.ankleMd == null ->
            FootOperationResult.Partial(
                read.ankleError ?: "Ankle angle could not be verified"
            )
        else -> FootOperationResult.Complete(reduction.snapshot)
    }
    return FullSnapshotApplicationDecision(reduction, result)
}

private fun partialCheckMessage(read: FullSnapshotRead): String {
    val standbyConfirmed = read.standby != null && read.standby != StandbyState.UNKNOWN
    return when {
        read.batteryLevel != null && standbyConfirmed &&
            read.ankleDisposition == AnkleSnapshotDisposition.QUERIED && read.ankleMd == null ->
            read.ankleError ?: "Ankle check failed"
        read.batteryLevel != null && !standbyConfirmed ->
            read.standbyError ?: "Standby check failed"
        read.batteryLevel == null && standbyConfirmed ->
            read.batteryError ?: "Battery check failed"
        else -> read.standbyError ?: read.batteryError ?: "Check failed — is the foot in range?"
    }
}

/** Shared high-level transactions for the activity, notification service, worker, and live link. */
object FootOperations {
    private const val TAG = "FootPilotBle"
    private val userScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val modeJobGuard = Any()
    private val modeJobs = EnumMap<FootMode, Job>(FootMode::class.java)
    private val modeRefreshJobSlot = FootModeRefreshJobSlot<Job>()
    private val standbyGeneration = StandbyRequestGeneration()
    private val standbyRequestMutex = Mutex()

    fun launchManualCheck(ctx: Context) {
        val app = ctx.applicationContext
        userScope.launch { checkNow(app, CheckOrigin.MANUAL) }
    }

    fun launchStandbyChange(ctx: Context, requested: StandbyState) {
        require(requested != StandbyState.UNKNOWN)
        val app = ctx.applicationContext
        val target = SelectedFootRepository.current(app)
        if (target == null) {
            standbyGeneration.invalidate()
            userScope.launch {
                BatteryRepo.ensureInitialized(app)
                AnkleRepo.ensureInitialized(app)
                operationFailure(app, "No foot selected")
            }
            return
        }
        val token = beginStandbyRequest(
            target,
            StandbyAttemptRequest.Absolute(requested)
        )
        userScope.launch {
            BatteryRepo.ensureInitialized(app)
            AnkleRepo.ensureInitialized(app)
            PresetRepository.ensureInitialized(app)
            executeStandbyRequest(app, target, token, requireKnownPublicState = true)
        }
    }

    fun launchFineAdjustment(ctx: Context, adjustment: FineAdjustment) {
        val app = ctx.applicationContext
        userScope.launch { adjustFine(app, adjustment) }
    }

    fun launchPreset(ctx: Context, preset: FootwearPreset) {
        val app = ctx.applicationContext
        userScope.launch { applyPreset(app, preset) }
    }

    fun launchAutoAlign(ctx: Context) {
        val app = ctx.applicationContext
        userScope.launch { autoAlign(app) }
    }

    fun launchFootModesRefresh(ctx: Context) {
        val app = ctx.applicationContext
        val target = SelectedFootRepository.current(app)
        FootModeRepo.syncTarget(target?.address)
        if (target == null) return
        lateinit var launched: Job
        modeRefreshJobSlot.tryLaunch(
            beginRefresh = {
                FootModeRepo.beginRefresh(target.address).also { admitted ->
                    if (admitted) debugFootMode("FOOT_MODE_REFRESH begin")
                }
            },
            create = {
                userScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        refreshFootModes(app, target)
                    } finally {
                        modeRefreshJobSlot.clearIf(launched)
                    }
                }.also { launched = it }
            },
            start = Job::start
        )
    }

    fun launchFootModeChange(
        ctx: Context,
        mode: FootMode,
        requested: FootModeValue
    ) {
        require(requested != FootModeValue.UNKNOWN)
        val app = ctx.applicationContext
        val target = SelectedFootRepository.current(app)
        FootModeRepo.syncTarget(target?.address)
        if (target == null) return
        val token = FootModeRepo.beginIntent(target.address, mode, requested)

        synchronized(modeJobGuard) {
            val previous = modeJobs[mode]
            lateinit var launched: Job
            launched = userScope.launch(start = CoroutineStart.LAZY) {
                try {
                    previous?.cancelAndJoin()
                    runFootModeIntent(app, target, token)
                } finally {
                    synchronized(modeJobGuard) {
                        if (modeJobs[mode] === launched) modeJobs.remove(mode)
                    }
                }
            }
            modeJobs[mode] = launched
            previous?.cancel()
            launched.start()
        }
    }

    fun cancelPendingFootModeOperations() {
        modeRefreshJobSlot.take()?.cancel()
        synchronized(modeJobGuard) {
            modeJobs.values.forEach(Job::cancel)
            modeJobs.clear()
        }
    }

    fun cancelPendingStandbyOperations() {
        standbyGeneration.invalidate()
        BatteryRepo.standbyRetrySecondsRemaining.value = null
    }

    suspend fun checkNow(ctx: Context, origin: CheckOrigin): FootOperationResult {
        val app = ctx.applicationContext
        SelectedFootRepository.ensureInitialized(app)
        BatteryRepo.ensureInitialized(app)
        AnkleRepo.ensureInitialized(app)
        PresetRepository.ensureInitialized(app)
        if (SelectedFootRepository.current(app) == null) {
            return if (origin == CheckOrigin.SCHEDULED) {
                FootOperationResult.Skipped
            } else {
                operationFailure(app, "No foot selected")
            }
        }
        val kind = when {
            origin == CheckOrigin.MANUAL && LiveConnection.isReady() -> BleOperationKind.LIVE_REFRESH
            origin == CheckOrigin.NOTIFICATION -> BleOperationKind.NOTIFICATION_CHECK
            origin == CheckOrigin.SCHEDULED -> BleOperationKind.SCHEDULED_CHECK
            else -> BleOperationKind.MANUAL_CHECK
        }

        val coordinated = try {
            BleOperationCoordinator.tryRun(kind) {
                BatteryRepo.status.value = "Checking..."
                BatteryRepo.standbyStatus.value = "Checking standby..."
                Alerts.showOperation(app, "Checking...")

                val live = LiveConnection.readySession()
                when {
                    live != null -> readAndApplyOnSession(app, live, origin)
                    !LiveConnection.canUseTemporarySession() -> {
                        val message = "Bluetooth connection is not ready"
                        BatteryRepo.status.value = message
                        BatteryRepo.standbyStatus.value = message
                        FootOperationResult.Failed(message)
                    }
                    else -> withTemporarySession(app) { session ->
                        readAndApplyOnSession(app, session, origin)
                    }
                }
            }
        } catch (e: CancellationException) {
            AnkleRepo.fail("Check cancelled")
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "Bluetooth operation failed"
            BatteryRepo.status.value = message
            BatteryRepo.standbyStatus.value = message
            if (AnkleRepo.state.value.operation != AnkleOperation.IDLE) {
                AnkleRepo.fail(message)
            }
            Alerts.refreshApplicable(app, message)
            return FootOperationResult.Failed(message)
        }

        return when (coordinated) {
            is CoordinatedResult.Completed -> coordinated.value.also { result ->
                Alerts.refreshApplicable(app, result.transientMessage())
            }
            CoordinatedResult.Busy -> FootOperationResult.Busy
        }
    }

    suspend fun scheduledCheck(ctx: Context): FootOperationResult {
        val app = ctx.applicationContext
        SelectedFootRepository.ensureInitialized(app)
        BatteryRepo.ensureInitialized(app)
        if (SelectedFootRepository.current(app) == null || !Prefs.polling(app) ||
            !LiveConnection.canUseTemporarySession()
        ) {
            return FootOperationResult.Skipped
        }
        return checkNow(app, CheckOrigin.SCHEDULED)
    }

    suspend fun changeStandby(ctx: Context, requested: StandbyState): FootOperationResult {
        require(requested != StandbyState.UNKNOWN)
        val app = ctx.applicationContext
        BatteryRepo.ensureInitialized(app)
        AnkleRepo.ensureInitialized(app)
        PresetRepository.ensureInitialized(app)
        val target = SelectedFootRepository.current(app) ?: run {
            standbyGeneration.invalidate()
            return operationFailure(app, "No foot selected")
        }
        val token = beginStandbyRequest(
            target,
            StandbyAttemptRequest.Absolute(requested)
        )
        return executeStandbyRequest(app, target, token, requireKnownPublicState = true)
    }

    /** Notification Standby action: target is derived from a fresh query on the acquired session. */
    suspend fun toggleStandby(ctx: Context): FootOperationResult {
        val app = ctx.applicationContext
        BatteryRepo.ensureInitialized(app)
        AnkleRepo.ensureInitialized(app)
        PresetRepository.ensureInitialized(app)
        val target = SelectedFootRepository.current(app) ?: run {
            standbyGeneration.invalidate()
            return operationFailure(app, "No foot selected")
        }
        val token = beginStandbyRequest(target, StandbyAttemptRequest.Toggle)
        return executeStandbyRequest(app, target, token, requireKnownPublicState = false)
    }

    private fun beginStandbyRequest(
        target: SelectedFoot,
        request: StandbyAttemptRequest
    ): StandbyRequestToken {
        BatteryRepo.standbyRetrySecondsRemaining.value = null
        return standbyGeneration.begin(target.address, request)
    }

    private suspend fun executeStandbyRequest(
        ctx: Context,
        target: SelectedFoot,
        token: StandbyRequestToken,
        requireKnownPublicState: Boolean
    ): FootOperationResult = standbyRequestMutex.withLock {
        if (!isCurrentStandbyRequest(token)) {
            return@withLock FootOperationResult.Failed("Standby request superseded")
        }
        standbyPrerequisiteError(ctx, target, requireSafeSession = true)?.let {
            return@withLock operationFailure(ctx, it)
        }
        if (requireKnownPublicState && BatteryRepo.snapshot.value.standby == StandbyState.UNKNOWN) {
            val message = if (
                BatteryRepo.snapshot.value.completeness ==
                SnapshotCompleteness.STANDBY_STATE_UNKNOWN_AFTER_COMMAND
            ) {
                "Check now to restore a confirmed standby state"
            } else {
                "Check now to verify standby"
            }
            BatteryRepo.status.value = message
            BatteryRepo.standbyStatus.value = message
            Alerts.refreshApplicable(ctx, message)
            return@withLock FootOperationResult.Failed(message)
        }
        runStandbyIntent(ctx, target, token)
    }

    private suspend fun runStandbyIntent(
        ctx: Context,
        target: SelectedFoot,
        token: StandbyRequestToken
    ): FootOperationResult {
        var publishedResult: FootOperationResult? = null
        val run = try {
            StandbyOneShotRetry().run(
                initialRequest = token.initialRequest,
                stillCurrent = { isCurrentStandbyRequest(token) },
                publishSecondsRemaining = { seconds ->
                    if (isCurrentStandbyRequest(token)) {
                        BatteryRepo.standbyRetrySecondsRemaining.value = seconds
                    }
                },
                onRetryScheduled = { _, _ ->
                    if (isCurrentStandbyRequest(token)) {
                        BatteryRepo.status.value = "Retrying standby..."
                        BatteryRepo.standbyStatus.value = "Retrying standby..."
                        Alerts.showOperation(ctx, "Retrying standby...")
                    }
                },
                onRetryStarting = {},
                attempt = { request ->
                    performStandbyAttempt(ctx, target, token, request).also { result ->
                        if (isCurrentStandbyRequest(token) && result !is StandbyAttemptResult.Busy) {
                            publishedResult = applyStandbyAttempt(ctx, result)
                        }
                    }
                }
            )
        } catch (e: CancellationException) {
            if (isCurrentStandbyRequest(token)) {
                val message = "Standby operation cancelled"
                BatteryRepo.standbyRetrySecondsRemaining.value = null
                BatteryRepo.status.value = message
                BatteryRepo.standbyStatus.value = message
                Alerts.refreshApplicable(ctx, message)
            }
            throw e
        } finally {
            if (standbyGeneration.isCurrent(token)) {
                BatteryRepo.standbyRetrySecondsRemaining.value = null
            }
        }

        if (run.superseded) {
            return publishedResult ?: FootOperationResult.Failed("Standby request superseded")
        }
        val result = when (run.finalAttempt) {
            StandbyAttemptResult.Busy -> FootOperationResult.Busy
            null -> FootOperationResult.Failed("Standby request superseded")
            else -> publishedResult ?: FootOperationResult.Failed("Standby operation failed")
        }
        if (result !is FootOperationResult.Busy) {
            Alerts.refreshApplicable(ctx, result.transientMessage())
        }
        return result
    }

    private suspend fun performStandbyAttempt(
        ctx: Context,
        target: SelectedFoot,
        token: StandbyRequestToken,
        request: StandbyAttemptRequest
    ): StandbyAttemptResult {
        standbyPrerequisiteError(ctx, target, requireSafeSession = false)?.let {
            return StandbyAttemptResult.Rejected(it)
        }
        val kind = standbyOperationKind(request)
        val coordinated = try {
            BleOperationCoordinator.runDeviceControl(kind) {
                if (!isCurrentStandbyRequest(token)) {
                    return@runDeviceControl StandbyAttemptResult.Rejected(
                        "Standby request superseded"
                    )
                }
                val operationText = kind.statusText
                BatteryRepo.status.value = operationText
                BatteryRepo.standbyStatus.value = operationText
                Alerts.showOperation(ctx, operationText)
                val live = LiveConnection.readySession()
                when {
                    live != null -> executeStandbyOnSession(live, token, request)
                    !LiveConnection.canUseTemporarySession() ->
                        StandbyAttemptResult.TransientFailure(
                            "Bluetooth connection is not ready"
                        )
                    else -> withTemporaryStandbySession(ctx, target, token, request)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return StandbyAttemptResult.TransientFailure(
                e.message ?: "Bluetooth operation failed"
            )
        }
        return when (coordinated) {
            is CoordinatedResult.Completed -> coordinated.value
            CoordinatedResult.Busy -> StandbyAttemptResult.Busy
        }
    }

    private suspend fun executeStandbyOnSession(
        session: FootGattSession,
        token: StandbyRequestToken,
        request: StandbyAttemptRequest
    ): StandbyAttemptResult {
        if (!isCurrentStandbyRequest(token)) {
            return StandbyAttemptResult.Rejected("Standby request superseded")
        }
        val read = when (request) {
            is StandbyAttemptRequest.Absolute -> session.changeStandby(request.requested)
            StandbyAttemptRequest.Toggle -> session.toggleStandby()
        }
        return StandbyAttemptResult.Transaction(read)
    }

    private suspend fun withTemporaryStandbySession(
        ctx: Context,
        target: SelectedFoot,
        token: StandbyRequestToken,
        request: StandbyAttemptRequest
    ): StandbyAttemptResult {
        val session = FootGattSession(ctx, target)
        return try {
            withTimeout(30_000L) {
                session.connectAndInitialize()
                executeStandbyOnSession(session, token, request)
            }
        } catch (_: TimeoutCancellationException) {
            StandbyAttemptResult.TransientFailure("Bluetooth transaction timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StandbyAttemptResult.TransientFailure(
                e.message ?: "Bluetooth operation failed"
            )
        } finally {
            withContext(NonCancellable) {
                BleTargetReleaseBarrier.releaseTemporarySession(ctx, session)
            }
        }
    }

    private fun applyStandbyAttempt(
        ctx: Context,
        result: StandbyAttemptResult
    ): FootOperationResult = when (result) {
        is StandbyAttemptResult.Transaction -> applyStandbyRead(ctx, result.read)
        is StandbyAttemptResult.TransientFailure -> standbyFailure(result.message)
        is StandbyAttemptResult.Rejected -> standbyFailure(result.message)
        StandbyAttemptResult.Busy -> FootOperationResult.Busy
    }

    private fun standbyFailure(message: String): FootOperationResult.Failed {
        BatteryRepo.status.value = message
        BatteryRepo.standbyStatus.value = message
        AnkleRepo.fail(message)
        return FootOperationResult.Failed(message)
    }

    private fun standbyOperationKind(request: StandbyAttemptRequest): BleOperationKind =
        when (request) {
            is StandbyAttemptRequest.Absolute -> if (request.requested == StandbyState.ON) {
                BleOperationKind.STANDBY_ON
            } else {
                BleOperationKind.STANDBY_OFF
            }
            StandbyAttemptRequest.Toggle -> BleOperationKind.STANDBY_TOGGLE
        }

    private fun isCurrentStandbyRequest(token: StandbyRequestToken): Boolean =
        standbyGeneration.isCurrent(token) &&
            SelectedFootRepository.selected.value?.address == token.targetAddress

    private fun standbyPrerequisiteError(
        ctx: Context,
        target: SelectedFoot,
        requireSafeSession: Boolean
    ): String? {
        if (SelectedFootRepository.current(ctx)?.address != target.address) {
            return "Selected foot changed"
        }
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) return "Bluetooth permission is required"
        val enabled = try {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter?.isEnabled == true
        } catch (_: Exception) {
            false
        }
        if (!enabled) return "Turn on Bluetooth"
        if (requireSafeSession &&
            !LiveConnection.isReady() && !LiveConnection.canUseTemporarySession()
        ) {
            return "Bluetooth connection is not ready"
        }
        return null
    }

    suspend fun adjustFine(
        ctx: Context,
        adjustment: FineAdjustment
    ): FootOperationResult = runAnkleRequest(
        ctx = ctx,
        request = AnkleTargetRequest.Fine(adjustment),
        kind = BleOperationKind.ANKLE_ADJUST
    )

    suspend fun applyPreset(
        ctx: Context,
        preset: FootwearPreset
    ): FootOperationResult {
        val app = ctx.applicationContext
        BatteryRepo.ensureInitialized(app)
        AnkleRepo.ensureInitialized(app)
        PresetRepository.ensureInitialized(app)
        executionPrerequisiteError(app)?.let { return operationFailure(app, it) }
        PresetRepository.select(preset)
        val target = PresetRepository.state.value.targets.target(preset)
            ?: return operationFailure(app, "${preset.displayName} has no saved angle")
        return runAnkleRequest(
            ctx = app,
            request = AnkleTargetRequest.Absolute(target),
            kind = BleOperationKind.PRESET_APPLY
        )
    }

    suspend fun autoAlign(ctx: Context): FootOperationResult {
        val app = ctx.applicationContext
        BatteryRepo.ensureInitialized(app)
        AnkleRepo.ensureInitialized(app)
        PresetRepository.ensureInitialized(app)
        executionPrerequisiteError(app)?.let { return operationFailure(app, it) }
        var movementMayHaveOccurred = false

        val coordinated = try {
            BleOperationCoordinator.runDeviceControl(BleOperationKind.AUTO_ALIGN) {
                AnkleRepo.begin(AnkleOperation.AUTO_STARTING, "Automatic alignment")
                Alerts.showOperation(app, "Automatic alignment")
                val live = LiveConnection.readySession()
                when {
                    live != null -> autoAndApplyOnSession(app, live) {
                        movementMayHaveOccurred = true
                    }
                    !LiveConnection.canUseTemporarySession() ->
                        operationFailure(app, "Bluetooth connection is not ready")
                    else -> withTemporarySession(
                        ctx = app,
                        timeoutMs = null,
                        possibleMovement = { movementMayHaveOccurred }
                    ) { session ->
                        autoAndApplyOnSession(app, session) {
                            movementMayHaveOccurred = true
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            markCancelledMovementUnknown(app, movementMayHaveOccurred)
            throw e
        } catch (e: Exception) {
            return movementFailure(
                app,
                e.message ?: "Automatic alignment failed",
                movementMayHaveOccurred
            )
        }
        return finishCoordinated(app, coordinated)
    }

    private suspend fun refreshFootModes(ctx: Context, target: SelectedFoot) {
        modeExecutionPrerequisiteError(ctx, target)?.let {
            FootModeRepo.failRefresh(target.address, it)
            return
        }
        val coordinated = try {
            BleOperationCoordinator.tryRun(BleOperationKind.FOOT_MODES_REFRESH) {
                if (SelectedFootRepository.current(ctx)?.address != target.address) {
                    return@tryRun ModeSessionExecution.Failed("Selected foot changed")
                }
                val live = LiveConnection.readySession()
                when {
                    live != null -> {
                        refreshFootModesOnSession(target, live)
                        ModeSessionExecution.Success(Unit)
                    }
                    !LiveConnection.canUseTemporarySession() ->
                        ModeSessionExecution.Failed("Bluetooth connection is not ready")
                    else -> withTemporaryFootModeSession(ctx, target) { session ->
                        refreshFootModesOnSession(target, session)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FootModeRepo.failRefresh(target.address, e.message ?: "Foot mode check failed")
            return
        }

        when (coordinated) {
            is CoordinatedResult.Completed -> when (val result = coordinated.value) {
                is ModeSessionExecution.Success -> Unit
                is ModeSessionExecution.Failed ->
                    FootModeRepo.failRefresh(target.address, result.message)
            }
            CoordinatedResult.Busy ->
                FootModeRepo.failRefresh(target.address, "Another foot action is in progress")
        }
    }

    private suspend fun refreshFootModesOnSession(
        target: SelectedFoot,
        session: FootGattSession
    ) {
        FootModeRefresh.execute(
            transport = object : FootModeRefreshTransport {
                override suspend fun query(mode: FootMode): FootModeCommandExchangeResult =
                    session.queryFootMode(mode)
            },
            onResult = { read ->
                FootModeRepo.applyQuery(target.address, read)
                debugFootMode(
                    "FOOT_MODE_VERIFY ${read.mode.name} ${read.value?.name ?: "UNKNOWN"}"
                )
            }
        )
    }

    private suspend fun runFootModeIntent(
        ctx: Context,
        target: SelectedFoot,
        token: FootModeIntentToken
    ) {
        FootModeOneShotRetry().run(
            stillCurrent = {
                FootModeRepo.isCurrent(token) &&
                    SelectedFootRepository.selected.value?.address == token.targetAddress
            },
            publishSecondsRemaining = {
                FootModeRepo.updateRetrySeconds(token, it)
            },
            onRetryScheduled = {
                debugFootMode(
                    "FOOT_MODE_RETRY ${token.mode.name} ${token.requested.name} scheduled"
                )
                FootModeRepo.beginRetry(token)
            },
            onRetryStarting = {
                FootModeRepo.beginRetryAttempt(token)
            },
            attempt = {
                performFootModeAttempt(ctx, target, token).also { result ->
                    when (result) {
                        is FootModeMutationAttemptResult.Transaction -> {
                            FootModeRepo.applyMutation(token, result.read)
                            debugFootMode(
                                "FOOT_MODE_VERIFY ${token.mode.name} " +
                                    (result.read.finalValue?.name ?: "UNKNOWN")
                            )
                        }
                        is FootModeMutationAttemptResult.TransientFailure ->
                            FootModeRepo.applyTransientFailure(token, result.message)
                        is FootModeMutationAttemptResult.Rejected ->
                            FootModeRepo.applyRejectedIntent(token, result.message)
                        FootModeMutationAttemptResult.Busy ->
                            FootModeRepo.applyRejectedIntent(
                                token,
                                "Another foot action is in progress"
                            )
                    }
                }
            }
        )
    }

    private suspend fun performFootModeAttempt(
        ctx: Context,
        target: SelectedFoot,
        token: FootModeIntentToken
    ): FootModeMutationAttemptResult {
        modeMutationPrerequisiteError(ctx, target)?.let {
            return FootModeMutationAttemptResult.Rejected(it)
        }
        val kind = footModeOperationKind(token.mode, token.requested)
        val coordinated = try {
            BleOperationCoordinator.runDeviceControl(kind) {
                if (!FootModeRepo.isCurrent(token) ||
                    SelectedFootRepository.current(ctx)?.address != target.address
                ) {
                    return@runDeviceControl FootModeMutationAttemptResult.Rejected(
                        "Selected foot changed"
                    )
                }
                val live = LiveConnection.readySession()
                when {
                    live != null -> FootModeMutationAttemptResult.Transaction(
                        live.changeFootMode(token.mode, token.requested)
                    )
                    !LiveConnection.canUseTemporarySession() ->
                        FootModeMutationAttemptResult.TransientFailure(
                            "Bluetooth connection is not ready"
                        )
                    else -> when (val result = withTemporaryFootModeSession(ctx, target) { session ->
                        FootModeMutationAttemptResult.Transaction(
                            session.changeFootMode(token.mode, token.requested)
                        )
                    }) {
                        is ModeSessionExecution.Success -> result.value
                        is ModeSessionExecution.Failed ->
                            FootModeMutationAttemptResult.TransientFailure(result.message)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FootModeMutationAttemptResult.TransientFailure(
                e.message ?: "Bluetooth operation failed"
            )
        }
        return when (coordinated) {
            is CoordinatedResult.Completed -> coordinated.value
            CoordinatedResult.Busy -> FootModeMutationAttemptResult.Busy
        }
    }

    private fun footModeOperationKind(
        mode: FootMode,
        requested: FootModeValue
    ): BleOperationKind {
        require(requested != FootModeValue.UNKNOWN)
        return when (mode) {
            FootMode.CHAIR_EXIT -> if (requested == FootModeValue.ON) {
                BleOperationKind.CHAIR_EXIT_ON
            } else {
                BleOperationKind.CHAIR_EXIT_OFF
            }
            FootMode.RELAX -> if (requested == FootModeValue.ON) {
                BleOperationKind.RELAX_ON
            } else {
                BleOperationKind.RELAX_OFF
            }
        }
    }

    private sealed interface ModeSessionExecution<out T> {
        data class Success<T>(val value: T) : ModeSessionExecution<T>
        data class Failed(val message: String) : ModeSessionExecution<Nothing>
    }

    private suspend fun <T> withTemporaryFootModeSession(
        ctx: Context,
        target: SelectedFoot,
        block: suspend (FootGattSession) -> T
    ): ModeSessionExecution<T> {
        val session = FootGattSession(ctx, target)
        return try {
            withTimeout(30_000L) {
                session.connectAndInitialize()
                ModeSessionExecution.Success(block(session))
            }
        } catch (_: TimeoutCancellationException) {
            ModeSessionExecution.Failed("Bluetooth transaction timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ModeSessionExecution.Failed(e.message ?: "Bluetooth operation failed")
        } finally {
            withContext(NonCancellable) {
                BleTargetReleaseBarrier.releaseTemporarySession(ctx, session)
            }
        }
    }

    private fun modeExecutionPrerequisiteError(ctx: Context, target: SelectedFoot): String? {
        modeMutationPrerequisiteError(ctx, target)?.let { return it }
        if (!LiveConnection.isReady() && !LiveConnection.canUseTemporarySession()) {
            return "Bluetooth connection is not ready"
        }
        return null
    }

    private fun modeMutationPrerequisiteError(ctx: Context, target: SelectedFoot): String? {
        if (SelectedFootRepository.current(ctx)?.address != target.address) {
            return "Selected foot changed"
        }
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) return "Bluetooth permission is required"
        val enabled = try {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter?.isEnabled == true
        } catch (_: Exception) {
            false
        }
        if (!enabled) return "Turn on Bluetooth"
        return null
    }

    private fun debugFootMode(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private suspend fun runAnkleRequest(
        ctx: Context,
        request: AnkleTargetRequest,
        kind: BleOperationKind
    ): FootOperationResult {
        val app = ctx.applicationContext
        BatteryRepo.ensureInitialized(app)
        AnkleRepo.ensureInitialized(app)
        PresetRepository.ensureInitialized(app)
        val absolute = (request as? AnkleTargetRequest.Absolute)?.targetMd
        if (absolute != null && !AnkleProtocol.isSupported(absolute)) {
            return operationFailure(app, "Ankle target is outside -2.0° to +14.0°")
        }
        executionPrerequisiteError(app)?.let { return operationFailure(app, it) }
        var movementMayHaveOccurred = false

        val coordinated = try {
            BleOperationCoordinator.runDeviceControl(kind) {
                AnkleRepo.begin(AnkleOperation.SETTING, kind.statusText)
                Alerts.showOperation(app, kind.statusText)
                val live = LiveConnection.readySession()
                when {
                    live != null -> ankleAndApplyOnSession(app, live, request) {
                        movementMayHaveOccurred = true
                    }
                    !LiveConnection.canUseTemporarySession() ->
                        operationFailure(app, "Bluetooth connection is not ready")
                    else -> withTemporarySession(
                        ctx = app,
                        possibleMovement = { movementMayHaveOccurred }
                    ) { session ->
                        ankleAndApplyOnSession(app, session, request) {
                            movementMayHaveOccurred = true
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            markCancelledMovementUnknown(app, movementMayHaveOccurred)
            throw e
        } catch (e: Exception) {
            return movementFailure(
                app,
                e.message ?: "Ankle adjustment failed",
                movementMayHaveOccurred
            )
        }
        return finishCoordinated(app, coordinated)
    }

    internal suspend fun readAndApplyOnSession(
        ctx: Context,
        session: FootGattSession,
        origin: CheckOrigin
    ): FootOperationResult {
        AnkleRepo.begin(AnkleOperation.QUERYING, "Checking ankle angle...")
        val read = session.readFullSnapshot()
        AnkleRepo.applySnapshotRead(ctx, read)
        val previous = BatteryRepo.snapshot.value
        val decision = classifyFullSnapshotRead(
            previous = previous,
            read = read,
            checkedAt = System.currentTimeMillis()
        )
        val reduction = decision.reduction
        applyFreshBattery(ctx, reduction.freshBatteryLevel)

        if (reduction.completeSnapshotSaved) {
            Prefs.saveCompleteSnapshot(ctx, reduction.snapshot)
            BatteryRepo.applySnapshot(reduction.snapshot)
            BatteryRepo.status.value = when (origin) {
                CheckOrigin.SCHEDULED -> "Checked (background)"
                CheckOrigin.LIVE_INITIAL, CheckOrigin.LIVE_RECONNECT -> "Monitoring"
                else -> "Checked"
            }
            BatteryRepo.standbyStatus.value = ""
            if (decision.result is FootOperationResult.Partial) {
                BatteryRepo.status.value = "${BatteryRepo.status.value} · ankle unavailable"
            }
            return decision.result
        }

        val message = (decision.result as FootOperationResult.Partial).message
        BatteryRepo.status.value = message
        BatteryRepo.standbyStatus.value = message
        return decision.result
    }

    private fun applyStandbyRead(
        ctx: Context,
        read: StandbyTransactionRead
    ): FootOperationResult {
        val requested = read.requested
        if (requested == StandbyState.UNKNOWN) {
            val message = read.error ?: "Standby could not be verified"
            BatteryRepo.status.value = message
            BatteryRepo.standbyStatus.value = message
            return FootOperationResult.Failed(message)
        }
        val previous = BatteryRepo.snapshot.value
        val reduction = SnapshotReducer.reduce(
            previous,
            SnapshotEvent.StandbyChange(
                requested = requested,
                finalState = read.finalState,
                verified = read.verified,
                batteryLevel = read.batteryLevel,
                checkedAt = System.currentTimeMillis(),
                ambiguous = read.ambiguous
            )
        )
        applyFreshBattery(ctx, reduction.freshBatteryLevel)

        if (reduction.completeSnapshotSaved) {
            Prefs.saveCompleteSnapshot(ctx, reduction.snapshot)
            BatteryRepo.applySnapshot(reduction.snapshot)
            val message = if (reduction.standbyChangeConfirmed) {
                "Standby ${requested.displayName()} confirmed"
            } else {
                read.error ?: "Standby change failed"
            }
            BatteryRepo.status.value = message
            BatteryRepo.standbyStatus.value = ""
            return if (reduction.standbyChangeConfirmed) {
                FootOperationResult.Complete(reduction.snapshot)
            } else {
                FootOperationResult.Failed(message)
            }
        }

        val snapshotChanged = reduction.snapshot != previous
        if (snapshotChanged && reduction.snapshot.completeness != SnapshotCompleteness.COMPLETE) {
            Prefs.saveIncompleteSnapshot(ctx, reduction.snapshot)
            BatteryRepo.applyIncompleteSnapshot(reduction.snapshot)
        }

        if (reduction.standbyChangeConfirmed && read.batteryLevel == null) {
            val message = if (read.setWriteAccepted) {
                "Standby changed, but battery check failed"
            } else {
                "Standby confirmed, but battery check failed"
            }
            BatteryRepo.status.value = message
            BatteryRepo.standbyStatus.value = message
            return FootOperationResult.Partial(message)
        }

        val message = read.error ?: "Standby change could not be confirmed"
        BatteryRepo.status.value = message
        BatteryRepo.standbyStatus.value = message
        return FootOperationResult.Failed(message)
    }

    private suspend fun ankleAndApplyOnSession(
        ctx: Context,
        session: FootGattSession,
        request: AnkleTargetRequest,
        onPotentialMovement: () -> Unit
    ): FootOperationResult {
        val read = session.changeAnkle(request, onPotentialMovement)
        applyFreshStandbyObservation(ctx, read.freshStandby)
        val confirmed = read.finalConfirmedMd
        if (read.finalTruthConfirmed && confirmed != null) {
            val message = read.error ?: "Ankle confirmed ${AnkleProtocol.format(confirmed)}"
            AnkleRepo.confirm(ctx, confirmed, message)
            BatteryRepo.status.value = message
            return if (read.requestSatisfied) {
                FootOperationResult.ControlComplete(message, confirmed)
            } else {
                FootOperationResult.Failed(message)
            }
        }
        val message = read.error ?: "Ankle adjustment failed"
        if (read.unknownAfterCommand) {
            AnkleRepo.unknownAfterCommand(ctx, message)
        } else {
            AnkleRepo.fail(message)
        }
        BatteryRepo.status.value = message
        return FootOperationResult.Failed(message)
    }

    private suspend fun autoAndApplyOnSession(
        ctx: Context,
        session: FootGattSession,
        onPotentialMovement: () -> Unit
    ): FootOperationResult {
        val read = session.autoAlign(
            onOperation = { operation ->
                val message = when (operation) {
                    AnkleOperation.AUTO_STARTING -> "Automatic alignment"
                    AnkleOperation.AUTO_RUNNING ->
                        "Keep foot flat until the second beep, then lift your foot."
                    AnkleOperation.VERIFYING -> "Verifying automatic alignment..."
                    else -> "Automatic alignment"
                }
                AnkleRepo.updateOperation(operation, message)
                Alerts.showOperation(ctx, message)
            },
            onPotentialMovement = onPotentialMovement
        )
        applyFreshStandbyObservation(ctx, read.freshStandby)
        val confirmed = read.finalConfirmedMd
        if (read.finalTruthConfirmed && confirmed != null) {
            val message = if (read.completionObserved) {
                "Aligned · ${AnkleProtocol.format(confirmed)}"
            } else {
                read.error ?: "Automatic alignment completion was not confirmed"
            }
            AnkleRepo.confirm(ctx, confirmed, message)
            BatteryRepo.status.value = message
            return if (read.completionObserved) {
                FootOperationResult.ControlComplete(message, confirmed)
            } else {
                FootOperationResult.Failed(message)
            }
        }
        val message = read.error ?: "Automatic alignment failed"
        if (read.unknownAfterCommand) {
            AnkleRepo.unknownAfterCommand(ctx, message)
        } else {
            AnkleRepo.fail(message)
        }
        BatteryRepo.status.value = message
        return FootOperationResult.Failed(message)
    }

    private suspend fun withTemporarySession(
        ctx: Context,
        timeoutMs: Long? = 30_000L,
        possibleMovement: () -> Boolean = { false },
        block: suspend (FootGattSession) -> FootOperationResult
    ): FootOperationResult {
        val target = SelectedFootRepository.current(ctx)
            ?: return operationFailure(ctx, "No foot selected")
        val session = FootGattSession(ctx, target)
        return try {
            val runSession: suspend () -> FootOperationResult = {
                session.connectAndInitialize()
                block(session)
            }
            if (timeoutMs == null) runSession() else withTimeout(timeoutMs) { runSession() }
        } catch (_: TimeoutCancellationException) {
            val message = "Bluetooth transaction timed out"
            BatteryRepo.status.value = message
            BatteryRepo.standbyStatus.value = message
            if (possibleMovement()) {
                AnkleRepo.unknownAfterCommand(ctx, "$message; ankle position requires verification")
            } else {
                AnkleRepo.fail(message)
            }
            FootOperationResult.Failed(message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "Bluetooth operation failed"
            BatteryRepo.status.value = message
            BatteryRepo.standbyStatus.value = message
            if (possibleMovement()) {
                AnkleRepo.unknownAfterCommand(ctx, "$message; ankle position requires verification")
            } else {
                AnkleRepo.fail(message)
            }
            FootOperationResult.Failed(message)
        } finally {
            // This remains inside the coordinator-owned operation. NonCancellable guarantees that
            // cancellation cannot strand its GATT, bond observer, or published release generation.
            withContext(NonCancellable) {
                BleTargetReleaseBarrier.releaseTemporarySession(ctx, session)
            }
        }
    }

    private fun executionPrerequisiteError(ctx: Context): String? {
        if (SelectedFootRepository.current(ctx) == null) return "No foot selected"
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) return "Bluetooth permission is required"
        val enabled = try {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter?.isEnabled == true
        } catch (_: Exception) {
            false
        }
        if (!enabled) return "Turn on Bluetooth"
        if (!LiveConnection.isReady() && !LiveConnection.canUseTemporarySession()) {
            return "Bluetooth connection is not ready"
        }
        return null
    }

    private fun operationFailure(ctx: Context, message: String): FootOperationResult.Failed {
        BatteryRepo.status.value = message
        BatteryRepo.standbyStatus.value = message
        AnkleRepo.fail(message)
        Alerts.refreshApplicable(ctx, message)
        return FootOperationResult.Failed(message)
    }

    private fun movementFailure(
        ctx: Context,
        message: String,
        possibleMovement: Boolean
    ): FootOperationResult.Failed {
        BatteryRepo.status.value = message
        BatteryRepo.standbyStatus.value = message
        if (possibleMovement) {
            AnkleRepo.unknownAfterCommand(
                ctx,
                "$message; ankle position requires verification"
            )
        } else {
            AnkleRepo.fail(message)
        }
        Alerts.refreshApplicable(ctx, message)
        return FootOperationResult.Failed(message)
    }

    private fun markCancelledMovementUnknown(ctx: Context, possibleMovement: Boolean) {
        if (possibleMovement) {
            AnkleRepo.unknownAfterCommand(
                ctx,
                "Ankle operation was interrupted after a command; Check now to verify"
            )
        } else {
            AnkleRepo.fail("Ankle operation cancelled")
        }
    }

    private fun finishCoordinated(
        ctx: Context,
        coordinated: CoordinatedResult<FootOperationResult>
    ): FootOperationResult = when (coordinated) {
        is CoordinatedResult.Completed -> coordinated.value.also { result ->
            Alerts.refreshApplicable(ctx, result.transientMessage())
        }
        CoordinatedResult.Busy -> FootOperationResult.Busy
    }

    private fun applyFreshBattery(ctx: Context, batteryLevel: Int?) {
        FreshBatteryResultHandler.handle(
            batteryLevel = batteryLevel,
            updateLiveLevel = { BatteryRepo.level.value = it },
            evaluateLowBattery = { Alerts.maybeAlert(ctx, it) }
        )
    }

    private fun applyFreshStandbyObservation(ctx: Context, observed: StandbyState?) {
        if (observed == null || observed == StandbyState.UNKNOWN) return
        val previous = BatteryRepo.snapshot.value
        val updated = snapshotAfterStandbyObservation(previous, observed)
        if (updated == previous) return
        Prefs.saveIncompleteSnapshot(ctx, updated)
        BatteryRepo.applyIncompleteSnapshot(updated)
    }

    private fun FootOperationResult.transientMessage(): String? = when (this) {
        is FootOperationResult.ControlComplete -> message
        is FootOperationResult.Partial -> message
        is FootOperationResult.Failed -> message
        else -> null
    }

}
