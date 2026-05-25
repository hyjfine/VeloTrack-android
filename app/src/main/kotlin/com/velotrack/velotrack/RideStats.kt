package com.velotrack.velotrack

import com.velotrack.velotrack.speed.SpeedEstimator
import com.velotrack.velotrack.speed.TrackDataFilter

/**
 * 骑行统计口径（与停止录制、详情展示一致）。
 * 一律经 [TrackDataFilter] 过滤 GPS 尖峰与异常速度后再汇总。
 */
object RideStats {

    fun totalDistanceMeters(points: List<GpsPoint>): Double =
        TrackDataFilter.summarize(points).totalDistanceM

    fun movingDurationSeconds(points: List<GpsPoint>): Double =
        TrackDataFilter.summarize(points).movingDurationSec

    fun avgSpeedMps(points: List<GpsPoint>): Double =
        TrackDataFilter.summarize(points).avgSpeedMps

    fun maxSpeedMps(points: List<GpsPoint>): Double =
        TrackDataFilter.summarize(points).maxSpeedMps

    /** 详情 PERFORMANCE 曲线纵轴（m/s），已清洗 + 中值平滑。 */
    fun chartSpeedMps(points: List<GpsPoint>): List<Double> =
        TrackDataFilter.summarize(points).chartSpeedMps

    /** 从轨迹点重算汇总（用于修正历史脏数据展示，不写回 DB）。 */
    fun summarize(points: List<GpsPoint>): TrackDataFilter.Summary =
        TrackDataFilter.summarize(points)

    @Deprecated("Use SpeedEstimator.STANDSTILL_SPEED_MPS", ReplaceWith("SpeedEstimator.STANDSTILL_SPEED_MPS"))
    val movingThresholdMps: Double = SpeedEstimator.STANDSTILL_SPEED_MPS
}
