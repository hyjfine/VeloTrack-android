package com.velotrack.velotrack

import android.app.Application
import android.util.Log
import com.amap.api.maps.MapsInitializer

/**
 * 高德地图 SDK 8.1+ 要求：在调用任何地图相关接口前完成隐私合规声明，否则国内常见表现为白屏、瓦片不加载。
 * 必须在 [Application.onCreate] 中尽早调用（早于任何 MapView 创建）。
 */
class VeloApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            MapsInitializer.updatePrivacyShow(this, true, true)
            MapsInitializer.updatePrivacyAgree(this, true)
        }.onFailure { e ->
            Log.e("VeloTrack", "AMap privacy init failed", e)
        }
    }
}
