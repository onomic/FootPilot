package com.example.footbattery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTargetPolicyTest {
    private val now = 1_000L

    @Test fun selectedAddressIsAllowedCaseInsensitively() {
        assertTrue(pairingAddressAllowed("AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff", null, now))
    }

    @Test fun activeVerificationCandidateIsAllowedUntilExpiry() {
        val candidate = VerificationPairingCandidate("11:22:33:44:55:66", now + 1)
        assertTrue(pairingAddressAllowed("11:22:33:44:55:66", null, candidate, now))
        assertFalse(pairingAddressAllowed("11:22:33:44:55:66", null, candidate, now + 1))
    }

    @Test fun unrelatedNearbyAddressIsIgnored() {
        val candidate = VerificationPairingCandidate("11:22:33:44:55:66", now + 10_000)
        assertFalse(
            pairingAddressAllowed(
                "77:88:99:AA:BB:CC",
                "AA:BB:CC:DD:EE:FF",
                candidate,
                now
            )
        )
    }
}
