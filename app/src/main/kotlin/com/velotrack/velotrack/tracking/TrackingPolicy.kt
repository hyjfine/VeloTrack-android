package com.velotrack.velotrack.tracking

/** 在线估速、轨迹过滤与 provider 归一化共同遵守的物理上限。 */
object TrackingPolicy {
    /** 约 90 km/h，覆盖正常骑行并拒绝明显的定位/多普勒尖峰。 */
    const val MAX_PLAUSIBLE_SPEED_MPS = 25.0
    const val DOPPLER_MAX_ACCURACY_M = 15.0

    /** 小于此间隔的位置差分容易放大 GPS 抖动。 */
    const val MIN_DERIVED_SEGMENT_DT_MS = 700L

    /** 超过此间隔的两点不应连接或参与速度计算。 */
    const val MAX_CONTIGUOUS_SEGMENT_GAP_MS = 10_000L
}
