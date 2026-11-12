package app.aaps.plugins.aps.openAPSSMB

import app.aaps.core.data.model.HR
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ActivityFusionTest {

    private val now = 1_700_000_000_000L

    private fun hr(bpm: Double, minutesAgo: Long = 0) = HR(
        duration = 60_000,
        timestamp = now - minutesAgo * 60_000,
        beatsPerMinute = bpm,
        device = "test"
    )

    private fun classify(
        readings: List<HR>,
        steps15m: Int,
        phoneActivity: Int = ActivityClassifier.PHONE_STILL,
        stressDetection: Boolean = false
    ) = ActivityFusion.classifyActivity(
        hrReadings = readings,
        nowMillis = now,
        hrWindowMinutes = 15,
        hrMax = 180,
        hrResting = 60,
        steps15m = steps15m,
        phoneActivity = phoneActivity,
        stressDetection = stressDetection,
        aapsLogger = null
    )

    @Test fun `os signals win over any hr data`() {
        val garbage = listOf(hr(130.0), hr(140.0), hr(135.0), hr(138.0))
        assertThat(classify(garbage, steps15m = 0, phoneActivity = ActivityClassifier.PHONE_BICYCLE)).isEqualTo(ActivityClass.AEROBIC)
        assertThat(classify(garbage, steps15m = 0, phoneActivity = ActivityClassifier.PHONE_WALKING)).isEqualTo(ActivityClass.AEROBIC)
        assertThat(classify(garbage, steps15m = 0, phoneActivity = ActivityClassifier.PHONE_RUNNING)).isEqualTo(ActivityClass.AEROBIC)
    }

    @Test fun `no hr readings falls back to legacy step rules`() {
        assertThat(classify(emptyList(), steps15m = 200)).isEqualTo(ActivityClass.AEROBIC)
        assertThat(classify(emptyList(), steps15m = 10)).isEqualTo(ActivityClass.REST)
    }

    @Test fun `frozen hr window falls back to legacy step rules`() {
        val frozen = listOf(hr(124.0), hr(124.0), hr(124.0), hr(124.0))
        assertThat(classify(frozen, steps15m = 200)).isEqualTo(ActivityClass.AEROBIC)
        assertThat(classify(frozen, steps15m = 10)).isEqualTo(ActivityClass.REST)
    }

    @Test fun `zone two hr with almost no steps is stress when opted in`() {
        val readings = listOf(hr(100.0, minutesAgo = 1))
        assertThat(classify(readings, steps15m = 5, stressDetection = true)).isEqualTo(ActivityClass.STRESS)
    }

    @Test fun `zone three hr with almost no steps is resistance even when stress detection on`() {
        val readings = listOf(hr(120.0, minutesAgo = 1))
        assertThat(classify(readings, steps15m = 5, stressDetection = true)).isEqualTo(ActivityClass.RESISTANCE)
    }

    @Test fun `elevated hr with almost no steps is resistance when stress detection off`() {
        val readings = listOf(hr(120.0, minutesAgo = 1))
        assertThat(classify(readings, steps15m = 5, stressDetection = false)).isEqualTo(ActivityClass.RESISTANCE)
    }

    @Test fun `classifyWithHr carries the hr classification for guards`() {
        val readings = listOf(hr(110.0, minutesAgo = 1))
        val result = ActivityFusion.classifyWithHr(
            hrReadings = readings,
            nowMillis = now,
            hrWindowMinutes = 15,
            hrMax = 180,
            hrResting = 60,
            steps15m = 400,
            phoneActivity = ActivityClassifier.PHONE_STILL,
            stressDetection = false,
            aapsLogger = null
        )
        assertThat(result.activityClass).isEqualTo(ActivityClass.VIGOROUS_AEROBIC)
        assertThat(result.hrResult).isNotNull()
        assertThat(result.hrResult!!.hrZone).isEqualTo(HrActivityCalculator.HrZone.ZONE_3_MODERATE)
    }

    @Test fun `classifyWithHr has no hr result on the legacy path`() {
        val result = ActivityFusion.classifyWithHr(
            hrReadings = emptyList(),
            nowMillis = now,
            hrWindowMinutes = 15,
            hrMax = 180,
            hrResting = 60,
            steps15m = 10,
            phoneActivity = ActivityClassifier.PHONE_STILL,
            stressDetection = false,
            aapsLogger = null
        )
        assertThat(result.activityClass).isEqualTo(ActivityClass.REST)
        assertThat(result.hrResult).isNull()
    }

    @Test fun `classifyActivity delegates to classifyWithHr`() {
        val readings = listOf(hr(110.0, minutesAgo = 1))
        assertThat(
            classify(readings, steps15m = 400)
        ).isEqualTo(
            ActivityFusion.classifyWithHr(
                hrReadings = readings,
                nowMillis = now,
                hrWindowMinutes = 15,
                hrMax = 180,
                hrResting = 60,
                steps15m = 400,
                phoneActivity = ActivityClassifier.PHONE_STILL,
                stressDetection = false,
                aapsLogger = null
            ).activityClass
        )
    }

    @Test fun `inactivity raise is allowed for a legacy rest with no hr result`() {
        val legacyRest = ActivityFusion.FusionResult(ActivityClass.REST, hrResult = null)
        assertThat(ActivityFusion.inactivityRaiseAllowed(legacyRest)).isTrue()
    }

    @Test fun `inactivity raise is blocked when hr fusion read an elevated zone`() {
        val readings = listOf(hr(100.0, minutesAgo = 1))
        val zone2Rest = ActivityFusion.classifyWithHr(
            hrReadings = readings, nowMillis = now, hrWindowMinutes = 15, hrMax = 180, hrResting = 60,
            steps15m = 10, phoneActivity = ActivityClassifier.PHONE_STILL, stressDetection = false, aapsLogger = null
        )
        assertThat(zone2Rest.activityClass).isEqualTo(ActivityClass.REST)
        assertThat(zone2Rest.hrResult!!.hrZone).isEqualTo(HrActivityCalculator.HrZone.ZONE_2_LIGHT)
        assertThat(ActivityFusion.inactivityRaiseAllowed(zone2Rest)).isFalse()
    }

    @Test fun `inactivity raise is allowed for a genuine zone one inactive`() {
        val zone1Inactive = ActivityFusion.classifyWithHr(
            hrReadings = listOf(hr(65.0, minutesAgo = 1)), nowMillis = now, hrWindowMinutes = 15, hrMax = 180, hrResting = 60,
            steps15m = 10, phoneActivity = ActivityClassifier.PHONE_STILL, stressDetection = false, aapsLogger = null
        )
        assertThat(zone1Inactive.activityClass).isEqualTo(ActivityClass.INACTIVE)
        assertThat(ActivityFusion.inactivityRaiseAllowed(zone1Inactive)).isTrue()
    }

    @Test fun `nan hr average falls back to legacy rules instead of a phantom zone`() {
        val nanReadings = listOf(hr(Double.NaN), hr(Double.NaN), hr(Double.NaN), hr(Double.NaN))
        assertThat(classify(nanReadings, steps15m = 400)).isEqualTo(ActivityClass.AEROBIC)
        assertThat(classify(nanReadings, steps15m = 10)).isEqualTo(ActivityClass.REST)
    }

    @Test fun `brisk walking hr fuses into graded aerobic`() {
        val readings = listOf(hr(110.0, minutesAgo = 1))
        assertThat(classify(readings, steps15m = 400)).isEqualTo(ActivityClass.VIGOROUS_AEROBIC)
    }
}
