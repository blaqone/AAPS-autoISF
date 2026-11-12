package app.aaps.plugins.aps.openAPSSMB

/**
 * Result of [ActivityClassifier] / [HrActivityCalculator].
 *
 * The sensitivity change for the aerobic states comes from the activity monitor ratio (capped by
 * autosens), not from this class. Here are only the optional target offset and the factor that
 * makes SMBs smaller.
 *
 * Value rationale for the graded states (ported from Boost 6.3.2):
 *  - LIGHT/MODERATE/VIGOROUS_AEROBIC get graded SMB caps (0.9 / 0.8 / 0.7) with target offsets
 *    10 mg/dL (light, moderate) and 15 mg/dL (vigorous) — stronger effort means a bigger target
 *    lift and smaller SMBs.
 *  - STRESS raises the target only: acute stress raises insulin resistance, so the SMB cap stays
 *    at 1.0 — this class must never reduce SMB for stress.
 *  - INACTIVE mirrors REST's numbers: the insulin-adding inactivity raise remains the activity
 *    monitor's job (InactivityScaleFactor), gated by HrActivityCalculator's inactivity guards.
 */
enum class ActivityClass(
    val targetBgOffsetMgdl: Double,
    val smbCapFactor: Double
) {
    AEROBIC        (targetBgOffsetMgdl = 10.0, smbCapFactor = 0.7),
    LIGHT_AEROBIC  (targetBgOffsetMgdl = 10.0, smbCapFactor = 0.9),
    MODERATE_AEROBIC(targetBgOffsetMgdl = 10.0, smbCapFactor = 0.8),
    VIGOROUS_AEROBIC(targetBgOffsetMgdl = 15.0, smbCapFactor = 0.7),
    RESISTANCE     (targetBgOffsetMgdl = 15.0, smbCapFactor = 0.8),
    STRESS         (targetBgOffsetMgdl = 15.0, smbCapFactor = 1.0),
    REST           (targetBgOffsetMgdl =  0.0, smbCapFactor = 1.0),
    INACTIVE       (targetBgOffsetMgdl =  0.0, smbCapFactor = 1.0);

    /** True for every aerobic state — these count as detected step activity. */
    val isAerobic: Boolean
        get() = this == AEROBIC || this == LIGHT_AEROBIC || this == MODERATE_AEROBIC || this == VIGOROUS_AEROBIC

    /** True for states where no movement was evidenced (REST, INACTIVE). */
    val isRestLike: Boolean
        get() = this == REST || this == INACTIVE

    companion object {

        /**
         * Maps an [HrActivityCalculator.ExerciseState] to an [ActivityClass]. STRESS maps to
         * [ActivityClass.STRESS] only when stress detection is opted in; otherwise it degrades to
         * [ActivityClass.REST] (fail-safe: no target offset, no SMB reduction).
         */
        fun fromHrState(state: HrActivityCalculator.ExerciseState, stressDetection: Boolean): ActivityClass =
            when (state) {
                HrActivityCalculator.ExerciseState.VIGOROUS_AEROBIC -> VIGOROUS_AEROBIC
                HrActivityCalculator.ExerciseState.MODERATE_AEROBIC -> MODERATE_AEROBIC
                HrActivityCalculator.ExerciseState.LIGHT_AEROBIC -> LIGHT_AEROBIC
                HrActivityCalculator.ExerciseState.RESISTANCE -> RESISTANCE
                HrActivityCalculator.ExerciseState.STRESS -> if (stressDetection) STRESS else REST
                HrActivityCalculator.ExerciseState.RESTING -> REST
                HrActivityCalculator.ExerciseState.INACTIVE -> INACTIVE
            }
    }
}
