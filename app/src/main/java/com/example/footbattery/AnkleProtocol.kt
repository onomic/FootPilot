package com.example.footbattery

import java.util.Locale

enum class AnkleResponseKind {
    QUERY,
    SET
}
data class AnkleResponse(
    val kind: AnkleResponseKind,
    val millidegrees: Int,
    /** Undecoded protocol bytes retained for diagnostics without assigning semantics. */
    val trailingBytes: List<Int> = emptyList()
)

enum class FineAdjustment(val deltaMd: Int) {
    MINUS(-100),
    PLUS(100)
}

sealed interface AnkleTargetRequest {
    data class Absolute(val targetMd: Int) : AnkleTargetRequest
    data class Fine(val adjustment: FineAdjustment) : AnkleTargetRequest
}

/** Capture-proven ankle packet primitives. Millidegrees remain the canonical unit. */
object AnkleProtocol {
    const val MIN_MILLIDEGREES = -2_000
    const val MAX_MILLIDEGREES = 14_000

    private val QUERY = byteArrayOf(
        0xB0.toByte(), 0xB0.toByte(), 0x08, 0x13, 0x34, 0x20
    )

    fun queryCommand(): ByteArray = QUERY.copyOf()

    fun setCommand(targetMd: Int): ByteArray {
        require(isSupported(targetMd)) { "Ankle target is outside -2.0° to +14.0°" }
        return byteArrayOf(
            0xB1.toByte(),
            0xB0.toByte(),
            0x08,
            0x13,
            0x34,
            0x00,
            (targetMd and 0xFF).toByte(),
            ((targetMd ushr 8) and 0xFF).toByte(),
            ((targetMd ushr 16) and 0xFF).toByte(),
            ((targetMd ushr 24) and 0xFF).toByte()
        )
    }

    fun parseResponse(payload: ByteArray): AnkleResponse? {
        if (payload.size < 10) return null
        val kind = when (payload[0].toInt() and 0xFF) {
            0xB0 -> AnkleResponseKind.QUERY
            0xB1 -> AnkleResponseKind.SET
            else -> return null
        }
        if ((payload[1].toInt() and 0xFF) != 0xB0) return null
        if ((payload[2].toInt() and 0xFF) != 0x08) return null
        if ((payload[3].toInt() and 0xFF) != 0x13) return null
        if ((payload[4].toInt() and 0xFF) != 0x34) return null
        if ((payload[5].toInt() and 0xFF) != 0xA0) return null

        val value = (payload[6].toInt() and 0xFF) or
            ((payload[7].toInt() and 0xFF) shl 8) or
            ((payload[8].toInt() and 0xFF) shl 16) or
            ((payload[9].toInt() and 0xFF) shl 24)
        return AnkleResponse(
            kind = kind,
            millidegrees = value,
            trailingBytes = payload.drop(10).map { it.toInt() and 0xFF }
        )
    }

    fun matches(response: AnkleResponse, expectedKind: AnkleResponseKind): Boolean =
        response.kind == expectedKind

    fun isSupported(millidegrees: Int): Boolean =
        millidegrees in MIN_MILLIDEGREES..MAX_MILLIDEGREES

    fun fineTarget(confirmedMd: Int, adjustment: FineAdjustment): Int? {
        val target = confirmedMd.toLong() + adjustment.deltaMd
        return target.toInt().takeIf { target in MIN_MILLIDEGREES.toLong()..MAX_MILLIDEGREES.toLong() }
    }

    fun format(millidegrees: Int): String =
        String.format(Locale.US, "%+.1f°", millidegrees / 1000.0)
}
