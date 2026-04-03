package com.pixelhunter.cam.session

/**
 * Holds all camera settings for a locked session.
 * When session lock is active, these values override auto settings.
 */
data class SessionSettings(
    val iso: Int = 0,                        // 0 = auto
    val shutterSpeedNs: Long = 0L,           // nanoseconds, 0 = auto
    val whiteBalanceKelvin: Int = 0,         // 0 = auto
    val exposureCompensation: Int = 0,       // EV steps
    val focusDistanceDiopters: Float = 0f,   // 0 = auto
    val isLocked: Boolean = false,
    val lockedAtTimestamp: Long = 0L,
    val locationLabel: String = "",

    // Baseline values captured from first shot — used for drift detection
    val baselineLuminance: Float = -1f,      // -1 = not yet set
    val baselineColorTemp: Float = -1f
) {
    companion object {
        fun unlocked() = SessionSettings(isLocked = false)
    }

    fun toDisplayString(): String {
        if (!isLocked) return "AUTO"
        val isoStr = if (iso > 0) "ISO $iso" else "ISO auto"
        val ssStr = if (shutterSpeedNs > 0) "1/${(1_000_000_000L / shutterSpeedNs).toInt()}s" else "SS auto"
        val wbStr = if (whiteBalanceKelvin > 0) "${whiteBalanceKelvin}K" else "WB auto"
        return "$isoStr · $ssStr · $wbStr"
    }
}
