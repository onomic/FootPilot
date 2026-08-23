package com.onomic.footpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Beta3SafetyRulesTest {
    @Test fun scheduledChecksRequireASelectedFoot() {
        val selected = SelectedFoot("MyFoot", "AA:BB:CC:DD:EE:FF")
        assertTrue(shouldRunScheduledCheck(true, selected, true))
        assertFalse(shouldRunScheduledCheck(true, null, true))
        assertFalse(shouldRunScheduledCheck(false, selected, true))
        assertFalse(shouldRunScheduledCheck(true, selected, false))
    }

    @Test fun api31ColorsAreResolvedWhenRemoteViewsAreApplied() {
        assertEquals(
            RemoteColorApplication.RESOURCE_DEFERRED,
            notificationColorApplication(31)
        )
        assertEquals(
            RemoteColorApplication.RESOLVED_FALLBACK,
            notificationColorApplication(30)
        )
    }
}
