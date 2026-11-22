package app.aaps.plugins.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.receivers.Intents
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.objects.workflow.MetroWorkerCreator
import app.aaps.core.ui.compose.icons.IcXDrip
import app.aaps.core.utils.receivers.DataInbox
import app.aaps.core.utils.receivers.Inbox
import app.aaps.plugins.source.compose.BgSourceComposeContent
import app.aaps.plugins.source.keys.XdripDoubleNonKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(400)
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<XDripSource>())
@Inject
class XdripSourcePlugin(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    preferences: Preferences,
    config: Config,
    notificationManager: NotificationManager
) : AbstractBgSourceWithSensorInsertLogPlugin(
    pluginDescription = PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .composeContent { plugin ->
            BgSourceComposeContent(
                title = rh.gs(R.string.source_xdrip)
            )
        }
        .icon(IcXDrip)
        .pluginName(TextRef.AndroidRes(R.string.source_xdrip))
        .preferencesVisibleInSimpleMode(false)
        .description(TextRef.AndroidRes(R.string.description_source_xdrip)),
    aapsLogger = aapsLogger,
    rh = rh,
    preferences = preferences,
    ownPreferences = XdripDoubleNonKey.entries,
    notificationManager = notificationManager
), BgSource, XDripSource {

    // AutoISF fork: the base settings plus the fixed CGM correction (slope and offset, no smoothing)
    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "bg_source_with_sensor_settings",
        title = TextRef.AndroidRes(R.string.source_xdrip),
        items = listOf(
            BooleanKey.BgSourceUploadToNs,
            BooleanKey.BgSourceCreateSensorChange,
            DoubleKey.BgSourceCorrectionSlope,
            DoubleKey.BgSourceCorrectionOffset
        ),
        icon = IcXDrip
    )

    override var sensorBatteryLevel = -1

    // cannot be inner class because of needed injection

    @AssistedInject
    class XdripSourceWorker(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        aapsLogger: AAPSLogger,
        fabricPrivacy: FabricPrivacy,
        private val xdripSourcePlugin: XdripSourcePlugin,
        private val persistenceLayer: PersistenceLayer,
        private val preferences: Preferences,
        private val dateUtil: DateUtil,
        private val dataInbox: DataInbox
    ) : LoggingWorker(context, params, Dispatchers.IO, aapsLogger, fabricPrivacy) {

        /**
         * Metro builds this worker. The parameter names must match [MetroWorkerCreator],
         * because Metro matches assisted parameters by name and not only by type.
         */
        @AssistedFactory
        fun interface Factory : MetroWorkerCreator {

            override fun create(context: Context, params: WorkerParameters): XdripSourceWorker
        }

        fun getSensorStartTime(bundle: Bundle): Long? {
            val now = dateUtil.now()
            var sensorStartTime: Long? = if (preferences.get(BooleanKey.BgSourceCreateSensorChange)) {
                bundle.getLong(Intents.EXTRA_SENSOR_STARTED_AT, 0)
            } else {
                null
            }
            // check start time validity
            sensorStartTime?.let {
                if (abs(it - now) > T.months(1).msecs() || it > now) sensorStartTime = null
            }
            return sensorStartTime
        }

        /**
         * AutoISF fork [SAFETY]: when slope or offset was changed since the last value, the stored values of
         * the last [BgSlopeOffset.RECORRECT_WINDOW_MS] (not before the last sensor change) are corrected
         * again with the new setting. Otherwise the old and the new values would not fit together, and the
         * jump between them would look like a fast rise or fall to AutoISF, the parabola and UKF.
         * The changed values are written as updates, so Nightscout gets them too.
         */
        private suspend fun recorrectStoredValues(slope: Double, offset: Double) {
            val lastSlope = preferences.get(XdripDoubleNonKey.LastCorrectionSlope)
            val lastOffset = preferences.get(XdripDoubleNonKey.LastCorrectionOffset)
            if (lastSlope == slope && lastOffset == offset) return
            // A slope of 0 means the last setting is not known (first value after the update): only remember it.
            if (lastSlope > 0.0) {
                val now = dateUtil.now()
                val sensorChange = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)?.timestamp ?: 0L
                val from = max(now - BgSlopeOffset.RECORRECT_WINDOW_MS, sensorChange)
                val changed = persistenceLayer.getBgReadingsDataFromTimeToTime(from, now, ascending = true).mapNotNull { gv ->
                    BgSlopeOffset.recorrect(gv.value, lastSlope, lastOffset, slope, offset)
                        ?.takeIf { it != gv.value }
                        ?.let { gv.copy(value = it) }
                }
                aapsLogger.debug(LTag.BGSOURCE, "CGM correction changed from $lastSlope/$lastOffset to $slope/$offset: ${changed.size} stored values corrected again")
                if (changed.isNotEmpty()) persistenceLayer.insertCgmSourceData(Sources.Xdrip, changed, emptyList(), null)
            }
            preferences.put(XdripDoubleNonKey.LastCorrectionSlope, slope)
            preferences.put(XdripDoubleNonKey.LastCorrectionOffset, offset)
        }

        @SuppressLint("CheckResult")
        override suspend fun doWorkAndLog(): Result {
            // Drain first, unconditionally: drain() clears DataInbox's pending-work gate, so every
            // enqueued worker MUST reach it. If we returned early (plugin disabled) before draining,
            // the gate would stay set and silently block all future enqueues for this slot until
            // the process restarts. Bundles drained while disabled are intentionally discarded.
            val bundles = dataInbox.drain(XdripInbox)
            if (!xdripSourcePlugin.isEnabled()) return Result.success(workDataOf("Result" to "Plugin not enabled"))
            if (bundles.isEmpty()) return Result.success(workDataOf("Result" to "no data"))

            var hadFailure = false
            for ((index, bundle) in bundles.withIndex()) {
                try {
                    processBundle(bundle)
                } catch (e: CancellationException) {
                    // WorkManager stopped this run (e.g. chain overload / run-attempt limit). The
                    // coroutine contract requires CancellationException to propagate, otherwise the
                    // loop keeps fighting a cancelled Job and spams failures for every remaining
                    // bundle. drain() already removed the whole batch, so re-queue this bundle plus
                    // everything not yet processed before propagating — otherwise those readings are
                    // lost. requeue/enqueue are non-suspending, so they complete despite cancellation.
                    dataInbox.requeue(XdripInbox, bundles.subList(index, bundles.size))
                    throw e
                } catch (e: Exception) {
                    // processBundle early-returns on malformed-bundle conditions; anything that
                    // reaches the catch is a real exception (typically a DB write). Surface as
                    // failure so WorkInfo reflects the truth.
                    aapsLogger.error(LTag.BGSOURCE, "Failed processing xDrip bundle", e)
                    hadFailure = true
                }
            }
            return if (hadFailure) Result.failure(workDataOf("Error" to "one or more bundles failed")) else Result.success()
        }

        private suspend fun processBundle(bundle: Bundle) {
            aapsLogger.debug(LTag.BGSOURCE, "Received xDrip data: $bundle")
            val glucoseValues = mutableListOf<GV>()
            // AutoISF fork: optional fixed correction, value x slope + offset. No smoothing. The value as it
            // came from xDrip/Juggluco is kept in raw when xDrip sends no raw value of its own.
            val received = round(bundle.getDouble(Intents.EXTRA_BG_ESTIMATE, 0.0))
            val receivedRaw = round(bundle.getDouble(Intents.EXTRA_RAW, 0.0))
            val slope = preferences.get(DoubleKey.BgSourceCorrectionSlope)
            val offset = preferences.get(DoubleKey.BgSourceCorrectionOffset)
            val corrected = BgSlopeOffset.apply(received, slope, offset)
            if (corrected != received) aapsLogger.debug(LTag.BGSOURCE, "CGM correction: $received x $slope + $offset = $corrected mg/dL")
            recorrectStoredValues(slope, offset)
            glucoseValues += GV(
                timestamp = bundle.getLong(Intents.EXTRA_TIMESTAMP, 0),
                value = corrected,
                raw = if (corrected != received && receivedRaw == 0.0) received else receivedRaw,
                noise = null,
                trendArrow = TrendArrow.fromString(bundle.getString(Intents.EXTRA_BG_SLOPE_NAME)),
                sourceSensor = SourceSensor.fromString(bundle.getString(Intents.XDRIP_DATA_SOURCE) ?: "")
            )
            val newSensorStartTime = getSensorStartTime(bundle)
            // Retrieve last stored sensorStartTime from the database
            val lastTherapyEvent = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)
            val lastStoredSensorStartTime = lastTherapyEvent?.timestamp
            // Decide whether to update sensorStartTime or keep the last stored one
            val finalSensorStartTime = when {
                lastStoredSensorStartTime != null && newSensorStartTime != null &&
                    abs(newSensorStartTime - lastStoredSensorStartTime) <= 300_000 -> {
                    aapsLogger.debug(LTag.BGSOURCE, "Sensor start time is within 5 minutes range, skipping update.")
                    null
                }

                lastStoredSensorStartTime != null && newSensorStartTime != null &&
                    newSensorStartTime < lastStoredSensorStartTime                 -> {
                    aapsLogger.debug(LTag.BGSOURCE, "Sensor start time is older than last stored time, skipping update.")
                    null
                }

                else                                                               -> newSensorStartTime
            }
            // Always update glucoseValues, but use the decided sensorStartTime
            if (glucoseValues[0].timestamp > 0 && glucoseValues[0].value > 0.0) {
                persistenceLayer.insertCgmSourceData(Sources.Xdrip, glucoseValues, emptyList(), finalSensorStartTime)
            } else {
                aapsLogger.warn(LTag.BGSOURCE, "Skipping xDrip bundle: missing glucoseValue")
                return
            }
            xdripSourcePlugin.sensorBatteryLevel = bundle.getInt(Intents.EXTRA_SENSOR_BATTERY, -1)
        }
    }
}

object XdripInbox : Inbox<Bundle>("xdrip-bg", XdripSourcePlugin.XdripSourceWorker::class.java)
