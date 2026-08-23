package com.onomic.footpilot

/** Only the capture-proven Auto start command is exposed for outgoing use. */
object AutoAlignmentProtocol {
    private val START = byteArrayOf(0xB2.toByte(), 0xB0.toByte(), 0x04, 0x00)

    fun startCommand(): ByteArray = START.copyOf()
}
