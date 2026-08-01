package com.example.footbattery

/** Confirmed Össur AA01 standby packets and response parser. */
object StandbyProtocol {
    private val QUERY = byteArrayOf(0xB0.toByte(), 0xB0.toByte(), 0x11, 0x03, 0x81.toByte(), 0x20)
    private val SET_ON = byteArrayOf(
        0xB1.toByte(), 0xB0.toByte(), 0x11, 0x03, 0x81.toByte(), 0x00, 0x01
    )
    private val SET_OFF = byteArrayOf(
        0xB1.toByte(), 0xB0.toByte(), 0x11, 0x03, 0x81.toByte(), 0x00, 0x00
    )

    fun queryCommand(): ByteArray = QUERY.copyOf()

    fun setCommand(state: StandbyState): ByteArray = when (state) {
        StandbyState.ON -> SET_ON.copyOf()
        StandbyState.OFF -> SET_OFF.copyOf()
        StandbyState.UNKNOWN -> error("Cannot set an unknown standby state")
    }

    fun parseResponse(payload: ByteArray): StandbyState? {
        if (payload.size < 7) return null
        val first = payload[0].toInt() and 0xFF
        if (first != 0xB0 && first != 0xB1) return null
        if ((payload[1].toInt() and 0xFF) != 0xB0) return null
        if ((payload[2].toInt() and 0xFF) != 0x11) return null
        if ((payload[3].toInt() and 0xFF) != 0x03) return null
        if ((payload[4].toInt() and 0xFF) != 0x81) return null
        if ((payload[5].toInt() and 0xFF) != 0xA0) return null
        return when (payload[6].toInt() and 0xFF) {
            0x00 -> StandbyState.OFF
            0x01 -> StandbyState.ON
            else -> null
        }
    }
}
