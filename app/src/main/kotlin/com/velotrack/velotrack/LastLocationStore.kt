package com.velotrack.velotrack

import android.content.Context
import androidx.core.content.edit

class LastLocationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("last_location", Context.MODE_PRIVATE)

    fun read(): GpsPoint? {
        if (!prefs.contains(KEY_LAT) || !prefs.contains(KEY_LNG)) return null
        val timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
        val storedAt = prefs.getLong(KEY_STORED_AT, timestamp)
        val cacheAgeMs = System.currentTimeMillis() - storedAt
        if (timestamp <= 0L || storedAt <= 0L || cacheAgeMs !in 0..MAX_CACHE_AGE_MS) {
            clear()
            return null
        }
        val accuracy = prefs.getFloat(KEY_ACCURACY, 0f).toDouble()
        if (accuracy <= 0.0) {
            clear()
            return null
        }
        return GpsPoint(
            lat = Double.fromBits(prefs.getLong(KEY_LAT, 0L)),
            lng = Double.fromBits(prefs.getLong(KEY_LNG, 0L)),
            timestamp = timestamp,
            speedMps = 0.0,
            altitude = if (prefs.contains(KEY_ALTITUDE)) {
                Double.fromBits(prefs.getLong(KEY_ALTITUDE, 0L))
            } else {
                null
            },
            accuracy = accuracy,
            isCached = true,
        )
    }

    fun write(point: GpsPoint) {
        if (point.isCached) return
        if (point.accuracy <= 0.0 || point.accuracy > MAX_CACHE_ACCURACY_M) return
        prefs.edit {
            putLong(KEY_LAT, point.lat.toBits())
            putLong(KEY_LNG, point.lng.toBits())
            putLong(KEY_TIMESTAMP, point.timestamp)
            putLong(KEY_STORED_AT, System.currentTimeMillis())
            putFloat(KEY_ACCURACY, point.accuracy.toFloat())
            if (point.altitude != null) {
                putLong(KEY_ALTITUDE, point.altitude.toBits())
            } else {
                remove(KEY_ALTITUDE)
            }
        }
    }

    private fun clear() {
        prefs.edit { clear() }
    }

    private companion object {
        const val MAX_CACHE_AGE_MS = 5 * 60 * 1000L
        const val MAX_CACHE_ACCURACY_M = 200.0
        const val KEY_LAT = "lat"
        const val KEY_LNG = "lng"
        const val KEY_TIMESTAMP = "timestamp"
        const val KEY_STORED_AT = "stored_at"
        const val KEY_ALTITUDE = "altitude"
        const val KEY_ACCURACY = "accuracy"
    }
}
