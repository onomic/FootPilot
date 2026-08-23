package com.onomic.footpilot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkleProtocolTest {
    @Test fun queryPacketMatchesCapture() {
        assertArrayEquals(
            bytes(0xB0, 0xB0, 0x08, 0x13, 0x34, 0x20),
            AnkleProtocol.queryCommand()
        )
    }

    @Test fun capturedSignedLittleEndianFixturesRoundTrip() {
        val fixtures = listOf(
            -2000 to bytes(0x30, 0xF8, 0xFF, 0xFF),
            -1900 to bytes(0x94, 0xF8, 0xFF, 0xFF),
            0 to bytes(0x00, 0x00, 0x00, 0x00),
            569 to bytes(0x39, 0x02, 0x00, 0x00),
            277 to bytes(0x15, 0x01, 0x00, 0x00),
            4499 to bytes(0x93, 0x11, 0x00, 0x00),
            4599 to bytes(0xF7, 0x11, 0x00, 0x00),
            7261 to bytes(0x5D, 0x1C, 0x00, 0x00),
            14000 to bytes(0xB0, 0x36, 0x00, 0x00)
        )

        fixtures.forEach { (millidegrees, encoded) ->
            val command = AnkleProtocol.setCommand(millidegrees)
            assertArrayEquals(encoded, command.copyOfRange(6, 10))
            val parsed = AnkleProtocol.parseResponse(
                bytes(0xB0, 0xB0, 0x08, 0x13, 0x34, 0xA0) + encoded
            )
            assertEquals(millidegrees, parsed?.millidegrees)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun belowMinimumIsRejected() {
        AnkleProtocol.setCommand(-2001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aboveMaximumIsRejected() {
        AnkleProtocol.setCommand(14001)
    }

    @Test fun exactFineAdjustmentUsesUnroundedMillidegrees() {
        assertEquals(4599, AnkleProtocol.fineTarget(4499, FineAdjustment.PLUS))
        assertEquals(4399, AnkleProtocol.fineTarget(4499, FineAdjustment.MINUS))
    }

    @Test fun nonRoundBoundaryAvailabilityNeverClamps() {
        assertNull(AnkleProtocol.fineTarget(-1950, FineAdjustment.MINUS))
        assertEquals(-2000, AnkleProtocol.fineTarget(-1900, FineAdjustment.MINUS))
        assertEquals(14000, AnkleProtocol.fineTarget(13900, FineAdjustment.PLUS))
        assertNull(AnkleProtocol.fineTarget(13950, FineAdjustment.PLUS))
    }

    @Test fun parserAcceptsOnlyCompleteTypedAnkleFamilies() {
        val query = response(0xB0, 569) + bytes(0x55, 0x66)
        val set = response(0xB1, 4599)

        assertEquals(AnkleResponseKind.QUERY, AnkleProtocol.parseResponse(query)?.kind)
        assertEquals(listOf(0x55, 0x66), AnkleProtocol.parseResponse(query)?.trailingBytes)
        assertEquals(AnkleResponseKind.SET, AnkleProtocol.parseResponse(set)?.kind)
        assertNull(AnkleProtocol.parseResponse(bytes(0xB0, 0xB0, 0x08)))
        assertNull(AnkleProtocol.parseResponse(bytes(0xB0, 0xB0, 0x11, 0x03, 0x81, 0xA0, 0x00)))
        assertNull(AnkleProtocol.parseResponse(bytes(0xB2, 0xB0, 0x04, 0x01, 0x1E)))
        assertNull(AnkleProtocol.parseResponse(response(0xB0, 569).also { it[4] = 0x35 }))
    }

    @Test fun signedOneDecimalFormattingUsesLeadingPlus() {
        assertEquals("+4.5°", AnkleProtocol.format(4499))
        assertEquals("-1.9°", AnkleProtocol.format(-1900))
        assertTrue(AnkleProtocol.isSupported(-2000))
        assertFalse(AnkleProtocol.isSupported(14001))
    }

    private fun response(first: Int, md: Int): ByteArray =
        bytes(first, 0xB0, 0x08, 0x13, 0x34, 0xA0) +
            AnkleProtocol.setCommand(md).copyOfRange(6, 10)

    private fun bytes(vararg values: Int): ByteArray =
        values.map(Int::toByte).toByteArray()
}
