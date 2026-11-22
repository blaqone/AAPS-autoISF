package app.aaps.plugins.source

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** AutoISF fork: the fixed CGM correction is only value x slope + offset, nothing else. */
class BgSlopeOffsetTest {

    @Test fun `default settings leave the value exactly as it is`() {
        assertThat(BgSlopeOffset.apply(123.0, slope = 1.0, offset = 0.0)).isEqualTo(123.0)
        assertThat(BgSlopeOffset.isActive(1.0, 0.0)).isFalse()
    }

    @Test fun `offset and slope are applied`() {
        // sensor reads 30 mg/dL too low
        assertThat(BgSlopeOffset.apply(90.0, slope = 1.0, offset = 30.0)).isEqualTo(120.0)
        assertThat(BgSlopeOffset.apply(100.0, slope = 1.1, offset = -5.0)).isWithin(1e-9).of(105.0)
    }

    @Test fun `limits are kept`() {
        // slope and offset outside the allowed range are clamped, the result is never below 40
        assertThat(BgSlopeOffset.apply(100.0, slope = 3.0, offset = 0.0)).isEqualTo(150.0)
        assertThat(BgSlopeOffset.apply(100.0, slope = 1.0, offset = -90.0)).isEqualTo(50.0)
        assertThat(BgSlopeOffset.apply(60.0, slope = 0.5, offset = -50.0)).isEqualTo(40.0)
    }

    @Test fun `no value stays no value`() {
        assertThat(BgSlopeOffset.apply(0.0, slope = 1.0, offset = 30.0)).isEqualTo(0.0)
    }

    @Test fun `revert gives the value before the correction`() {
        assertThat(BgSlopeOffset.revert(120.0, slope = 1.0, offset = 30.0)).isWithin(1e-9).of(90.0)
        assertThat(BgSlopeOffset.revert(105.0, slope = 1.1, offset = -5.0)).isWithin(1e-9).of(100.0)
        // no correction active: the value is the original
        assertThat(BgSlopeOffset.revert(123.0, slope = 1.0, offset = 0.0)).isEqualTo(123.0)
    }

    @Test fun `revert cannot tell a value that was raised to the lower limit`() {
        assertThat(BgSlopeOffset.revert(40.0, slope = 0.5, offset = -50.0)).isNull()
    }

    @Test fun `recorrect moves a stored value from the old to the new setting`() {
        // offset changed from 0 to -20: 140 becomes 120
        assertThat(BgSlopeOffset.recorrect(140.0, 1.0, 0.0, 1.0, -20.0)).isWithin(1e-9).of(120.0)
        // from slope 1.1 offset -5 back to no correction: 105 becomes the original 100
        assertThat(BgSlopeOffset.recorrect(105.0, 1.1, -5.0, 1.0, 0.0)).isWithin(1e-9).of(100.0)
        assertThat(BgSlopeOffset.recorrect(40.0, 0.5, -50.0, 1.0, 0.0)).isNull()
    }
}
