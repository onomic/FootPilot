package com.example.footbattery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FootSetupSearchCoordinatorTest {
    private val candidate = FootCandidate("MyFoot", "AA:BB:CC:DD:EE:FF")

    @Test fun blankNameDoesNotStartScanning() = runBlocking {
        val fixture = Fixture(FootScanResult.NotFound, FootVerificationResult.Compatible)
        val result = fixture.coordinator.findAndSelect("   ")
        assertEquals(FootSetupSearchResult.EnterName, result)
        assertEquals(0, fixture.scanner.calls)
        assertEquals(0, fixture.commits.size)
    }

    @Test fun exactCandidateIsVerifiedBeforeItIsCommitted() = runBlocking {
        val fixture = Fixture(FootScanResult.Candidate(candidate), FootVerificationResult.Compatible)
        val result = fixture.coordinator.findAndSelect("MyFoot")
        assertTrue(result is FootSetupSearchResult.Selected)
        assertTrue((result as FootSetupSearchResult.Selected).targetChanged)
        assertEquals(listOf(candidate), fixture.verified)
        assertEquals(listOf(SelectedFoot("MyFoot", "AA:BB:CC:DD:EE:FF")), fixture.commits)
        assertEquals(10_000L, fixture.scanner.timeoutMs)
    }

    @Test fun nonmatchingScanResultIsNeverVerifiedOrSaved() = runBlocking {
        val other = FootCandidate("OtherFoot", "AA:BB:CC:DD:EE:FF")
        val fixture = Fixture(FootScanResult.Candidate(other), FootVerificationResult.Compatible)
        val result = fixture.coordinator.findAndSelect("MyFoot")
        assertEquals(FootSetupSearchResult.NotFound("MyFoot"), result)
        assertTrue(fixture.verified.isEmpty())
        assertTrue(fixture.commits.isEmpty())
    }

    @Test fun timeoutDoesNotReplaceCurrentSelection() = runBlocking {
        val fixture = Fixture(FootScanResult.NotFound, FootVerificationResult.Compatible)
        assertEquals(
            FootSetupSearchResult.NotFound("MyFoot"),
            fixture.coordinator.findAndSelect("MyFoot")
        )
        assertTrue(fixture.commits.isEmpty())
    }

    @Test fun incompatibleOrFailedVerificationDoesNotCommit() = runBlocking {
        val incompatible = Fixture(
            FootScanResult.Candidate(candidate),
            FootVerificationResult.Incompatible
        )
        assertEquals(
            FootSetupSearchResult.Incompatible("MyFoot"),
            incompatible.coordinator.findAndSelect("MyFoot")
        )
        assertTrue(incompatible.commits.isEmpty())

        val failed = Fixture(
            FootScanResult.Candidate(candidate),
            FootVerificationResult.Failed("Couldn't verify MyFoot.")
        )
        assertTrue(failed.coordinator.findAndSelect("MyFoot") is FootSetupSearchResult.Failed)
        assertTrue(failed.commits.isEmpty())
    }

    @Test fun busyCommitLeavesTheExistingSelectionUntouched() = runBlocking {
        val fixture = Fixture(
            FootScanResult.Candidate(candidate),
            FootVerificationResult.Compatible,
            commitResult = SelectedFootChangeResult.Blocked(
                "Disconnect before changing the foot."
            )
        )
        assertTrue(fixture.coordinator.findAndSelect("MyFoot") is FootSetupSearchResult.Blocked)
        assertEquals(1, fixture.commits.size)
    }

    @Test fun reverifiedCurrentTargetIsSelectedWithoutReportingAChange() = runBlocking {
        val fixture = Fixture(
            FootScanResult.Candidate(candidate),
            FootVerificationResult.Compatible,
            commitResult = SelectedFootChangeResult.Unchanged
        )

        val result = fixture.coordinator.findAndSelect("MyFoot")

        assertTrue(result is FootSetupSearchResult.Selected)
        assertEquals(false, (result as FootSetupSearchResult.Selected).targetChanged)
    }

    private class Fixture(
        scanResult: FootScanResult,
        verificationResult: FootVerificationResult,
        commitResult: SelectedFootChangeResult = SelectedFootChangeResult.Changed(
            SelectedFoot("MyFoot", "AA:BB:CC:DD:EE:FF")
        )
    ) {
        val scanner = FakeScanner(scanResult)
        val verified = mutableListOf<FootCandidate>()
        val commits = mutableListOf<SelectedFoot>()
        val coordinator = FootSetupSearchCoordinator(
            scanner = scanner,
            verifier = FootVerifier {
                verified += it
                verificationResult
            },
            committer = SelectedFootCommitter {
                commits += it
                commitResult
            }
        )
    }

    private class FakeScanner(private val result: FootScanResult) : FootScanner {
        var calls = 0
        var timeoutMs = 0L

        override suspend fun findExact(name: String, timeoutMs: Long): FootScanResult {
            calls++
            this.timeoutMs = timeoutMs
            return result
        }
    }
}
