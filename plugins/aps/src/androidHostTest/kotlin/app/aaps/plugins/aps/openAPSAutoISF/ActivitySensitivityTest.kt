package app.aaps.plugins.aps.openAPSAutoISF

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ActivitySensitivityTest {

    @Test
    fun `activity never gives more insulin than autosens`() {
        // autosens says sensitive (0.8), activity monitor says neutral (1.0): keep 0.8
        assertThat(activitySensitivityRatio(autosensRatio = 0.8, activityRatio = 1.0, activityDetected = true, autosensMax = 1.2)).isEqualTo(0.8)
        // autosens says resistant (1.2), activity says 0.7: use 0.7
        assertThat(activitySensitivityRatio(autosensRatio = 1.2, activityRatio = 0.7, activityDetected = true, autosensMax = 1.2)).isEqualTo(0.7)
        // both below 1: the smaller one wins
        assertThat(activitySensitivityRatio(autosensRatio = 0.6, activityRatio = 0.85, activityDetected = true, autosensMax = 1.2)).isEqualTo(0.6)
    }

    // Issue #2, option C: inactivity never overrules autosens when autosens says "more sensitive"
    @Test
    fun `inactivity never gives more insulin than a sensitive autosens`() {
        assertThat(activitySensitivityRatio(autosensRatio = 0.7, activityRatio = 1.2, activityDetected = false, autosensMax = 1.2)).isEqualTo(0.7)
        assertThat(activitySensitivityRatio(autosensRatio = 0.9, activityRatio = 1.1, activityDetected = false, autosensMax = 1.2)).isEqualTo(0.9)
    }

    @Test
    fun `inactivity works when autosens is neutral or off`() {
        // autosens switched off gives 1.0
        assertThat(activitySensitivityRatio(autosensRatio = 1.0, activityRatio = 1.1, activityDetected = false, autosensMax = 1.2)).isEqualTo(1.1)
    }

    @Test
    fun `inactivity and a resistant autosens take the larger one`() {
        assertThat(activitySensitivityRatio(autosensRatio = 1.15, activityRatio = 1.1, activityDetected = false, autosensMax = 1.2)).isEqualTo(1.15)
        assertThat(activitySensitivityRatio(autosensRatio = 1.05, activityRatio = 1.1, activityDetected = false, autosensMax = 1.2)).isEqualTo(1.1)
    }

    @Test
    fun `inactivity is capped at autosens max`() {
        // inactivity scale factor 1.5 gives 1.3 in the step monitor; the user allows autosens only up to 1.2
        assertThat(activitySensitivityRatio(autosensRatio = 1.0, activityRatio = 1.3, activityDetected = false, autosensMax = 1.2)).isEqualTo(1.2)
    }

}
