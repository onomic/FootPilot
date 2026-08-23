package com.example.footbattery

sealed interface Aa01Event {
    data class Standby(
        val response: StandbyResponse,
        val trailingBytes: List<Int>
    ) : Aa01Event

    data class Ankle(val response: AnkleResponse) : Aa01Event

    data class FootMode(
        val response: FootModeResponse,
        val trailingBytes: List<Int>
    ) : Aa01Event

    /** The observed status byte is deliberately opaque. */
    data class AutoActivity(
        val opaqueStatus: Int,
        val trailingBytes: List<Int>
    ) : Aa01Event

    data class AutoCompletion(val trailingBytes: List<Int>) : Aa01Event

    data class Unknown(val payload: List<Int>) : Aa01Event
}

/** One typed parser for all recognized traffic arriving on the shared AA01 characteristic. */
object Aa01Router {
    fun parse(payload: ByteArray): Aa01Event {
        StandbyProtocol.parseResponse(payload)?.let { response ->
            return Aa01Event.Standby(
                response = response,
                trailingBytes = payload.drop(7).map { it.toInt() and 0xFF }
            )
        }
        AnkleProtocol.parseResponse(payload)?.let { response ->
            return Aa01Event.Ankle(response)
        }
        FootModeProtocol.parseResponse(payload)?.let { response ->
            return Aa01Event.FootMode(
                response = response,
                trailingBytes = payload.drop(7).map { it.toInt() and 0xFF }
            )
        }

        if (payload.hasPrefix(0xB2, 0xB0, 0x04, 0x01) && payload.size >= 5) {
            return Aa01Event.AutoActivity(
                opaqueStatus = payload[4].toInt() and 0xFF,
                trailingBytes = payload.drop(5).map { it.toInt() and 0xFF }
            )
        }
        if (payload.hasPrefix(0xB2, 0xB0, 0x04, 0x00)) {
            return Aa01Event.AutoCompletion(
                trailingBytes = payload.drop(4).map { it.toInt() and 0xFF }
            )
        }
        return Aa01Event.Unknown(payload.map { it.toInt() and 0xFF })
    }

    private fun ByteArray.hasPrefix(vararg expected: Int): Boolean =
        size >= expected.size && expected.indices.all { index ->
            (this[index].toInt() and 0xFF) == expected[index]
        }
}

/** Defense-in-depth authorization at the sole proprietary write boundary. */
object Aa01WriteAllowlist {
    fun isAllowed(payload: ByteArray): Boolean =
        payload.contentEquals(StandbyProtocol.queryCommand()) ||
            payload.contentEquals(StandbyProtocol.setCommand(StandbyState.ON)) ||
            payload.contentEquals(StandbyProtocol.setCommand(StandbyState.OFF)) ||
            FootMode.entries.any { mode ->
                payload.contentEquals(FootModeProtocol.queryCommand(mode)) ||
                    payload.contentEquals(FootModeProtocol.setCommand(mode, FootModeValue.ON)) ||
                    payload.contentEquals(FootModeProtocol.setCommand(mode, FootModeValue.OFF))
            } ||
            payload.contentEquals(AnkleProtocol.queryCommand()) ||
            isSupportedAnkleSet(payload) ||
            payload.contentEquals(AutoAlignmentProtocol.startCommand())

    private fun isSupportedAnkleSet(payload: ByteArray): Boolean {
        if (payload.size != 10) return false
        val prefix = intArrayOf(0xB1, 0xB0, 0x08, 0x13, 0x34, 0x00)
        if (!prefix.indices.all { index ->
                (payload[index].toInt() and 0xFF) == prefix[index]
            }
        ) {
            return false
        }
        val targetMd = (payload[6].toInt() and 0xFF) or
            ((payload[7].toInt() and 0xFF) shl 8) or
            ((payload[8].toInt() and 0xFF) shl 16) or
            ((payload[9].toInt() and 0xFF) shl 24)
        return AnkleProtocol.isSupported(targetMd)
    }
}
