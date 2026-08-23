package com.onomic.footpilot

enum class FootMode(
    val displayName: String,
    val parameter: Int
) {
    RELAX("Relax Mode", 0x33),
    CHAIR_EXIT("Chair Exit Mode", 0x34)
}

enum class FootModeValue {
    OFF,
    ON,
    UNKNOWN
}

enum class FootModeResponseKind {
    QUERY,
    SET
}

data class FootModeResponse(
    val mode: FootMode,
    val kind: FootModeResponseKind,
    val value: FootModeValue
)

/** Capture-proven AA01 packets for Relax Mode and Chair Exit Mode only. */
object FootModeProtocol {
    private val RELAX_QUERY = bytes(0xB0, 0xB0, 0x33, 0x13, 0x81, 0x20)
    private val RELAX_ON = bytes(0xB1, 0xB0, 0x33, 0x13, 0x81, 0x00, 0x01)
    private val RELAX_OFF = bytes(0xB1, 0xB0, 0x33, 0x13, 0x81, 0x00, 0x00)
    private val CHAIR_EXIT_QUERY = bytes(0xB0, 0xB0, 0x34, 0x13, 0x81, 0x20)
    private val CHAIR_EXIT_ON = bytes(0xB1, 0xB0, 0x34, 0x13, 0x81, 0x00, 0x01)
    private val CHAIR_EXIT_OFF = bytes(0xB1, 0xB0, 0x34, 0x13, 0x81, 0x00, 0x00)

    fun queryCommand(mode: FootMode): ByteArray = when (mode) {
        FootMode.RELAX -> RELAX_QUERY.copyOf()
        FootMode.CHAIR_EXIT -> CHAIR_EXIT_QUERY.copyOf()
    }

    fun setCommand(mode: FootMode, value: FootModeValue): ByteArray = when (mode) {
        FootMode.RELAX -> when (value) {
            FootModeValue.ON -> RELAX_ON.copyOf()
            FootModeValue.OFF -> RELAX_OFF.copyOf()
            FootModeValue.UNKNOWN -> error("Cannot set an unknown foot mode value")
        }
        FootMode.CHAIR_EXIT -> when (value) {
            FootModeValue.ON -> CHAIR_EXIT_ON.copyOf()
            FootModeValue.OFF -> CHAIR_EXIT_OFF.copyOf()
            FootModeValue.UNKNOWN -> error("Cannot set an unknown foot mode value")
        }
    }

    fun matches(
        response: FootModeResponse,
        mode: FootMode,
        expectedKind: FootModeResponseKind,
        expectedValue: FootModeValue? = null
    ): Boolean = response.mode == mode && response.kind == expectedKind &&
        (expectedValue == null || response.value == expectedValue)

    fun parseResponse(payload: ByteArray): FootModeResponse? {
        if (payload.size < 7) return null
        val kind = when (payload.unsigned(0)) {
            0xB0 -> FootModeResponseKind.QUERY
            0xB1 -> FootModeResponseKind.SET
            else -> return null
        }
        if (payload.unsigned(1) != 0xB0) return null
        val mode = when (payload.unsigned(2)) {
            FootMode.RELAX.parameter -> FootMode.RELAX
            FootMode.CHAIR_EXIT.parameter -> FootMode.CHAIR_EXIT
            else -> return null
        }
        if (payload.unsigned(3) != 0x13) return null
        if (payload.unsigned(4) != 0x81) return null
        if (payload.unsigned(5) != 0xA0) return null
        val value = when (payload.unsigned(6)) {
            0x00 -> FootModeValue.OFF
            0x01 -> FootModeValue.ON
            else -> return null
        }
        return FootModeResponse(mode, kind, value)
    }

    private fun ByteArray.unsigned(index: Int): Int = this[index].toInt() and 0xFF

    private fun bytes(vararg values: Int): ByteArray = values.map(Int::toByte).toByteArray()
}
