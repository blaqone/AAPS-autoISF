package app.aaps.plugins.aps.openAPSSMB

import kotlin.concurrent.Volatile

object ActivityClassifier {

    // Values match DetectedActivity constants from com.google.android.gms.location
    const val PHONE_BICYCLE = 1
    const val PHONE_STILL   = 3
    const val PHONE_WALKING = 7
    const val PHONE_RUNNING = 8

    @Volatile var phoneActivity: Int = PHONE_STILL

    /** AutoISF fork: values of `IntKey.ActivityClassifierSensitivity`. */
    const val SENSITIVITY_HIGH = 0
    const val SENSITIVITY_MEDIUM = 1
    const val SENSITIVITY_LOW = 2

    /**
     * AutoISF fork: the step limits for a sensitivity, in steps in the last 15 minutes.
     * [aerobicSteps15m]: above it the steps alone count as endurance activity.
     * [resistanceMaxSteps15m]: below it an elevated heart rate counts as strength training.
     */
    data class Limits(val aerobicSteps15m: Int, val resistanceMaxSteps15m: Int)

    /**
     * - high: 450 / 90 (30 steps per minute), what the classifier did in effect before the fix
     * - medium (default): 600 / 45 (40 steps per minute, brisk walking)
     * - low: 750 / 30 (50 steps per minute, running or very brisk walking)
     */
    fun limits(sensitivity: Int): Limits = when (sensitivity) {
        SENSITIVITY_HIGH -> Limits(aerobicSteps15m = 450, resistanceMaxSteps15m = 90)
        SENSITIVITY_LOW  -> Limits(aerobicSteps15m = 750, resistanceMaxSteps15m = 30)
        else             -> Limits(aerobicSteps15m = 600, resistanceMaxSteps15m = 45)
    }

    /**
     * [steps15m] is the SUM of the steps in the last 15 minutes. Before, the AutoISF plugin gave only one 5 minute
     * block from 10 to 15 minutes ago: rule 3 then needed 150 steps in 5 minutes (30 per minute) and reacted
     * 10 minutes late, and rule 4 fired on a single quiet block. The limits come from [limits], chosen by the
     * user; the default is a little less sensitive than before.
     */
    fun classify(
        steps15m: Int,
        avgHrLast15m: Double,
        phoneActivity: Int = this.phoneActivity,
        restingHr: Int = 60,
        limits: Limits = limits(SENSITIVITY_MEDIUM)
    ): ActivityClass {
        // Rule 1: cycling — few steps but OS knows for sure
        if (phoneActivity == PHONE_BICYCLE) return ActivityClass.AEROBIC
        // Rule 2: explicit OS signal
        if (phoneActivity == PHONE_WALKING || phoneActivity == PHONE_RUNNING) return ActivityClass.AEROBIC
        // Rule 3: brisk walking or more, from the steps alone
        if (steps15m > limits.aerobicSteps15m) return ActivityClass.AEROBIC
        // Rule 4: almost no steps + elevated HR = gym/strength training
        if (steps15m < limits.resistanceMaxSteps15m && avgHrLast15m > 0.0 && avgHrLast15m > restingHr * 1.25) return ActivityClass.RESISTANCE
        return ActivityClass.REST
    }
}
