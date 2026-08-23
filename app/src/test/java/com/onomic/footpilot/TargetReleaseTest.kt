package com.onomic.footpilot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetReleaseTest {
    @Test fun alreadyDisconnectedAndUnbondedResolvesImmediately() = runBlocking {
        val bond = FakeBondObservation(state = TargetBondState.UNBONDED)

        val outcome = TargetReleaseEngine.release(
            disconnectAndClose = { TargetReleaseOutcome.AlreadyReleased },
            bond = bond
        )

        assertSame(TargetReleaseOutcome.AlreadyReleased, outcome)
        assertEquals(0, bond.requestCount)
        assertEquals(0, bond.awaitCount)
        assertTrue(bond.closed)
    }

    @Test fun acceptedUnbondRequestDoesNotMeanReleaseIsComplete() = runBlocking {
        val released = CompletableDeferred<TargetReleaseOutcome>()
        val bond = FakeBondObservation(
            state = TargetBondState.BONDED_OR_BONDING,
            request = BondRemovalRequestOutcome.Requested,
            wait = { released.await() }
        )
        val operation = async {
            TargetReleaseEngine.release(
                disconnectAndClose = { TargetReleaseOutcome.AlreadyReleased },
                bond = bond
            )
        }

        yield()
        assertEquals(1, bond.requestCount)
        assertFalse(operation.isCompleted)

        released.complete(TargetReleaseOutcome.Complete)
        assertSame(TargetReleaseOutcome.Complete, operation.await())
    }

    @Test fun matchingBondNoneCompletesTargetSignal() = runBlocking {
        val signal = TargetBondReleaseSignal("AA:BB:CC:DD:EE:FF")

        signal.onBondStateChanged("aa:bb:cc:dd:ee:ff", TargetBondState.UNBONDED)

        assertTrue(signal.isReleased())
        signal.await()
    }

    @Test fun anotherTargetsBondEventIsIgnored() {
        val signal = TargetBondReleaseSignal("AA:BB:CC:DD:EE:FF")

        signal.onBondStateChanged("11:22:33:44:55:66", TargetBondState.UNBONDED)

        assertFalse(signal.isReleased())
    }

    @Test fun gattDisconnectMustFinishBeforeReleaseCompletes() = runBlocking {
        val disconnected = CompletableDeferred<TargetReleaseOutcome>()
        val operation = async {
            TargetReleaseEngine.release(
                disconnectAndClose = { disconnected.await() },
                bond = FakeBondObservation(state = TargetBondState.UNBONDED)
            )
        }

        yield()
        assertFalse(operation.isCompleted)

        disconnected.complete(TargetReleaseOutcome.Complete)
        assertSame(TargetReleaseOutcome.Complete, operation.await())
    }

    @Test fun missingGattDisconnectIsExplicitlyUncertain() = runBlocking {
        val outcome = TargetReleaseEngine.release(
            disconnectAndClose = {
                TargetReleaseOutcome.Uncertain("GATT disconnect callback timed out")
            },
            bond = FakeBondObservation(state = TargetBondState.UNBONDED)
        )

        assertTrue(outcome is TargetReleaseOutcome.Uncertain)
        assertTrue((outcome as TargetReleaseOutcome.Uncertain).reason.contains("GATT"))
    }

    @Test fun missingBondEventIsExplicitlyUncertain() = runBlocking {
        val outcome = TargetReleaseEngine.release(
            disconnectAndClose = { TargetReleaseOutcome.Complete },
            bond = FakeBondObservation(
                state = TargetBondState.BONDED_OR_BONDING,
                request = BondRemovalRequestOutcome.Requested,
                wait = { TargetReleaseOutcome.Uncertain("bond release event timed out") }
            )
        )

        assertTrue(outcome is TargetReleaseOutcome.Uncertain)
        assertTrue((outcome as TargetReleaseOutcome.Uncertain).reason.contains("bond"))
    }

    @Test fun failedUnbondRequestIsNotReportedAsComplete() = runBlocking {
        val outcome = TargetReleaseEngine.release(
            disconnectAndClose = { TargetReleaseOutcome.Complete },
            bond = FakeBondObservation(
                state = TargetBondState.BONDED_OR_BONDING,
                request = BondRemovalRequestOutcome.Failed("Android rejected unbond")
            )
        )

        assertEquals(
            TargetReleaseOutcome.Uncertain("Android rejected unbond"),
            outcome
        )
    }

    @Test fun cancellationClosesPreparedBondObserver() = runBlocking {
        val waiting = CompletableDeferred<TargetReleaseOutcome>()
        val bond = FakeBondObservation(
            state = TargetBondState.BONDED_OR_BONDING,
            request = BondRemovalRequestOutcome.Requested,
            wait = { waiting.await() }
        )
        val operation = launch {
            TargetReleaseEngine.release(
                disconnectAndClose = { TargetReleaseOutcome.Complete },
                bond = bond
            )
        }

        yield()
        operation.cancelAndJoin()

        assertTrue(bond.closed)
    }

    @Test fun repeatedCompletionIsIdempotent() {
        val tracker = TargetReleaseTracker()
        val token = tracker.begin("AA:BB:CC:DD:EE:FF")

        assertTrue(tracker.complete(token, TargetReleaseOutcome.Complete))
        assertFalse(
            tracker.complete(
                token,
                TargetReleaseOutcome.Uncertain("must not replace the first terminal outcome")
            )
        )

        assertEquals(
            TargetReleaseState.Released(TargetReleaseOutcome.Complete),
            tracker.snapshot(token.address)?.state
        )
    }

    @Test fun pendingReleaseBlocksPersistentPreflightUntilTerminal() = runBlocking {
        val tracker = TargetReleaseTracker()
        val token = tracker.begin("AA:BB:CC:DD:EE:FF")
        val connectMayStart = async { tracker.awaitLatest(token.address) }

        yield()
        assertEquals(TargetReleaseState.Pending, tracker.snapshot(token.address)?.state)
        assertFalse(connectMayStart.isCompleted)

        tracker.complete(token, TargetReleaseOutcome.Complete)
        assertEquals(
            TargetReleaseState.Released(TargetReleaseOutcome.Complete),
            connectMayStart.await()?.state
        )
    }

    @Test fun uncertainReleaseRemainsDistinctAndClaimsOneRecoveryOnly() = runBlocking {
        val tracker = TargetReleaseTracker()
        val token = tracker.begin("AA:BB:CC:DD:EE:FF")
        val uncertain = TargetReleaseOutcome.Uncertain("callback missing")
        tracker.complete(token, uncertain)
        val snapshot = requireNotNull(tracker.awaitLatest(token.address))

        assertEquals(TargetReleaseState.Released(uncertain), snapshot.state)
        assertTrue(tracker.claimUncertainRecovery(snapshot))
        assertFalse(tracker.claimUncertainRecovery(snapshot))
    }

    @Test fun aNewReleaseGenerationMayClaimItsOwnRecovery() = runBlocking {
        val tracker = TargetReleaseTracker()
        val first = tracker.begin("AA:BB:CC:DD:EE:FF")
        tracker.complete(first, TargetReleaseOutcome.Uncertain("first"))
        val firstSnapshot = requireNotNull(tracker.awaitLatest(first.address))
        assertTrue(tracker.claimUncertainRecovery(firstSnapshot))

        val second = tracker.begin(first.address)
        tracker.complete(second, TargetReleaseOutcome.Uncertain("second"))
        val secondSnapshot = requireNotNull(tracker.awaitLatest(second.address))

        assertTrue(second.generation > first.generation)
        assertTrue(tracker.claimUncertainRecovery(secondSnapshot))
        assertFalse(tracker.claimUncertainRecovery(secondSnapshot))
    }

    @Test fun completeReleaseAndOrdinaryLaterFailureDoNotClaimHardRecovery() = runBlocking {
        val tracker = TargetReleaseTracker()
        val token = tracker.begin("AA:BB:CC:DD:EE:FF")
        tracker.complete(token, TargetReleaseOutcome.Complete)
        val snapshot = requireNotNull(tracker.awaitLatest(token.address))

        assertFalse(tracker.claimUncertainRecovery(snapshot))
        assertEquals(TargetReleaseState.Released(TargetReleaseOutcome.Complete), snapshot.state)
    }

    private class FakeBondObservation(
        var state: TargetBondState,
        private val request: BondRemovalRequestOutcome = BondRemovalRequestOutcome.Requested,
        private val wait: suspend () -> TargetReleaseOutcome = {
            TargetReleaseOutcome.Complete
        }
    ) : BondReleaseObservation {
        var requestCount = 0
        var awaitCount = 0
        var closed = false

        override fun currentState(): TargetBondState = state

        override fun requestRemoval(): BondRemovalRequestOutcome {
            requestCount++
            return request
        }

        override suspend fun awaitUnbonded(): TargetReleaseOutcome {
            awaitCount++
            return wait()
        }

        override fun close() {
            closed = true
        }
    }
}
