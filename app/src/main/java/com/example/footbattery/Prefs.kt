package com.example.footbattery

import android.content.Context

/** All persisted settings live here, typed, to avoid stringly-typed mistakes. */
object Prefs {
    private const val FILE = "foot"
    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun threshold(ctx: Context): Int = p(ctx).getInt("threshold", BatteryService.LOW_BATTERY_THRESHOLD)
    fun setThreshold(ctx: Context, v: Int) = p(ctx).edit().putInt("threshold", v).apply()

    fun polling(ctx: Context): Boolean = p(ctx).getBoolean("polling", false)
    fun setPolling(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("polling", v).apply()

    fun intervalMin(ctx: Context): Int = p(ctx).getInt("interval_min", 60)
    fun setIntervalMin(ctx: Context, v: Int) = p(ctx).edit().putInt("interval_min", v).apply()

    fun monitoring(ctx: Context): Boolean = p(ctx).getBoolean("monitoring", false)
    fun setMonitoring(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("monitoring", v).apply()

    // "armed" = ready to fire a low alert. Re-arms once charged back above threshold.
    fun armed(ctx: Context): Boolean = p(ctx).getBoolean("armed", true)
    fun setArmed(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("armed", v).apply()

    // Pairing PIN the app auto-submits when the foot asks to bond. Empty = none saved.
    fun pairingCode(ctx: Context): String = p(ctx).getString("pairing_code", "") ?: ""
    fun setPairingCode(ctx: Context, v: String) = p(ctx).edit().putString("pairing_code", v.trim()).apply()

    // Epoch millis of the most recent battery reading (any source). 0 = never.
    fun lastChecked(ctx: Context): Long = p(ctx).getLong("last_checked", 0L)
    fun setLastChecked(ctx: Context, v: Long) = p(ctx).edit().putLong("last_checked", v).apply()
}
