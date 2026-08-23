package com.onomic.footpilot

enum class AnkleSnapshotDisposition {
    QUERIED,
    SKIPPED_STANDBY_ON,
    SKIPPED_STANDBY_UNKNOWN
}

data class FullSnapshotRead(
    val batteryLevel: Int?,
    val standby: StandbyState?,
    val ankleMd: Int?,
    val ankleDisposition: AnkleSnapshotDisposition,
    val batteryError: String? = null,
    val standbyError: String? = null,
    val ankleError: String? = null
)

sealed interface FullSnapshotFieldRead<out T> {
    data class Success<T>(val value: T) : FullSnapshotFieldRead<T>
    data class Failed(val message: String) : FullSnapshotFieldRead<Nothing>
}

/** Android-free boundary for the ordered Standby, optional ankle, and battery snapshot reads. */
interface FullSnapshotTransport {
    suspend fun queryStandby(): FullSnapshotFieldRead<StandbyState>
    suspend fun queryAnkle(): FullSnapshotFieldRead<Int>
    suspend fun readBattery(): FullSnapshotFieldRead<Int>
}

/** A fresh Standby result is the sole authority for whether snapshot ankle traffic is allowed. */
object FullSnapshotTransaction {
    suspend fun execute(transport: FullSnapshotTransport): FullSnapshotRead {
        val standbyRead = transport.queryStandby()
        val standby = (standbyRead as? FullSnapshotFieldRead.Success)?.value
            ?.takeIf { it != StandbyState.UNKNOWN }
        val standbyError = when (standbyRead) {
            is FullSnapshotFieldRead.Success -> if (standby == null) {
                "Standby check failed: foot reported unknown"
            } else {
                null
            }
            is FullSnapshotFieldRead.Failed -> standbyRead.message
        }
        val ankleDisposition = when (standby) {
            StandbyState.OFF -> AnkleSnapshotDisposition.QUERIED
            StandbyState.ON -> AnkleSnapshotDisposition.SKIPPED_STANDBY_ON
            null, StandbyState.UNKNOWN -> AnkleSnapshotDisposition.SKIPPED_STANDBY_UNKNOWN
        }

        val ankleRead = if (ankleDisposition == AnkleSnapshotDisposition.QUERIED) {
            transport.queryAnkle()
        } else {
            null
        }
        val reportedAnkleMd = (ankleRead as? FullSnapshotFieldRead.Success)?.value
        val ankleMd = reportedAnkleMd?.takeIf(AnkleProtocol::isSupported)
        val ankleError = when {
            ankleRead is FullSnapshotFieldRead.Failed -> ankleRead.message
            reportedAnkleMd != null && ankleMd == null ->
                "Foot reported an unsupported ankle angle"
            else -> null
        }

        val batteryRead = transport.readBattery()
        val batteryLevel = (batteryRead as? FullSnapshotFieldRead.Success)?.value
        val batteryError = (batteryRead as? FullSnapshotFieldRead.Failed)?.message

        return FullSnapshotRead(
            batteryLevel = batteryLevel,
            standby = standby,
            ankleMd = ankleMd,
            ankleDisposition = ankleDisposition,
            batteryError = batteryError,
            standbyError = standbyError,
            ankleError = ankleError
        )
    }
}
