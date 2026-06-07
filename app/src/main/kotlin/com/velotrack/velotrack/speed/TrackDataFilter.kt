package com.velotrack.velotrack.speed

import com.velotrack.velotrack.GeoUtils
import com.velotrack.velotrack.GpsPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 轨迹后处理：过滤 GPS 位置尖峰与异常 [GpsPoint.speedMps]，供统计、详情曲线与落库汇总共用。
 *
 * ## 算法（四层）
 *
 * 1. **段速有效性**（与 [SpeedEstimator] 录制口径对齐）
 *    - dt ∈ [700ms, 10s]（monotonic 优先）
 *    - 位移 ≥ max(2m, 0.3×accuracy)
 *    - 段速 ≤ 25 m/s（≈90 km/h）
 *
 * 2. **位置尖峰**（经典 detour 检测）
 *    - 对中间点 B：若 dist(A,B)+dist(B,C) > 1.8×dist(A,C) 且任一侧段速超上限 → 标记 B 为尖峰点
 *    - 尖峰点不参与距离累加、不参与 max/avg 段速
 *
 * 3. **逐点速度清洗**
 *    - 用相邻有效段导出的速度替换离谱的 `speedMps`（>|上限| 或与导数偏差 >60%）
 *    - 供 PERFORMANCE 折线图使用，避免单点脏值拉高纵轴
 *
 * 4. **稳健极值**
 *    - 最大速度：仅在有效段速上做 5 点滑动平均的最大值（抑制单段毛刺）
 *    - 图表序列：对清洗后速度做 3 点中值滤波
 */
object TrackDataFilter {

    /** 与 SpeedEstimator 一致：约 90 km/h。 */
    const val MAX_PLAUSIBLE_SPEED_MPS = 25.0

    private const val MAX_SEGMENT_GAP_MS = 10_000L
    private const val MIN_SEGMENT_DT_MS = 700L
    private const val MIN_SEGMENT_DISTANCE_BASE_M = 2.0
    private const val MIN_SEGMENT_DISTANCE_ACC_FACTOR = 0.3

    /** A→B→C 绕路倍数；骑行轨迹通常接近直线，尖峰会明显绕路。 */
    private const val SPIKE_PATH_RATIO = 1.8

    /** 孤立尖峰：与前后都很近但中间点漂移很远（米）。 */
    private const val SPIKE_MIN_DETOUR_M = 8.0

    /** 录制时单步位移超过 max(acc)×此系数视为野点。 */
    private const val OUTLIER_JUMP_ACC_FACTOR = 1.5

    /** 录制时最小可判定的异常跳变（米）。 */
    private const val OUTLIER_MIN_JUMP_M = 6.0

    private const val STORED_SPEED_MAX_RATIO = 0.6
    private const val SLIDING_MAX_WINDOW = 5
    private const val CHART_MEDIAN_WINDOW = 3

    data class Summary(
        val totalDistanceM: Double,
        val movingDurationSec: Double,
        val avgSpeedMps: Double,
        val maxSpeedMps: Double,
        /** 与 points 等长；用于详情 PERFORMANCE 图（km/h 由 UI 换算）。 */
        val chartSpeedMps: List<Double>,
        val spikePointCount: Int,
    )

    /** 实时录制 HUD/地图展示：一次尖峰扫描同时产出折线与距离。 */
    data class DisplaySnapshot(
        val points: List<GpsPoint>,
        val totalDistanceM: Double,
        val spikePointCount: Int,
    )

    /**
     * 录制时与上一已入库点比对，返回丢弃原因；null 表示位置合理。
     */
    fun rejectReasonForCandidate(previous: GpsPoint?, candidate: GpsPoint): String? {
        if (previous == null) return null
        val dist = GeoUtils.haversineMeters(previous.lat, previous.lng, candidate.lat, candidate.lng)
        val dtMs = segmentDtMs(previous, candidate)
        if (dtMs > 0L && dtMs <= MAX_SEGMENT_GAP_MS) {
            val speed = dist / (dtMs / 1000.0)
            if (speed > MAX_PLAUSIBLE_SPEED_MPS) {
                return "outlier: speed>${MAX_PLAUSIBLE_SPEED_MPS.toInt()}m/s"
            }
        }
        val maxJumpM = max(previous.accuracy, candidate.accuracy) * OUTLIER_JUMP_ACC_FACTOR
        if (dist > maxJumpM && dist >= OUTLIER_MIN_JUMP_M) {
            return "outlier: jump>${maxJumpM.toInt()}m"
        }
        return null
    }

