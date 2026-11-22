package app.aaps.plugins.source.keys

import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey

/**
 * AutoISF fork: the CGM correction (slope and offset) that the stored xDrip values were last corrected
 * with. When the setting is changed, the values of the last minutes are corrected again with the new
 * setting, so the change gives no jump in the BG curve.
 *
 * Not exported: it describes the values in the database of THIS phone.
 */
enum class XdripDoubleNonKey(
    override val key: String,
    override val defaultValue: Double,
    override val exportable: Boolean = true
) : DoubleNonPreferenceKey {

    /** 0 means "not known yet" (first value after the update), a real slope is never below 0.5. */
    LastCorrectionSlope("xdrip_last_correction_slope", 0.0, exportable = false),
    LastCorrectionOffset("xdrip_last_correction_offset", 0.0, exportable = false)
}
