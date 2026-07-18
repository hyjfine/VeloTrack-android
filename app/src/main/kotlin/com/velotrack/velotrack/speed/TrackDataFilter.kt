package com.velotrack.velotrack.speed

import com.velotrack.velotrack.GeoUtils
import com.velotrack.velotrack.GpsPoint
import com.velotrack.velotrack.tracking.TrackingPolicy
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
    const val MAX_PLAUSIBLE_SPEED_MPS = TrackingPolicy.MAX_PLAUSIBLE_SPEED_MPS

    private const val MIN_SEGMENT_DISTANCE_BASE_M = 2.0
    private const val MIN_SEGMENT_DISTANCE_ACC_FACTOR = 0.3

    /** A→B→C 绕路倍数；骑行轨迹通常接近直线，尖峰会明显绕路。 */
    private const val SPIKE_PATH_RATIO = 1.8

    /** 孤立尖峰：与前后都很近但中间点漂移很远（米）。 */
    private const val SPIKE_MIN_DETOUR_M = 8.0

    /** 录制时单步位移超过 max(acc)×此系数视为野点（仅 dt 无效时兜底）。 */
    private const val OUTLIER_JUMP_ACC_FACTOR = 1.5

    /** 录制时最小可判定的异常跳变（米），仅用于无有效 dt 的兜底判断。 */
    private const val OUTLIER_MIN_JUMP_M = 12.0

    /** 速度一致时允许的位移裕量（相对理论最大位移）。 */
    private const val OUTLIER_SPEED_MARGIN = 1.15

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

    private data class ValidSegment(
        val distanceM: Double,
        val dtMs: Long,
        val speedMps: Double,
    )

    private data class IndexedValidSegment(
        val fromIndex: Int,
        val toIndex: Int,
        val value: ValidSegment,
    )

    /**
     * 录制时与最近已入库点比对，返回丢弃原因；null 表示位置合理。
     *
     * 有效 dt 内仅按段速上限判断，避免合法骑行速度触发固定跳变阈值导致「死亡螺旋」。
     */
    fun rejectReasonForCandidate(recentAccepted: List<GpsPoint>, candidate: GpsPoint): String? {
        if (recentAccepted.isEmpty()) return null
        val previous = recentAccepted.last()
        val dist = GeoUtils.haversineMeters(previous.lat, previous.lng, candidate.lat, candidate.lng)
        val dtMs = segmentDtMs(previous, candidate)
        if (dtMs > 0L && dtMs <= TrackingPolicy.MAX_CONTIGUOUS_SEGMENT_GAP_MS) {
            val speed = dist / (dtMs / 1000.0)
            if (speed > MAX_PLAUSIBLE_SPEED_MPS) {
                return "outlier: speed>${MAX_PLAUSIBLE_SPEED_MPS.toInt()}m/s"
            }
            return null
        }
        val dtSec = if (dtMs > 0L) dtMs / 1000.0 else 1.0
        val accJumpM = max(previous.accuracy, candidate.accuracy) * OUTLIER_JUMP_ACC_FACTOR
        val speedJumpM = MAX_PLAUSIBLE_SPEED_MPS * dtSec * OUTLIER_SPEED_MARGIN
        val thresholdM = max(accJumpM, speedJumpM)
        if (dist > thresholdM && dist >= OUTLIER_MIN_JUMP_M) {
            return "outlier: jump>${thresholdM.toInt()}m"
        }
        return null
    }

    /**
     * 地图绘制分段。显式 [GpsPoint.segmentId] 变化、时间断层或不可能速度均会断线。
     * 这也能在不改写旧数据的前提下修复历史缓存首点造成的跨城连线。
     */
    fun routeSegments(points: List<GpsPoint>): List<List<GpsPoint>> {
        if (points.isEmpty()) return emptyList()
        val segments = mutableListOf<MutableList<GpsPoint>>()
        var current = mutableListOf(points.first())
        segments += current
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val point = points[i]
            if (isRouteBreak(previous, point)) {
                current = mutableListOf()
                segments += current
            }
            current += point
        }
        return segments
    }

    /** 将旧版平面点列中的不可能跨段固化为连续 segmentId，供一次性历史修复使用。 */
    fun withInferredSegments(points: List<GpsPoint>): List<GpsPoint> {
        if (points.isEmpty()) return emptyList()
        val spikes = spikeIndices(points)
        var segmentId = 0
        return points.mapIndexed { index, point ->
            if (index > 0 && index - 1 !in spikes && index !in spikes &&
                isRouteBreak(points[index - 1], point)
            ) {
                segmentId++
            }
            point.copy(segmentId = segmentId)
        }
    }

    fun rejectReasonForCandidate(previous: GpsPoint?, candidate: GpsPoint): String? =
        rejectReasonForCandidate(
            recentAccepted = if (previous == null) emptyList() else listOf(previous),
            candidate = candidate,
        )

    /** 实时地图展示：剔除尖峰点，与 [summarize] 统计口径一致。 */
    fun filterForDisplay(points: List<GpsPoint>): List<GpsPoint> =
        displaySnapshot(points).points

    fun displaySnapshot(points: List<GpsPoint>): DisplaySnapshot {
        if (points.isEmpty()) {
            return DisplaySnapshot(emptyList(), 0.0, 0)
        }
        val spikes = spikeIndices(points)
        val displayPoints = if (spikes.isEmpty()) {
            points
        } else {
            points.filterIndexed { index, _ -> index !in spikes }
        }
        val totalDistance = validSegmentsAfterSpikeRemoval(points, spikes).sumOf { it.value.distanceM }
        return DisplaySnapshot(
            points = displayPoints,
            totalDistanceM = totalDistance,
            spikePointCount = spikes.size,
        )
    }

    /** 将长轨迹限制到地图可承受的点数，始终保留首尾点。 */
    fun downsampleForMap(points: List<GpsPoint>, maxPoints: Int = 2_000): List<GpsPoint> {
        require(maxPoints >= 2)
        var result = points
        while (result.size > maxPoints) {
            result = result.filterIndexed { index, _ ->
                index == 0 || index == result.lastIndex || index % 2 == 0
            }
        }
        return result
    }

    fun summarize(points: List<GpsPoint>): Summary {
        if (points.isEmpty()) {
            return Summary(0.0, 0.0, 0.0, 0.0, emptyList(), 0)
        }
        val spikes = spikeIndices(points)
        val segments = validSegmentsAfterSpikeRemoval(points, spikes)
        val segmentSpeeds = segments.map { it.value.speedMps }
        val totalDistance = segments.sumOf { it.value.distanceM }
        val movingMs = segments
            .filter { it.value.speedMps > SpeedEstimator.STANDSTILL_SPEED_MPS }
            .sumOf { it.value.dtMs }
        val avgSpeed = if (movingMs > 0L) totalDistance / (movingMs / 1000.0) else 0.0
        val maxSpeed = robustMaxSpeed(segmentSpeeds)
        val chartSpeeds = medianFilterBySegment(
            values = sanitizePointSpeeds(points, spikes, segments),
            points = points,
            spikes = spikes,
            window = CHART_MEDIAN_WINDOW,
        )
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

    fun spikeIndices(points: List<GpsPoint>): Set<Int> {
        if (points.size < 3) return emptySet()
        val spikes = mutableSetOf<Int>()
        for (i in 1 until points.size - 1) {
            if (isPositionSpike(points[i - 1], points[i], points[i + 1])) {
                spikes.add(i)
            }
        }
        return spikes
    }

    fun isPositionSpike(a: GpsPoint, b: GpsPoint, c: GpsPoint): Boolean {
        if (a.segmentId != b.segmentId || b.segmentId != c.segmentId) return false
        val dAb = GeoUtils.haversineMeters(a.lat, a.lng, b.lat, b.lng)
        val dBc = GeoUtils.haversineMeters(b.lat, b.lng, c.lat, c.lng)
        val dAc = GeoUtils.haversineMeters(a.lat, a.lng, c.lat, c.lng)
        val detour = dAb + dBc
        if (detour < SPIKE_MIN_DETOUR_M) return false
        val pathRatio = if (dAc < 1.0) detour / max(dAc, 1.0) else detour / dAc
        if (pathRatio < SPIKE_PATH_RATIO) return false
        // 尖峰判定必须使用未应用速度上限的原始段速，否则超速段会先变成 null。
        val vAb = rawSegmentSpeedMps(a, b)
        val vBc = rawSegmentSpeedMps(b, c)
        return (vAb != null && vAb > MAX_PLAUSIBLE_SPEED_MPS) ||
            (vBc != null && vBc > MAX_PLAUSIBLE_SPEED_MPS) ||
            pathRatio >= SPIKE_PATH_RATIO + 0.5
    }

    // -----------------------------------------------------------------
    // 有效段
    // -----------------------------------------------------------------

    /** 与实时累计距离相同的段有效性口径。 */
    fun validSegmentDistanceMeters(a: GpsPoint, b: GpsPoint): Double? {
        return validSegment(a, b)?.distanceM
    }

    /** 剔除尖峰后重新连接相邻有效点，避免 A→B→C 中移除 B 时漏掉真实的 A→C。 */
    private fun validSegmentsAfterSpikeRemoval(
        points: List<GpsPoint>,
        spikes: Set<Int>,
    ): List<IndexedValidSegment> {
        if (points.size < 2) return emptyList()
        val out = ArrayList<IndexedValidSegment>(points.size - 1)
        var previousIndex: Int? = null
        points.forEachIndexed { index, point ->
            if (index in spikes) return@forEachIndexed
            previousIndex?.let { fromIndex ->
                validSegment(points[fromIndex], point)?.let { segment ->
                    out += IndexedValidSegment(fromIndex, index, segment)
                }
            }
            previousIndex = index
        }
        return out
    }

    fun segmentSpeedMps(a: GpsPoint, b: GpsPoint): Double? {
        return validSegment(a, b)?.speedMps
    }

    private fun rawSegmentSpeedMps(a: GpsPoint, b: GpsPoint): Double? {
        if (a.segmentId != b.segmentId) return null
        val dtMs = segmentDtMs(a, b)
        if (dtMs < TrackingPolicy.MIN_DERIVED_SEGMENT_DT_MS ||
            dtMs > TrackingPolicy.MAX_CONTIGUOUS_SEGMENT_GAP_MS
        ) {
            return null
        }
        val distanceM = GeoUtils.haversineMeters(a.lat, a.lng, b.lat, b.lng)
        return distanceM / (dtMs / 1000.0)
    }

    private fun validSegment(a: GpsPoint, b: GpsPoint): ValidSegment? {
        if (a.segmentId != b.segmentId) return null
        val dtMs = segmentDtMs(a, b)
        if (dtMs < TrackingPolicy.MIN_DERIVED_SEGMENT_DT_MS ||
            dtMs > TrackingPolicy.MAX_CONTIGUOUS_SEGMENT_GAP_MS
        ) {
            return null
        }
        val dist = GeoUtils.haversineMeters(a.lat, a.lng, b.lat, b.lng)
        val minDist = max(
            MIN_SEGMENT_DISTANCE_BASE_M,
            max(a.accuracy, b.accuracy) * MIN_SEGMENT_DISTANCE_ACC_FACTOR,
        )
        if (dist < minDist) return null
        val v = dist / (dtMs / 1000.0)
        if (v > MAX_PLAUSIBLE_SPEED_MPS) return null
        return ValidSegment(distanceM = dist, dtMs = dtMs, speedMps = v)
    }

    private fun isRouteBreak(a: GpsPoint, b: GpsPoint): Boolean {
        if (a.segmentId != b.segmentId) return true
        val dtMs = segmentDtMs(a, b)
        if (dtMs <= 0L || dtMs > TrackingPolicy.MAX_CONTIGUOUS_SEGMENT_GAP_MS) return true
        val distanceM = GeoUtils.haversineMeters(a.lat, a.lng, b.lat, b.lng)
        return distanceM / (dtMs / 1000.0) > MAX_PLAUSIBLE_SPEED_MPS
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

    private fun sanitizePointSpeeds(
        points: List<GpsPoint>,
        spikes: Set<Int>,
        segments: List<IndexedValidSegment>,
    ): List<Double> {
        if (points.isEmpty()) return emptyList()
        val derived = DoubleArray(points.size) { Double.NaN }
        segments.forEach { segment ->
            val speed = segment.value.speedMps
            // 后一个点先取上一段；其下一段有效时会再被前向速度覆盖。
            if (derived[segment.toIndex].isNaN()) derived[segment.toIndex] = speed
            derived[segment.fromIndex] = speed
        }
        return List(points.size) { i ->
            if (i in spikes) return@List 0.0
            val fromSeg = derived[i].takeUnless(Double::isNaN)
            // 历史数据没有持久化速度可信度；没有同段位移依据时不能继续采用旧 speedMps。
            if (fromSeg == null) return@List 0.0
            val stored = points[i].speedMps.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            when {
                fromSeg > MAX_PLAUSIBLE_SPEED_MPS -> 0.0
                stored > MAX_PLAUSIBLE_SPEED_MPS -> fromSeg
                fromSeg > 0.0 && abs(stored - fromSeg) / fromSeg > STORED_SPEED_MAX_RATIO -> fromSeg
                else -> stored.coerceIn(0.0, MAX_PLAUSIBLE_SPEED_MPS)
            }.coerceAtLeast(0.0)
        }
    }

    private fun medianFilterBySegment(
        values: List<Double>,
        points: List<GpsPoint>,
        spikes: Set<Int>,
        window: Int,
    ): List<Double> {
        if (values.isEmpty() || window <= 1) return values
        val half = window / 2
        return values.mapIndexed { i, _ ->
            if (i in spikes) return@mapIndexed 0.0
            val slice = (max(0, i - half) until min(values.size, i + half + 1))
                .filter { index -> index !in spikes && points[index].segmentId == points[i].segmentId }
                .map(values::get)
                .sorted()
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
