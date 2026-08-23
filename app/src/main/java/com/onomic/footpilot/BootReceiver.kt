package com.onomic.footpilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Live monitoring runs in-process and ends when the app closes, so there's nothing to
 * resume at boot. Background polling is restored automatically by WorkManager, so this
 * receiver intentionally does nothing.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // no-op
    }
}
