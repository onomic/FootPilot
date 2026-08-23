package com.onomic.footpilot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException

/** Publishes temporary-session release and gates the next live GATT for the same target. */
object BleTargetReleaseBarrier {
    private const val TAG = "FootPilotBle"
    private const val BOND_RELEASE_TIMEOUT_MS = 5_000L

    private val releases = TargetReleaseTracker()

    suspend fun releaseTemporarySession(
        ctx: Context,
        session: FootGattSession
    ): TargetReleaseOutcome {
        val token = releases.begin(session.target.address)
        debug("TEMP_RELEASE begin generation ${token.generation}")
        var outcome: TargetReleaseOutcome =
            TargetReleaseOutcome.Uncertain("temporary release was interrupted")

        try {
            val bond = BondHelper.observeRelease(
                ctx = ctx,
                target = session.target,
                timeoutMs = BOND_RELEASE_TIMEOUT_MS
            )
            outcome = TargetReleaseEngine.release(
                disconnectAndClose = { session.disconnectAndCloseAwaitingRelease() },
                bond = bond
            )
            return outcome
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            session.disconnectAndClose(removeBond = false)
            BondHelper.forceUnbond(ctx, session.target)
            outcome = TargetReleaseOutcome.Uncertain("temporary release failed")
            return outcome
        } finally {
            // Publish the completed-release time before waking a live-connect waiter so it
            // cannot observe the terminal generation without also observing its quiet period.
            BleInterOperationCooldown.recordReleaseCompleted(token.address)
            releases.complete(token, outcome)
            debug(
                when (outcome) {
                    TargetReleaseOutcome.AlreadyReleased ->
                        "TEMP_RELEASE already released generation ${token.generation}"
                    TargetReleaseOutcome.Complete ->
                        "TEMP_RELEASE complete generation ${token.generation}"
                    is TargetReleaseOutcome.Uncertain ->
                        "TEMP_RELEASE uncertain generation ${token.generation}: ${outcome.reason}"
                }
            )
        }
    }

    /** Called from the live coroutine while it owns the process-wide BLE coordinator. */
    suspend fun awaitLiveConnectReady(ctx: Context, target: SelectedFoot) {
        val pending = releases.snapshot(target.address)
        if (pending?.state == TargetReleaseState.Pending) {
            debug("LIVE_CONNECT waiting for release generation ${pending.generation}")
        }
        val released = releases.awaitLatest(target.address)
        if (released != null) {
            val state = released.state as TargetReleaseState.Released
            debug("LIVE_CONNECT release ready generation ${released.generation}")

            if (state.outcome is TargetReleaseOutcome.Uncertain &&
                releases.claimUncertainRecovery(released)
            ) {
                debug("LIVE_CONNECT recovery generation ${released.generation}")
                val recovery = try {
                    val bond = BondHelper.observeRelease(
                        ctx = ctx,
                        target = target,
                        timeoutMs = BOND_RELEASE_TIMEOUT_MS
                    )
                    TargetReleaseEngine.release(
                        disconnectAndClose = {
                            val closed = BleRegistry.closeTarget(target.address)
                            if (closed == 0) {
                                TargetReleaseOutcome.AlreadyReleased
                            } else {
                                TargetReleaseOutcome.Uncertain(
                                    "closed $closed stale tracked GATT during recovery"
                                )
                            }
                        },
                        bond = bond
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    TargetReleaseOutcome.Uncertain("target release recovery failed")
                }
                BleInterOperationCooldown.recordReleaseCompleted(target.address)
                debug(
                    when (recovery) {
                        TargetReleaseOutcome.AlreadyReleased ->
                            "LIVE_CONNECT recovery already released generation ${released.generation}"
                        TargetReleaseOutcome.Complete ->
                            "LIVE_CONNECT recovery complete generation ${released.generation}"
                        is TargetReleaseOutcome.Uncertain ->
                            "LIVE_CONNECT recovery uncertain generation ${released.generation}: ${recovery.reason}"
                    }
                )
            }
        }

        BleInterOperationCooldown.awaitReady(target.address)
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
