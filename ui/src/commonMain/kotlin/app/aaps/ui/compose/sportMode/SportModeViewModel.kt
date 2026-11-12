package app.aaps.ui.compose.sportMode

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.ActiveSceneState
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.SceneLifecycle
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.protection.ProtectionResult
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.interfaces.scenes.ActiveSceneSync
import app.aaps.core.interfaces.scenes.SceneActions
import app.aaps.core.interfaces.scenes.SceneStore
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.clientcontrol.failText
import app.aaps.core.ui.compose.formatMinutesAsDuration
import app.aaps.core.ui.compose.navigation.icon
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A scene the sport mode can start. */
data class SportSceneOption(val id: String, val name: String, val defaultMinutes: Int)

/**
 * The sport mode as the overview shows it.
 * [active]: the sport scene runs. [remainingText] and [endClock]: time left and end of the scene.
 * [basalPercent]: what the loop makes of the high temp target (half basal), from its last run.
 * [noEffect]: "High temp target raises sensitivity" is off, so the temp target does not change the insulin.
 */
data class SportModeUiState(
    val active: Boolean = false,
    val remainingText: String = "",
    val endClock: String? = null,
    val basalPercent: Int? = null,
    val noEffect: Boolean = false
)

/** One line of the summary after the activity: a label and its value, both ready to show. */
data class SportSummaryLine(val label: String, val value: String)

/**
 * AutoISF fork: the sport mode of the sport version.
 *
 * It starts the sport scene the user picked, with the duration picked, and the overview then shows the sport
 * view while that scene runs. It changes no dosing logic: the scene sets the temp target (and what else it has),
 * and AutoISF works with it as with any temp target, half basal included.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class SportModeViewModel(
    private val preferences: Preferences,
    private val sceneStore: SceneStore,
    private val sceneActions: SceneActions,
    private val activeSceneSync: ActiveSceneSync,
    private val protectionCheck: ProtectionCheck,
    private val persistenceLayer: PersistenceLayer,
    private val loop: Loop,
    private val profileUtil: ProfileUtil,
    private val dateUtil: DateUtil,
    private val rh: TextResolver,
    private val rxBus: RxBus,
    private val appScope: CoroutineScope
) : ViewModel() {

    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(30_000L)
        }
    }

    val uiState: StateFlow<SportModeUiState> = combine(activeSceneSync.activeSceneState, ticker) { state, _ -> buildState(state) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), SportModeUiState())

    private val _summary = MutableStateFlow<List<SportSummaryLine>?>(null)

    /** The summary after the sport scene ended, or null. */
    val summary: StateFlow<List<SportSummaryLine>?> = _summary.asStateFlow()

    init {
        // When the sport scene ends (end, expiry or another scene), show what happened during it
        viewModelScope.launch {
            var last: ActiveSceneState? = null
            activeSceneSync.activeSceneState.collect { state ->
                val previous = last
                if (previous != null && previous.lifecycle == SceneLifecycle.ACTIVE && isSportScene(previous) &&
                    (state == null || state.lifecycle != SceneLifecycle.ACTIVE || state.activatedAt != previous.activatedAt)
                ) _summary.value = buildSummary(previous)
                last = state
            }
        }
    }

    private fun isSportScene(state: ActiveSceneState): Boolean {
        val id = preferences.get(StringNonKey.AutoIsfSportSceneId)
        return id.isNotEmpty() && state.scene.id == id
    }

    private fun buildState(state: ActiveSceneState?): SportModeUiState {
        if (state == null || state.lifecycle != SceneLifecycle.ACTIVE || !isSportScene(state)) return SportModeUiState()
        val now = dateUtil.now()
        val ratio = (loop.lastRun?.request?.rawData() as? RT)?.sensitivityRatio
        return SportModeUiState(
            active = true,
            remainingText = state.remainingMs(now)?.let { dateUtil.timeRemainingString(it, rh) } ?: "",
            endClock = state.endsAt?.let { dateUtil.timeString(it) },
            basalPercent = ratio?.let { (it * 100).roundToInt() },
            noEffect = !preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens)
        )
    }

    /** The scenes to choose from, the enabled ones. */
    fun scenes(): List<SportSceneOption> =
        sceneStore.getScenes().filter { it.isEnabled }.map { SportSceneOption(it.id, it.name, it.defaultDurationMinutes) }

    /** The scene picked last time, if it still exists. */
    fun lastSceneId(): String? = preferences.get(StringNonKey.AutoIsfSportSceneId).takeIf { id -> scenes().any { it.id == id } }

    /**
     * Starts [sceneId] for [minutes] as the sport scene. Asks for the same protection as starting a scene, and the
     * master's confirmation of what the scene does comes before anything happens.
     */
    fun start(sceneId: String, minutes: Int) {
        preferences.put(StringNonKey.AutoIsfSportSceneId, sceneId)
        protectionCheck.requestProtection(ElementType.SCENE.protection) { result ->
            if (result == ProtectionResult.GRANTED) prepareStart(sceneId, minutes)
        }
    }

    private fun prepareStart(sceneId: String, minutes: Int) {
        val title = rh.gs(CoreUiStrings.sport_mode_start_title)
        viewModelScope.launch {
            when (val prepared = sceneActions.prepareStart(sceneId, minutes)) {
                is ActionProgress.Prepared ->
                    rxBus.send(
                        EventShowDialog.OkCancel(
                            title = title, message = "", confirmationLines = prepared.lines, icon = ElementType.SCENE.icon(),
                            onOk = { appScope.launch { sceneActions.commitStart(prepared.id) } }
                        )
                    )

                is ActionProgress.Rejected -> rxBus.send(EventShowDialog.Ok(title = title, message = prepared.detail ?: rh.gs(prepared.reason.failText())))
                else                       -> Unit
            }
        }
    }

    /** Moves the end of the sport scene [minutes] later, with its temp target. */
    fun extend(minutes: Int) {
        protectionCheck.requestProtection(ElementType.SCENE.protection) { result ->
            if (result != ProtectionResult.GRANTED) return@requestProtection
            appScope.launch {
                val progress = sceneActions.extend(minutes)
                if (progress is ActionProgress.Rejected)
                    rxBus.send(EventShowDialog.Ok(title = rh.gs(CoreUiStrings.sport_mode_title), message = progress.detail ?: rh.gs(progress.reason.failText())))
            }
        }
    }

    fun dismissSummary() {
        _summary.value = null
    }

    private suspend fun buildSummary(state: ActiveSceneState): List<SportSummaryLine> {
        val start = state.activatedAt
        val end = minOf(dateUtil.now(), state.endsAt ?: Long.MAX_VALUE)
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
