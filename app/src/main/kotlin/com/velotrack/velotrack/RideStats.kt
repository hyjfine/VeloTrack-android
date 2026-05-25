package com.velotrack.velotrack

/**
 * 骑行统计口径（与 [TrackViewModel] 停止录制时一致）。
 */
object RideStats {
    private const val MOVING_THRESHOLD_MPS = 0.5
    private const val MAX_SPEED_WINDOW = 5
    private const val MAX_SEGMENT_GAP_MS = 10_000L

    fun totalDistanceMeters(points: List<GpsPoint>): Double =
        points.zipWithNext()
            .sumOf { (a, b) -> GeoUtils.haversineMeters(a.lat, a.lng, b.lat, b.lng) }

    fun movingDurationSeconds(points: List<GpsPoint>): Double {
        if (points.size < 2) return 0.0
        var totalMs = 0L
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dtMs = b.timestamp - a.timestamp
            if (dtMs <= 0L || dtMs > MAX_SEGMENT_GAP_MS) continue
            val dist = GeoUtils.haversineMeters(a.lat, a.lng, b.lat, b.lng)
            val v = dist / (dtMs / 1000.0)
            if (v > MOVING_THRESHOLD_MPS) totalMs += dtMs
        }
        return totalMs / 1000.0
    }

    fun avgSpeedMps(points: List<GpsPoint>): Double {
        val totalDistance = totalDistanceMeters(points)
        val movingTimeSec = movingDurationSeconds(points)
        return if (movingTimeSec > 0.0) totalDistance / movingTimeSec else 0.0
    }

    fun maxSpeedMps(points: List<GpsPoint>): Double = slidingWindowMaxSpeed(points, MAX_SPEED_WINDOW)

    private fun segmentSpeedsMps(points: List<GpsPoint>): List<Double> {
        if (points.size < 2) return emptyList()
        val out = ArrayList<Double>(points.size - 1)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dtMs = b.timestamp - a.timestamp
            if (dtMs <= 0L || dtMs > MAX_SEGMENT_GAP_MS) continue
            val dist = GeoUtils.haversineMeters(a.lat, a.lng, b.lat, b.lng)
            out += dist / (dtMs / 1000.0)
        }
        return out
    }

    private fun slidingWindowMaxSpeed(points: List<GpsPoint>, window: Int): Double {
        val speeds = segmentSpeedsMps(points)
        if (speeds.isEmpty()) return 0.0
        if (speeds.size < window) return speeds.max()
        var sum = 0.0
        for (i in 0 until window) sum += speeds[i]
        var maxAvg = sum / window
        for (i in window until speeds.size) {
            sum += speeds[i] - speeds[i - window]
            val avg = sum / window
            if (avg > maxAvg) maxAvg = avg
        }
        return maxAvg
    }
}
