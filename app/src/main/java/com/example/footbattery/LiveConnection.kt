package com.example.footbattery

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Owns the one persistent, fully initialized GATT session used by live monitoring. */
object LiveConnection {
    private const val TAG = "FootPilotBle"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val retryCountdown = LiveRetryCountdown()
    private val generation = AtomicInteger(0)
    private val jobGuard = Any()

    @Volatile private var appContext: Context? = null
    @Volatile private var wantConnected = false
    @Volatile private var session: FootGattSession? = null
    @Volatile private var activeTarget: SelectedFoot? = null
    @Volatile private var hasBeenReady = false
    private var connectJob: Job? = null

    fun isMonitoringRequested(): Boolean = wantConnected

    /** Temporary clients are safe only after the live owner has completely released its GATT. */
    fun canUseTemporarySession(): Boolean =
        SelectedFootRepository.selected.value != null && !wantConnected && session == null &&
            BatteryRepo.connectionState.value == LiveConnectionState.IDLE &&
            !BatteryRepo.running.value

    /** A usable connection means all services and notification CCCDs are initialized. */
    fun isReady(): Boolean =
        wantConnected && BatteryRepo.connectionState.value == LiveConnectionState.READY &&
            session?.isUsable() == true

    fun isConnected(): Boolean = isReady()

    internal fun readySession(): FootGattSession? = session?.takeIf { isReady() && it.isUsable() }

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        SelectedFootRepository.ensureInitialized(app)
        BatteryRepo.ensureInitialized(app)
        AnkleRepo.ensureInitialized(app)
        PresetRepository.ensureInitialized(app)
        if (wantConnected) return
        val target = SelectedFootRepository.current(app)
        if (target == null) {
            BatteryRepo.retrySecondsRemaining.value = null
            BatteryRepo.status.value = "Add a foot in Settings"
            Prefs.setMonitoring(app, false)
            return
        }