    /** 实时地图展示：剔除尖峰点，与 [summarize] 统计口径一致。 */
    fun filterForDisplay(points: List<GpsPoint>): List<GpsPoint> =
        displaySnapshot(points).points

    fun displaySnapshot(points: List<GpsPoint>): DisplaySnapshot {
        if (points.isEmpty()) {
            return DisplaySnapshot(emptyList(), 0.0, 0)
        }
        val spikes = if (points.size >= 3) detectSpikeIndices(points) else emptySet()
        val displayPoints = if (spikes.isEmpty()) {
            points
        } else {
            points.filterIndexed { index, _ -> index !in spikes }
        }
        val totalDistance = validSegmentDistances(points, spikes).sum()
        return DisplaySnapshot(
            points = displayPoints,
            totalDistanceM = totalDistance,
            spikePointCount = spikes.size,
        )
    }

    fun summarize(points: List<GpsPoint>): Summary {
        if (points.isEmpty()) {
            return Summary(0.0, 0.0, 0.0, 0.0, emptyList(), 0)
        }
        val spikes = detectSpikeIndices(points)
        val segmentSpeeds = validSegmentSpeeds(points, spikes)
        val totalDistance = validSegmentDistances(points, spikes).sum()
        val movingMs = validMovingDurationMs(points, spikes)
        val avgSpeed = if (movingMs > 0L) totalDistance / (movingMs / 1000.0) else 0.0
        val maxSpeed = robustMaxSpeed(segmentSpeeds)
        val chartSpeeds = medianFilter(sanitizePointSpeeds(points, spikes), CHART_MEDIAN_WINDOW)
        return Summary(
            totalDistanceM = totalDistance,
            movingDurationSec = movingMs / 1000.0,
            avgSpeedMps = avgSpeed,
            maxSpeedMps = maxSpeed,
            chartSpeedMps = chartSpeeds,
            spikePointCount = spikes.size,
        )
    }

    // -----------------------------------------------------------------
    // 尖峰检测
    // -----------------------------------------------------------------

    private fun detectSpikeIndices(points: List<GpsPoint>): Set<Int> {
        if (points.size < 3) return emptySet()
        val spikes = mutableSetOf<Int>()
        for (i in 1 until points.size - 1) {
            if (isPositionSpike(points[i - 1], points[i], points[i + 1])) {
                spikes.add(i)
            }
        }
        return spikes
    }

    private fun isPositionSpike(a: GpsPoint, b: GpsPoint, c: GpsPoint): Boolean {
        val dAb = GeoUtils.haversineMeters(a.lat, a.lng, b.lat, b.lng)
        val dBc = GeoUtils.haversineMeters(b.lat, b.lng, c.lat, c.lng)
        val dAc = GeoUtils.haversineMeters(a.lat, a.lng, c.lat, c.lng)
        val detour = dAb + dBc
        if (detour < SPIKE_MIN_DETOUR_M) return false
        val pathRatio = if (dAc < 1.0) detour / max(dAc, 1.0) else detour / dAc
        if (pathRatio < SPIKE_PATH_RATIO) return false
        val vAb = segmentSpeedMps(a, b)
        val vBc = segmentSpeedMps(b, c)
        return (vAb != null && vAb > MAX_PLAUSIBLE_SPEED_MPS) ||
            (vBc != null && vBc > MAX_PLAUSIBLE_SPEED_MPS) ||
            pathRatio >= SPIKE_PATH_RATIO + 0.5
    }

    // -----------------------------------------------------------------
    // 有效段
    // -----------------------------------------------------------------

    private fun validSegmentSpeeds(points: List<GpsPoint>, spikes: Set<Int>): List<Double> {
        if (points.size < 2) return emptyList()
        val out = ArrayList<Double>(points.size - 1)
        for (i in 1 until points.size) {
            if (i - 1 in spikes || i in spikes) continue
            segmentSpeedMps(points[i - 1], points[i])?.let { out.add(it) }
        }
        return out
    }

    private fun validSegmentDistances(points: List<GpsPoint>, spikes: Set<Int>): List<Double> {
        if (points.size < 2) return emptyList()
        val out = ArrayList<Double>(points.size - 1)
        for (i in 1 until points.size) {
            if (i - 1 in spikes || i in spikes) continue
            val a = points[i - 1]
            val b = points[i]
            val dtMs = segmentDtMs(a, b)
            if (dtMs <= 0L || dtMs > MAX_SEGMENT_GAP_MS) continue
            out.add(GeoUtils.haversineMeters(a.lat, a.lng, b.lat, b.lng))
        }
        return out
    }

