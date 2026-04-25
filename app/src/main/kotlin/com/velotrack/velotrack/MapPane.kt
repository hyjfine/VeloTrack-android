package com.velotrack.velotrack

import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng as AmapLatLng
import com.amap.api.maps.model.PolylineOptions as AmapPolylineOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng as GoogleLatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.velotrack.velotrack.ui.VeloColors

private val defaultLat = 39.9042
private val defaultLng = 116.4074

/**
 * @param followLatestPosition `true`：相机跟随最后一个点（录制）；`false`：固定首点 + [mapZoom]（详情预览）
 * @param polylineWidth 与 h5 `weight={5}` / design-tokens `polylineWeight` 一致
 */
@Composable
fun MapPane(
    provider: MapProvider,
    points: List<GpsPoint>,
    modifier: Modifier = Modifier,
    followLatestPosition: Boolean = true,
    mapZoom: Float = 16f,
    polylineWidth: Float = 5f,
) {
    when (provider) {
        MapProvider.AMAP -> AmapPane(
            points = points,
            modifier = modifier,
            followLatestPosition = followLatestPosition,
            mapZoom = mapZoom,
            polylineWidth = polylineWidth,
        )
        MapProvider.GOOGLE_MAPS -> GooglePane(
            points = points,
            modifier = modifier,
            followLatestPosition = followLatestPosition,
            mapZoom = mapZoom,
            polylineWidth = polylineWidth,
        )
    }
}

@Composable
private fun GooglePane(
    points: List<GpsPoint>,
    modifier: Modifier = Modifier,
    followLatestPosition: Boolean,
    mapZoom: Float,
    polylineWidth: Float,
) {
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            GoogleLatLng(defaultLat, defaultLng),
            mapZoom,
        )
    }
    val polyline = remember(points) { points.map { GoogleLatLng(it.lat, it.lng) } }
    val polyColor = VeloColors.polyline.copy(alpha = 0.9f)

    val focus = when {
        points.isEmpty() -> null
        followLatestPosition -> points.last()
        else -> points.first()
    }
    LaunchedEffect(focus?.lat, focus?.lng, followLatestPosition, mapZoom) {
        if (focus != null) {
            cameraState.position = CameraPosition.fromLatLngZoom(
                GoogleLatLng(focus.lat, focus.lng),
                mapZoom,
            )
        }
    }
    GoogleMap(
        modifier = modifier,
        properties = MapProperties(isMyLocationEnabled = false),
        cameraPositionState = cameraState,
    ) {
        if (polyline.size >= 2) {
            Polyline(
                points = polyline,
                color = polyColor,
                width = polylineWidth,
            )
        }
    }
}

@Composable
private fun AmapPane(
    points: List<GpsPoint>,
    modifier: Modifier = Modifier,
    followLatestPosition: Boolean,
    mapZoom: Float,
    polylineWidth: Float,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var aMap by remember { mutableStateOf<AMap?>(null) }

    val mapView = remember {
        MapView(context).apply {
            onCreate(Bundle())
            post {
                val map = map
                aMap = map
                map.uiSettings.isZoomGesturesEnabled = true
                map.uiSettings.isScrollGesturesEnabled = true
                map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(AmapLatLng(defaultLat, defaultLng), mapZoom),
                )
            }
        }
    }

    DisposableEffect(mapView, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    val pxPerDp = LocalContext.current.resources.displayMetrics.density
    LaunchedEffect(points, aMap, followLatestPosition, mapZoom, polylineWidth, pxPerDp) {
        val map = aMap ?: return@LaunchedEffect
        map.clear()
        if (points.size >= 2) {
            val path = points.map { AmapLatLng(it.lat, it.lng) }
            val argb = VeloColors.polyline.copy(alpha = 0.9f).toArgb()
            map.addPolyline(
                AmapPolylineOptions()
                    .addAll(path)
                    .color(argb)
                    .width(polylineWidth * pxPerDp),
            )
        }
        val focus = when {
            points.isEmpty() -> null
            followLatestPosition -> points.last()
            else -> points.first()
        }
        focus?.let { p ->
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(AmapLatLng(p.lat, p.lng), mapZoom))
        }
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
    }
}
