package com.onomic.footpilot

import android.content.Context
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow

data class SelectedFoot(
    val name: String,
    val address: String
)

data class StoredSelectedFoot(
    val name: String?,
    val address: String?
)

/** Strict, Android-free validation for saved and newly verified targets. */
object SelectedFootPersistence {
    private val ADDRESS = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")

    fun decode(name: String?, address: String?): SelectedFoot? {
        val cleanName = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cleanAddress = address?.trim()?.uppercase(Locale.US) ?: return null
        if (!ADDRESS.matches(cleanAddress)) return null
        return SelectedFoot(cleanName, cleanAddress)
    }

    fun decode(stored: StoredSelectedFoot): SelectedFoot? = decode(stored.name, stored.address)

    fun encode(selectedFoot: SelectedFoot?): StoredSelectedFoot {
        val valid = selectedFoot?.let { decode(it.name, it.address) }
        return StoredSelectedFoot(valid?.name, valid?.address)
    }
}

sealed interface SelectedFootChangeResult {
    data class Changed(val selectedFoot: SelectedFoot?) : SelectedFootChangeResult
    data object Unchanged : SelectedFootChangeResult
    data class Blocked(val message: String) : SelectedFootChangeResult
    data class Failed(val message: String) : SelectedFootChangeResult
}

/** Process-wide selected-target state backed by the typed preferences store. */
object SelectedFootRepository {
    private val initialized = AtomicBoolean(false)
    val selected = MutableStateFlow<SelectedFoot?>(null)

    fun ensureInitialized(ctx: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        selected.value = Prefs.selectedFoot(app)
        if (selected.value == null) {
            // A beta2 upgrade intentionally has no implicit target and cannot keep polling one.
            Prefs.disableConnectionAutomation(app)
            PollScheduler.apply(app, enabled = false, intervalMinutes = Prefs.intervalMin(app))
            Alerts.cancelFootSpecificNotifications(app)
        }
    }

    fun current(ctx: Context): SelectedFoot? {
        ensureInitialized(ctx.applicationContext)
        return selected.value
    }

    fun canChangeNow(): Boolean =
        !LiveConnection.isMonitoringRequested() &&
            !BatteryRepo.running.value &&
            !BleOperationCoordinator.isBusy() &&
            AnkleRepo.state.value.operation == AnkleOperation.IDLE

    suspend fun replace(ctx: Context, target: SelectedFoot): SelectedFootChangeResult {
        val app = ctx.applicationContext
        ensureInitialized(app)
        val verified = SelectedFootPersistence.decode(target.name, target.address)
            ?: return SelectedFootChangeResult.Failed("The verified foot could not be saved.")
        if (!canChangeNow()) {
            return SelectedFootChangeResult.Blocked("Disconnect before changing the foot.")
        }
        val coordinated = BleOperationCoordinator.tryRun(BleOperationKind.FOOT_SELECTION_CHANGE) {
            if (!nonCoordinatorChangeSafety()) {
                SelectedFootChangeResult.Blocked("Disconnect before changing the foot.")
            } else {
                replaceWhileCoordinated(app, verified)
            }
        }
        return when (coordinated) {
            is CoordinatedResult.Completed -> coordinated.value
            CoordinatedResult.Busy ->
                SelectedFootChangeResult.Blocked("Disconnect before changing the foot.")
        }
    }

    suspend fun remove(ctx: Context): SelectedFootChangeResult {
        val app = ctx.applicationContext
        ensureInitialized(app)
        if (!canChangeNow()) {
            return SelectedFootChangeResult.Blocked("Disconnect before changing the foot.")
        }
        val coordinated = BleOperationCoordinator.tryRun(BleOperationKind.FOOT_SELECTION_CHANGE) {
            if (!nonCoordinatorChangeSafety()) {
                SelectedFootChangeResult.Blocked("Disconnect before changing the foot.")
            } else {
                removeWhileCoordinated(app)
            }
        }
        return when (coordinated) {
            is CoordinatedResult.Completed -> coordinated.value
            CoordinatedResult.Busy ->
                SelectedFootChangeResult.Blocked("Disconnect before changing the foot.")
        }
    }

    private fun nonCoordinatorChangeSafety(): Boolean =
        !LiveConnection.isMonitoringRequested() &&
            !BatteryRepo.running.value &&
            AnkleRepo.state.value.operation == AnkleOperation.IDLE

    private fun replaceWhileCoordinated(
        app: Context,
        verified: SelectedFoot
    ): SelectedFootChangeResult {
        val previous = selected.value
        if (previous == verified) return SelectedFootChangeResult.Unchanged
        val pollingWasEnabled = Prefs.polling(app)
        PollScheduler.apply(app, enabled = false, intervalMinutes = Prefs.intervalMin(app))
        Alerts.cancelFootSpecificNotifications(app)
        if (!Prefs.replaceSelectedFoot(app, verified)) {
            restorePollingAfterFailedCommit(app, pollingWasEnabled)
            return SelectedFootChangeResult.Failed("The verified foot could not be saved.")
        }
        selected.value = verified
        resetInMemoryDeviceState(hasSelectedFoot = true)
        return SelectedFootChangeResult.Changed(verified)
    }

    private fun removeWhileCoordinated(app: Context): SelectedFootChangeResult {
        if (selected.value == null) return SelectedFootChangeResult.Unchanged
        val pollingWasEnabled = Prefs.polling(app)
        PollScheduler.apply(app, enabled = false, intervalMinutes = Prefs.intervalMin(app))
        Alerts.cancelFootSpecificNotifications(app)
        if (!Prefs.replaceSelectedFoot(app, null)) {
            restorePollingAfterFailedCommit(app, pollingWasEnabled)
            return SelectedFootChangeResult.Failed("The selected foot could not be removed.")
        }
        selected.value = null
        resetInMemoryDeviceState(hasSelectedFoot = false)
        return SelectedFootChangeResult.Changed(null)
    }

    private fun restorePollingAfterFailedCommit(app: Context, pollingWasEnabled: Boolean) {
        if (!pollingWasEnabled) return
        PollScheduler.apply(app, enabled = true, intervalMinutes = Prefs.intervalMin(app))
        Alerts.updatePollStatus(app)
    }

    private fun resetInMemoryDeviceState(hasSelectedFoot: Boolean) {
        FootOperations.cancelPendingFootModeOperations()
        FootOperations.cancelPendingStandbyOperations()
        BatteryRepo.resetForFootChange(hasSelectedFoot)
        AnkleRepo.resetForFootChange()
        PresetRepository.resetForFootChange()
        FootModeRepo.resetForFootChange(selected.value?.address)
    }
}
