package com.velotrack.velotrack.debug

import com.velotrack.velotrack.GnssSatelliteSnapshot
import com.velotrack.velotrack.GpsPoint
import com.velotrack.velotrack.recording.RecordingSessionState
import java.util.Locale

internal object DebugLogFormats {

    fun procLine(state: RecordingSessionState, accepted: Boolean): String {
        val acc = state.lastLocationAccuracyM?.let { "${it.toInt()}m" } ?: "-"
        val ui = state.currentSpeedMps * 3.6
        val drv = state.lastDerivedSpeedMps?.times(3.6)
        val raw = state.lastRawSpeedMps?.times(3.6)
        return buildString {
            append("acc=$acc track=${if (accepted) 1 else 0}")
            append(" ui=").append(formatKmh(ui))
            append(" drv=").append(drv?.let { formatKmh(it) } ?: "-")
            append(" raw=").append(raw?.let { formatKmh(it) } ?: "-")
            append(" m=").append(state.lastSpeedMethod ?: "-")
            append(" src=").append(state.lastSpeedSource ?: "-")
            append(" reason=").append(state.lastLocationDropReason ?: "-")
            append(" pause=").append(state.trackPausedForSignal)
            append(" bad=").append(state.consecutiveBadGpsCount).append("/2")
            append(" good=").append(state.consecutiveGoodGpsCount).append("/3")
            append(" outlier=").append(state.consecutiveTrackOutlierCount).append("/3")
            append(" pts=").append(state.livePoints.size)
        }
    }

    fun gnssLine(snapshot: GnssSatelliteSnapshot): String =
        "use=${snapshot.inUse}/${snapshot.visible} bds=${snapshot.beidouInUse}/${snapshot.beidouVisible} " +
            "gps=${snapshot.gpsInUse} glo=${snapshot.glonassInUse} gal=${snapshot.galileoInUse}"

    fun speedLine(
        rawKmh: Double,
        drvKmh: Double?,
        insKmh: Double,
        uiKmh: Double,
        prevKmh: Double,
        method: String,
        weight: Double,
        segments: Int,
        dtMs: Long,
        point: GpsPoint,
    ): String =
        "raw=${formatKmh(rawKmh)} drv=${drvKmh?.let { formatKmh(it) } ?: "-"} " +
            "ins=${formatKmh(insKmh)} ui=${formatKmh(uiKmh)} prev=${formatKmh(prevKmh)} " +
            "m=$method w=${String.format(Locale.US, "%.2f", weight)} segs=$segments dt=${dtMs}ms " +
            "acc=${String.format(Locale.US, "%.1f", point.accuracy)}m gnss=${point.isGpsFix} src=${point.source.label}"

    private fun formatKmh(kmh: Double): String = String.format(Locale.US, "%.1f", kmh)
}
