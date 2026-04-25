package com.velotrack.velotrack

import android.os.Bundle
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInteropFilter
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
import com.google.android.gms.maps.CameraUpdateFactory as GoogleCameraUpdateFactory
import com.google.android.gms.maps.model.LatLng as GoogleLatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.velotrack.velotrack.ui.VeloColors
import kotlinx.coroutines.delay

private val defaultLat = 39.9042
private val defaultLng = 116.4074
private const val RECENTER_DELAY_MS = 3000L
private const val GOOGLE_DARK_MAP_STYLE = """
[
  {"elementType":"geometry","stylers":[{"color":"#24262B"}]},
  {"elementType":"labels.icon","stylers":[{"visibility":"off"}]},
  {"elementType":"labels.text.fill","stylers":[{"color":"#8A8F98"}]},
  {"elementType":"labels.text.stroke","stylers":[{"color":"#1B1D21"}]},
  {"featureType":"administrative","elementType":"geometry","stylers":[{"color":"#3B3F46"}]},
  {"featureType":"poi","elementType":"geometry","stylers":[{"color":"#2D3036"}]},
  {"featureType":"poi.park","elementType":"geometry","stylers":[{"color":"#263426"}]},
  {"featureType":"road","elementType":"geometry","stylers":[{"color":"#343840"}]},
  {"featureType":"road","elementType":"geometry.stroke","stylers":[{"color":"#202329"}]},
  {"featureType":"road","elementType":"labels.text.fill","stylers":[{"color":"#9EA3AD"}]},
  {"featureType":"transit","elementType":"geometry","stylers":[{"color":"#2F333A"}]},
  {"featureType":"water","elementType":"geometry","stylers":[{"color":"#17212B"}]},
  {"featureType":"water","elementType":"labels.text.fill","stylers":[{"color":"#5B6874"}]}
]
"""

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
    darkMode: Boolean = false,
    centerLat: Double = defaultLat,
    centerLng: Double = defaultLng,
) {
    when (provider) {
        MapProvider.AMAP -> AmapPane(
            points = points,
            modifier = modifier,
            followLatestPosition = followLatestPosition,
            mapZoom = mapZoom,
            polylineWidth = polylineWidth,
            darkMode = darkMode,
            centerLat = centerLat,
            centerLng = centerLng,
        )
        MapProvider.GOOGLE_MAPS -> GooglePane(
            points = points,
            modifier = modifier,
            followLatestPosition = followLatestPosition,
            mapZoom = mapZoom,
            polylineWidth = polylineWidth,
            darkMode = darkMode,
            centerLat = centerLat,
            centerLng = centerLng,
        )
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun GooglePane(
    points: List<GpsPoint>,
    modifier: Modifier = Modifier,
    followLatestPosition: Boolean,
    mapZoom: Float,
    polylineWidth: Float,
    darkMode: Boolean,
    centerLat: Double,
    centerLng: Double,
) {
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            GoogleLatLng(centerLat, centerLng),
            mapZoom,
        )
    }
    val polyline = remember(points) { points.map { GoogleLatLng(it.lat, it.lng) } }
    val polyColor = VeloColors.polyline.copy(alpha = 1f)
    val polyShadowColor = Color.Black.copy(alpha = 0.4f)

    val focus = when {
        points.isEmpty() -> null
        followLatestPosition -> points.last()
        else -> points.first()
    }
    val latestTarget by rememberUpdatedState(
        if (focus != null) GoogleLatLng(focus.lat, focus.lng) else GoogleLatLng(centerLat, centerLng),
    )
    var lastUserGestureAt by remember { mutableStateOf(0L) }
    fun markUserGesture() {
        if (followLatestPosition) lastUserGestureAt = System.currentTimeMillis()
    }

    LaunchedEffect(focus?.lat, focus?.lng, centerLat, centerLng, followLatestPosition, mapZoom, lastUserGestureAt) {
        if (followLatestPosition && lastUserGestureAt > 0L) return@LaunchedEffect
        cameraState.animate(
            update = GoogleCameraUpdateFactory.newLatLngZoom(latestTarget, mapZoom),
            durationMs = 450,
        )
    }
    LaunchedEffect(lastUserGestureAt, followLatestPosition, mapZoom) {
        if (!followLatestPosition || lastUserGestureAt == 0L) return@LaunchedEffect
        val gestureAt = lastUserGestureAt
        delay(RECENTER_DELAY_MS)
        if (lastUserGestureAt == gestureAt) {
            cameraState.animate(
                update = GoogleCameraUpdateFactory.newLatLngZoom(latestTarget, mapZoom),
                durationMs = 650,
            )
            lastUserGestureAt = 0L
        }
    }
    GoogleMap(
        modifier = modifier.pointerInteropFilter { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> markUserGesture()
            }
            false
        },
        properties = MapProperties(
            isMyLocationEnabled = false,
            mapStyleOptions = if (darkMode) MapStyleOptions(GOOGLE_DARK_MAP_STYLE) else null,
        ),
        cameraPositionState = cameraState,
    ) {
        if (polyline.size >= 2) {
            Polyline(
                points = polyline,
                color = polyShadowColor,
                width = polylineWidth + 5f,
            )
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
    darkMode: Boolean,
    centerLat: Double,
    centerLng: Double,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var aMap by remember { mutableStateOf<AMap?>(null) }
    var lastUserGestureAt by remember { mutableStateOf(0L) }
    fun markUserGesture() {
        if (followLatestPosition) lastUserGestureAt = System.currentTimeMillis()
    }

    val mapView = remember {
        MapView(context).apply {
            onCreate(Bundle())
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL,
                    -> markUserGesture()
                }
                false
            }
            post {
                val map = map
                aMap = map
                map.uiSettings.isZoomGesturesEnabled = true
                map.uiSettings.isScrollGesturesEnabled = true
                map.mapType = if (darkMode) AMap.MAP_TYPE_NIGHT else AMap.MAP_TYPE_NORMAL
                map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(AmapLatLng(centerLat, centerLng), mapZoom),
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
    LaunchedEffect(aMap, darkMode) {
        val map = aMap ?: return@LaunchedEffect
        map.mapType = if (darkMode) AMap.MAP_TYPE_NIGHT else AMap.MAP_TYPE_NORMAL
    }
    LaunchedEffect(points, aMap, polylineWidth, pxPerDp) {
        val map = aMap ?: return@LaunchedEffect
        map.clear()
        if (points.size >= 2) {
            val path = points.map { AmapLatLng(it.lat, it.lng) }
            val shadowArgb = Color.Black.copy(alpha = 0.4f).toArgb()
            val argb = VeloColors.polyline.copy(alpha = 1f).toArgb()
            map.addPolyline(
                AmapPolylineOptions()
                    .addAll(path)
                    .color(shadowArgb)
                    .width((polylineWidth + 5f) * pxPerDp),
            )
            map.addPolyline(
                AmapPolylineOptions()
                    .addAll(path)
                    .color(argb)
                    .width(polylineWidth * pxPerDp),
            )
        }
    }
    val focus = when {
        points.isEmpty() -> null
        followLatestPosition -> points.last()
        else -> points.first()
    }
    val latestTarget by rememberUpdatedState(
        if (focus != null) AmapLatLng(focus.lat, focus.lng) else AmapLatLng(centerLat, centerLng),
    )
    LaunchedEffect(focus?.lat, focus?.lng, centerLat, centerLng, followLatestPosition, mapZoom, lastUserGestureAt, aMap) {
        val map = aMap ?: return@LaunchedEffect
        if (followLatestPosition && lastUserGestureAt > 0L) return@LaunchedEffect
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latestTarget, mapZoom), 450L, null)
    }
    LaunchedEffect(lastUserGestureAt, followLatestPosition, mapZoom, aMap) {
        val map = aMap ?: return@LaunchedEffect
        if (!followLatestPosition || lastUserGestureAt == 0L) return@LaunchedEffect
        val gestureAt = lastUserGestureAt
        delay(RECENTER_DELAY_MS)
        if (lastUserGestureAt == gestureAt) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(latestTarget, mapZoom), 650L, null)
            lastUserGestureAt = 0L
        }
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
    }
}
