package app.aaps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSSMB.StepService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Registers the step-counter sensor ([StepService]) that feeds the AutoISF activity monitor - including the existing
 * "steps_graph_from_smartphone" preference (BooleanKey.ActivityMonitorShowStepsFromSmartphone),
 * which is meant to let a user without a paired watch feed step data from the phone instead. Neither
 * worked because the sensor was never actually registered.
 *
 * It requires `ACTIVITY_RECOGNITION` on Android 10+. The app only asks for it through the
 * Permissions sheet, which the user can act on at any point in the app's lifetime - not only at
 * process start - so this is re-tried on every resume ([ComposeMainActivity.refreshOnResume])
 * rather than once in [MainApp.doInit], which would otherwise leave the sensors unregistered until
 * the process happens to restart after the grant.
 */
@Inject
@SingleIn(AppScope::class)
class ActivityMonitoringRegistrar(
    private val aapsLogger: AAPSLogger
) {

    @Volatile private var registered = false

    fun registerIfPermitted(context: Context) {
        if (registered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) return

        registerStepCounter(context)
        registered = true
    }

    private fun registerStepCounter(context: Context) {
        try {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (stepSensor != null) {
                sensorManager.registerListener(StepService, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
                aapsLogger.debug(LTag.CORE, "Step counter sensor registered")
            } else {
                aapsLogger.warn(LTag.CORE, "Step counter sensor not available on this device")
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Failed to register step counter sensor", e)
        }
    }
}
