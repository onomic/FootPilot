package com.example.footbattery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectedFootPersistenceTest {
    @Test fun freshInstallHasNoSelectedFoot() {
        assertNull(SelectedFootPersistence.decode(StoredSelectedFoot(null, null)))
    }

    @Test fun legacyBeta2ValuesAreNotInventedOrMigrated() {
        val legacyWithoutSelectionKeys = StoredSelectedFoot(name = null, address = null)
        assertNull(SelectedFootPersistence.decode(legacyWithoutSelectionKeys))
    }

    @Test fun verifiedSelectionRoundTripsNameAndAddress() {
        val selected = SelectedFoot("MyFoot", "aa:bb:cc:dd:ee:ff")
        val stored = SelectedFootPersistence.encode(selected)

        assertEquals(StoredSelectedFoot("MyFoot", "AA:BB:CC:DD:EE:FF"), stored)
        assertEquals(
            SelectedFoot("MyFoot", "AA:BB:CC:DD:EE:FF"),
            SelectedFootPersistence.decode(stored)
        )
    }

    @Test fun removingSelectionEncodesAndReadsNull() {
        assertNull(SelectedFootPersistence.decode(SelectedFootPersistence.encode(null)))
    }

    @Test fun blankOrMalformedStoredValuesFailClosed() {
        assertNull(SelectedFootPersistence.decode("", "AA:BB:CC:DD:EE:FF"))
        assertNull(SelectedFootPersistence.decode("Foot", ""))
        assertNull(SelectedFootPersistence.decode("Foot", "not-an-address"))
        assertNull(SelectedFootPersistence.decode("Foot", "AA:BB:CC:DD:EE"))
    }
}
