package com.onomic.footpilot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FootModeRefreshCoordinatorDeferTest {
    private val address = "AA:BB:CC:DD:EE:FF"

    @Test fun coordinatorAvailableImmediatelyRunsOneAdmittedRefresh() = runBlocking {
        val script = ScriptedAdmission(listOf(false))
        var admittedAttempts = 0

        val result = script.defer.run(stillCurrent = { true }) {
            admittedAttempts++
            FootModeRefreshAttemptResult.Success
        }

        assertEquals(FootModeRefreshAttemptResult.Success, result)
        assertEquals(1, script.acquisitionCalls)
        assertEquals(0, script.waitCalls)
        assertEquals(1, admittedAttempts)
    }

    @Test fun firstBusyWaitsThenAcquiresWithoutStartingAnotherBleAttempt() = runBlocking {
        val script = ScriptedAdmission(listOf(true, false))
        var admittedAttempts = 0

        val result = script.defer.run(stillCurrent = { true }) {
            admittedAttempts++
            FootModeRefreshAttemptResult.Success
        }

        assertEquals(FootModeRefreshAttemptResult.Success, result)
        assertEquals(2, script.acquisitionCalls)
        assertEquals(1, script.waitCalls)
        assertEquals(1, admittedAttempts)
    }

    @Test fun repeatedBusyRacesSilentlyDeferUntilAcquisitionSucceeds() = runBlocking {
        val script = ScriptedAdmission(listOf(true, true, true, false))
        var admittedAttempts = 0

        val result = script.defer.run(stillCurrent = { true }) {
            admittedAttempts++
            FootModeRefreshAttemptResult.Success
        }

        assertEquals(FootModeRefreshAttemptResult.Success, result)
        assertEquals(4, script.acquisitionCalls)
        assertEquals(3, script.waitCalls)
        assertEquals(1, admittedAttempts)
    }

    @Test fun busyKeepsCheckingAndDoesNotScheduleCountdownOrRefreshRetry() = runBlocking {
        val store = FootModeStateStore(FootModesState(targetAddress = address))
        assertTrue(store.beginRefresh(address))
        var observedCheckingDuringWait = false
        var countdownTicks = 0
        var refreshRetryCalls = 0
        val script = ScriptedAdmission(
            busySequence = listOf(true, false),
            onWait = {
                FootMode.entries.forEach { mode ->
                    val status = store.state.value.status(mode)
                    val presentation = FootModePresentation.create(
                        mode = mode,
                        status = status,
                        hasSelectedFoot = true,
                        controlsAvailable = false
                    )
                    assertEquals(FootModeOperation.CHECKING, status.operation)
                    assertEquals("Checking...", presentation.secondaryText)
                }
                observedCheckingDuringWait = true
            }
        )

        val run = FootModeRefreshOneShotRetry(
            countdown = LiveRetryCountdown(awaitTick = { countdownTicks++ })
        ).run(
            stillCurrent = { true },
            publishSecondsRemaining = {},
            onRetryScheduled = {
                refreshRetryCalls++
                store.beginRefreshRetry(address)
            },
            onRetryStarting = {},
            attempt = {
                script.defer.run(stillCurrent = { true }) {
                    FootModeRefreshAttemptResult.Success
                }
            }
        )

        assertTrue(observedCheckingDuringWait)
        assertEquals(FootModeRefreshAttemptResult.Success, run.finalAttempt)
        assertEquals(1, run.attempts)
        assertEquals(0, countdownTicks)
        assertEquals(0, refreshRetryCalls)
        FootMode.entries.forEach { mode ->
            assertEquals(FootModeOperation.CHECKING, store.state.value.status(mode).operation)
        }
    }

    @Test fun transientFailureAfterBusyGetsFullRetryEvenWhenRetryAdmissionIsBusy() = runBlocking {
        val script = ScriptedAdmission(listOf(true, false, true, true, false))
        val publishedSeconds = mutableListOf<Int?>()
        var admittedAttempts = 0
        var countdownTicks = 0
        var retryScheduled = 0
        var retryStarted = 0

        val run = FootModeRefreshOneShotRetry(
            countdown = LiveRetryCountdown(awaitTick = { countdownTicks++ })
        ).run(
            stillCurrent = { true },
            publishSecondsRemaining = publishedSeconds::add,
            onRetryScheduled = { retryScheduled++ },
            onRetryStarting = { retryStarted++ },
            attempt = {
                script.defer.run(stillCurrent = { true }) {
                    admittedAttempts++
                    if (admittedAttempts == 1) {
                        FootModeRefreshAttemptResult.TransientFailure("query timed out")
                    } else {
                        FootModeRefreshAttemptResult.Success
                    }
                }
            }
        )

        assertEquals(1, BleRetryPolicy.ONE_SHOT_CONTROL_RETRIES)
        assertEquals(FootModeRefreshAttemptResult.Success, run.finalAttempt)
        assertEquals(2, run.attempts)
        assertEquals(2, admittedAttempts)
        assertEquals(5, script.acquisitionCalls)
        assertEquals(3, script.waitCalls)
        assertEquals(1, retryScheduled)
        assertEquals(1, retryStarted)
        assertEquals(BleRetryPolicy.retryDelaySeconds, countdownTicks)
        assertEquals(
            (BleRetryPolicy.retryDelaySeconds downTo 1).toList() + null,
            publishedSeconds
        )
    }

    @Test fun selectedFootChangeWhileWaitingPreventsOldTargetAttempt() = runBlocking {
        var stillCurrent = true
        val script = ScriptedAdmission(
            busySequence = listOf(true),
            onWait = { stillCurrent = false }
        )
        var admittedAttempts = 0

        val result = script.defer.run(stillCurrent = { stillCurrent }) {
            admittedAttempts++
            FootModeRefreshAttemptResult.Success
        }

        assertEquals(
            FootModeRefreshAttemptResult.Rejected("Selected foot changed"),
            result
        )
        assertEquals(1, script.acquisitionCalls)
        assertEquals(1, script.waitCalls)
        assertEquals(0, admittedAttempts)
    }

    @Test fun cancellationWhileWaitingPreventsEveryLaterAttempt() = runBlocking {
        val waitEntered = CompletableDeferred<Unit>()
        val coordinatorAvailable = CompletableDeferred<Unit>()
        var acquisitionCalls = 0
        var admittedAttempts = 0
        val defer = FootModeRefreshCoordinatorDefer(
            tryAcquire = {
                acquisitionCalls++
                CoordinatedResult.Busy
            },
            awaitAvailable = {
                waitEntered.complete(Unit)
                coordinatorAvailable.await()
            }
        )
        val refresh = async {
            defer.run(stillCurrent = { true }) {
                admittedAttempts++
                FootModeRefreshAttemptResult.Success
            }
        }

        waitEntered.await()
        refresh.cancel()
        try {
            refresh.await()
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // The state-flow-style wait is cancellable and cancellation must escape the helper.
        }
        coordinatorAvailable.complete(Unit)
        yield()

        assertEquals(1, acquisitionCalls)
        assertEquals(0, admittedAttempts)
    }

    @Test fun deviceControlReservationStaysAheadOfWaitingAutomaticRefresh() = runBlocking {
        val coordinator = OperationCoordinator()
        val checkEntered = CompletableDeferred<Unit>()
        val releaseCheck = CompletableDeferred<Unit>()
        val controlEntered = CompletableDeferred<Unit>()
        val releaseControl = CompletableDeferred<Unit>()
        val firstRefreshBusy = CompletableDeferred<Unit>()
        val refreshEntered = CompletableDeferred<Unit>()
        var acquisitionCalls = 0

        val check = async {
            coordinator.tryRun(BleOperationKind.MANUAL_CHECK) {
                checkEntered.complete(Unit)
                releaseCheck.await()
            }
        }
        checkEntered.await()

        val defer = FootModeRefreshCoordinatorDefer(
            tryAcquire = { block ->
                acquisitionCalls++
                coordinator.tryRun(BleOperationKind.FOOT_MODES_REFRESH, block).also { result ->
                    if (result === CoordinatedResult.Busy) firstRefreshBusy.complete(Unit)
                }
            },
            awaitAvailable = {
                coordinator.state.first { !it.isBusy }
            }
        )
        val refresh = async {
            defer.run(stillCurrent = { true }) {
                refreshEntered.complete(Unit)
                FootModeRefreshAttemptResult.Success
            }
        }
        firstRefreshBusy.await()

        val control = async {
            coordinator.runDeviceControl(BleOperationKind.CHAIR_EXIT_ON) {
                controlEntered.complete(Unit)
                releaseControl.await()
            }
        }
        coordinator.state.first {
            it.standbyPending == BleOperationKind.CHAIR_EXIT_ON
        }
        releaseCheck.complete(Unit)
        check.await()
        controlEntered.await()

        assertFalse(refreshEntered.isCompleted)
        releaseControl.complete(Unit)
        control.await()

        assertEquals(FootModeRefreshAttemptResult.Success, refresh.await())
        assertTrue(refreshEntered.isCompleted)
        assertEquals(2, acquisitionCalls)
    }

    private class ScriptedAdmission(
        busySequence: List<Boolean>,
        private val onWait: suspend () -> Unit = {}
    ) {
        private val outcomes = ArrayDeque<Boolean>().apply { addAll(busySequence) }
        var acquisitionCalls = 0
            private set
        var waitCalls = 0
            private set

        val defer = FootModeRefreshCoordinatorDefer(
            tryAcquire = { block ->
                acquisitionCalls++
                if (outcomes.removeFirst()) {
                    CoordinatedResult.Busy
                } else {
                    CoordinatedResult.Completed(block())
                }
            },
            awaitAvailable = {
                waitCalls++
                onWait()
            }
        )
    }
}
