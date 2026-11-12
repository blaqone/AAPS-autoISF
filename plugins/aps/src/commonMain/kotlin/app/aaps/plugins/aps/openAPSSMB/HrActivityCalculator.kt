package app.aaps.plugins.aps.openAPSSMB

import app.aaps.core.data.model.HR
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag

import kotlin.math.roundToInt

/**
 * Classifies exercise type by combining Karvonen Heart Rate Reserve (HRR%) zones
 * with step count signals from [StepService].
 *
 * ## Zone definitions (Karvonen HRR%)
 * HRR% = (currentHR - HRrest) / (HRmax - HRrest) * 100
 *
 *   Zone 1 — Very Light :  < 30% HRR  (recovery, rest)
 *   Zone 2 — Light       : 30–40% HRR  (easy aerobic)
 *   Zone 3 — Moderate    : 40–60% HRR  (aerobic conditioning)
 *   Zone 4 — Hard        : 60–80% HRR  (vigorous aerobic / strength)
 *   Zone 5 — Maximum     :  > 80% HRR  (near-maximal effort)
 *
 * ## Combined exercise states
 *
 *   VIGOROUS_AEROBIC  — High steps + Zone 3–5: immediate insulin sensitivity increase, hypo risk.
 *                       Response: reduce profile %, raise target BG.
 *   MODERATE_AEROBIC  — Moderate steps + Zone 2–3: moderate sensitivity increase.
 *   LIGHT_AEROBIC     — Steps elevated + Zone 1–2: mild sensitivity change.
 *   RESISTANCE        — Low/no steps + Zone 3–4: acute BG rise short-term, delayed hypo risk.
 *                       Response: raise target BG, do NOT reduce profile % yet.
 *   STRESS            — Low steps + Zone 2–3, no exercise context (opt-in): cortisol/adrenaline
 *                       raise BG → insulin resistance. Response: raise target BG.
 *   RESTING           — Normal HR (Zone 1) + low steps.
 *   INACTIVE          — Very low steps + normal HR.
 *
 * Ported from Boost 6.3.2 (openAPSBoost/HrActivityCalculator). Stateless and functional —
 * no side effects, no stored state; safe to call from any thread.
 */
object HrActivityCalculator {

    /** Karvonen HR zone classification (1–5, or 0/NONE if HR data unavailable). */
    enum class HrZone(val label: String) {
        NONE("none"),
        ZONE_1_VERY_LIGHT("zone1"),
        ZONE_2_LIGHT("zone2"),
        ZONE_3_MODERATE("zone3"),
        ZONE_4_HARD("zone4"),
        ZONE_5_MAX("zone5"),
    }

    /**
     * Confidence in the classification.
     * HIGH   — both HR and step signals agree
     * MEDIUM — only one signal available or signals are consistent but not confirmatory
     * LOW    — signals contradict each other or HR data is missing
     */
    enum class Confidence { HIGH, MEDIUM, LOW }

    /** Combined exercise state from HR + step fusion. */
    enum class ExerciseState {
        VIGOROUS_AEROBIC,
        MODERATE_AEROBIC,
        LIGHT_AEROBIC,
        RESISTANCE,
        STRESS,
        RESTING,
        INACTIVE,
    }

    data class HrClassificationResult(
        val exerciseState: ExerciseState,
        val hrZone: HrZone,
        val averageHrBpm: Double?,
        val hrrPercent: Double?,
        val confidence: Confidence,
        val debugInfo: String
    )

    // Step thresholds for HR-fusion classification (deliberately modest; the activity
    // monitor's own prefs govern the step-only path).
    private const val STEPS_15MIN_HIGH_THRESHOLD = 300
    private const val STEPS_15MIN_MODERATE_THRESHOLD = 100
    private const val STEPS_15MIN_LOW_THRESHOLD = 30

    private const val FROZEN_MIN_READINGS = 4

    private fun format1(value: Double): String = "${(value * 10).roundToInt() / 10.0}"

