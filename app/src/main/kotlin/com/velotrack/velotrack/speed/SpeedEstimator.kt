package com.velotrack.velotrack.speed

import android.util.Log
import com.velotrack.velotrack.GeoUtils
import com.velotrack.velotrack.GpsPoint
import com.velotrack.velotrack.debug.DebugLogFormats
import com.velotrack.velotrack.debug.DebugLogRecorder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 骑行瞬时速度估算。
 *
 * - 位移导数（监控时间窗 + 加权中值）为主
 * - GNSS 多普勒可信时按一致性 + 精度调权融合
 * - 显示层做自适应 EMA 与不对称加/减速限幅
 */
object SpeedEstimator {

    /** 约 1.0 km/h，低于此判定为静止。 */
    const val STANDSTILL_SPEED_MPS = 0.28

    // ---- 段速过滤 ----
    private const val MAX_SEGMENT_GAP_MS = 10_000L

    /** GPS 偶发会推 300ms 间隔的「补点」，dt 太小会把位置抖动放大成几十 km/h。 */
    private const val MIN_SEGMENT_DT_MS = 700L

    /** 基础静止位移阈值（米）；实际取 max(此值, accuracy * 0.3)。 */
    private const val MIN_SEGMENT_DISTANCE_BASE_M = 2.0
    private const val MIN_SEGMENT_DISTANCE_ACC_FACTOR = 0.3

    /** 约 90 km/h，单段尖峰丢弃。 */
    private const val MAX_PLAUSIBLE_SEGMENT_MPS = 25.0

    // ---- 时间窗口（按时间，不按段数） ----
    private const val DERIVED_WINDOW_MS = 3_500L
    private const val DERIVED_MIN_SEGMENTS = 2
    private const val DERIVED_MAX_SEGMENTS = 6

    // ---- 多普勒融合 ----
    private const val DOPPLER_MAX_ACCURACY_M = 15.0
    private const val DOPPLER_MIN_MPS = 0.15

    /** speedAccuracy 已知时优先用；未知时退化到经验权重。 */
    private const val DOPPLER_BASE_WEIGHT = 0.3
    private const val DOPPLER_WEIGHT_MAX = 0.6
    private const val DOPPLER_SPEED_ACC_GOOD = 0.5      // m/s
    private const val DOPPLER_SPEED_ACC_BAD = 2.5       // m/s

    // ---- 显示平滑 ----
    /** 平稳时 α */
    private const val DISPLAY_EMA_ALPHA_CALM = 0.40
    /** 急加/急减时 α，更跟手 */
    private const val DISPLAY_EMA_ALPHA_FAST = 0.80
    /** 触发「快响应」的瞬时变化阈值（m/s） */
    private const val DISPLAY_EMA_FAST_DELTA_MPS = 1.5

    /** 自行车持续加速极限（m/s²） */
    private const val MAX_ACCEL_MPS2 = 2.5
    /** 强刹车（碟刹）极限（m/s²） */
    private const val MAX_DECEL_MPS2 = 6.0

    data class Estimate(
        /** 仪表显示（短 EMA + 加速度限幅） */
        val displaySpeedMps: Double,
        /** 本帧导数中值速度（未 EMA） */
        val derivedSpeedMps: Double?,
        /** 本帧融合后的瞬时速度（导数 + 可选多普勒，未 EMA），入库用 */
        val instantSpeedMps: Double,
        /** 多普勒在融合中的实际权重；0 表示未参与 */
        val dopplerWeight: Double,
        /** derived/fused/doppler/hold-zero */
        val method: String,
        /** 用于 debug：最近一段的 dt 毫秒（无段时为 -1） */
        val lastSegmentDtMs: Long = -1L,
        /** 用于 debug：本帧实际参与导数计算的段数 */
        val segmentCount: Int = 0,
    )

