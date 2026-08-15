package com.example.footbattery

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred

sealed interface TargetReleaseOutcome {
    data object Complete : TargetReleaseOutcome
    data object AlreadyReleased : TargetReleaseOutcome
    data class Uncertain(val reason: String) : TargetReleaseOutcome
}

internal enum class TargetBondState {
    UNBONDED,
    BONDED_OR_BONDING,
    UNKNOWN
}

internal sealed interface BondRemovalRequestOutcome {
    data object AlreadyUnbonded : BondRemovalRequestOutcome
    data object Requested : BondRemovalRequestOutcome
    data class Failed(val reason: String) : BondRemovalRequestOutcome
}

/** A short-lived, target-filtered bond observer prepared before removal is requested. */
internal interface BondReleaseObservation : AutoCloseable {
    fun currentState(): TargetBondState
    fun requestRemoval(): BondRemovalRequestOutcome
    suspend fun awaitUnbonded(): TargetReleaseOutcome
}

/** Pure release decision logic; Android callbacks and broadcasts stay behind the two inputs. */
internal object TargetReleaseEngine {
    suspend fun release(
        disconnectAndClose: suspend () -> TargetReleaseOutcome,
        bond: BondReleaseObservation
    ): TargetReleaseOutcome = try {
        val disconnectOutcome = disconnectAndClose()
        val bondOutcome = releaseBond(bond)
        combineReleaseOutcomes(disconnectOutcome, bondOutcome)
    } finally {
        bond.close()
    }

    private suspend fun releaseBond(bond: BondReleaseObservation): TargetReleaseOutcome {
        if (bond.currentState() == TargetBondState.UNBONDED) {
            return TargetReleaseOutcome.AlreadyReleased
        }

        return when (val request = bond.requestRemoval()) {
            BondRemovalRequestOutcome.AlreadyUnbonded -> TargetReleaseOutcome.AlreadyReleased
            BondRemovalRequestOutcome.Requested -> {
                if (bond.currentState() == TargetBondState.UNBONDED) {
                    TargetReleaseOutcome.Complete
                } else {
                    bond.awaitUnbonded()
                }
            }
            is BondRemovalRequestOutcome.Failed -> {
                if (bond.currentState() == TargetBondState.UNBONDED) {
                    TargetReleaseOutcome.Complete
                } else {
                    TargetReleaseOutcome.Uncertain(request.reason)
                }
            }
        }
    }
}

internal fun combineReleaseOutcomes(
    first: TargetReleaseOutcome,
    second: TargetReleaseOutcome
): TargetReleaseOutcome {
    val reasons = listOfNotNull(
        (first as? TargetReleaseOutcome.Uncertain)?.reason,
        (second as? TargetReleaseOutcome.Uncertain)?.reason
    )
    return when {
        reasons.isNotEmpty() -> TargetReleaseOutcome.Uncertain(reasons.joinToString("; "))
        first == TargetReleaseOutcome.AlreadyReleased &&
            second == TargetReleaseOutcome.AlreadyReleased -> TargetReleaseOutcome.AlreadyReleased
        else -> TargetReleaseOutcome.Complete
    }
}

/** Pure exact-address event filter used by the Android bond broadcast observer. */
internal class TargetBondReleaseSignal(targetAddress: String) {
    private val expectedAddress = normalizeTargetAddress(targetAddress)
    private val released = CompletableDeferred<Unit>()

    fun onBondStateChanged(address: String?, state: TargetBondState) {
        if (address == null || normalizeTargetAddress(address) != expectedAddress) return
        if (state == TargetBondState.UNBONDED) released.complete(Unit)
    }

    fun isReleased(): Boolean = released.isCompleted

    suspend fun await() = released.await()
}

internal sealed interface TargetReleaseState {
    data object Pending : TargetReleaseState
    data class Released(val outcome: TargetReleaseOutcome) : TargetReleaseState
}

internal data class TargetReleaseSnapshot(
    val address: String,
    val generation: Long,
    val state: TargetReleaseState
)

internal class TargetReleaseToken internal constructor(
    val address: String,
    val generation: Long,
    internal val completion: CompletableDeferred<TargetReleaseOutcome>
)

/**
 * Process-local release generations. This is observable state, not another BLE ownership lock;
 * [BleOperationCoordinator] remains the only transaction owner.
 */
internal class TargetReleaseTracker {
    private data class Entry(
        val token: TargetReleaseToken,
        var outcome: TargetReleaseOutcome? = null,
        var recoveryClaimed: Boolean = false
    )

    private val guard = Any()
    private val nextGeneration = AtomicLong(0L)
    private val entries = mutableMapOf<String, Entry>()

    fun begin(address: String): TargetReleaseToken {
        val normalized = normalizeTargetAddress(address)
        val token = TargetReleaseToken(
            address = normalized,
            generation = nextGeneration.incrementAndGet(),
            completion = CompletableDeferred()
        )
        synchronized(guard) {
            entries[normalized] = Entry(token)
        }
        return token
    }

    fun complete(token: TargetReleaseToken, outcome: TargetReleaseOutcome): Boolean {
        return synchronized(guard) {
            val current = entries[token.address]
            if (current?.token?.generation == token.generation && current.outcome == null) {
                current.outcome = outcome
            }
            // Complete while holding the same guard so a resumed waiter cannot observe PENDING
            // after the terminal deferred has already fired.
            token.completion.complete(outcome)
        }
    }

    fun snapshot(address: String): TargetReleaseSnapshot? = synchronized(guard) {
        entries[normalizeTargetAddress(address)]?.toSnapshot()
    }

    suspend fun awaitLatest(address: String): TargetReleaseSnapshot? {
        val normalized = normalizeTargetAddress(address)
        while (true) {
            val entry = synchronized(guard) { entries[normalized] } ?: return null
            val outcome = synchronized(guard) { entry.outcome }
            if (outcome != null) {
                return TargetReleaseSnapshot(
                    address = normalized,
                    generation = entry.token.generation,
                    state = TargetReleaseState.Released(outcome)
                )
            }
            entry.token.completion.await()
        }
    }

    fun claimUncertainRecovery(snapshot: TargetReleaseSnapshot): Boolean = synchronized(guard) {
        val released = snapshot.state as? TargetReleaseState.Released ?: return@synchronized false
        if (released.outcome !is TargetReleaseOutcome.Uncertain) return@synchronized false
        val current = entries[snapshot.address] ?: return@synchronized false
        if (current.token.generation != snapshot.generation ||
            current.outcome !is TargetReleaseOutcome.Uncertain ||
            current.recoveryClaimed
        ) {
            return@synchronized false
        }
        current.recoveryClaimed = true
        true
    }

    private fun Entry.toSnapshot(): TargetReleaseSnapshot = TargetReleaseSnapshot(
        address = token.address,
        generation = token.generation,
        state = outcome?.let(TargetReleaseState::Released) ?: TargetReleaseState.Pending
    )
}

internal fun normalizeTargetAddress(address: String): String =
    address.trim().uppercase(Locale.US)
