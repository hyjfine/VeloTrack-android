package com.velotrack.velotrack

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Reads device heading from the rotation-vector sensor.
 *
 * Returned heading uses map/navigation convention: 0° = north, 90° = east.
 * It is only a device-orientation heading; route bearing remains the fallback in [MapPane].
 */
@Composable
fun rememberDeviceHeadingDegrees(enabled: Boolean): Float? {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var headingDegrees by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(context, lifecycleOwner, enabled) {
        if (!enabled) {
            headingDegrees = null
            return@DisposableEffect onDispose { }
        }

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            ?: return@DisposableEffect onDispose { headingDegrees = null }
        val rotationMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        fun displayRotation(): Int {
            @Suppress("DEPRECATION")
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            return windowManager.defaultDisplay.rotation
        }

        fun updateHeading(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val success = when (displayRotation()) {
                Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    SensorManager.AXIS_Y,
                    SensorManager.AXIS_MINUS_X,
                    remappedMatrix,
                )
                Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    SensorManager.AXIS_MINUS_X,
                    SensorManager.AXIS_MINUS_Y,
                    remappedMatrix,
                )
                Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    SensorManager.AXIS_MINUS_Y,
                    SensorManager.AXIS_X,
                    remappedMatrix,
                )
                else -> {
                    System.arraycopy(rotationMatrix, 0, remappedMatrix, 0, rotationMatrix.size)
                    true
                }
            }
            if (!success) return
            SensorManager.getOrientation(remappedMatrix, orientation)
            val azimuthRad = orientation[0]
            val azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
            // Sensor azimuth and map marker rotation use opposite rotation directions on our marker.
            // The marker artwork's forward zero also needs a 180° offset. Route-bearing fallback stays unchanged.
            headingDegrees = normalizeDegrees(180f - azimuthDeg)
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = updateHeading(event)
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        fun register() {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }

        fun unregister() {
            sensorManager.unregisterListener(listener)
            headingDegrees = null
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE -> unregister()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            register()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregister()
        }
    }

    return headingDegrees
}

private fun normalizeDegrees(value: Float): Float = ((value % 360f) + 360f) % 360f

