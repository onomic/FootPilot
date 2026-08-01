package com.example.footbattery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StandbyProtocolTest {
    @Test fun queryPacketMatchesConfirmedProtocol() {
        assertArrayEquals(
            byteArrayOf(0xB0.toByte(), 0xB0.toByte(), 0x11, 0x03, 0x81.toByte(), 0x20),
            StandbyProtocol.queryCommand()
        )
    }

    @Test fun setPacketsMatchConfirmedProtocol() {
        assertArrayEquals(
            byteArrayOf(
                0xB1.toByte(), 0xB0.toByte(), 0x11, 0x03, 0x81.toByte(), 0x00, 0x01
            ),
            StandbyProtocol.setCommand(StandbyState.ON)
        )
        assertArrayEquals(
            byteArrayOf(
                0xB1.toByte(), 0xB0.toByte(), 0x11, 0x03, 0x81.toByte(), 0x00, 0x00
            ),
            StandbyProtocol.setCommand(StandbyState.OFF)
        )
    }

    @Test fun validQueryResponseOff() {
        assertEquals(
            StandbyResponse(StandbyResponseKind.QUERY, StandbyState.OFF),
            StandbyProtocol.parseResponse(response(0xB0, 0x00))
        )
    }

    @Test fun validQueryResponseOn() {
        assertEquals(
            StandbyResponse(StandbyResponseKind.QUERY, StandbyState.ON),
            StandbyProtocol.parseResponse(response(0xB0, 0x01))
        )
    }

    @Test fun validSetResponseOff() {
        assertEquals(
            StandbyResponse(StandbyResponseKind.SET, StandbyState.OFF),
            StandbyProtocol.parseResponse(response(0xB1, 0x00))
        )
    }

    @Test fun validSetResponseOn() {
        assertEquals(
            StandbyResponse(StandbyResponseKind.SET, StandbyState.ON),
            StandbyProtocol.parseResponse(response(0xB1, 0x01))
        )
    }

    @Test fun trailingBytesAreIgnored() {
        val payload = response(0xB1, 0x01) + byteArrayOf(0x55, 0x66, 0x77)
        assertEquals(
            StandbyResponse(StandbyResponseKind.SET, StandbyState.ON),
            StandbyProtocol.parseResponse(payload)
        )
    }

    @Test fun shorterThanSevenBytesIsRejected() {
        assertNull(StandbyProtocol.parseResponse(byteArrayOf(0xB0.toByte(), 0xB0.toByte(), 0x11)))
    }

    @Test fun wrongPrefixIsRejected() {
        val payload = response(0xB0, 0x01).also { it[1] = 0xAF.toByte() }
        assertNull(StandbyProtocol.parseResponse(payload))
    }

    @Test fun wrongCommandFamilyIsRejected() {
        val payload = response(0xB0, 0x01).also { it[2] = 0x12 }
        assertNull(StandbyProtocol.parseResponse(payload))
    }

    @Test fun invalidStateByteIsRejected() {
        assertNull(StandbyProtocol.parseResponse(response(0xB1, 0x02)))
    }

    @Test fun unrelatedAa01NotificationIsRejected() {
        val payload = response(0xB0, 0x01).also { it[5] = 0x90.toByte() }
        assertNull(StandbyProtocol.parseResponse(payload))
    }

    @Test fun responseMatchingRequiresTheExpectedFamilyAndOptionalState() {
        val queryOn = StandbyResponse(StandbyResponseKind.QUERY, StandbyState.ON)
        val setOn = StandbyResponse(StandbyResponseKind.SET, StandbyState.ON)
        val setOff = StandbyResponse(StandbyResponseKind.SET, StandbyState.OFF)

        assertFalse(StandbyProtocol.matches(setOn, StandbyResponseKind.QUERY))
        assertFalse(StandbyProtocol.matches(queryOn, StandbyResponseKind.SET))
        assertFalse(
            StandbyProtocol.matches(setOff, StandbyResponseKind.SET, StandbyState.ON)
        )
        assertEquals(
            true,
            StandbyProtocol.matches(setOn, StandbyResponseKind.SET, StandbyState.ON)
        )
    }

    private fun response(first: Int, state: Int) = byteArrayOf(
        first.toByte(), 0xB0.toByte(), 0x11, 0x03, 0x81.toByte(), 0xA0.toByte(), state.toByte()
    )
}