        appContext = app
        activeTarget = target
        wantConnected = true
        hasBeenReady = false
        val expectedGeneration = generation.incrementAndGet()
        Prefs.setMonitoring(app, true)
        BatteryRepo.running.value = true
        BatteryRepo.connectionState.value = LiveConnectionState.CONNECTING
        BatteryRepo.retrySecondsRemaining.value = null
        BatteryRepo.status.value = "Connecting..."
        Alerts.ensureChannels(app)
        Alerts.cancelPollStatus(app)
        Alerts.postOngoing(app, "Connecting...")
        launchConnectLoop(expectedGeneration, target)
    }

    private fun launchConnectLoop(expectedGeneration: Int, target: SelectedFoot) {
        synchronized(jobGuard) {
            if (connectJob?.isActive == true) return
            connectJob = scope.launch {
                try {
                    runPersistentLiveConnection(
                        stillRequested = { stillRequested(expectedGeneration, target) },
                        attempt = { connectOnce(expectedGeneration, target) },
                        awaitRetry = {
                            retryCountdown.awaitRetry(
                                stillRequested = {
                                    stillRequested(expectedGeneration, target)
                                },
                                publishSecondsRemaining = {
                                    BatteryRepo.retrySecondsRemaining.value = it
                                }
                            )
                        }
                    )
                } finally {
                    BatteryRepo.retrySecondsRemaining.value = null
                }
            }
        }
    }

    private suspend fun connectOnce(
        expectedGeneration: Int,
        target: SelectedFoot
    ): LiveConnectionAttemptResult {
        var candidate: FootGattSession? = null
        var snapshotResult: FootOperationResult? = null
        var reachedReady = false
        val disconnectSignal = LiveDisconnectSignal()

        try {
            val coordinated = BleOperationCoordinator.tryRun(BleOperationKind.LIVE_CONNECT) {
                if (!stillRequested(expectedGeneration, target)) return@tryRun

                beginConnectAttempt()
                BleTargetReleaseBarrier.awaitLiveConnectReady(
                    requireNotNull(appContext),
                    target
                )
                if (!stillRequested(expectedGeneration, target)) return@tryRun

                lateinit var created: FootGattSession
                created = FootGattSession(
                    requireNotNull(appContext),
                    target,
                    onBatteryNotification = { level -> handleLiveBattery(created, level) },
                    onUnexpectedDisconnect = { message ->
                        handleUnexpectedDisconnect(
                            created,
                            message,
                            expectedGeneration,
                            target,
                            disconnectSignal
                        )
                    }
                )
                candidate = created
                session = created
                debug("LIVE_CONNECT connect attempt")
                created.connectAndInitialize { state ->
                    if (session === created) {
                        BatteryRepo.connectionState.value = state
                        BatteryRepo.status.value = when (state) {
                            LiveConnectionState.CONNECTING -> "Connecting..."
                            LiveConnectionState.DISCOVERING -> "Discovering services..."
                            LiveConnectionState.INITIALIZING -> "Initializing foot..."
                            else -> BatteryRepo.status.value
                        }
                        appContext?.let { Alerts.postOngoing(it, BatteryRepo.status.value) }
                    }
                }

                val origin = if (hasBeenReady) {
                    CheckOrigin.LIVE_RECONNECT
                } else {
                    CheckOrigin.LIVE_INITIAL
                }
                BatteryRepo.standbyStatus.value = "Checking standby..."
                Alerts.showOperation(requireNotNull(appContext), "Checking...")
                snapshotResult = FootOperations.readAndApplyOnSession(
                    requireNotNull(appContext),
                    created,
                    origin
                )
                created.enableBatteryMonitoring()

                if (!stillRequested(expectedGeneration, target)) {
                    throw CancellationException("Monitoring stopped")
                }
                if (session !== created || !created.isUsable()) {
                    throw BleSessionException("Foot disconnected during initialization")
                }
                BatteryRepo.retrySecondsRemaining.value = null
                BatteryRepo.connectionState.value = LiveConnectionState.READY
                BatteryRepo.running.value = true
                hasBeenReady = true
                reachedReady = true
                debug("LIVE_CONNECT READY")
                if (BatteryRepo.status.value == "Monitoring" ||
                    BatteryRepo.status.value == "Checked"
                ) {
                    BatteryRepo.status.value = "Monitoring"
                }
            }

            if (coordinated is CoordinatedResult.Busy) {
                return LiveConnectionAttemptResult.Busy
            }
            if (!stillRequested(expectedGeneration, target)) {
                return LiveConnectionAttemptResult.Stopped
            }
            if (!reachedReady) {
                publishConnectAttemptFailure("Connection failed")
                return LiveConnectionAttemptResult.Failed
            }

            val transient = when (val result = snapshotResult) {
                is FootOperationResult.Partial -> result.message
                is FootOperationResult.Failed -> result.message
                else -> null
            }
            Alerts.refreshApplicable(requireNotNull(appContext), transient)
            return LiveConnectionAttemptResult.Ready { disconnectSignal.await() }
        } catch (e: CancellationException) {
            closeFailedCandidate(candidate)
            throw e
        } catch (e: Exception) {
            closeFailedCandidate(candidate)
            if (!stillRequested(expectedGeneration, target)) {
                return LiveConnectionAttemptResult.Stopped
            }
            publishConnectAttemptFailure(e.message ?: "Connection failed")
            return LiveConnectionAttemptResult.Failed
        }
    }

    private fun beginConnectAttempt() {
        BatteryRepo.retrySecondsRemaining.value = null
        BatteryRepo.running.value = true
        BatteryRepo.connectionState.value = LiveConnectionState.CONNECTING
        BatteryRepo.status.value = "Connecting..."
        appContext?.let { Alerts.postOngoing(it, "Connecting...") }
    }

    private fun closeFailedCandidate(candidate: FootGattSession?) {
        if (session === candidate) session = null
        try {
            candidate?.disconnectAndClose(removeBond = false)
        } catch (e: Exception) {
            debug("LIVE_CONNECT failed candidate cleanup: ${e.message}")
        }
    }

    private fun publishPersistentFailure(message: String) {
        BatteryRepo.connectionState.value = LiveConnectionState.FAILED
        BatteryRepo.status.value = message
        appContext?.let { Alerts.postOngoing(it, "$message — retrying") }
    }

    private fun publishConnectAttemptFailure(message: String) {
        if (AnkleRepo.state.value.operation == AnkleOperation.QUERYING) {
            AnkleRepo.fail(message)
        }
        publishPersistentFailure(message)
    }

    private fun stillRequested(expectedGeneration: Int, target: SelectedFoot): Boolean =
        wantConnected && generation.get() == expectedGeneration && activeTarget == target &&
            SelectedFootRepository.selected.value == target

    private fun handleLiveBattery(owner: FootGattSession, level: Int) {
        if (session !== owner || !wantConnected) return
        val app = appContext
        FreshBatteryResultHandler.handle(
            batteryLevel = level,
            updateLiveLevel = { BatteryRepo.level.value = it },
            evaluateLowBattery = { value -> app?.let { Alerts.maybeAlert(it, value) } }
        )
        app?.let { Alerts.refreshLiveBattery(it) }
        // Live battery changes neither the persisted snapshot nor its Last checked time.
    }

    private fun handleUnexpectedDisconnect(
        owner: FootGattSession,
        message: String,
        expectedGeneration: Int,
        target: SelectedFoot,
        disconnectSignal: LiveDisconnectSignal
    ) {
        val accepted = synchronized(jobGuard) {
            if (session !== owner || !stillRequested(expectedGeneration, target)) {
                false
            } else {
                session = null
                true
            }
        }
        if (!accepted) return
        try {
            owner.disconnectAndClose(removeBond = false)
        } finally {
            publishPersistentFailure(message)
            disconnectSignal.signal()
        }
    }

    fun stop() {
        BatteryRepo.retrySecondsRemaining.value = null
        val app = appContext ?: return
        if (!wantConnected && BatteryRepo.connectionState.value == LiveConnectionState.IDLE) return

        wantConnected = false
        generation.incrementAndGet()
        Prefs.setMonitoring(app, false)
        BatteryRepo.connectionState.value = LiveConnectionState.DISCONNECTING
        BatteryRepo.status.value = "Disconnecting..."
        Alerts.postOngoing(app, "Disconnecting...")

        val job = synchronized(jobGuard) { connectJob.also { connectJob = null } }
        val target = activeTarget
        scope.launch {
            job?.cancelAndJoin()
            BleOperationCoordinator.runQueued(BleOperationKind.DISCONNECT) {
                val current = session
                session = null
                current?.requestDisconnect()
                delay(600L)
                current?.disconnectAndClose(removeBond = false)
                BleRegistry.closeAll()
                target?.let { BondHelper.forceUnbond(app, it) }
            }
            activeTarget = null
            BatteryRepo.running.value = false
            BatteryRepo.connectionState.value = LiveConnectionState.IDLE
            BatteryRepo.retrySecondsRemaining.value = null
            BatteryRepo.status.value = "Disconnected"
            Alerts.cancelOngoing(app)
            if (Prefs.polling(app)) Alerts.updatePollStatus(app)
        }
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
