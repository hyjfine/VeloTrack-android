package com.velotrack.velotrack

import android.app.Application
import android.util.Log
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.velotrack.velotrack.db.AppDatabase
import com.velotrack.velotrack.recording.RecordingSessionManager

/**
 * 高德地图 SDK 8.1+ 要求：在调用任何地图相关接口前完成隐私合规声明，否则国内常见表现为白屏、瓦片不加载。
 * 必须在 [Application.onCreate] 中尽早调用（早于任何 MapView 创建）。
 */
class VeloApp : Application() {

    lateinit var recordingManager: RecordingSessionManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        runCatching {
            MapsInitializer.updatePrivacyShow(this, true, true)
            MapsInitializer.updatePrivacyAgree(this, true)
            AMapLocationClient.updatePrivacyShow(this, true, true)
            AMapLocationClient.updatePrivacyAgree(this, true)
            if (BuildConfig.AMAP_API_KEY.isNotBlank()) {
                AMapLocationClient.setApiKey(BuildConfig.AMAP_API_KEY)
            }
        }.onFailure { e ->
            Log.e("VeloTrack", "AMap privacy init failed", e)
        }

        val repo = RideRepository(AppDatabase.get(this).rideDao())
        recordingManager = RecordingSessionManager(
            applicationContext,
            repo,
            MapProviderSelector.select(),
        )
    }

    companion object {
        lateinit var instance: VeloApp
            private set
    }
}

/** @deprecated 使用 [VeloApp]；保留类型别名便于迁移。 */
typealias VeloApplication = VeloApp
