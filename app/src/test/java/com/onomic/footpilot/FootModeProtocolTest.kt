package com.onomic.footpilot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FootModeProtocolTest {
    @Test fun relaxCommandsMatchCapturedBytesExactly() {
        assertArrayEquals(bytes(0xB0, 0xB0, 0x33, 0x13, 0x81, 0x20),
            FootModeProtocol.queryCommand(FootMode.RELAX))
        assertArrayEquals(bytes(0xB1, 0xB0, 0x33, 0x13, 0x81, 0x00, 0x01),
            FootModeProtocol.setCommand(FootMode.RELAX, FootModeValue.ON))
        assertArrayEquals(bytes(0xB1, 0xB0, 0x33, 0x13, 0x81, 0x00, 0x00),
            FootModeProtocol.setCommand(FootMode.RELAX, FootModeValue.OFF))
    }

    @Test fun chairExitCommandsMatchCapturedBytesExactly() {
        assertArrayEquals(bytes(0xB0, 0xB0, 0x34, 0x13, 0x81, 0x20),
            FootModeProtocol.queryCommand(FootMode.CHAIR_EXIT))
        assertArrayEquals(bytes(0xB1, 0xB0, 0x34, 0x13, 0x81, 0x00, 0x01),
            FootModeProtocol.setCommand(FootMode.CHAIR_EXIT, FootModeValue.ON))
        assertArrayEquals(bytes(0xB1, 0xB0, 0x34, 0x13, 0x81, 0x00, 0x00),
            FootModeProtocol.setCommand(FootMode.CHAIR_EXIT, FootModeValue.OFF))
    }

    @Test fun publicCommandArraysAreDefensiveCopies() {
        val first = FootModeProtocol.queryCommand(FootMode.RELAX)
        first[2] = 0x35
        assertArrayEquals(
            bytes(0xB0, 0xB0, 0x33, 0x13, 0x81, 0x20),
            FootModeProtocol.queryCommand(FootMode.RELAX)
        )
    }

    @Test fun allCapturedQueryResponsesParse() {
        FootMode.entries.forEach { mode ->
            FootModeValue.entries.filter { it != FootModeValue.UNKNOWN }.forEach { value ->
                val parsed = FootModeProtocol.parseResponse(response(mode, 0xB0, value))
                assertEquals(FootModeResponse(mode, FootModeResponseKind.QUERY, value), parsed)
            }
        }
    }

    @Test fun allCapturedSetResponsesParse() {
        FootMode.entries.forEach { mode ->
            FootModeValue.entries.filter { it != FootModeValue.UNKNOWN }.forEach { value ->
                val parsed = FootModeProtocol.parseResponse(response(mode, 0xB1, value))
                assertEquals(FootModeResponse(mode, FootModeResponseKind.SET, value), parsed)
            }
        }
    }

    @Test fun trailingBytesAreAcceptedWithoutChangingParsedState() {
        val payload = response(FootMode.CHAIR_EXIT, 0xB0, FootModeValue.ON) +
            bytes(0x55, 0xAA, 0x10)
        assertEquals(
            FootModeResponse(FootMode.CHAIR_EXIT, FootModeResponseKind.QUERY, FootModeValue.ON),
            FootModeProtocol.parseResponse(payload)
        )
    }

    @Test fun payloadShorterThanSevenBytesIsRejected() {
        assertNull(FootModeProtocol.parseResponse(bytes(0xB0, 0xB0, 0x33, 0x13, 0x81, 0xA0)))
    }

    @Test fun unknownParameterIsRejected() {
        assertNull(FootModeProtocol.parseResponse(bytes(0xB0, 0xB0, 0x35, 0x13, 0x81, 0xA0, 0x01)))
    }

    @Test fun invalidStateIsRejected() {
        assertNull(FootModeProtocol.parseResponse(bytes(0xB0, 0xB0, 0x33, 0x13, 0x81, 0xA0, 0x02)))
    }

    @Test fun wrongFamilyByteIsRejected() {
        assertNull(FootModeProtocol.parseResponse(bytes(0xB2, 0xB0, 0x33, 0x13, 0x81, 0xA0, 0x01)))
    }

    @Test fun wrongThirteenByteIsRejected() {
        assertNull(FootModeProtocol.parseResponse(bytes(0xB0, 0xB0, 0x33, 0x12, 0x81, 0xA0, 0x01)))
    }

    @Test fun wrongEightyOneByteIsRejected() {
        assertNull(FootModeProtocol.parseResponse(bytes(0xB0, 0xB0, 0x33, 0x13, 0x80, 0xA0, 0x01)))
    }

    @Test fun wrongA0ByteIsRejected() {
        assertNull(FootModeProtocol.parseResponse(bytes(0xB0, 0xB0, 0x33, 0x13, 0x81, 0xA1, 0x01)))
    }

    @Test fun unknownCannotBeConstructedAsSetTarget() {
        assertThrows(IllegalStateException::class.java) {
            FootModeProtocol.setCommand(FootMode.RELAX, FootModeValue.UNKNOWN)
        }
    }

    private fun response(mode: FootMode, family: Int, value: FootModeValue): ByteArray = bytes(
        family,
        0xB0,
        mode.parameter,
        0x13,
        0x81,
        0xA0,
        if (value == FootModeValue.ON) 0x01 else 0x00
    )

    private fun bytes(vararg values: Int): ByteArray = values.map(Int::toByte).toByteArray()
}
