package com.velotrack.velotrack.recording

import com.velotrack.velotrack.GpsPoint

/** Access only from the serialized database queue. A failed write never acknowledges points. */
class DraftWriteBuffer(initialPointCount: Int = 0) {
    var acknowledgedPointCount: Int = initialPointCount
        private set

    fun flush(
        points: List<GpsPoint>,
        ensureDraft: () -> Unit,
        append: (Int, List<GpsPoint>) -> Unit,
    ) {
        require(points.size >= acknowledgedPointCount)
        ensureDraft()
        val start = acknowledgedPointCount
        append(start, points.subList(start, points.size))
        acknowledgedPointCount = points.size
    }
}
