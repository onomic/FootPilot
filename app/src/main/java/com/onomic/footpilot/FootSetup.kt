package com.onomic.footpilot

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class FootCandidate(
    val advertisedName: String,
    val address: String
)

fun exactAdvertisedNameMatch(expected: String, advertised: String?): Boolean =
    advertised != null && advertised == expected.trim() && expected.trim().isNotEmpty()

sealed interface FootScanResult {
    data class Candidate(val candidate: FootCandidate) : FootScanResult
    data object NotFound : FootScanResult
    data object BluetoothDisabled : FootScanResult
    data object PermissionMissing : FootScanResult
    data class Failed(val message: String) : FootScanResult
}

fun interface FootScanner {
    suspend fun findExact(name: String, timeoutMs: Long): FootScanResult
}

sealed interface FootVerificationResult {
    data object Compatible : FootVerificationResult
    data object Incompatible : FootVerificationResult
    data object Busy : FootVerificationResult
    data class Failed(val message: String) : FootVerificationResult
}

fun interface FootVerifier {
    suspend fun verify(candidate: FootCandidate): FootVerificationResult
}

fun interface SelectedFootCommitter {
    suspend fun commit(target: SelectedFoot): SelectedFootChangeResult
}

sealed interface FootSetupSearchResult {
    data object EnterName : FootSetupSearchResult
    data class Selected(
        val foot: SelectedFoot,
        val targetChanged: Boolean
    ) : FootSetupSearchResult
    data class NotFound(val name: String) : FootSetupSearchResult
    data class Incompatible(val name: String) : FootSetupSearchResult
    data object BluetoothDisabled : FootSetupSearchResult
    data object PermissionMissing : FootSetupSearchResult
    data class Blocked(val message: String) : FootSetupSearchResult
    data class Failed(val message: String) : FootSetupSearchResult
}

class FootSetupSearchCoordinator(
    private val scanner: FootScanner,
    private val verifier: FootVerifier,
    private val committer: SelectedFootCommitter,
    private val timeoutMs: Long = 10_000L
) {
    suspend fun findAndSelect(rawName: String): FootSetupSearchResult {
        val name = rawName.trim()
        if (name.isEmpty()) return FootSetupSearchResult.EnterName

        val candidate = when (val scan = scanner.findExact(name, timeoutMs)) {
            is FootScanResult.Candidate -> scan.candidate.takeIf {
                exactAdvertisedNameMatch(name, it.advertisedName)
            } ?: return FootSetupSearchResult.NotFound(name)
            FootScanResult.NotFound -> return FootSetupSearchResult.NotFound(name)
            FootScanResult.BluetoothDisabled -> return FootSetupSearchResult.BluetoothDisabled
            FootScanResult.PermissionMissing -> return FootSetupSearchResult.PermissionMissing
            is FootScanResult.Failed -> return FootSetupSearchResult.Failed(scan.message)
        }

        when (val verification = verifier.verify(candidate)) {
            FootVerificationResult.Compatible -> Unit
            FootVerificationResult.Incompatible -> return FootSetupSearchResult.Incompatible(name)
            FootVerificationResult.Busy -> return FootSetupSearchResult.Blocked(
                "Disconnect before changing the foot."
            )
            is FootVerificationResult.Failed -> return FootSetupSearchResult.Failed(
                verification.message
            )
        }

        currentCoroutineContext().ensureActive()

        val target = SelectedFootPersistence.decode(candidate.advertisedName, candidate.address)
            ?: return FootSetupSearchResult.Failed("The verified foot could not be saved.")
        return when (val commit = committer.commit(target)) {
            is SelectedFootChangeResult.Changed -> FootSetupSearchResult.Selected(
                target,
                targetChanged = true
            )
            SelectedFootChangeResult.Unchanged -> FootSetupSearchResult.Selected(
                target,
                targetChanged = false
            )
            is SelectedFootChangeResult.Blocked -> FootSetupSearchResult.Blocked(commit.message)
            is SelectedFootChangeResult.Failed -> FootSetupSearchResult.Failed(commit.message)
        }
    }
}

sealed interface FootSetupFeedback {
    data object Idle : FootSetupFeedback
    data class Finding(val name: String) : FootSetupFeedback
    data class Error(val message: String) : FootSetupFeedback
}

enum class FootSetupStatusTone { MUTED, SUCCESS, WARNING }

data class FootSetupStatusPresentation(
    val text: String,
    val tone: FootSetupStatusTone,
    val showRemove: Boolean
)

fun footSetupStatusPresentation(
    selectedFoot: SelectedFoot?,
    feedback: FootSetupFeedback
): FootSetupStatusPresentation = when (feedback) {
    is FootSetupFeedback.Finding -> FootSetupStatusPresentation(
        text = "Finding ${feedback.name}\u2026",
        tone = FootSetupStatusTone.MUTED,
        showRemove = false
    )
    is FootSetupFeedback.Error -> FootSetupStatusPresentation(
        text = feedback.message,
        tone = FootSetupStatusTone.WARNING,
        showRemove = false
    )
    FootSetupFeedback.Idle -> if (selectedFoot == null) {
        FootSetupStatusPresentation("No foot selected", FootSetupStatusTone.MUTED, false)
    } else {
        FootSetupStatusPresentation(
            "Selected: ${selectedFoot.name}",
            FootSetupStatusTone.SUCCESS,
            true
        )
    }
}

data class FootSetupActionAvailability(
    val canChange: Boolean,
    val helperText: String
)

fun footSetupActionAvailability(
    monitoringActive: Boolean,
    bleOperationActive: Boolean,
    ankleOperationActive: Boolean,
    searching: Boolean
): FootSetupActionAvailability {
    val unsafe = monitoringActive || bleOperationActive || ankleOperationActive
    return FootSetupActionAvailability(
        canChange = !unsafe && !searching,
        helperText = if (unsafe) {
            "Disconnect before changing the foot."
        } else {
            "Enter the Bluetooth name for your foot."
        }
    )
}
