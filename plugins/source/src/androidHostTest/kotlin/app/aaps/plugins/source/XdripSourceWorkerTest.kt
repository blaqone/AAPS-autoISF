package app.aaps.plugins.source

import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.receivers.Intents
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.utils.receivers.DataInbox
import app.aaps.plugins.source.keys.XdripDoubleNonKey
import app.aaps.shared.tests.BundleMock
import app.aaps.shared.tests.TestBaseWithProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class XdripSourceWorkerTest : TestBaseWithProfile() {

    private lateinit var worker: XdripSourcePlugin.XdripSourceWorker
    @Mock lateinit var xdripSourcePlugin: XdripSourcePlugin
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var workerParameters: WorkerParameters
    @Mock lateinit var dataInbox: DataInbox

    @BeforeEach
    fun setupMock() {
        whenever(workerParameters.inputData).thenReturn(workDataOf())
        // AutoISF fork: CGM correction at its defaults, values pass through unchanged
        whenever(preferences.get(DoubleKey.BgSourceCorrectionSlope)).thenReturn(1.0)
        whenever(preferences.get(DoubleKey.BgSourceCorrectionOffset)).thenReturn(0.0)
        worker = XdripSourcePlugin.XdripSourceWorker(context, workerParameters, aapsLogger, fabricPrivacy, xdripSourcePlugin, persistenceLayer, preferences, dateUtil, dataInbox)
    }

    @Test
    fun `When plugin disabled then return success`() {
        runTest {
            whenever(xdripSourcePlugin.isEnabled()).thenReturn(false)

            val result = worker.doWork()

            Assertions.assertEquals(ListenableWorker.Result.success(workDataOf("Result" to "Plugin not enabled")), result)
            verify(persistenceLayer, never()).insertCgmSourceData(any(), any(), any(), any())
        }
    }

    @Test
    fun `When plugin disabled the inbox is still drained so the pending-work gate clears`() {
        runTest {
            whenever(xdripSourcePlugin.isEnabled()).thenReturn(false)
            val bundle = validBundle(now - 60000, 150.0)
            whenever(dataInbox.drain(eq(XdripInbox))).thenReturn(listOf(bundle))

            val result = worker.doWork()

            Assertions.assertEquals(ListenableWorker.Result.success(workDataOf("Result" to "Plugin not enabled")), result)
            // Regression: drain() MUST run even when disabled, otherwise DataInbox's pending-work
            // flag stays set and silently wedges all future enqueues until the process restarts.
            verify(dataInbox).drain(XdripInbox)
            // Data drained while disabled is intentionally discarded, not stored.
            verify(persistenceLayer, never()).insertCgmSourceData(any(), any(), any(), any())
        }
    }

    @Test
    fun `When plugin enabled then insert G6 data`() {
        val timestamp = now - 60000
        runTest {
            whenever(xdripSourcePlugin.isEnabled()).thenReturn(true)
            whenever(preferences.get(BooleanKey.BgSourceCreateSensorChange)).thenReturn(true)
            whenever(persistenceLayer.insertCgmSourceData(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(PersistenceLayer.TransactionResult())
            val bundle = BundleMock.mocked().apply {
                putString(Intents.XDRIP_DATA_SOURCE, "G6 Native")
                putLong(Intents.EXTRA_TIMESTAMP, timestamp)
                putLong(Intents.EXTRA_SENSOR_STARTED_AT, timestamp)
                putDouble(Intents.EXTRA_BG_ESTIMATE, 150.0)
                putDouble(Intents.EXTRA_RAW, 150.0)
                putString(Intents.EXTRA_BG_SLOPE_NAME, "FortyFiveDown")
            }
            whenever(dataInbox.drain(eq(XdripInbox))).thenReturn(listOf(bundle))

            val result = worker.doWork()

            Assertions.assertEquals(ListenableWorker.Result.success(), result)
            val expectedGv = GV(
                timestamp = timestamp,
                value = 150.0,
                raw = 150.0,
                noise = null,
                trendArrow = TrendArrow.FORTY_FIVE_DOWN,
                sourceSensor = SourceSensor.DEXCOM_G6
            )
            verify(persistenceLayer).insertCgmSourceData(Sources.Xdrip, listOf(expectedGv), emptyList(), timestamp)
        }
    }

    @Test
    fun `When inbox is empty then return success with no-data marker`() {
        runTest {
            whenever(xdripSourcePlugin.isEnabled()).thenReturn(true)
            whenever(dataInbox.drain(eq(XdripInbox))).thenReturn(emptyList())

            val result = worker.doWork()

            Assertions.assertEquals(ListenableWorker.Result.success(workDataOf("Result" to "no data")), result)
            verify(persistenceLayer, never()).insertCgmSourceData(any(), any(), any(), any())
        }
    }

    @Test
    fun `When processing is cancelled then unprocessed bundles are re-queued and cancellation propagates`() {
        runTest {
            whenever(xdripSourcePlugin.isEnabled()).thenReturn(true)
            whenever(preferences.get(BooleanKey.BgSourceCreateSensorChange)).thenReturn(true)
            val bundle1 = validBundle(now - 120000, 150.0)
            val bundle2 = validBundle(now - 60000, 151.0)
            val bundles = listOf(bundle1, bundle2)
            whenever(dataInbox.drain(eq(XdripInbox))).thenReturn(bundles)
            // Simulate WorkManager cancelling the coroutine during the first DB write.
            whenever(persistenceLayer.insertCgmSourceData(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
                .thenThrow(CancellationException("Job was cancelled"))

            val thrown = runCatching { worker.doWork() }.exceptionOrNull()

            // Cancellation must propagate, not be swallowed as a per-bundle failure.
            Assertions.assertTrue(thrown is CancellationException)
            // Nothing was committed, so the whole drained batch is re-queued for the next run.
            verify(dataInbox).requeue(eq(XdripInbox), eq(bundles))
        }
    }

    @Test
    fun `When glucoseValues are missing the bundle is skipped`() {
        runTest {
            whenever(xdripSourcePlugin.isEnabled()).thenReturn(true)
            val bundle = BundleMock.mocked().apply {
                putString("sensorType", "G6")
            }
            whenever(dataInbox.drain(eq(XdripInbox))).thenReturn(listOf(bundle))

            val result = worker.doWork()

            // Batch returns success even when some bundles are skipped; verify no insert happened.
            Assertions.assertEquals(ListenableWorker.Result.success(), result)
            verify(persistenceLayer, never()).insertCgmSourceData(any(), any(), any(), any())
        }
    }

    // AutoISF fork: a changed CGM correction is applied again to the values of the last 45 minutes

    private fun storedGv(timestamp: Long, value: Double) =
        GV(timestamp = timestamp, value = value, raw = value, noise = null, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.DEXCOM_G6)

    private suspend fun setUpCorrection(lastSlope: Double, lastOffset: Double, slope: Double, offset: Double) {
        whenever(xdripSourcePlugin.isEnabled()).thenReturn(true)
        whenever(preferences.get(XdripDoubleNonKey.LastCorrectionSlope)).thenReturn(lastSlope)
        whenever(preferences.get(XdripDoubleNonKey.LastCorrectionOffset)).thenReturn(lastOffset)
        whenever(preferences.get(DoubleKey.BgSourceCorrectionSlope)).thenReturn(slope)
        whenever(preferences.get(DoubleKey.BgSourceCorrectionOffset)).thenReturn(offset)
        whenever(persistenceLayer.insertCgmSourceData(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(PersistenceLayer.TransactionResult())
        val bundle = validBundle(now - 1000, 150.0)
        whenever(dataInbox.drain(eq(XdripInbox))).thenReturn(listOf(bundle))
    }

    @Test
    fun `When the correction was changed the stored values are corrected again`() {
        runTest {
            setUpCorrection(lastSlope = 1.0, lastOffset = 0.0, slope = 1.0, offset = -20.0)
            val window = BgSlopeOffset.RECORRECT_WINDOW_MS
            whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(now - window, now, true))
                .thenReturn(listOf(storedGv(now - 600_000, 140.0), storedGv(now - 300_000, 150.0)))

            worker.doWork()

            verify(persistenceLayer).insertCgmSourceData(
                Sources.Xdrip, listOf(storedGv(now - 600_000, 140.0).copy(value = 120.0), storedGv(now - 300_000, 150.0).copy(value = 130.0)), emptyList(), null
            )
            verify(preferences).put(XdripDoubleNonKey.LastCorrectionSlope, 1.0)
            verify(preferences).put(XdripDoubleNonKey.LastCorrectionOffset, -20.0)
        }
    }

    @Test
    fun `Values from before the last sensor change are not corrected again`() {
        runTest {
            setUpCorrection(lastSlope = 1.0, lastOffset = 0.0, slope = 1.0, offset = -20.0)
            val sensorChange = now - 600_000
            whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE))
                .thenReturn(TE(timestamp = sensorChange, type = TE.Type.SENSOR_CHANGE, glucoseUnit = GlucoseUnit.MGDL))
            whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any())).thenReturn(emptyList())

            worker.doWork()

            verify(persistenceLayer).getBgReadingsDataFromTimeToTime(sensorChange, now, true)
        }
    }

    @Test
    fun `When the correction is unchanged the stored values are not read`() {
        runTest {
            setUpCorrection(lastSlope = 1.0, lastOffset = -20.0, slope = 1.0, offset = -20.0)

            worker.doWork()

            verify(persistenceLayer, never()).getBgReadingsDataFromTimeToTime(any(), any(), any())
            verify(preferences, never()).put(eq(XdripDoubleNonKey.LastCorrectionOffset), any<Double>())
        }
    }

    @Test
    fun `When the last correction is not known it is only remembered`() {
        runTest {
            // first value after the update: nothing is known about the stored values, so nothing is changed
            setUpCorrection(lastSlope = 0.0, lastOffset = 0.0, slope = 1.0, offset = -20.0)

            worker.doWork()

            verify(persistenceLayer, never()).getBgReadingsDataFromTimeToTime(any(), any(), any())
            verify(preferences).put(XdripDoubleNonKey.LastCorrectionOffset, -20.0)
        }
    }

    private fun validBundle(timestamp: Long, bg: Double) = BundleMock.mocked().apply {
        putString(Intents.XDRIP_DATA_SOURCE, "G6 Native")
        putLong(Intents.EXTRA_TIMESTAMP, timestamp)
        putLong(Intents.EXTRA_SENSOR_STARTED_AT, timestamp)
        putDouble(Intents.EXTRA_BG_ESTIMATE, bg)
        putDouble(Intents.EXTRA_RAW, bg)
        putString(Intents.EXTRA_BG_SLOPE_NAME, "Flat")
    }
}
