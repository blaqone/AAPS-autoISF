package app.aaps.plugins.aps.openAPSSMB

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ActivityClassifierTest {

    @BeforeEach fun resetPhoneActivity() {
        ActivityClassifier.phoneActivity = ActivityClassifier.PHONE_STILL
    }

    @Test fun `bicycle activity returns AEROBIC`() {
        val result = ActivityClassifier.classify(steps15m = 0, avgHrLast15m = 0.0, phoneActivity = ActivityClassifier.PHONE_BICYCLE)
        assertThat(result).isEqualTo(ActivityClass.AEROBIC)
    }

    @Test fun `walking activity returns AEROBIC`() {
        val result = ActivityClassifier.classify(steps15m = 0, avgHrLast15m = 0.0, phoneActivity = ActivityClassifier.PHONE_WALKING)
        assertThat(result).isEqualTo(ActivityClass.AEROBIC)
    }

    @Test fun `running activity returns AEROBIC`() {
        val result = ActivityClassifier.classify(steps15m = 0, avgHrLast15m = 0.0, phoneActivity = ActivityClassifier.PHONE_RUNNING)
        assertThat(result).isEqualTo(ActivityClass.AEROBIC)
    }

    @Test fun `high steps without phone signal returns AEROBIC`() {
        val result = ActivityClassifier.classify(steps15m = 700, avgHrLast15m = 0.0, phoneActivity = ActivityClassifier.PHONE_STILL)
        assertThat(result).isEqualTo(ActivityClass.AEROBIC)
    }

    @Test fun `steps just above the threshold of 600 return AEROBIC`() {
        val result = ActivityClassifier.classify(steps15m = 601, avgHrLast15m = 0.0, phoneActivity = ActivityClassifier.PHONE_STILL)
        assertThat(result).isEqualTo(ActivityClass.AEROBIC)
    }

    @Test fun `slow walking of 600 steps in 15 minutes is not AEROBIC`() {
        // 40 steps per minute: below the limit the steps alone do not count as endurance activity
        assertThat(ActivityClassifier.classify(steps15m = 600, avgHrLast15m = 0.0, phoneActivity = ActivityClassifier.PHONE_STILL))
            .isEqualTo(ActivityClass.REST)
        assertThat(ActivityClassifier.classify(steps15m = 200, avgHrLast15m = 0.0, phoneActivity = ActivityClassifier.PHONE_STILL))
            .isEqualTo(ActivityClass.REST)
    }

    @Test fun `RESISTANCE needs almost no steps in the whole 15 minutes`() {
        assertThat(ActivityClassifier.classify(steps15m = 44, avgHrLast15m = 80.0, phoneActivity = ActivityClassifier.PHONE_STILL, restingHr = 60))
            .isEqualTo(ActivityClass.RESISTANCE)
        assertThat(ActivityClassifier.classify(steps15m = 45, avgHrLast15m = 80.0, phoneActivity = ActivityClassifier.PHONE_STILL, restingHr = 60))
            .isEqualTo(ActivityClass.REST)
    }

    @Test fun `low steps and high HR returns RESISTANCE`() {
        // restingHr=60, threshold = 60 * 1.25 = 75.0, avgHr=80 > 75 → RESISTANCE
        val result = ActivityClassifier.classify(steps15m = 10, avgHrLast15m = 80.0, phoneActivity = ActivityClassifier.PHONE_STILL, restingHr = 60)
        assertThat(result).isEqualTo(ActivityClass.RESISTANCE)
    }

    @Test fun `HR exactly at threshold does not trigger RESISTANCE`() {
        // restingHr=60, threshold=75.0, avgHr=75.0 is NOT > 75.0
        val result = ActivityClassifier.classify(steps15m = 10, avgHrLast15m = 75.0, phoneActivity = ActivityClassifier.PHONE_STILL, restingHr = 60)
        assertThat(result).isEqualTo(ActivityClass.REST)
    }

    @Test fun `high steps with high HR returns AEROBIC not RESISTANCE`() {
        val result = ActivityClassifier.classify(steps15m = 700, avgHrLast15m = 90.0, phoneActivity = ActivityClassifier.PHONE_STILL, restingHr = 60)
        assertThat(result).isEqualTo(ActivityClass.AEROBIC)
    }

    @Test fun `zero HR does not trigger RESISTANCE`() {
        val result = ActivityClassifier.classify(steps15m = 5, avgHrLast15m = 0.0, phoneActivity = ActivityClassifier.PHONE_STILL)
        assertThat(result).isEqualTo(ActivityClass.REST)
    }

    @Test fun `no activity returns REST`() {
        val result = ActivityClassifier.classify(steps15m = 0, avgHrLast15m = 60.0, phoneActivity = ActivityClassifier.PHONE_STILL)
        assertThat(result).isEqualTo(ActivityClass.REST)
    }

    @Test fun `default phoneActivity is STILL`() {
        assertThat(ActivityClassifier.phoneActivity).isEqualTo(ActivityClassifier.PHONE_STILL)
    }

    @Test fun `phoneActivity singleton can be updated`() {
        ActivityClassifier.phoneActivity = ActivityClassifier.PHONE_WALKING
        val result = ActivityClassifier.classify(steps15m = 0, avgHrLast15m = 0.0)
        assertThat(result).isEqualTo(ActivityClass.AEROBIC)
    }

    @Test fun `the user chooses how sensitive the steps count`() {
        fun classify(steps: Int, level: Int, hr: Double = 0.0) = ActivityClassifier.classify(
            steps15m = steps, avgHrLast15m = hr, phoneActivity = ActivityClassifier.PHONE_STILL, restingHr = 60,
            limits = ActivityClassifier.limits(level)
        )
        // 500 steps in 15 minutes: endurance only with high sensitivity
        assertThat(classify(500, ActivityClassifier.SENSITIVITY_HIGH)).isEqualTo(ActivityClass.AEROBIC)
        assertThat(classify(500, ActivityClassifier.SENSITIVITY_MEDIUM)).isEqualTo(ActivityClass.REST)
        assertThat(classify(500, ActivityClassifier.SENSITIVITY_LOW)).isEqualTo(ActivityClass.REST)
        // 700 steps: not yet with low sensitivity
        assertThat(classify(700, ActivityClassifier.SENSITIVITY_MEDIUM)).isEqualTo(ActivityClass.AEROBIC)
        assertThat(classify(700, ActivityClassifier.SENSITIVITY_LOW)).isEqualTo(ActivityClass.REST)
        // strength: 40 steps with high heart rate - not with low sensitivity
        assertThat(classify(40, ActivityClassifier.SENSITIVITY_MEDIUM, hr = 80.0)).isEqualTo(ActivityClass.RESISTANCE)
        assertThat(classify(40, ActivityClassifier.SENSITIVITY_LOW, hr = 80.0)).isEqualTo(ActivityClass.REST)
        // an unknown value is treated as medium
        assertThat(ActivityClassifier.limits(7)).isEqualTo(ActivityClassifier.limits(ActivityClassifier.SENSITIVITY_MEDIUM))
    }
}
