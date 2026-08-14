package com.example.footbattery

import android.content.Context
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

data class VerificationPairingCandidate(
    val address: String,
    val expiresAtElapsedMs: Long
)

fun pairingAddressAllowed(
    requestAddress: String,
    selectedAddress: String?,
    candidate: VerificationPairingCandidate?,
    nowElapsedMs: Long
): Boolean {
    val request = requestAddress.uppercase(Locale.US)
    if (selectedAddress?.uppercase(Locale.US) == request) return true
    return candidate != null && candidate.expiresAtElapsedMs > nowElapsedMs &&
        candidate.address.uppercase(Locale.US) == request
}

/** Allows pairing only for the saved target or one short-lived verification candidate. */
object PairingTargetPolicy {
    private const val CANDIDATE_TTL_MS = 45_000L
    private val candidate = AtomicReference<VerificationPairingCandidate?>(null)

    fun beginVerification(address: String) {
        candidate.set(
            VerificationPairingCandidate(
                address = address,
                expiresAtElapsedMs = SystemClock.elapsedRealtime() + CANDIDATE_TTL_MS
            )
        )
    }

    fun endVerification(address: String) {
        val active = candidate.get()
        if (active?.address.equals(address, ignoreCase = true)) {
            candidate.compareAndSet(active, null)
        }
    }

    fun allows(ctx: Context, address: String): Boolean = pairingAddressAllowed(
        requestAddress = address,
        selectedAddress = Prefs.selectedFoot(ctx.applicationContext)?.address,
        candidate = candidate.get(),
        nowElapsedMs = SystemClock.elapsedRealtime()
    )
}
