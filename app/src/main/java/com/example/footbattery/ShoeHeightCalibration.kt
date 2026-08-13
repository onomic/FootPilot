package com.example.footbattery

data class ShoeHeightChange(val thousandthsOfInch: Int) {
    init {
        require(thousandthsOfInch != 0)
    }

    val label: String
        get() = when (thousandthsOfInch) {
            250 -> "+0.25 in"
            500 -> "+0.5 in"
            1_000 -> "+1.0 in"
            else -> "${if (thousandthsOfInch > 0) "+" else ""}${thousandthsOfInch / 1000.0} in"
        }

    companion object {
        val APPROVED_V1 = listOf(
            ShoeHeightChange(250),
            ShoeHeightChange(500),
            ShoeHeightChange(1_000)
        )
    }
}
interface ShoeHeightCalibration {
    val configured: Boolean

    /** Returns an absolute validated millidegree target, or null when unavailable/unsafe. */
    fun targetFor(currentConfirmedMd: Int, change: ShoeHeightChange): Int?
}

/** Calibration measurements were not supplied; this boundary deliberately emits no target. */
object UnconfiguredShoeHeightCalibration : ShoeHeightCalibration {
    override val configured: Boolean = false
    override fun targetFor(currentConfirmedMd: Int, change: ShoeHeightChange): Int? = null
}

fun quickAdjustVisible(calibration: ShoeHeightCalibration): Boolean = calibration.configured
