package app.aaps.core.keys.interfaces

interface DoublePreferenceKey : PreferenceKey, DoubleNonPreferenceKey {

    /**
     * Default value if not changed from preferences
     */
    override val defaultValue: Double

    /**
     *  Minimal allowed value
     */
    val min: Double

    /**
     *  Maximal allowed value
     */
    val max: Double

    /**
     *  Value with calculation in simple mode
     */
    val calculatedBySM: Boolean

    /**
     * AutoISF fork: step of the slider when it must differ from the step of [unitType]. Null uses the
     * step of the unit type. A coarser step can bring a wide range down to at most 200 steps, so the
     * slider with its +/- buttons is shown instead of a text field (some number keyboards have no minus).
     */
    val step: Double? get() = null

}