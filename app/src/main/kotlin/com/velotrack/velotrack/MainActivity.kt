package com.velotrack.velotrack

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import com.velotrack.pigeon.VeloNativeApi

class MainActivity : FlutterActivity() {

    private var veloImpl: VeloNativeApiImpl? = null

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
