package com.velotrack.velotrack

/** 保证有 provider fix 单调时间的定位按顺序且至多投递一次。 */
internal class LocationDeliveryGate {
    private var lastFixMonotonicMs = 0L

    fun accept(point: GpsPoint): Boolean {
        val fixTime = point.fixMonotonicMs
        if (fixTime <= 0L) return true
        if (fixTime <= lastFixMonotonicMs) return false
        lastFixMonotonicMs = fixTime
        return true
    }

    companion object {
        fun order(points: List<GpsPoint>): List<GpsPoint> =
            points.sortedWith(
                compareBy<GpsPoint> {
                    it.fixMonotonicMs.takeIf { time -> time > 0L } ?: Long.MAX_VALUE
                }.thenBy { it.timestamp },
            )
    }
}
