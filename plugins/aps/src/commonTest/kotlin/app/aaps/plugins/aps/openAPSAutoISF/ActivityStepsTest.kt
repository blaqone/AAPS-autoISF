package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.model.SC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ActivityStepsTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private fun sc(ago: Int, s5: Int, s10: Int = 0, s15: Int = 0, s30: Int = 0, s60: Int = 0, device: String = "Garmin") =
        SC(duration = 5 * minute, timestamp = now - ago * minute, steps5min = s5, steps10min = s10, steps15min = s15,
           steps30min = s30, steps60min = s60, steps180min = 0, device = device, utcOffset = 0)

    private val phone = MonitorSteps(1, 2, 3, 4, 5, fromWatch = false)

    @Test
    fun aWatchRecordWithWindowsIsUsedAsItIs() {
        val steps = watchSteps(listOf(sc(9, 50), sc(1, s5 = 320, s10 = 500, s15 = 700, s30 = 1200, s60 = 2600)), now)!!
        // 5/10/15 become single blocks like the phone's counter; 30/60 stay sums
        assertEquals(MonitorSteps(320, 180, 200, 1200, 2600, fromWatch = true), steps)
    }

    @Test
    fun dayTotalRecordsAreAddedUpPerWindow() {
        // a watch face that sends its day total: each record holds the steps since the last request
        val records = listOf(sc(2, 100), sc(4, 120), sc(8, 60), sc(13, 40), sc(25, 500), sc(50, 700), sc(70, 9999))
        val steps = watchSteps(records, now)!!
        assertEquals(220, steps.last5)        // 2 and 4 minutes ago
        assertEquals(60, steps.before5)       // 5..10 minutes ago
        assertEquals(40, steps.before10)      // 10..15 minutes ago
        assertEquals(820, steps.last30)
        assertEquals(1520, steps.last60)      // the record 70 minutes ago is outside
        assertTrue(steps.fromWatch)
    }

    @Test
    fun oldOrMissingWatchStepsGiveNothing() {
        assertNull(watchSteps(emptyList(), now))
        assertNull(watchSteps(listOf(sc(11, 300)), now))
    }

    @Test
    fun theStepsThePhoneStoredAreNotTakenForTheWatch() {
        assertNull(watchSteps(listOf(sc(1, 300, device = PHONE_STEPS_DEVICE)), now))
    }

    @Test
    fun theSourceDecidesWhichStepsAreUsed() {
        val watch = MonitorSteps(10, 20, 30, 40, 50, fromWatch = true)
        assertSame(phone, chooseSteps(STEP_SOURCE_PHONE, phone, watch))
        assertSame(watch, chooseSteps(STEP_SOURCE_WATCH, phone, watch))
        assertSame(watch, chooseSteps(STEP_SOURCE_AUTOMATIC, phone, watch))
        // automatic falls back to the phone, watch only gives nothing
        assertSame(phone, chooseSteps(STEP_SOURCE_AUTOMATIC, phone, null))
        assertNull(chooseSteps(STEP_SOURCE_WATCH, phone, null))
    }

    @Test
    fun nightByHoursStateOrBoth() {
        // night hours need the switch
        assertTrue(isNightForActivityMonitor(nightHoursOn = true, NIGHT_MODE_TIME, inNightHours = true, sleeping = false))
        assertFalse(isNightForActivityMonitor(nightHoursOn = false, NIGHT_MODE_TIME, inNightHours = true, sleeping = false))
        assertFalse(isNightForActivityMonitor(nightHoursOn = true, NIGHT_MODE_TIME, inNightHours = false, sleeping = true))
        // the state counts also with the switch off, as before
        assertTrue(isNightForActivityMonitor(nightHoursOn = false, NIGHT_MODE_STATE, inNightHours = false, sleeping = true))
        assertFalse(isNightForActivityMonitor(nightHoursOn = true, NIGHT_MODE_STATE, inNightHours = true, sleeping = false))
        // both: one of them is enough
        assertTrue(isNightForActivityMonitor(nightHoursOn = true, NIGHT_MODE_BOTH, inNightHours = true, sleeping = false))
        assertTrue(isNightForActivityMonitor(nightHoursOn = false, NIGHT_MODE_BOTH, inNightHours = false, sleeping = true))
        assertFalse(isNightForActivityMonitor(nightHoursOn = true, NIGHT_MODE_BOTH, inNightHours = false, sleeping = false))
    }

    @Test
    fun aWearWatchWithOneRecordPerWindowCountsOnce() {
        // a Wear OS watch writes six records with the same time, one per window; an older watch app leaves
        // the windows above the record's own duration empty
        fun wear(minutes: Int, s10: Int, s15: Int, s30: Int, s60: Int) = SC(
            duration = minutes * minute, timestamp = now - minute, steps5min = 320, steps10min = s10, steps15min = s15,
            steps30min = s30, steps60min = s60, steps180min = 0, device = "Pixel Watch", utcOffset = 0
        )
        val records = listOf(
            wear(5, 0, 0, 0, 0), wear(10, 500, 0, 0, 0), wear(15, 500, 700, 0, 0),
            wear(30, 500, 700, 1200, 0), wear(60, 500, 700, 1200, 2600), wear(180, 500, 700, 1200, 2600)
        )
        assertEquals(MonitorSteps(320, 180, 200, 1200, 2600, fromWatch = true), watchSteps(records, now))
        assertEquals(MonitorSteps(320, 180, 200, 1200, 2600, fromWatch = true), watchSteps(records.reversed(), now))
    }
}
