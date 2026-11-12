package app.aaps.plugins.aps.openAPSSMB

import app.aaps.plugins.aps.openAPSSMB.HrActivityCalculator.ExerciseState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ActivityClassMappingTest {

    @Test fun `legacy entries keep their values`() {
        assertThat(ActivityClass.AEROBIC.targetBgOffsetMgdl).isEqualTo(10.0)
        assertThat(ActivityClass.AEROBIC.smbCapFactor).isEqualTo(0.7)
        assertThat(ActivityClass.RESISTANCE.targetBgOffsetMgdl).isEqualTo(15.0)
        assertThat(ActivityClass.RESISTANCE.smbCapFactor).isEqualTo(0.8)
        assertThat(ActivityClass.REST.targetBgOffsetMgdl).isEqualTo(0.0)
        assertThat(ActivityClass.REST.smbCapFactor).isEqualTo(1.0)
    }

    @Test fun `graded aerobic entries carry graded caps`() {
        assertThat(ActivityClass.LIGHT_AEROBIC.targetBgOffsetMgdl).isEqualTo(10.0)
        assertThat(ActivityClass.LIGHT_AEROBIC.smbCapFactor).isEqualTo(0.9)
        assertThat(ActivityClass.MODERATE_AEROBIC.targetBgOffsetMgdl).isEqualTo(10.0)
        assertThat(ActivityClass.MODERATE_AEROBIC.smbCapFactor).isEqualTo(0.8)
        assertThat(ActivityClass.VIGOROUS_AEROBIC.targetBgOffsetMgdl).isEqualTo(15.0)
        assertThat(ActivityClass.VIGOROUS_AEROBIC.smbCapFactor).isEqualTo(0.7)
    }

    @Test fun `stress never reduces smb`() {
        assertThat(ActivityClass.STRESS.smbCapFactor).isEqualTo(1.0)
        assertThat(ActivityClass.STRESS.targetBgOffsetMgdl).isEqualTo(15.0)
    }

    @Test fun `inactive mirrors rest numbers`() {
        assertThat(ActivityClass.INACTIVE.smbCapFactor).isEqualTo(1.0)
        assertThat(ActivityClass.INACTIVE.targetBgOffsetMgdl).isEqualTo(0.0)
    }

    @Test fun `isAerobic covers the four aerobic states only`() {
        assertThat(ActivityClass.AEROBIC.isAerobic).isTrue()
        assertThat(ActivityClass.LIGHT_AEROBIC.isAerobic).isTrue()
        assertThat(ActivityClass.MODERATE_AEROBIC.isAerobic).isTrue()
        assertThat(ActivityClass.VIGOROUS_AEROBIC.isAerobic).isTrue()
        assertThat(ActivityClass.RESISTANCE.isAerobic).isFalse()
        assertThat(ActivityClass.STRESS.isAerobic).isFalse()
        assertThat(ActivityClass.REST.isAerobic).isFalse()
        assertThat(ActivityClass.INACTIVE.isAerobic).isFalse()
    }

    @Test fun `isRestLike covers rest and inactive only`() {
        assertThat(ActivityClass.REST.isRestLike).isTrue()
        assertThat(ActivityClass.INACTIVE.isRestLike).isTrue()
        assertThat(ActivityClass.AEROBIC.isRestLike).isFalse()
        assertThat(ActivityClass.LIGHT_AEROBIC.isRestLike).isFalse()
        assertThat(ActivityClass.MODERATE_AEROBIC.isRestLike).isFalse()
        assertThat(ActivityClass.VIGOROUS_AEROBIC.isRestLike).isFalse()
        assertThat(ActivityClass.RESISTANCE.isRestLike).isFalse()
        assertThat(ActivityClass.STRESS.isRestLike).isFalse()
    }

    @Test fun `all seven exercise states map to distinct activity classes`() {
        val expected = mapOf(
            ExerciseState.VIGOROUS_AEROBIC to ActivityClass.VIGOROUS_AEROBIC,
            ExerciseState.MODERATE_AEROBIC to ActivityClass.MODERATE_AEROBIC,
            ExerciseState.LIGHT_AEROBIC to ActivityClass.LIGHT_AEROBIC,
            ExerciseState.RESISTANCE to ActivityClass.RESISTANCE,
            ExerciseState.STRESS to ActivityClass.STRESS,
            ExerciseState.RESTING to ActivityClass.REST,
            ExerciseState.INACTIVE to ActivityClass.INACTIVE
        )
        expected.forEach { (state, activityClass) ->
            assertThat(ActivityClass.fromHrState(state, stressDetection = true)).isEqualTo(activityClass)
        }
    }

    @Test fun `stress state maps to rest when stress detection is off`() {
        assertThat(ActivityClass.fromHrState(ExerciseState.STRESS, stressDetection = false)).isEqualTo(ActivityClass.REST)
    }
}
