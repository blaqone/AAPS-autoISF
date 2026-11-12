package app.aaps.plugins.aps.openAPSAutoISF

/**
 * Sensitivity ratio to use while the activity monitor reports activity or inactivity.
 *
 * - Activity: the smaller of the autosens ratio and the activity ratio. A ratio below 1 means less
 *   insulin, so detected movement can never give more insulin than autosens alone would.
 * - Inactivity (issue #2 on blaqone/AAPS-autoISF, option C): when autosens says "more sensitive" (below
 *   1), autosens wins, so inactivity can never give more insulin than autosens then. Otherwise the larger
 *   of the two, never above [autosensMax]. AutoISF 3.2.1 used the activity ratio alone, which could turn
 *   autosens 0.7 into 1.2. With autosens switched off the ratio is 1.0, so inactivity works as before.
 *
 * Used by the plugin (iobTH and autoISF) and by `DetermineBasalAutoISF`, so all three agree.
 */
internal fun activitySensitivityRatio(autosensRatio: Double, activityRatio: Double, activityDetected: Boolean, autosensMax: Double): Double =
    when {
        activityDetected  -> minOf(autosensRatio, activityRatio)
        autosensRatio < 1 -> autosensRatio
        else              -> minOf(maxOf(autosensRatio, activityRatio), autosensMax)
    }

/**
 * What the activity monitor found in one loop run. Handed directly from the
 * plugin to `autoISF` and `determine_basal`, so a value of an earlier run can never be used. The default is
 * "nothing detected".
 */
data class ActivityState(val ratio: Double = 1.0, val activityDetected: Boolean = false, val inactivityDetected: Boolean = false)
