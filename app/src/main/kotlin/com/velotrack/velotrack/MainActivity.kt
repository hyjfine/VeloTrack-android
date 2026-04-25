package com.velotrack.velotrack

import android.os.Bundle
import androidx.core.view.WindowCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import com.velotrack.pigeon.VeloNativeApi

class MainActivity : FlutterActivity() {

    private var veloImpl: VeloNativeApiImpl? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Align with VeloTrack-h5 full-bleed layout (Flutter reads cutout & nav bar insets). Long-press stop
        // feedback is a top linear progress bar in Flutter, matching h5 (no SVG ring).
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val impl = VeloNativeApiImpl(this, flutterEngine.dartExecutor.binaryMessenger)
        veloImpl = impl
        VeloNativeApi.setUp(flutterEngine.dartExecutor.binaryMessenger, impl)
    }

    override fun onDestroy() {
        veloImpl?.onAppDestroy()
        veloImpl = null
        super.onDestroy()
    }
}
