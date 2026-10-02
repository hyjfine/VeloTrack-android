package com.velotrack.velotrack

import com.velotrack.velotrack.speed.TrackDataFilter

/** Prepared on a worker thread once per loaded ride; drawing never scans the raw track. */
data class RidePresentationData(val mapPoints: List<GpsPoint>, val chart: List<ChartPoint>) {
    data class ChartPoint(val elapsedMs: Long, val speedKmh: Float, val segmentId: Int)

    companion object {
        fun build(points: List<GpsPoint>, summary: TrackDataFilter.Summary): RidePresentationData {
            val normalized = TrackDataFilter.withInferredSegments(points)
            val mapPoints = TrackDataFilter.downsampleForMap(TrackDataFilter.filterForDisplay(normalized))
            var elapsed = 0L
            val chart = normalized.mapIndexed { index, point ->
                if (index > 0) {
                    val previous = normalized[index - 1]
                    val dt = if (previous.segmentId == point.segmentId &&
                        previous.monotonicMs > 0L && point.monotonicMs > 0L
                    ) point.monotonicMs - previous.monotonicMs else point.timestamp - previous.timestamp
                    elapsed += dt.coerceAtLeast(0L)
                }
                ChartPoint(elapsed, ((summary.chartSpeedMps.getOrNull(index) ?: 0.0) * 3.6).toFloat(), point.segmentId)
            }
            if (chart.size <= 1000) return RidePresentationData(mapPoints, chart)
            // Keep local extrema and all segment endpoints; pauses never become chart bridges.
            val keep = sortedSetOf(0, chart.lastIndex)
            val bucketSize = ((chart.size + 499) / 500).coerceAtLeast(1)
            chart.indices.chunked(bucketSize).forEach { bucket ->
                keep += bucket.minBy { chart[it].speedKmh }
                keep += bucket.maxBy { chart[it].speedKmh }
            }
            for (i in 1 until chart.size) {
                if (chart[i].segmentId != chart[i - 1].segmentId) { keep += i - 1; keep += i }
            }
            return RidePresentationData(mapPoints, keep.map(chart::get))
        }
    }
}
