package app.aaps.plugins.source

import kotlin.math.max

/**
 * AutoISF fork: a fixed correction of incoming CGM values, `value × slope + offset` (offset in mg/dL).
 *
 * ONLY slope and offset. No smoothing and no calibration window: smoothing stays with the chosen
 * smoothing plugin (UKF). Meant for a sensor that reads a fixed amount too high or too low, for example
 * a Libre 3 that is 30 mg/dL off.
 *
 * Slope 1.0 and offset 0 leave the value exactly as it is. A value that is not above zero (no value) is
 * never changed, and a corrected value is never below 40 mg/dL, as in AutoISF 3.2.1.
 */
object BgSlopeOffset {

    const val SLOPE_MIN = 0.5
    const val SLOPE_MAX = 1.5
    const val OFFSET_MIN_MGDL = -50.0
    const val OFFSET_MAX_MGDL = 50.0
    const val LOWEST_RESULT_MGDL = 40.0

    fun isActive(slope: Double, offset: Double): Boolean = slope != 1.0 || offset != 0.0

    fun apply(valueMgdl: Double, slope: Double, offset: Double): Double {
        if (valueMgdl <= 0.0 || !isActive(slope, offset)) return valueMgdl
        val safeSlope = slope.coerceIn(SLOPE_MIN, SLOPE_MAX)
        val safeOffset = offset.coerceIn(OFFSET_MIN_MGDL, OFFSET_MAX_MGDL)
        return max(LOWEST_RESULT_MGDL, valueMgdl * safeSlope + safeOffset)
    }

    /**
     * How far back stored values are corrected again when slope or offset was changed. Without this a
     * change gives a jump between the old and the new values, and that jump looks like a fast rise or
     * fall to AutoISF, the parabola and UKF.
     */
    const val RECORRECT_WINDOW_MS = 45 * 60 * 1000L

    /**
     * The value as it was before [apply], or null when it cannot be told: a result of
     * [LOWEST_RESULT_MGDL] or lower may have been raised to that limit.
     */
    fun revert(correctedMgdl: Double, slope: Double, offset: Double): Double? {
        if (correctedMgdl <= 0.0 || !isActive(slope, offset)) return correctedMgdl
        if (correctedMgdl <= LOWEST_RESULT_MGDL) return null
        val safeSlope = slope.coerceIn(SLOPE_MIN, SLOPE_MAX)
        val safeOffset = offset.coerceIn(OFFSET_MIN_MGDL, OFFSET_MAX_MGDL)
        return (correctedMgdl - safeOffset) / safeSlope
    }

    /**
     * A stored value, corrected with the old slope and offset, corrected again with the new ones.
     * Null when the value before the old correction cannot be told, see [revert].
     */
    fun recorrect(storedMgdl: Double, oldSlope: Double, oldOffset: Double, newSlope: Double, newOffset: Double): Double? =
        revert(storedMgdl, oldSlope, oldOffset)?.let { apply(it, newSlope, newOffset) }
}
