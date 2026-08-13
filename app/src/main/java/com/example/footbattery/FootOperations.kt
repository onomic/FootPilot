package com.example.footbattery

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
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

/** Shared high-level transactions for the activity, notification service, worker, and live link. */
object FootOperations {
    private val userScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun launchManualCheck(ctx: Context) {
        val app = ctx.applicationContext
        userScope.launch { checkNow(app, CheckOrigin.MANUAL) }
    }

    fun launchStandbyChange(ctx: Context, requested: StandbyState) {
        val app = ctx.applicationContext
        userScope.launch { changeStandby(app, requested) }
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

    suspend fun checkNow(ctx: Context, origin: CheckOrigin): FootOperationResult {
        val app = ctx.applicationContext
        BatteryRepo.ensureInitialized(app)
        AnkleRepo.ensureInitialized(app)
        PresetRepository.ensureInitialized(app)
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
        BatteryRepo.ensureInitialized(app)
        if (!Prefs.polling(app) || !LiveConnection.canUseTemporarySession()) {
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

        if (BatteryRepo.snapshot.value.standby == StandbyState.UNKNOWN) {
            val message = if (
                BatteryRepo.snapshot.value.completeness ==
                SnapshotCompleteness.STANDBY_STATE_UNKNOWN_AFTER_COMMAND
            ) {
                "Check now to restore a confirmed standby state"
            } else {
                "Check now to verify standby"
            }
            BatteryRepo.standbyStatus.value = message
            Alerts.refreshApplicable(app, message)
            return FootOperationResult.Failed(message)
        }
        if (!LiveConnection.isReady() && !LiveConnection.canUseTemporarySession()) {
            val message = "Bluetooth connection is not ready"
            BatteryRepo.status.value = message
            BatteryRepo.standbyStatus.value = message
            Alerts.refreshApplicable(app, message)
            return FootOperationResult.Failed(message)
        }

        val kind = if (requested == StandbyState.ON) {
            BleOperationKind.STANDBY_ON
        } else {
            BleOperationKind.STANDBY_OFF
        }
        val operationText = kind.statusText
        BatteryRepo.status.value = operationText
        BatteryRepo.standbyStatus.value = operationText
        Alerts.showOperation(app, operationText)

        val coordinated = try {
            BleOperationCoordinator.runStandby(kind) {
                BatteryRepo.status.value = operationText
                BatteryRepo.standbyStatus.value = operationText
                Alerts.showOperation(app, operationText)
                val live = LiveConnection.readySession()
                when {
                    live != null -> changeAndApplyOnSession(app, live, requested)
                    !LiveConnection.canUseTemporarySession() -> {
                        val message = "Bluetooth connection is not ready"
                        BatteryRepo.status.value = message
                        BatteryRepo.standbyStatus.value = message
                        FootOperationResult.Failed(message)
                    }
                    else -> withTemporarySession(app) { session ->
                        changeAndApplyOnSession(app, session, requested)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "Bluetooth operation failed"
            BatteryRepo.status.value = message
            BatteryRepo.standbyStatus.value = message
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

    /** Notification Standby action: target is derived from a fresh query on the acquired session. */
    suspend fun toggleStandby(ctx: Context): FootOperationResult {
        val app = ctx.applicationContext
        BatteryRepo.ensureInitialized(app)
        AnkleRepo.ensureInitialized(app)
        PresetRepository.ensureInitialized(app)
        executionPrerequisiteError(app)?.let { return operationFailure(app, it) }

        val coordinated = try {
            BleOperationCoordinator.runDeviceControl(BleOperationKind.STANDBY_TOGGLE) {
                val text = BleOperationKind.STANDBY_TOGGLE.statusText
                BatteryRepo.status.value = text
                BatteryRepo.standbyStatus.value = text
                Alerts.showOperation(app, text)
                val live = LiveConnection.readySession()
                when {
                    live != null -> applyStandbyRead(app, live.toggleStandby())
                    !LiveConnection.canUseTemporarySession() ->
                        FootOperationResult.Failed("Bluetooth connection is not ready")
                    else -> withTemporarySession(app) { session ->
                        applyStandbyRead(app, session.toggleStandby())
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return operationFailure(app, e.message ?: "Bluetooth operation failed")
        }
        return finishCoordinated(app, coordinated)
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
        if (read.ankleMd != null) {
            AnkleRepo.confirm(ctx, read.ankleMd)
        } else {
            AnkleRepo.verificationFailed(
                ctx,
                read.ankleError ?: "Ankle angle could not be verified"
            )
        }
        val previous = BatteryRepo.snapshot.value
        val reduction = SnapshotReducer.reduce(
            previous,
            SnapshotEvent.NormalCheck(
                batteryLevel = read.batteryLevel,
                standby = read.standby,
                checkedAt = System.currentTimeMillis()
            )
        )
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
            if (read.ankleMd == null) {
                val message = read.ankleError ?: "Ankle angle could not be verified"
                BatteryRepo.status.value = "${BatteryRepo.status.value} · ankle unavailable"
                return FootOperationResult.Partial(message)
            }
            return FootOperationResult.Complete(reduction.snapshot)
        }

        val message = partialCheckMessage(read)
        BatteryRepo.status.value = message
        BatteryRepo.standbyStatus.value = message
        return FootOperationResult.Partial(message)
    }

    private suspend fun changeAndApplyOnSession(
        ctx: Context,
        session: FootGattSession,
        requested: StandbyState
    ): FootOperationResult = applyStandbyRead(ctx, session.changeStandby(requested))

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
                "Aligned · ${AnkleProtocol.format(confirmed)} ✓"
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
        val session = FootGattSession(ctx)
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
            session.disconnectAndClose(removeBond = true)
        }
    }

    private fun executionPrerequisiteError(ctx: Context): String? {
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

    private fun partialCheckMessage(read: FullSnapshotRead): String = when {
        read.batteryLevel != null && read.standby != null && read.ankleMd == null ->
            read.ankleError ?: "Ankle check failed"
        read.batteryLevel != null && read.standby == null ->
            read.standbyError ?: "Standby check failed"
        read.batteryLevel == null && read.standby != null ->
            read.batteryError ?: "Battery check failed"
        else -> read.standbyError ?: read.batteryError ?: "Check failed — is the foot in range?"
    }

    private fun FootOperationResult.transientMessage(): String? = when (this) {
        is FootOperationResult.ControlComplete -> message
        is FootOperationResult.Partial -> message
        is FootOperationResult.Failed -> message
        else -> null
    }

}