    fun estimate(
        trackPointsIncludingNew: List<GpsPoint>,
        newPoint: GpsPoint,
        previousDisplaySpeedMps: Double,
        rawDopplerMps: Double,
        segmentStartIndex: Int = 0,
    ): Estimate {
        val activeTrack = if (segmentStartIndex <= 0) {
            trackPointsIncludingNew
        } else {
            trackPointsIncludingNew.subList(
                segmentStartIndex.coerceAtMost(trackPointsIncludingNew.size),
                trackPointsIncludingNew.size,
            )
        }
        val derivedSummary = derivedWeightedSummary(activeTrack)
        val derived = derivedSummary.mps
        val dopplerWeight = dopplerFusionWeight(newPoint, rawDopplerMps, derived)
        val instant = when {
            derived == null && dopplerWeight > 0.0 -> rawDopplerMps
            derived == null -> 0.0
            dopplerWeight > 0.0 -> dopplerWeight * rawDopplerMps + (1.0 - dopplerWeight) * derived.coerceAtLeast(0.0)
            else -> derived
        }
        val gatedInstant = if (instant < STANDSTILL_SPEED_MPS) 0.0 else instant
        val method = when {
            derived == null && dopplerWeight > 0.0 -> "doppler"
            derived == null -> "hold-zero"
            dopplerWeight > 0.0 -> "fused"
            else -> "derived"
        }
        val prevPoint = activeTrack.getOrNull(activeTrack.size - 2)
        val display = smoothDisplay(
            previousDisplaySpeedMps = previousDisplaySpeedMps,
            instantMps = gatedInstant,
            lastPoint = prevPoint,
            newPoint = newPoint,
        )
        val lastDt = if (prevPoint != null) monoDtMs(prevPoint, newPoint) else -1L
        if (Log.isLoggable(TAG, Log.DEBUG) || ALWAYS_LOG) {
            Log.d(
                TAG,
                "raw=" + kmh(rawDopplerMps) +
                    " drv=" + (derived?.let { kmh(it) } ?: "-") +
                    " ins=" + kmh(gatedInstant) +
                    " ui=" + kmh(display) +
                    " (prev=" + kmh(previousDisplaySpeedMps) + ")" +
                    " m=" + method +
                    " w=" + String.format("%.2f", dopplerWeight) +
                    " segs=" + derivedSummary.count +
                    " dt=" + lastDt + "ms" +
                    " acc=" + String.format("%.1f", newPoint.accuracy) + "m" +
                    " sacc=" + (newPoint.speedAccuracyMps?.let { String.format("%.2f", it) } ?: "-") +
                    " gnss=" + newPoint.isGpsFix +
                    " src=" + newPoint.source.label +
                    " segStart=" + segmentStartIndex + "/" + trackPointsIncludingNew.size,
            )
        }
        if (DebugLogRecorder.isRecording) {
            DebugLogRecorder.append(
                "SPEED",
                DebugLogFormats.speedLine(
                    rawKmh = rawDopplerMps * 3.6,
                    drvKmh = derived?.times(3.6),
                    insKmh = gatedInstant * 3.6,
                    uiKmh = display * 3.6,
                    prevKmh = previousDisplaySpeedMps * 3.6,
                    method = method,
                    weight = dopplerWeight,
                    segments = derivedSummary.count,
                    dtMs = lastDt,
                    point = newPoint,
                ),
            )
        }
        return Estimate(
            displaySpeedMps = display,
            derivedSpeedMps = derived,
            instantSpeedMps = gatedInstant,
            dopplerWeight = dopplerWeight,
            method = method,
            lastSegmentDtMs = lastDt,
            segmentCount = derivedSummary.count,
        )
    }

    private const val TAG = "VeloSpeed"

    /** 默认开启 debug 输出；调好后改 false 或直接删 Log 行。 */
    private const val ALWAYS_LOG = true

    private fun kmh(mps: Double): String = String.format("%.1f", mps * 3.6)

    // -----------------------------------------------------------------
    // 导数：按时间窗 + 精度加权中值
    // -----------------------------------------------------------------

    private data class WeightedSpeed(val mps: Double, val weight: Double)
    private data class DerivedSummary(val mps: Double?, val count: Int)

    private fun derivedWeightedSummary(points: List<GpsPoint>): DerivedSummary {
        if (points.size < 2) return DerivedSummary(null, 0)
        val newest = points.last()
        val newestMono = monoOrTimestamp(newest)
        val segments = ArrayList<WeightedSpeed>(DERIVED_MAX_SEGMENTS)
        for (i in points.size - 1 downTo 1) {
            if (segments.size >= DERIVED_MAX_SEGMENTS) break
            val a = points[i - 1]
            val b = points[i]
            // 时间窗口剪枝：当段「较新点」距最新点 > 窗口，且已经凑够最少段，则停
            if (segments.size >= DERIVED_MIN_SEGMENTS && newestMono - monoOrTimestamp(b) > DERIVED_WINDOW_MS) {
                break
            }
            segmentSpeedMps(a, b)?.let { v ->
                val acc = max(a.accuracy, b.accuracy).coerceAtLeast(3.0)
                // 权重 = 1/accuracy²；精度越好，置信越高
                segments.add(WeightedSpeed(v, 1.0 / (acc * acc)))
            }
        }
        if (segments.isEmpty()) return DerivedSummary(null, 0)
        if (segments.size < DERIVED_MIN_SEGMENTS) {
            // 单段也允许，避免起步时一直没值
            return DerivedSummary(segments.first().mps, segments.size)
        }
        return DerivedSummary(weightedMedian(segments), segments.size)
    }