    private fun validMovingDurationMs(points: List<GpsPoint>, spikes: Set<Int>): Long {
        if (points.size < 2) return 0L
        var totalMs = 0L
        for (i in 1 until points.size) {
            if (i - 1 in spikes || i in spikes) continue
            val a = points[i - 1]
            val b = points[i]
            val dtMs = segmentDtMs(a, b)
            if (dtMs <= 0L || dtMs > MAX_SEGMENT_GAP_MS) continue
            val dist = GeoUtils.haversineMeters(a.lat, a.lng, b.lat, b.lng)
            val v = dist / (dtMs / 1000.0)
            if (v > SpeedEstimator.STANDSTILL_SPEED_MPS) totalMs += dtMs
        }
        return totalMs
    }

    fun segmentSpeedMps(a: GpsPoint, b: GpsPoint): Double? {
        val dtMs = segmentDtMs(a, b)
        if (dtMs < MIN_SEGMENT_DT_MS || dtMs > MAX_SEGMENT_GAP_MS) return null
        val dist = GeoUtils.haversineMeters(a.lat, a.lng, b.lat, b.lng)
        val minDist = max(
            MIN_SEGMENT_DISTANCE_BASE_M,
            max(a.accuracy, b.accuracy) * MIN_SEGMENT_DISTANCE_ACC_FACTOR,
        )
        if (dist < minDist) return null
        val v = dist / (dtMs / 1000.0)
        return if (v > MAX_PLAUSIBLE_SPEED_MPS) null else v
    }

    private fun segmentDtMs(a: GpsPoint, b: GpsPoint): Long =
        if (a.monotonicMs > 0L && b.monotonicMs > 0L) {
            b.monotonicMs - a.monotonicMs
        } else {
            b.timestamp - a.timestamp
        }

    // -----------------------------------------------------------------
    // 逐点速度清洗 + 图表
    // -----------------------------------------------------------------

    private fun sanitizePointSpeeds(points: List<GpsPoint>, spikes: Set<Int>): List<Double> {
        if (points.isEmpty()) return emptyList()
        val derived = DoubleArray(points.size) { Double.NaN }
        for (i in 1 until points.size) {
            if (i - 1 in spikes || i in spikes) continue
            segmentSpeedMps(points[i - 1], points[i])?.let { derived[i] = it }
        }
        for (i in 0 until points.size - 1) {
            if (i in spikes || i + 1 in spikes) continue
            segmentSpeedMps(points[i], points[i + 1])?.let { derived[i] = it }
        }
        return List(points.size) { i ->
            if (i in spikes) return@List 0.0
            val fromSeg = when {
                !derived[i].isNaN() -> derived[i]
                i > 0 && !derived[i - 1].isNaN() -> derived[i - 1]
                else -> null
            }
            val stored = points[i].speedMps
            val candidate = fromSeg ?: stored
            when {
                candidate > MAX_PLAUSIBLE_SPEED_MPS -> fromSeg?.coerceAtMost(MAX_PLAUSIBLE_SPEED_MPS) ?: 0.0
                fromSeg == null -> stored.coerceIn(0.0, MAX_PLAUSIBLE_SPEED_MPS)
                stored > MAX_PLAUSIBLE_SPEED_MPS -> fromSeg
                fromSeg > 0.0 && abs(stored - fromSeg) / fromSeg > STORED_SPEED_MAX_RATIO -> fromSeg
                else -> stored.coerceIn(0.0, MAX_PLAUSIBLE_SPEED_MPS)
            }.coerceAtLeast(0.0)
        }
    }

    private fun medianFilter(values: List<Double>, window: Int): List<Double> {
        if (values.isEmpty() || window <= 1) return values
        val half = window / 2
        return values.mapIndexed { i, _ ->
            val slice = values.subList(max(0, i - half), min(values.size, i + half + 1)).sorted()
            slice[slice.size / 2]
        }
    }

    private fun robustMaxSpeed(segmentSpeeds: List<Double>): Double {
        if (segmentSpeeds.isEmpty()) return 0.0
        val window = SLIDING_MAX_WINDOW
        if (segmentSpeeds.size < window) return segmentSpeeds.max()
        var sum = 0.0
        for (i in 0 until window) sum += segmentSpeeds[i]
        var maxAvg = sum / window
        for (i in window until segmentSpeeds.size) {
            sum += segmentSpeeds[i] - segmentSpeeds[i - window]
            val avg = sum / window
            if (avg > maxAvg) maxAvg = avg
        }
        return maxAvg.coerceAtMost(MAX_PLAUSIBLE_SPEED_MPS)
    }
}
