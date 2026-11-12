package app.aaps.plugins.aps.openAPSSMB

import app.aaps.core.data.model.HR
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HrActivityCalculatorTest {

    private val now = 1_700_000_000_000L

    private fun hr(bpm: Double, minutesAgo: Long = 0, durationMs: Long = 60_000, valid: Boolean = true) =
        HR(
            duration = durationMs,
            timestamp = now - minutesAgo * 60_000,
            beatsPerMinute = bpm,
            device = "test",
            isValid = valid
        )

    private fun fused(stepsLast15Min: Int, avgBpm: Double, stressDetection: Boolean = false) =
        HrActivityCalculator.classify(
            hrReadings = listOf(hr(avgBpm, minutesAgo = 1)),
            nowMillis = now,
            hrWindowMinutes = 15,
            hrMax = 180,
            hrResting = 60,
            stepsLast15Min = stepsLast15Min,
            stressDetection = stressDetection
        )

    @Test fun `zone boundaries match Karvonen thresholds`() {
        val cases = listOf(
            95.88 to HrActivityCalculator.HrZone.ZONE_1_VERY_LIGHT,
            96.0 to HrActivityCalculator.HrZone.ZONE_2_LIGHT,
            107.88 to HrActivityCalculator.HrZone.ZONE_2_LIGHT,
            108.0 to HrActivityCalculator.HrZone.ZONE_3_MODERATE,
            131.88 to HrActivityCalculator.HrZone.ZONE_3_MODERATE,
            132.0 to HrActivityCalculator.HrZone.ZONE_4_HARD,
            155.88 to HrActivityCalculator.HrZone.ZONE_4_HARD,
            156.0 to HrActivityCalculator.HrZone.ZONE_5_MAX
        )
        cases.forEach { (bpm, expected) ->
            assertThat(HrActivityCalculator.classifyZone(bpm, hrMax = 180, hrResting = 60)).isEqualTo(expected)
        }
    }

    @Test fun `averageHrInWindow is duration weighted`() {
        val readings = listOf(hr(60.0, durationMs = 120_000), hr(100.0, durationMs = 60_000))
        val result = HrActivityCalculator.averageHrInWindow(readings, now, windowMinutes = 15)!!
        assertThat(result).isWithin(0.01).of(73.33)
    }

    @Test fun `averageHrInWindow filters invalid and out of window readings`() {
        val readings = listOf(hr(60.0, durationMs = 120_000), hr(200.0, valid = false), hr(180.0, minutesAgo = 20))
        assertThat(HrActivityCalculator.averageHrInWindow(readings, now, windowMinutes = 15)).isEqualTo(60.0)
    }

    @Test fun `averageHrInWindow returns null on empty`() {
        assertThat(HrActivityCalculator.averageHrInWindow(emptyList(), now, windowMinutes = 15)).isNull()
    }

    @Test fun `frozen window of identical readings is detected`() {
        assertThat(HrActivityCalculator.isHrWindowFrozen(listOf(hr(124.0), hr(124.0), hr(124.0), hr(124.0)), now, 15)).isTrue()
    }

    @Test fun `frozen detection needs at least four readings`() {
        assertThat(HrActivityCalculator.isHrWindowFrozen(listOf(hr(124.0), hr(124.0), hr(124.0)), now, 15)).isFalse()
    }

    @Test fun `genuine varying window is not frozen`() {
        assertThat(HrActivityCalculator.isHrWindowFrozen(listOf(hr(120.0), hr(124.0), hr(122.0), hr(126.0)), now, 15)).isFalse()
    }

    @Test fun `high steps with zone three is vigorous aerobic`() {
        val result = fused(stepsLast15Min = 400, avgBpm = 110.0)
        assertThat(result.exerciseState).isEqualTo(HrActivityCalculator.ExerciseState.VIGOROUS_AEROBIC)
        assertThat(result.confidence).isEqualTo(HrActivityCalculator.Confidence.HIGH)
    }

    @Test fun `moderate steps with zone two is moderate aerobic`() {
        val result = fused(stepsLast15Min = 150, avgBpm = 100.0)
        assertThat(result.exerciseState).isEqualTo(HrActivityCalculator.ExerciseState.MODERATE_AEROBIC)
        assertThat(result.confidence).isEqualTo(HrActivityCalculator.Confidence.MEDIUM)
    }

    @Test fun `few steps with zone three is resistance despite dead zone`() {
        val result = fused(stepsLast15Min = 50, avgBpm = 110.0)
        assertThat(result.exerciseState).isEqualTo(HrActivityCalculator.ExerciseState.RESISTANCE)
        assertThat(result.confidence).isEqualTo(HrActivityCalculator.Confidence.MEDIUM)
    }

    @Test fun `low steps with zone two and stress opt in is stress`() {
        val result = fused(stepsLast15Min = 10, avgBpm = 100.0, stressDetection = true)
        assertThat(result.exerciseState).isEqualTo(HrActivityCalculator.ExerciseState.STRESS)
        assertThat(result.confidence).isEqualTo(HrActivityCalculator.Confidence.LOW)
    }

    @Test fun `low steps with zone one is inactive`() {
        val result = fused(stepsLast15Min = 10, avgBpm = 65.0)
        assertThat(result.exerciseState).isEqualTo(HrActivityCalculator.ExerciseState.INACTIVE)
        assertThat(result.confidence).isEqualTo(HrActivityCalculator.Confidence.HIGH)
    }

    @Test fun `no readings classify as resting with no hr evidence`() {
        val result = HrActivityCalculator.classify(
            hrReadings = emptyList(),
            nowMillis = now,
            hrWindowMinutes = 15,
            hrMax = 180,
            hrResting = 60,
            stepsLast15Min = 0,
            stressDetection = false
        )
        assertThat(result.exerciseState).isEqualTo(HrActivityCalculator.ExerciseState.RESTING)
        assertThat(result.hrZone).isEqualTo(HrActivityCalculator.HrZone.NONE)
        assertThat(result.averageHrBpm).isNull()
        assertThat(result.confidence).isEqualTo(HrActivityCalculator.Confidence.LOW)
    }

    @Test fun `inactivity raise is suppressed from zone three`() {
        assertThat(HrActivityCalculator.inactivitySuppressedByElevatedHr(fused(stepsLast15Min = 50, avgBpm = 110.0))).isTrue()
        assertThat(HrActivityCalculator.inactivitySuppressedByElevatedHr(fused(stepsLast15Min = 10, avgBpm = 65.0))).isFalse()
    }

    @Test fun `inactivity raise is blocked when hr is unknown`() {
        assertThat(HrActivityCalculator.inactivityRaiseBlockedByHr(null)).isTrue()
        assertThat(HrActivityCalculator.inactivityRaiseBlockedByHr(fused(stepsLast15Min = 10, avgBpm = 100.0))).isTrue()
        assertThat(HrActivityCalculator.inactivityRaiseBlockedByHr(fused(stepsLast15Min = 10, avgBpm = 65.0))).isFalse()
    }
}
