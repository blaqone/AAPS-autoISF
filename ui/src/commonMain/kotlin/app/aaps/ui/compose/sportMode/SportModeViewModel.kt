package app.aaps.ui.compose.sportMode

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.protection.ProtectionResult
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.formatMinutesAsDuration
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The sport mode as the overview shows it.
 * [active]: the Exercise chip switched half basal on and it is still on.
 * [highTarget]: a temp target above the normal target runs, so half basal works now.
 * [basalPercent], [basalRate], [profileRate]: what the loop makes of it (half basal), from its last run.
 * [tbrNow]: the temp basal that runs now, if any. [since]: clock time the mode was switched on.
 */
data class SportModeUiState(
    val active: Boolean = false,
    val highTarget: Boolean = false,
    val basalPercent: Int? = null,
    val basalRate: String? = null,
    val profileRate: String? = null,
    val tbrNow: String? = null,
    val since: String? = null
)

/** One line of the summary after the activity: a label and its value, both ready to show. */
data class SportSummaryLine(val label: String, val value: String)

/**
 * AutoISF fork: the sport mode of the sport version.
 *
 * The Exercise chip switches the AutoISF setting "High temp target raises sensitivity" (half basal) on, the
 * overview then shows the sport view, and End switches it off again. The temp target is set by the user as
 * always. Nothing else changes: AutoISF calculates with the setting as when it is switched on in the settings.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class SportModeViewModel(
    private val preferences: Preferences,
    private val protectionCheck: ProtectionCheck,
    private val persistenceLayer: PersistenceLayer,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val profileFunction: ProfileFunction,
    private val loop: Loop,
    private val profileUtil: ProfileUtil,
    private val dateUtil: DateUtil,
    private val rh: TextResolver,
    private val appScope: CoroutineScope
) : ViewModel() {

    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(30_000L)
        }
    }

    val uiState: StateFlow<SportModeUiState> = combine(preferences.observe(LongNonKey.AutoIsfSportModeStart), ticker) { start, _ -> start }
        .map { start -> buildState(start) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), SportModeUiState())

    private val _summary = MutableStateFlow<List<SportSummaryLine>?>(null)

    /** The summary after the sport mode ended, or null. */
    val summary: StateFlow<List<SportSummaryLine>?> = _summary.asStateFlow()

    private suspend fun buildState(start: Long): SportModeUiState {
        // Switched off in the settings meanwhile: the sport mode is over as well
        if (start <= 0L || !preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens)) return SportModeUiState()
        val now = dateUtil.now()
        val profile = profileFunction.getProfile()
        val tt = persistenceLayer.getTemporaryTargetActiveAt(now)
        val highTarget = tt != null && tt.lowTarget > Constants.NORMAL_TARGET_MGDL
        val ratio = (loop.lastRun?.request?.rawData() as? RT)?.sensitivityRatio
        val profileBasal = profile?.getBasal()
        val tbr = profile?.let { p -> processedTbrEbData.getTempBasalIncludingConvertedExtended(now)?.convertedToAbsolute(now, p) }
        return SportModeUiState(
            active = true,
            highTarget = highTarget,
            basalPercent = if (highTarget) ratio?.let { (it * 100).roundToInt() } else null,
            basalRate = if (highTarget && ratio != null && profileBasal != null) rh.gs(CoreUiStrings.pump_base_basal_rate, profileBasal * ratio) else null,
            profileRate = profileBasal?.let { rh.gs(CoreUiStrings.pump_base_basal_rate, it) },
            tbrNow = tbr?.let { rh.gs(CoreUiStrings.pump_base_basal_rate, it) },
            since = dateUtil.timeString(start)
        )
    }

    /** The Exercise chip: half basal on. Asks for the same protection as a temp target. */
    fun start() {
        protectionCheck.requestProtection(ElementType.TEMP_TARGET_MANAGEMENT.protection) { result ->
            if (result != ProtectionResult.GRANTED) return@requestProtection
            preferences.put(BooleanKey.ApsAutoIsfHighTtRaisesSens, true)
            preferences.put(LongNonKey.AutoIsfSportModeStart, dateUtil.now())
        }
    }

    /** End: half basal off, then the summary of the time it was on. */
    fun end() {
        protectionCheck.requestProtection(ElementType.TEMP_TARGET_MANAGEMENT.protection) { result ->
            if (result != ProtectionResult.GRANTED) return@requestProtection
            val start = preferences.get(LongNonKey.AutoIsfSportModeStart)
            preferences.put(BooleanKey.ApsAutoIsfHighTtRaisesSens, false)
            preferences.put(LongNonKey.AutoIsfSportModeStart, 0L)
            if (start > 0L) appScope.launch { _summary.value = buildSummary(start, dateUtil.now()) }
        }
    }

    fun dismissSummary() {
        _summary.value = null
    }

    private suspend fun buildSummary(start: Long, end: Long): List<SportSummaryLine> {
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(start - 10 * 60_000L, end, true).filter { it.isValid }
        val bgStart = readings.lastOrNull { it.timestamp <= start } ?: readings.firstOrNull()
        val bgEnd = readings.lastOrNull()
        val bgLowest = readings.filter { it.timestamp >= start }.minByOrNull { it.value }
        val insulin = persistenceLayer.getBolusesFromTimeToTime(start, end, true)
            .filter { it.isValid && it.type != BS.Type.PRIMING }.sumOf { it.amount }
        val carbs = persistenceLayer.getCarbsFromTime(start, true)
            .filter { it.isValid && it.timestamp <= end }.sumOf { it.amount }
        val none = "-"
        return listOf(
            SportSummaryLine(rh.gs(CoreUiStrings.sport_summary_duration), formatMinutesAsDuration(((end - start) / 60_000L).toInt(), rh)),
            SportSummaryLine(rh.gs(CoreUiStrings.sport_summary_bg_start), bgStart?.let { profileUtil.fromMgdlToStringWithUnits(it.value) } ?: none),
            SportSummaryLine(rh.gs(CoreUiStrings.sport_summary_bg_end), bgEnd?.let { profileUtil.fromMgdlToStringWithUnits(it.value) } ?: none),
            SportSummaryLine(rh.gs(CoreUiStrings.sport_summary_bg_lowest), bgLowest?.let { profileUtil.fromMgdlToStringWithUnits(it.value) } ?: none),
            SportSummaryLine(rh.gs(CoreUiStrings.sport_summary_insulin), rh.gs(InterfacesStrings.format_insulin_units, insulin)),
            SportSummaryLine(rh.gs(CoreUiStrings.sport_summary_carbs), rh.gs(InterfacesStrings.format_carbs, carbs.roundToInt()))
        )
    }
}
