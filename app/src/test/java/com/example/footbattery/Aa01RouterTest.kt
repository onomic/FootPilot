package com.example.footbattery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aa01RouterTest {
    @Test fun standbyAndAnklePacketsRouteToDistinctTypedFamilies() {
        val standby = Aa01Router.parse(bytes(0xB0, 0xB0, 0x11, 0x03, 0x81, 0xA0, 0x00))
        val ankle = Aa01Router.parse(
            bytes(0xB1, 0xB0, 0x08, 0x13, 0x34, 0xA0, 0x39, 0x02, 0x00, 0x00, 0x77)
        )

        assertTrue(standby is Aa01Event.Standby)
        assertEquals(StandbyResponseKind.QUERY, (standby as Aa01Event.Standby).response.kind)
        assertTrue(ankle is Aa01Event.Ankle)
        assertEquals(AnkleResponseKind.SET, (ankle as Aa01Event.Ankle).response.kind)
        assertEquals(569, ankle.response.millidegrees)
        assertEquals(listOf(0x77), ankle.response.trailingBytes)
    }

    @Test fun observedAutoValuesRemainOpaqueActivity() {
        listOf(0x00, 0x1E, 0x3C).forEach { value ->
            val event = Aa01Router.parse(bytes(0xB2, 0xB0, 0x04, 0x01, value, 0x55))
            assertEquals(value, (event as Aa01Event.AutoActivity).opaqueStatus)
            assertEquals(listOf(0x55), event.trailingBytes)
        }
    }

    @Test fun relaxAndChairResponsesRouteToTypedModeEventsWithOpaqueTrailingBytes() {
        val relax = Aa01Router.parse(
            bytes(0xB0, 0xB0, 0x33, 0x13, 0x81, 0xA0, 0x00, 0x55)
        ) as Aa01Event.FootMode
        val chair = Aa01Router.parse(
            bytes(0xB1, 0xB0, 0x34, 0x13, 0x81, 0xA0, 0x01, 0x66, 0x77)
        ) as Aa01Event.FootMode

        assertEquals(FootMode.RELAX, relax.response.mode)
        assertEquals(FootModeResponseKind.QUERY, relax.response.kind)
        assertEquals(FootModeValue.OFF, relax.response.value)
        assertEquals(listOf(0x55), relax.trailingBytes)
        assertEquals(FootMode.CHAIR_EXIT, chair.response.mode)
        assertEquals(FootModeResponseKind.SET, chair.response.kind)
        assertEquals(FootModeValue.ON, chair.response.value)
        assertEquals(listOf(0x66, 0x77), chair.trailingBytes)
    }

    @Test fun incomingAutoCompletionIsTypedButUnknownInitCommandIsIgnored() {
        assertTrue(
            Aa01Router.parse(bytes(0xB2, 0xB0, 0x04, 0x00, 0x00)) is
                Aa01Event.AutoCompletion
        )
        assertTrue(
            Aa01Router.parse(bytes(0xB2, 0xB0, 0x04, 0x02)) is Aa01Event.Unknown
        )
    }

    @Test fun shortAndUnrelatedPacketsAreUnknown() {
        assertTrue(Aa01Router.parse(bytes(0xB2, 0xB0, 0x04, 0x01)) is Aa01Event.Unknown)
        assertTrue(Aa01Router.parse(bytes(0xB0, 0x10, 0x11, 0x11, 0x24, 0x90)) is Aa01Event.Unknown)
    }

    @Test fun outgoingAllowlistAcceptsOnlyProvenPacketsAndSupportedAnkleTargets() {
        assertTrue(Aa01WriteAllowlist.isAllowed(StandbyProtocol.queryCommand()))
        assertTrue(Aa01WriteAllowlist.isAllowed(StandbyProtocol.setCommand(StandbyState.ON)))
        assertTrue(Aa01WriteAllowlist.isAllowed(StandbyProtocol.setCommand(StandbyState.OFF)))
        FootMode.entries.forEach { mode ->
            assertTrue(Aa01WriteAllowlist.isAllowed(FootModeProtocol.queryCommand(mode)))
            assertTrue(Aa01WriteAllowlist.isAllowed(
                FootModeProtocol.setCommand(mode, FootModeValue.ON)
            ))
            assertTrue(Aa01WriteAllowlist.isAllowed(
                FootModeProtocol.setCommand(mode, FootModeValue.OFF)
            ))
        }
        assertTrue(Aa01WriteAllowlist.isAllowed(AnkleProtocol.queryCommand()))
        assertTrue(Aa01WriteAllowlist.isAllowed(AnkleProtocol.setCommand(-2000)))
        assertTrue(Aa01WriteAllowlist.isAllowed(AnkleProtocol.setCommand(14000)))
        assertTrue(Aa01WriteAllowlist.isAllowed(AutoAlignmentProtocol.startCommand()))

        assertTrue(!Aa01WriteAllowlist.isAllowed(bytes(0xB2, 0xB0, 0x04, 0x02)))
        assertTrue(!Aa01WriteAllowlist.isAllowed(
            bytes(0xB1, 0xB0, 0x08, 0x13, 0x34, 0x00, 0x2F, 0xF8, 0xFF, 0xFF)
        ))
        assertTrue(!Aa01WriteAllowlist.isAllowed(bytes(0xB1, 0xB0, 0x08, 0x13)))
        assertTrue(!Aa01WriteAllowlist.isAllowed(bytes(0xB0, 0xB0, 0x35, 0x13, 0x81, 0x20)))
        assertTrue(!Aa01WriteAllowlist.isAllowed(
            bytes(0xB1, 0xB0, 0x33, 0x13, 0x81, 0x00, 0x02)
        ))
        assertTrue(!Aa01WriteAllowlist.isAllowed(
            bytes(0xB1, 0xB0, 0x34, 0x13, 0x81, 0x01, 0x01)
        ))
        assertTrue(!Aa01WriteAllowlist.isAllowed(
            bytes(0xB1, 0xB0, 0x33, 0x12, 0x81, 0x00, 0x01)
        ))
    }

    private fun bytes(vararg values: Int): ByteArray = values.map(Int::toByte).toByteArray()
}
