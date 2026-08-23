package com.example.footbattery

sealed interface AutoStartWriteResult {
    data object Accepted : AutoStartWriteResult
    data class Failed(val message: String) : AutoStartWriteResult
}

sealed interface AutoEventWaitResult {
    data class Event(val event: Aa01Event) : AutoEventWaitResult
    data object TimedOut : AutoEventWaitResult
    data class Failed(val message: String) : AutoEventWaitResult
}

interface AutoAlignmentTransport {
    suspend fun exchangeStandbyQuery(): StandbyCommandExchangeResult
    suspend fun exchangeAnkleQuery(): AnkleCommandExchangeResult
    suspend fun writeStart(onWriteAccepted: () -> Unit = {}): AutoStartWriteResult
    suspend fun awaitRelevantEvent(timeoutMs: Long): AutoEventWaitResult
}

data class AutoAlignmentRead(
    val freshStandby: StandbyState?,
    val initialMd: Int?,
    val finalConfirmedMd: Int?,
    val startWriteAccepted: Boolean,
    val completionObserved: Boolean,
    val finalTruthConfirmed: Boolean,
    val unknownAfterCommand: Boolean,
    val error: String?
)

/** Event-driven Auto transaction. Opaque activity values are never interpreted as progress. */
object AutoAlignmentTransaction {
    const val START_ACK_TIMEOUT_MS = 3_000L
    const val INACTIVITY_TIMEOUT_MS = 15_000L

    suspend fun execute(
        transport: AutoAlignmentTransport,
        onOperation: (AnkleOperation) -> Unit = {},
        onPotentialMovement: () -> Unit = {}
    ): AutoAlignmentRead {
        val standby = when (val result = transport.exchangeStandbyQuery()) {
            is StandbyCommandExchangeResult.Response -> result.response.state
            is StandbyCommandExchangeResult.WriteFailed -> return failure(
                error = "Could not verify standby: ${result.message}"
            )
            is StandbyCommandExchangeResult.ResponseMissing -> return failure(
                error = "Standby is unknown; Check now before automatic alignment"
            )
        }
        if (standby != StandbyState.OFF) {
            return failure(
                freshStandby = standby,
                error = "Turn standby off before automatic alignment"
            )
        }

        val initial = when (val result = transport.exchangeAnkleQuery()) {
            is AnkleCommandExchangeResult.Response -> result.response.millidegrees
                .takeIf(AnkleProtocol::isSupported)
                ?: return failure(
                    freshStandby = standby,
                    error = "Foot reported an unsupported ankle angle"
                )
            is AnkleCommandExchangeResult.WriteFailed -> return failure(
                freshStandby = standby,
                error = "Could not query ankle angle: ${result.message}"
            )
            is AnkleCommandExchangeResult.ResponseMissing -> return failure(
                freshStandby = standby,
                error = "Could not verify the current ankle angle"
            )
        }

        onOperation(AnkleOperation.AUTO_STARTING)
        var startWriteAccepted = false
        fun markPotentialMovement() {
            if (!startWriteAccepted) {
                startWriteAccepted = true
                onPotentialMovement()
            }
        }
        when (val start = transport.writeStart(::markPotentialMovement)) {
            AutoStartWriteResult.Accepted -> markPotentialMovement()
            is AutoStartWriteResult.Failed -> {
                if (!startWriteAccepted) {
                    return failure(
                        freshStandby = standby,
                        initialMd = initial,
                        finalConfirmedMd = initial,
                        finalTruthConfirmed = true,
                        error = "Automatic alignment could not start: ${start.message}"
                    )
                }
                // The platform accepted the write before reporting failure. Retain exclusive
                // recovery ownership because the foot may still have begun moving.
            }
        }

        onOperation(AnkleOperation.AUTO_RUNNING)
        var completionObserved = false
        var keepWaiting = true
        var waitingForInitialAcknowledgement = true
        var wait = transport.awaitRelevantEvent(START_ACK_TIMEOUT_MS)
        while (keepWaiting) {
            when (wait) {
                is AutoEventWaitResult.Event -> {
                    waitingForInitialAcknowledgement = false
                    when (wait.event) {
                        is Aa01Event.AutoCompletion -> {
                            completionObserved = true
                            keepWaiting = false
                        }
                        is Aa01Event.AutoActivity,
                        is Aa01Event.Ankle ->
                            wait = transport.awaitRelevantEvent(INACTIVITY_TIMEOUT_MS)
                        is Aa01Event.Standby,
                        is Aa01Event.FootMode,
                        is Aa01Event.Unknown ->
                            wait = transport.awaitRelevantEvent(INACTIVITY_TIMEOUT_MS)
                    }
                }
                AutoEventWaitResult.TimedOut -> if (waitingForInitialAcknowledgement) {
                    // A successful write may have moved the foot even if the first event was lost.
                    waitingForInitialAcknowledgement = false
                    wait = transport.awaitRelevantEvent(INACTIVITY_TIMEOUT_MS)
                } else {
                    keepWaiting = false
                }
                is AutoEventWaitResult.Failed -> keepWaiting = false
            }
        }

        onOperation(AnkleOperation.VERIFYING)
        return when (val finalResult = transport.exchangeAnkleQuery()) {
            is AnkleCommandExchangeResult.Response -> {
                val confirmed = finalResult.response.millidegrees
                if (!AnkleProtocol.isSupported(confirmed)) {
                    unknownAfterCommand(standby, initial, completionObserved)
                } else {
                    AutoAlignmentRead(
                        freshStandby = standby,
                        initialMd = initial,
                        finalConfirmedMd = confirmed,
                        startWriteAccepted = startWriteAccepted,
                        completionObserved = completionObserved,
                        finalTruthConfirmed = true,
                        unknownAfterCommand = false,
                        error = if (completionObserved) null else {
                            "Automatic alignment completion was not confirmed; angle re-verified"
                        }
                    )
                }
            }
            is AnkleCommandExchangeResult.WriteFailed,
            is AnkleCommandExchangeResult.ResponseMissing ->
                unknownAfterCommand(standby, initial, completionObserved)
        }
    }

    private fun unknownAfterCommand(
        standby: StandbyState,
        initialMd: Int,
        completionObserved: Boolean
    ) = AutoAlignmentRead(
        freshStandby = standby,
        initialMd = initialMd,
        finalConfirmedMd = null,
        startWriteAccepted = true,
        completionObserved = completionObserved,
        finalTruthConfirmed = false,
        unknownAfterCommand = true,
        error = "Ankle position is unknown after automatic alignment; Check now to verify"
    )

    private fun failure(
        freshStandby: StandbyState? = null,
        initialMd: Int? = null,
        finalConfirmedMd: Int? = null,
        finalTruthConfirmed: Boolean = false,
        error: String
    ) = AutoAlignmentRead(
        freshStandby = freshStandby,
        initialMd = initialMd,
        finalConfirmedMd = finalConfirmedMd,
        startWriteAccepted = false,
        completionObserved = false,
        finalTruthConfirmed = finalTruthConfirmed,
        unknownAfterCommand = false,
        error = error
    )
}