    private fun segmentSpeedMps(a: GpsPoint, b: GpsPoint): Double? {
        val dtMs = monoDtMs(a, b)
        if (dtMs < MIN_SEGMENT_DT_MS || dtMs > MAX_SEGMENT_GAP_MS) return null
        val dist = GeoUtils.haversineMeters(a.lat, a.lng, b.lat, b.lng)
        val minDist = max(MIN_SEGMENT_DISTANCE_BASE_M, max(a.accuracy, b.accuracy) * MIN_SEGMENT_DISTANCE_ACC_FACTOR)
        if (dist < minDist) return null
        val v = dist / (dtMs / 1000.0)
        return if (v > MAX_PLAUSIBLE_SEGMENT_MPS) null else v
    }

    private fun monoDtMs(a: GpsPoint, b: GpsPoint): Long {
        // 若两端都有 monotonic 时间，使用之；否则回退 timestamp
        return if (a.monotonicMs > 0L && b.monotonicMs > 0L) {
            b.monotonicMs - a.monotonicMs
        } else {
            b.timestamp - a.timestamp
        }
    }

    private fun monoOrTimestamp(p: GpsPoint): Long =
        if (p.monotonicMs > 0L) p.monotonicMs else p.timestamp

    private fun weightedMedian(values: List<WeightedSpeed>): Double {
        if (values.size == 1) return values.first().mps
        val sorted = values.sortedBy { it.mps }
        val totalWeight = sorted.sumOf { it.weight }
        var cumulative = 0.0
        val half = totalWeight / 2.0
        for (item in sorted) {
            cumulative += item.weight
            if (cumulative >= half) return item.mps
        }
        return sorted.last().mps
    }

    // -----------------------------------------------------------------
    // 多普勒一致性 + 精度加权
    // -----------------------------------------------------------------

    private fun dopplerFusionWeight(point: GpsPoint, rawDopplerMps: Double, derived: Double?): Double {
        if (rawDopplerMps < DOPPLER_MIN_MPS) return 0.0
        if (!point.isGpsFix) return 0.0
        if (point.accuracy > DOPPLER_MAX_ACCURACY_M) return 0.0

        // 1) 用 speedAccuracy 直接调权（API 26+）
        val accBased = when (val sa = point.speedAccuracyMps) {
            null -> DOPPLER_BASE_WEIGHT
            else -> when {
                sa <= DOPPLER_SPEED_ACC_GOOD -> DOPPLER_WEIGHT_MAX
                sa >= DOPPLER_SPEED_ACC_BAD -> 0.0
                else -> {
                    val t = 1.0 - (sa - DOPPLER_SPEED_ACC_GOOD) / (DOPPLER_SPEED_ACC_BAD - DOPPLER_SPEED_ACC_GOOD)
                    DOPPLER_BASE_WEIGHT + (DOPPLER_WEIGHT_MAX - DOPPLER_BASE_WEIGHT) * t
                }
            }
        }
        if (accBased <= 0.0) return 0.0

        // 2) 与 derived 的一致性校验
        val d = derived ?: return accBased
        if (d < STANDSTILL_SPEED_MPS && rawDopplerMps > 3.0) {
            // 导数说静止、多普勒说在动 → 不信多普勒（多径反射）
            return 0.0
        }
        val ratio = abs(rawDopplerMps - d) / max(d, 1.0)
        val consistency = when {
            ratio > 0.6 -> 0.0
            ratio > 0.3 -> 0.5
            else -> 1.0
        }
        return accBased * consistency
    }

    // -----------------------------------------------------------------
    // 显示平滑：自适应 EMA + 不对称限幅
    // -----------------------------------------------------------------

    private fun smoothDisplay(
        previousDisplaySpeedMps: Double,
        instantMps: Double,
        lastPoint: GpsPoint?,
        newPoint: GpsPoint,
    ): Double {
        val delta = abs(instantMps - previousDisplaySpeedMps)
        val alpha = if (delta >= DISPLAY_EMA_FAST_DELTA_MPS) DISPLAY_EMA_ALPHA_FAST else DISPLAY_EMA_ALPHA_CALM
        val ema = if (previousDisplaySpeedMps <= 0.0) {
            instantMps
        } else {
            alpha * instantMps + (1.0 - alpha) * previousDisplaySpeedMps
        }
        if (lastPoint == null) return ema.coerceAtLeast(0.0)
        val dtMs = monoDtMs(lastPoint, newPoint).coerceAtLeast(MIN_SEGMENT_DT_MS)
        val dtSec = dtMs / 1000.0
        val maxUp = MAX_ACCEL_MPS2 * dtSec
        val maxDown = MAX_DECEL_MPS2 * dtSec
        val diff = ema - previousDisplaySpeedMps
        val cappedDiff = if (diff >= 0.0) min(diff, maxUp) else max(diff, -maxDown)
        return (previousDisplaySpeedMps + cappedDiff).coerceAtLeast(0.0)
    }
}
