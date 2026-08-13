package com.example.footbattery

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

enum class AnkleCertainty {
    UNKNOWN,
    CONFIRMED,
    UNKNOWN_AFTER_COMMAND;

    companion object {
        fun fromPersisted(value: String?): AnkleCertainty =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

enum class AnkleOperation {
    IDLE,
    QUERYING,
    SETTING,
    AUTO_STARTING,
    AUTO_RUNNING,
    VERIFYING
}

data class AnkleState(
    /** Retained for confirmed display or explicitly labelled historical display only. */
    val lastVerifiedMd: Int? = null,
    val lastVerifiedAt: Long? = null,
    val certainty: AnkleCertainty = AnkleCertainty.UNKNOWN,
    val operation: AnkleOperation = AnkleOperation.IDLE,
    val message: String? = null
) {
    val confirmedMd: Int?
        get() = lastVerifiedMd.takeIf { certainty == AnkleCertainty.CONFIRMED }
}

data class StoredAnkleState(
    val millidegrees: Int?,
    val verifiedAt: Long?,
    val certaintyName: String?
)

/** Android-free persistence validation for safe migration and tests. */
object AnklePersistence {
    fun decode(stored: StoredAnkleState): AnkleState {
        val md = stored.millidegrees?.takeIf(AnkleProtocol::isSupported)
        val certainty = when {
            md == null -> AnkleCertainty.UNKNOWN
            else -> AnkleCertainty.fromPersisted(stored.certaintyName)
        }
        return AnkleState(
            lastVerifiedMd = md,
            lastVerifiedAt = stored.verifiedAt?.takeIf { it > 0L && md != null },
            certainty = certainty,
            operation = AnkleOperation.IDLE
        )
    }

    fun encode(state: AnkleState): StoredAnkleState {
        val md = state.lastVerifiedMd?.takeIf(AnkleProtocol::isSupported)
        return StoredAnkleState(
            millidegrees = md,
            verifiedAt = state.lastVerifiedAt?.takeIf { it > 0L && md != null },
            certaintyName = if (md == null) {
                AnkleCertainty.UNKNOWN.name
            } else {
                state.certainty.name
            }
        )
    }

    /** Persisted confirmation is historical until this process obtains fresh device truth. */
    fun restoreForProcess(stored: StoredAnkleState): AnkleState = decode(stored).let { state ->
        if (state.certainty == AnkleCertainty.CONFIRMED) {
            state.copy(certainty = AnkleCertainty.UNKNOWN)
        } else {
            state
        }
    }
}

/** Shared ankle state kept independent from battery/standby snapshot completeness. */
object AnkleRepo {
    private val initialized = AtomicBoolean(false)
    val state = MutableStateFlow(AnkleState())

    fun ensureInitialized(ctx: Context) {
        if (!initialized.compareAndSet(false, true)) return
        state.value = AnklePersistence.restoreForProcess(Prefs.ankleState(ctx.applicationContext))
    }

    fun begin(operation: AnkleOperation, message: String) {
        state.update { it.copy(operation = operation, message = message) }
    }

    fun updateOperation(operation: AnkleOperation, message: String? = state.value.message) {
        state.update { it.copy(operation = operation, message = message) }
    }

    fun confirm(ctx: Context, millidegrees: Int, message: String? = null) {
        require(AnkleProtocol.isSupported(millidegrees))
        val confirmed = AnkleState(
            lastVerifiedMd = millidegrees,
            lastVerifiedAt = System.currentTimeMillis(),
            certainty = AnkleCertainty.CONFIRMED,
            operation = AnkleOperation.IDLE,
            message = message
        )
        state.value = confirmed
        Prefs.saveAnkleState(ctx.applicationContext, confirmed)
    }

    fun unknownAfterCommand(ctx: Context, message: String) {
        val unknown = state.value.copy(
            certainty = AnkleCertainty.UNKNOWN_AFTER_COMMAND,
            operation = AnkleOperation.IDLE,
            message = message
        )
        state.value = unknown
        Prefs.saveAnkleState(ctx.applicationContext, unknown)
    }

    /** A fresh query failed: retain any prior value only as history, never current truth. */
    fun verificationFailed(ctx: Context, message: String) {
        val current = state.value
        val unverified = current.copy(
            certainty = if (current.certainty == AnkleCertainty.UNKNOWN_AFTER_COMMAND) {
                AnkleCertainty.UNKNOWN_AFTER_COMMAND
            } else {
                AnkleCertainty.UNKNOWN
            },
            operation = AnkleOperation.IDLE,
            message = message
        )
        state.value = unverified
        Prefs.saveAnkleState(ctx.applicationContext, unverified)
    }

    fun fail(message: String) {
        state.update { it.copy(operation = AnkleOperation.IDLE, message = message) }
    }

    fun clearMessage() {
        state.update { it.copy(message = null) }
    }
}
