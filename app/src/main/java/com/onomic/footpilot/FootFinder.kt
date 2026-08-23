package com.onomic.footpilot

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** One bounded foreground scanner. It never exposes or retains a results list. */
class AndroidFootScanner(context: Context) : FootScanner {
    private val appContext = context.applicationContext
    private val guard = Any()
    private var active: ActiveScan? = null

    private data class ActiveScan(
        val scanner: BluetoothLeScanner,
        val callback: ScanCallback,
        val outcome: CompletableDeferred<FootScanResult>
    )

    @SuppressLint("MissingPermission")
    override suspend fun findExact(name: String, timeoutMs: Long): FootScanResult {
        if (!hasSearchPermissions()) return FootScanResult.PermissionMissing
        val adapter = try {
            (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        } catch (_: Exception) {
            null
        } ?: return FootScanResult.BluetoothDisabled
        if (!adapter.isEnabled) return FootScanResult.BluetoothDisabled
        val scanner = adapter.bluetoothLeScanner ?: return FootScanResult.BluetoothDisabled
        val outcome = CompletableDeferred<FootScanResult>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                acceptIfExact(name, result, outcome)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { acceptIfExact(name, it, outcome) }
            }

            override fun onScanFailed(errorCode: Int) {
                outcome.complete(FootScanResult.Failed("Couldn't search for $name. Try again."))
            }
        }
        val record = ActiveScan(scanner, callback, outcome)
        cancel()
        synchronized(guard) { active = record }

        return try {
            val filter = ScanFilter.Builder().setDeviceName(name).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(listOf(filter), settings, callback)
            withTimeoutOrNull(timeoutMs) { outcome.await() } ?: FootScanResult.NotFound
        } catch (_: SecurityException) {
            FootScanResult.PermissionMissing
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FootScanResult.Failed(e.message ?: "Couldn't search for $name. Try again.")
        } finally {
            stop(record)
        }
    }

    /** Returns true only when a foreground scan was active and has now been cancelled. */
    fun cancel(): Boolean {
        val record = synchronized(guard) { active }
        if (record == null) return false
        record.outcome.cancel()
        stop(record)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun acceptIfExact(
        expectedName: String,
        result: ScanResult,
        outcome: CompletableDeferred<FootScanResult>
    ) {
        if (outcome.isCompleted) return
        val advertisedName = result.scanRecord?.deviceName ?: try {
            result.device.name
        } catch (_: Exception) {
            null
        }
        if (!exactAdvertisedNameMatch(expectedName, advertisedName)) return
        val address = try {
            result.device.address
        } catch (_: Exception) {
            null
        } ?: return
        outcome.complete(
            FootScanResult.Candidate(
                FootCandidate(advertisedName = requireNotNull(advertisedName), address = address)
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun stop(record: ActiveScan) {
        synchronized(guard) {
            if (active !== record) return
            active = null
        }
        try {
            record.scanner.stopScan(record.callback)
        } catch (_: Exception) {
            // The callback is already detached from app state; a platform stop failure is harmless.
        }
    }

    private fun hasSearchPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
}

/** Profile-only verification through the same GATT owner used by every normal operation. */
class FootCandidateVerifier(context: Context) : FootVerifier {
    private val appContext = context.applicationContext

    override suspend fun verify(candidate: FootCandidate): FootVerificationResult {
        val target = SelectedFootPersistence.decode(candidate.advertisedName, candidate.address)
            ?: return FootVerificationResult.Incompatible
        val coordinated = BleOperationCoordinator.tryRun(BleOperationKind.FOOT_VERIFICATION) {
            PairingTargetPolicy.beginVerification(target.address)
            val session = FootGattSession(appContext, target)
            try {
                session.connectAndVerifyProfile()
                FootVerificationResult.Compatible
            } catch (_: IncompatibleFootException) {
                FootVerificationResult.Incompatible
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FootVerificationResult.Failed(
                    "Couldn't verify ${target.name}. Make sure it is nearby and try again."
                )
            } finally {
                session.disconnectAndClose(removeBond = false)
                PairingTargetPolicy.endVerification(target.address)
            }
        }
        return when (coordinated) {
            is CoordinatedResult.Completed -> coordinated.value
            CoordinatedResult.Busy -> FootVerificationResult.Busy
        }
    }
}