    /**
     * True when the HR window holds enough readings that are ALL bit-identical — the signature of a
     * stuck sensor value, NOT a heartbeat. Used to reject a phantom elevated HR that would otherwise
     * mis-fire RESISTANCE/STRESS every cycle.
     */
    fun isHrWindowFrozen(readings: List<HR>, nowMillis: Long, windowMinutes: Int): Boolean {
        val windowMs = windowMinutes * 60_000L
        val cutoff = nowMillis - windowMs
        val inWindow = readings.filter { it.isValid && it.timestamp > cutoff && it.timestamp <= nowMillis }
        if (inWindow.size < FROZEN_MIN_READINGS) return false
        return inWindow.minOf { it.beatsPerMinute } == inWindow.maxOf { it.beatsPerMinute }
    }

    /**
     * Average heart rate over [windowMinutes] from the provided readings, weighted by each
     * record's duration; null if no valid readings exist in the window.
     */
    fun averageHrInWindow(readings: List<HR>, nowMillis: Long, windowMinutes: Int): Double? {
        val windowMs = windowMinutes * 60_000L
        val cutoff = nowMillis - windowMs
        val inWindow = readings.filter { it.isValid && it.timestamp > cutoff && it.timestamp <= nowMillis }
        if (inWindow.isEmpty()) return null
        val totalDuration = inWindow.sumOf { it.duration.toDouble() }
        if (totalDuration <= 0.0) return null
        val weightedSum = inWindow.sumOf { it.beatsPerMinute * it.duration }
        return weightedSum / totalDuration
    }

    /**
     * Classifies a heart rate reading into a Karvonen zone.
     */
    fun classifyZone(bpm: Double, hrMax: Int, hrResting: Int): HrZone {
        val reserve = (hrMax - hrResting).coerceAtLeast(1)
        val hrrPct = ((bpm - hrResting) / reserve) * 100.0
        return when {
            hrrPct < 30.0 -> HrZone.ZONE_1_VERY_LIGHT
            hrrPct < 40.0 -> HrZone.ZONE_2_LIGHT
            hrrPct < 60.0 -> HrZone.ZONE_3_MODERATE
            hrrPct < 80.0 -> HrZone.ZONE_4_HARD
            else -> HrZone.ZONE_5_MAX
        }
    }

