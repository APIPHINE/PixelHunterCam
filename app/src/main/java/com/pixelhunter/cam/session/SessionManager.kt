package com.pixelhunter.cam.session

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Manages the active camera session.
 * - Locks settings after first shot (or manually)
 * - Tracks drift vs baseline
 * - Emits flags when drift exceeds thresholds
 *
 * Thread safety: all _flags mutations use StateFlow.update() for atomic
 * read-modify-write. This prevents lost updates if checkDrift() and
 * clearFlags() are called concurrently from different coroutines.
 */
class SessionManager {

    companion object {
        private const val TAG = "SessionManager"

        // Tunable thresholds
        const val LUMINANCE_DRIFT_THRESHOLD = 0.15f   // 15% change
        const val COLOR_TEMP_DRIFT_THRESHOLD = 500f    // 500K change
        const val BLUR_GLOBAL_THRESHOLD = 80.0
        const val BLUR_LOCAL_THRESHOLD = 60.0
        const val BLUR_TILE_FAIL_RATIO = 0.4f
    }

    private val _settings = MutableStateFlow(SessionSettings.unlocked())
    val settings: StateFlow<SessionSettings> = _settings

    private val _flags = MutableStateFlow<List<SessionFlag>>(emptyList())
    val flags: StateFlow<List<SessionFlag>> = _flags

    // ─── Lock / Unlock ─────────────────────────────────────────────

    fun lockSession(
        iso: Int,
        shutterSpeedNs: Long,
        whiteBalanceKelvin: Int,
        exposureCompensation: Int,
        baselineLuminance: Float,
        baselineColorTemp: Float,
        locationLabel: String = ""
    ) {
        _settings.value = SessionSettings(
            iso = iso,
            shutterSpeedNs = shutterSpeedNs,
            whiteBalanceKelvin = whiteBalanceKelvin,
            exposureCompensation = exposureCompensation,
            isLocked = true,
            lockedAtTimestamp = System.currentTimeMillis(),
            baselineLuminance = baselineLuminance,
            baselineColorTemp = baselineColorTemp,
            locationLabel = locationLabel
        )
        Log.d(TAG, "Session locked: ${_settings.value.toDisplayString()}")
    }

    fun unlockSession() {
        _settings.value = SessionSettings.unlocked()
        _flags.value = emptyList()
        Log.d(TAG, "Session unlocked")
    }

    fun updateLocationLabel(label: String) {
        _settings.update { it.copy(locationLabel = label) }
    }

    // ─── Flag Management ───────────────────────────────────────────
    // All mutations use StateFlow.update() for atomic read-modify-write.
    // This prevents the race where:
    //   Thread A reads _flags.value → Thread B clears → Thread A writes stale list back

    fun addFlag(flag: SessionFlag) {
        _flags.update { current ->
            current.filter { it.type != flag.type } + flag
        }
        Log.w(TAG, "Flag added: ${flag.type} [${flag.severity}] ${flag.message}")
    }

    fun clearFlags() {
        _flags.value = emptyList()
    }

    fun clearFlag(type: FlagType) {
        _flags.update { current -> current.filter { it.type != type } }
    }

    // ─── Drift Check ───────────────────────────────────────────────
    // Called after each frame analysis. Computes exposure and WB drift
    // vs. the session baseline and atomically updates flags.
    // Using a single update {} block means exposure + WB flags are
    // evaluated and applied together — no interleaved partial state.

    fun checkDrift(currentLuminance: Float, currentColorTemp: Float) {
        val s = _settings.value
        if (!s.isLocked || s.baselineLuminance < 0) return

        val luminanceDrift = Math.abs(currentLuminance - s.baselineLuminance) / s.baselineLuminance
        val colorTempDrift = Math.abs(currentColorTemp - s.baselineColorTemp)

        _flags.update { current ->
            val updated = current
                .filter { it.type != FlagType.EXPOSURE_DRIFT && it.type != FlagType.WHITE_BALANCE_DRIFT }
                .toMutableList()

            if (luminanceDrift > LUMINANCE_DRIFT_THRESHOLD) {
                updated += SessionFlag(
                    type = FlagType.EXPOSURE_DRIFT,
                    severity = if (luminanceDrift > 0.3f) Severity.HIGH else Severity.MEDIUM,
                    message = "Exposure shifted ${(luminanceDrift * 100).toInt()}% from baseline",
                    suggestApiReview = luminanceDrift > 0.3f
                )
            }

            if (colorTempDrift > COLOR_TEMP_DRIFT_THRESHOLD) {
                updated += SessionFlag(
                    type = FlagType.WHITE_BALANCE_DRIFT,
                    severity = Severity.MEDIUM,
                    message = "White balance drifted ${colorTempDrift.toInt()}K from baseline",
                    suggestApiReview = colorTempDrift > 1000f
                )
            }

            updated
        }
    }
}

// ─── Models ───────────────────────────────────────────────────────

data class SessionFlag(
    val type: FlagType,
    val severity: Severity,
    val message: String,
    val suggestApiReview: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

enum class FlagType {
    BLUR_GLOBAL,
    BLUR_LOCAL,
    EXPOSURE_DRIFT,
    WHITE_BALANCE_DRIFT
}

enum class Severity { LOW, MEDIUM, HIGH }
