package app.aaps.plugins.aps.openAPSSMB

import app.aaps.core.data.model.HR
import app.aaps.core.interfaces.logging.AAPSLogger

/**
 * Chooses between the HR-fused [HrActivityCalculator] classification and the legacy rule-based
 * [ActivityClassifier], in a fixed decision order:
 *
 *  1. An explicit OS activity signal (bicycle / walking / running) wins outright — the platform
 *     knows for sure, return the legacy AEROBIC mapping.
 *  2. HR unavailable (no valid readings in the window, or a frozen/stuck sensor value) → the
 *     legacy rule classifier on steps only. A missing heart rate is UNKNOWN, never evidence of
 *     rest: the legacy path has no INACTIVE output, so no HR-driven inactivity claim can ride
 *     on absent data.
 *  3. Otherwise the fused Karvonen-zone classification maps onto the graded [ActivityClass].
 */
object ActivityFusion {

    data class FusionResult(
        val activityClass: ActivityClass,
        /** Populated when the classification came from HR fusion; null on the legacy path. */
        val hrResult: HrActivityCalculator.HrClassificationResult?
    )

    /**
     * Whether the insulin-adding inactivity raise may fire for this classification. The raise is
     * allowed on the legacy path (no HR evidence was claimed) and for a genuine zone-1 INACTIVE;
     * it is blocked whenever HR fusion produced a result whose zone says otherwise (zone >= 2,
     * or an unavailable classification). Keys on the HR zone directly, so it holds even if the
     * fused exerciseState is wrong — the same invariant Boost applies.
     */
    fun inactivityRaiseAllowed(result: FusionResult): Boolean =
        !(result.hrResult != null && HrActivityCalculator.inactivityRaiseBlockedByHr(result.hrResult))

    fun classifyActivity(
        hrReadings: List<HR>,
        nowMillis: Long,
        hrWindowMinutes: Int,
        hrMax: Int,
        hrResting: Int,
        steps15m: Int,
        phoneActivity: Int,
        stressDetection: Boolean,
        aapsLogger: AAPSLogger?
    ): ActivityClass = classifyWithHr(
        hrReadings, nowMillis, hrWindowMinutes, hrMax, hrResting, steps15m, phoneActivity, stressDetection, aapsLogger
    ).activityClass

    fun classifyWithHr(
        hrReadings: List<HR>,
        nowMillis: Long,
        hrWindowMinutes: Int,
        hrMax: Int,
        hrResting: Int,
        steps15m: Int,
        phoneActivity: Int,
        stressDetection: Boolean,
        aapsLogger: AAPSLogger?
    ): FusionResult {
        if (phoneActivity == ActivityClassifier.PHONE_BICYCLE ||
            phoneActivity == ActivityClassifier.PHONE_WALKING ||
            phoneActivity == ActivityClassifier.PHONE_RUNNING
        ) {
            return FusionResult(ActivityClass.AEROBIC, null)
        }

        val avgHr = HrActivityCalculator.averageHrInWindow(hrReadings, nowMillis, hrWindowMinutes)
        // Non-finite average is garbage data, not "no HR" and not "elevated HR": fall back to the
        // legacy step rules rather than letting NaN classify as an extreme zone.
        val hrUsable = avgHr != null && avgHr.isFinite() &&
            !HrActivityCalculator.isHrWindowFrozen(hrReadings, nowMillis, hrWindowMinutes)
        if (!hrUsable) {
            return FusionResult(
                ActivityClassifier.classify(
                    steps15m = steps15m,
                    avgHrLast15m = 0.0,
                    phoneActivity = phoneActivity,
                    restingHr = hrResting
                ),
                null
            )
        }

        val result = HrActivityCalculator.classify(
            hrReadings = hrReadings,
            nowMillis = nowMillis,
            hrWindowMinutes = hrWindowMinutes,
            hrMax = hrMax,
            hrResting = hrResting,
            stepsLast15Min = steps15m,
            stressDetection = stressDetection,
            aapsLogger = aapsLogger
        )
        return FusionResult(ActivityClass.fromHrState(result.exerciseState, stressDetection), result)
    }
}