    /**
     * Produces a [HrClassificationResult] by fusing the HR zone with step counts.
     */
    fun classify(
        hrReadings: List<HR>,
        nowMillis: Long,
        hrWindowMinutes: Int,
        hrMax: Int,
        hrResting: Int,
        stepsLast15Min: Int,
        stressDetection: Boolean,
        aapsLogger: AAPSLogger? = null,
    ): HrClassificationResult {
        val debug = StringBuilder()

        val avgBpm = averageHrInWindow(hrReadings, nowMillis, hrWindowMinutes)
        if (avgBpm == null) {
            debug.append("HR: no valid readings in ${hrWindowMinutes}m window — falling back to step-only")
            return HrClassificationResult(
                exerciseState = ExerciseState.RESTING,
                hrZone = HrZone.NONE,
                averageHrBpm = null,
                hrrPercent = null,
                confidence = Confidence.LOW,
                debugInfo = debug.toString()
            )
        }

        // Frozen/stuck-value guard: treat a run of bit-identical readings as unavailable. It must
        // not be read as rest: with HR integration on, the INACTIVE raise requires positive HR
        // evidence of rest and is withheld when the classification is unavailable
        // (see [inactivityRaiseBlockedByHr]).
        if (isHrWindowFrozen(hrReadings, nowMillis, hrWindowMinutes)) {
            debug.append("HR: frozen value ${format1(avgBpm)} bpm across ${hrWindowMinutes}m window (stuck sensor?) — treating as unavailable")
            aapsLogger?.debug(LTag.APS, "HrActivityCalculator: $debug")
            return HrClassificationResult(
                exerciseState = ExerciseState.RESTING,
                hrZone = HrZone.NONE,
                averageHrBpm = null,
                hrrPercent = null,
                confidence = Confidence.LOW,
                debugInfo = debug.toString()
            )
        }

        val hrrPct = ((avgBpm - hrResting) / (hrMax - hrResting).coerceAtLeast(1)) * 100.0
        val zone = classifyZone(avgBpm, hrMax, hrResting)

        debug.append("HR: avg=${format1(avgBpm)} bpm | HRR=${format1(hrrPct)}% | zone=${zone.label}")
        debug.append(" | steps15m=$stepsLast15Min")

        aapsLogger?.debug(LTag.APS, "HrActivityCalculator: $debug")

        val highSteps = stepsLast15Min >= STEPS_15MIN_HIGH_THRESHOLD
        val moderateSteps = stepsLast15Min >= STEPS_15MIN_MODERATE_THRESHOLD
        val lowSteps = stepsLast15Min < STEPS_15MIN_LOW_THRESHOLD

        val (state, confidence) = when {
            highSteps && zone >= HrZone.ZONE_3_MODERATE ->
                ExerciseState.VIGOROUS_AEROBIC to Confidence.HIGH

            moderateSteps && zone >= HrZone.ZONE_2_LIGHT ->
                ExerciseState.MODERATE_AEROBIC to if (highSteps || zone >= HrZone.ZONE_3_MODERATE) Confidence.HIGH else Confidence.MEDIUM

            !lowSteps && zone <= HrZone.ZONE_2_LIGHT ->
                ExerciseState.LIGHT_AEROBIC to Confidence.MEDIUM

            // Widened from `lowSteps`: with the old threshold a cyclist/rower with 30–100
            // incidental steps fell through to RESTING and the INACTIVE branch then ADDED insulin.
            !moderateSteps && zone >= HrZone.ZONE_3_MODERATE && zone <= HrZone.ZONE_4_HARD ->
                ExerciseState.RESISTANCE to Confidence.MEDIUM

            stressDetection && lowSteps && zone >= HrZone.ZONE_2_LIGHT && zone <= HrZone.ZONE_3_MODERATE ->
                ExerciseState.STRESS to Confidence.LOW

            lowSteps && zone == HrZone.ZONE_1_VERY_LIGHT ->
                ExerciseState.INACTIVE to Confidence.HIGH

            else ->
                ExerciseState.RESTING to Confidence.MEDIUM
        }

        debug.append(" => $state ($confidence)")
        aapsLogger?.debug(LTag.APS, "HrActivityCalculator: classified as $state ($confidence)")

        return HrClassificationResult(
            exerciseState = state,
            hrZone = zone,
            averageHrBpm = avgBpm,
            hrrPercent = hrrPct,
            confidence = confidence,
            debugInfo = debug.toString()
        )
    }

    /**
     * SAFETY GUARD — the INACTIVE branch RAISES basal (adds insulin) on the assumption the user
     * is sedentary. An ELEVATED heart rate flatly contradicts that assumption: zone >= 3 is
     * exercise-range whatever the step count says. The inactivity insulin must never fire while
     * the heart says exercise.
     */
    fun inactivitySuppressedByElevatedHr(result: HrClassificationResult?): Boolean =
        result != null && result.hrZone >= HrZone.ZONE_3_MODERATE

    /**
     * Companion guard to [inactivitySuppressedByElevatedHr]: blocks the inactivity profile-raise
     * from zone 2 (HRR >= 30%) upward, and fails closed on a missing HR classification — a user
     * who has switched HR integration on has chosen HR as part of the evidence for "sedentary",
     * so a missing heart rate is unknown rather than resting.
     */
    fun inactivityRaiseBlockedByHr(result: HrClassificationResult?): Boolean =
        result == null || result.hrZone == HrZone.NONE || result.hrZone >= HrZone.ZONE_2_LIGHT
}
