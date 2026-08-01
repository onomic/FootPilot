package com.example.footbattery

import android.content.Context
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

    suspend fun checkNow(ctx: Context, origin: CheckOrigin): FootOperationResult {
        val app = ctx.applicationContext
        BatteryRepo.ensureInitialized(app)
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

        if (BatteryRepo.snapshot.value.standby == StandbyState.UNKNOWN) {
            val message = "Check now to verify standby"
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

    internal suspend fun readAndApplyOnSession(
        ctx: Context,
        session: FootGattSession,
        origin: CheckOrigin
    ): FootOperationResult {
        val read = session.readFullSnapshot()
        val previous = BatteryRepo.snapshot.value
        val reduction = SnapshotReducer.reduce(
            previous,
            SnapshotEvent.NormalCheck(
                batteryLevel = read.batteryLevel,
                standby = read.standby,
                checkedAt = System.currentTimeMillis()
            )
        )

        if (reduction.completeSnapshotSaved) {
            Prefs.saveCompleteSnapshot(ctx, reduction.snapshot)
            BatteryRepo.applySnapshot(reduction.snapshot)
            Alerts.maybeAlert(ctx, reduction.snapshot.batteryLevel!!)
            BatteryRepo.status.value = when (origin) {
                CheckOrigin.SCHEDULED -> "Checked (background)"
                CheckOrigin.LIVE_INITIAL, CheckOrigin.LIVE_RECONNECT -> "Monitoring"
                else -> "Checked"
            }
            BatteryRepo.standbyStatus.value = ""
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
    ): FootOperationResult {
        val read = session.changeStandby(requested)
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

        if (reduction.completeSnapshotSaved) {
            Prefs.saveCompleteSnapshot(ctx, reduction.snapshot)
            BatteryRepo.applySnapshot(reduction.snapshot)
            Alerts.maybeAlert(ctx, reduction.snapshot.batteryLevel!!)
            val message = "Standby ${requested.displayName()} confirmed"
            BatteryRepo.status.value = message
            BatteryRepo.standbyStatus.value = ""
            return FootOperationResult.Complete(reduction.snapshot)
        }

        val stateChanged = reduction.snapshot.standby != previous.standby
        if (stateChanged || reduction.standbyChangeConfirmed) {
            Prefs.saveStandbyOnly(ctx, reduction.snapshot.standby)
            BatteryRepo.applyStandbyOnly(reduction.snapshot.standby)
        }

        if (reduction.standbyChangeConfirmed && read.batteryLevel == null) {
            val message = if (read.setCommandSent) {
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

    private suspend fun withTemporarySession(
        ctx: Context,
        block: suspend (FootGattSession) -> FootOperationResult
    ): FootOperationResult {
        val session = FootGattSession(ctx)
        return try {
            withTimeout(30_000L) {
                session.connectAndInitialize()
                block(session)
            }
        } catch (_: TimeoutCancellationException) {
            val message = "Bluetooth transaction timed out"
            BatteryRepo.status.value = message
            BatteryRepo.standbyStatus.value = message
            FootOperationResult.Failed(message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "Bluetooth operation failed"
            BatteryRepo.status.value = message
            BatteryRepo.standbyStatus.value = message
            FootOperationResult.Failed(message)
        } finally {
            session.disconnectAndClose(removeBond = true)
        }
    }

    private fun partialCheckMessage(read: FullSnapshotRead): String = when {
        read.batteryLevel != null && read.standby == null ->
            read.standbyError ?: "Standby check failed"
        read.batteryLevel == null && read.standby != null ->
            read.batteryError ?: "Battery check failed"
        else -> read.standbyError ?: read.batteryError ?: "Check failed — is the foot in range?"
    }

    private fun FootOperationResult.transientMessage(): String? = when (this) {
        is FootOperationResult.Partial -> message
        is FootOperationResult.Failed -> message
        else -> null
    }
}
