package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.model.SC
import kotlin.math.max

/** Device name of the steps the phone itself counted and stored, see the activity monitor. */
const val PHONE_STEPS_DEVICE = "Smartphone"

/** Values of `IntKey.ActivityMonitorStepSource`. */
const val STEP_SOURCE_PHONE = 0
const val STEP_SOURCE_WATCH = 1
const val STEP_SOURCE_AUTOMATIC = 2

/** Values of `IntKey.ActivityMonitorNightMode`. */
const val NIGHT_MODE_TIME = 0
const val NIGHT_MODE_STATE = 1
const val NIGHT_MODE_BOTH = 2

/** Steps of a watch older than this are not used. */
const val WATCH_STEPS_MAX_AGE_MS = 10 * 60_000L

/**
 * The steps the activity monitor works with, in the form of the phone's step counter (`StepService`):
 * [last5] the last 5 minutes, [before5] and [before10] the 5 minutes before each of them (single blocks,
 * not sums), [last30] and [last60] sums. [fromWatch] says where they came from.
 */
data class MonitorSteps(
    val last5: Int,
    val before5: Int,
    val before10: Int,
    val last30: Int,
    val last60: Int,
    val fromWatch: Boolean
) {

    /** All steps of the last 15 minutes. */
    val last15Sum: Int get() = last5 + before5 + before10
}

/**
 * AutoISF fork: the steps of a watch (Garmin, Wear OS) from the stored step counts, or null when there is
 * no record of a watch newer than [WATCH_STEPS_MAX_AGE_MS].
 *
 * A record that carries sums for 10 minutes or more is used as it is (the newest one). A Wear OS watch
 * writes one record per window with the same time; the longest one has all windows (an older watch app
 * leaves the shorter ones partly empty, AAPS #5499), so only that one is used and nothing counts twice.
 * A record with only [SC.steps5min] comes from a watch face that sends its day total: then it holds the
 * steps since the last request, and the windows are the sums of those records that ended inside them.
 */
internal fun watchSteps(records: List<SC>, now: Long): MonitorSteps? {
    val watch = records.filter { it.device != PHONE_STEPS_DEVICE && it.timestamp <= now }
    val newestTime = watch.maxOfOrNull { it.timestamp } ?: return null
    val newest = watch.filter { it.timestamp == newestTime }.maxBy { it.duration }
    if (now - newest.timestamp > WATCH_STEPS_MAX_AGE_MS) return null
    val hasWindows = newest.steps10min > 0 || newest.steps15min > 0 || newest.steps30min > 0 || newest.steps60min > 0 || newest.steps180min > 0
    val sums = if (hasWindows) {
        listOf(newest.steps5min, newest.steps10min, newest.steps15min, newest.steps30min, newest.steps60min)
    } else {
        fun sum(minutes: Int) = watch
            .filter { it.timestamp > now - minutes * 60_000L && it.steps10min == 0 && it.steps60min == 0 }
            .sumOf { it.steps5min }
        listOf(sum(5), sum(10), sum(15), sum(30), sum(60))
    }
    return MonitorSteps(
        last5 = sums[0],
        before5 = max(0, sums[1] - sums[0]),
        before10 = max(0, sums[2] - sums[1]),
        last30 = sums[3],
        last60 = sums[4],
        fromWatch = true
    )
}

/**
 * The steps to use for [source]: the phone's, the watch's, or the watch's when they are fresh and the
 * phone's otherwise. Null when only the watch may be used and it has nothing fresh: then the monitor has no
 * steps and changes nothing.
 */
internal fun chooseSteps(source: Int, phone: MonitorSteps, watch: MonitorSteps?): MonitorSteps? = when (source) {
    STEP_SOURCE_PHONE -> phone
    STEP_SOURCE_WATCH -> watch
    else              -> watch ?: phone
}

/**
 * AutoISF fork: whether it is night for the activity monitor, so inactivity is not used. [nightMode] chooses
 * the night hours, the state "Sleeping" or both, where one of them is enough.
 *
 * - The hours count only with the switch [nightHoursOn] ("Disable inactivity detection during sleep"), as before.
 * - The state counts whenever the mode allows it, also with the switch off, as before.
 *
 * Before this choice the hours were left out as soon as the state "Sleeping" existed. With "both", the
 * default, they count as well, so the monitor never uses inactivity in more cases than before.
 */
internal fun isNightForActivityMonitor(nightHoursOn: Boolean, nightMode: Int, inNightHours: Boolean, sleeping: Boolean): Boolean {
    val byHours = nightHoursOn && nightMode != NIGHT_MODE_STATE && inNightHours
    val byState = nightMode != NIGHT_MODE_TIME && sleeping
    return byHours || byState
}
