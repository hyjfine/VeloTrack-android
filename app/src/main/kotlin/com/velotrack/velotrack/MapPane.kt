package com.velotrack.velotrack

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory as AmapBitmapDescriptorFactory
import com.amap.api.maps.model.LatLng as AmapLatLng
import com.amap.api.maps.model.LatLngBounds as AmapLatLngBounds
import com.amap.api.maps.model.Marker as AmapMarker
import com.amap.api.maps.model.MarkerOptions as AmapMarkerOptions
import com.amap.api.maps.model.Polyline as AmapPolyline
import com.amap.api.maps.model.PolylineOptions as AmapPolylineOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.BitmapDescriptorFactory as GoogleBitmapDescriptorFactory
import com.google.android.gms.maps.CameraUpdateFactory as GoogleCameraUpdateFactory
import com.google.android.gms.maps.model.LatLng as GoogleLatLng
import com.google.android.gms.maps.model.LatLngBounds as GoogleLatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberMarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.velotrack.velotrack.ui.VeloColors
import kotlinx.coroutines.delay

private val defaultLat = 39.9042
private val defaultLng = 116.4074
internal const val DEFAULT_RECORDING_MAP_ZOOM = 17f
private const val RECENTER_DELAY_MS = 3000L
private const val CAMERA_MIN_INTERVAL_MS = 2000L
private const val CAMERA_MIN_DISTANCE_M = 15.0
private const val ROUTE_BOUNDS_PADDING_DP = 48f
private const val START_MARKER_SIZE_DP = 18f
private const val FINISH_MARKER_WIDTH_DP = 34f
private const val FINISH_MARKER_HEIGHT_DP = 38f
private const val FINISH_MARKER_POLE_X_DP = 9f
private const val FINISH_MARKER_ANCHOR_U = FINISH_MARKER_POLE_X_DP / FINISH_MARKER_WIDTH_DP
private const val FINISH_MARKER_ANCHOR_V = 0.95f
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

private fun startEndpointMarkerBitmap(
    fillColor: Int,
    strokeColor: Int,
    density: Float,
): Bitmap {
    val size = START_MARKER_SIZE_DP * density
    val strokeWidth = 2.5f * density
    val bitmap = createBitmap(size.toInt(), size.toInt())
    val canvas = Canvas(bitmap)
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeColor
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    }
    val radius = size / 2f - strokeWidth / 2f
    canvas.drawCircle(size / 2f, size / 2f, radius, fillPaint)
    canvas.drawCircle(size / 2f, size / 2f, radius, strokePaint)
    return bitmap
}

private fun finishEndpointMarkerBitmap(
    flagColor: Int,
    poleColor: Int,
    density: Float,
): Bitmap {
    val width = FINISH_MARKER_WIDTH_DP * density
    val height = FINISH_MARKER_HEIGHT_DP * density
    val poleX = FINISH_MARKER_POLE_X_DP * density
    val top = 3f * density
    val bottom = height - 2f * density
    val flagTop = top + 2f * density
    val flagHeight = 15f * density
    val flagWidth = 19f * density
    val polePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = poleColor
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    val flagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = flagColor
        style = Paint.Style.FILL
    }
    val flagStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = poleColor
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    val bitmap = createBitmap(width.toInt(), height.toInt())
    val canvas = Canvas(bitmap)
    val flagPath = Path().apply {
        moveTo(poleX, flagTop)
        lineTo(poleX + flagWidth, flagTop + 3f * density)
        lineTo(poleX + flagWidth * 0.72f, flagTop + flagHeight * 0.55f)
        lineTo(poleX + flagWidth, flagTop + flagHeight)
        lineTo(poleX, flagTop + flagHeight - 2f * density)
        close()
    }
    canvas.drawPath(flagPath, flagPaint)
    canvas.drawPath(flagPath, flagStrokePaint)
    canvas.drawLine(poleX, top, poleX, bottom, polePaint)
    return bitmap
}

private fun hasDistinctRoutePoints(points: List<GpsPoint>): Boolean {
    val first = points.firstOrNull() ?: return false
    return points.any { it.lat != first.lat || it.lng != first.lng }
}

private fun wgs84ToAmapLatLng(lat: Double, lng: Double): AmapLatLng {
    val coordinate = CoordinateTransform.wgs84ToGcj02(lat, lng)
    return AmapLatLng(coordinate.lat, coordinate.lng)
}

private fun GpsPoint.toAmapLatLng(): AmapLatLng = wgs84ToAmapLatLng(lat, lng)

/**
 * @param followLatestPosition `true`：相机跟随最后一个点（录制）；`false`：固定首点 + [mapZoom]（详情预览）
 * @param polylineWidth 与 h5 `weight={5}` / design-tokens `polylineWeight` 一致
 * @param showEndpointMarkers 是否展示轨迹起点 / 终点标识，默认关闭以避免影响录制页
 * @param fitRouteBounds 是否在地图加载后适配整条轨迹视野，默认关闭
 * @param onMapTouchingChanged 地图触摸状态回调，可用于详情页临时禁用外层滚动
 */
@Composable
fun MapPane(
    provider: MapProvider,
    points: List<GpsPoint>,
    modifier: Modifier = Modifier,
    followLatestPosition: Boolean = true,
    mapZoom: Float = DEFAULT_RECORDING_MAP_ZOOM,
    polylineWidth: Float = 5f,
    darkMode: Boolean = false,
    centerLat: Double = defaultLat,
    centerLng: Double = defaultLng,
    showEndpointMarkers: Boolean = false,
    fitRouteBounds: Boolean = false,
    onMapTouchingChanged: (Boolean) -> Unit = {},
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
            showEndpointMarkers = showEndpointMarkers,
            fitRouteBounds = fitRouteBounds,
            onMapTouchingChanged = onMapTouchingChanged,
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
            showEndpointMarkers = showEndpointMarkers,
            fitRouteBounds = fitRouteBounds,
            onMapTouchingChanged = onMapTouchingChanged,
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
    showEndpointMarkers: Boolean,
    fitRouteBounds: Boolean,
    onMapTouchingChanged: (Boolean) -> Unit,
) {
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            GoogleLatLng(centerLat, centerLng),
            mapZoom,
        )
    }
    val polyline = remember(points) { points.map { GoogleLatLng(it.lat, it.lng) } }
    val canFitRouteBounds = fitRouteBounds && hasDistinctRoutePoints(points)
    val polyColor = VeloColors.polyline.copy(alpha = 1f)
    val polyShadowColor = Color.Black.copy(alpha = 0.4f)
    val markerDensity = LocalContext.current.resources.displayMetrics.density
    val startMarkerIcon = remember(markerDensity) {
        GoogleBitmapDescriptorFactory.fromBitmap(
            startEndpointMarkerBitmap(
                fillColor = VeloColors.accent.toArgb(),
                strokeColor = VeloColors.foreground.toArgb(),
                density = markerDensity,
            ),
        )
    }
    val finishMarkerIcon = remember(markerDensity) {
        GoogleBitmapDescriptorFactory.fromBitmap(
            finishEndpointMarkerBitmap(
                flagColor = VeloColors.accent.toArgb(),
                poleColor = VeloColors.foreground.toArgb(),
                density = markerDensity,
            ),
        )
    }

    val focus = when {
        points.isEmpty() -> null
        followLatestPosition -> null
        else -> points.first()
    }
    val latestTarget by rememberUpdatedState(
        if (followLatestPosition || focus == null) GoogleLatLng(centerLat, centerLng) else GoogleLatLng(focus.lat, focus.lng),
    )
    val followLatestPositionState by rememberUpdatedState(followLatestPosition)
    val onMapTouchingChangedState by rememberUpdatedState(onMapTouchingChanged)
    var isMapLoaded by remember { mutableStateOf(false) }
    var hasUserAdjustedCamera by remember { mutableStateOf(false) }
    var lastUserGestureAt by remember { mutableStateOf(0L) }
    var lastCameraMoveAt by remember { mutableStateOf(0L) }
    var lastCameraLatLng by remember { mutableStateOf<GoogleLatLng?>(null) }
    fun markUserGesture() {
        if (followLatestPositionState) {
            hasUserAdjustedCamera = true
            lastUserGestureAt = System.currentTimeMillis()
        }
    }
    fun notifyMapTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            -> onMapTouchingChangedState(true)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> onMapTouchingChangedState(false)
        }
    }
    suspend fun animateToTarget(target: GoogleLatLng, durationMs: Int) {
        val zoom = if (followLatestPosition && hasUserAdjustedCamera) cameraState.position.zoom else mapZoom
        cameraState.animate(
            update = GoogleCameraUpdateFactory.newLatLngZoom(target, zoom),
            durationMs = durationMs,
        )
        lastCameraMoveAt = System.currentTimeMillis()
        lastCameraLatLng = target
    }

    LaunchedEffect(cameraState.isMoving, cameraState.cameraMoveStartedReason, followLatestPosition) {
        if (cameraState.isMoving && cameraState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE) {
            markUserGesture()
        }
    }

    LaunchedEffect(focus?.lat, focus?.lng, centerLat, centerLng, followLatestPosition, mapZoom, lastUserGestureAt) {
        if (!followLatestPosition && canFitRouteBounds) return@LaunchedEffect
        if (followLatestPosition && lastUserGestureAt > 0L) return@LaunchedEffect
        val now = System.currentTimeMillis()
        val previous = lastCameraLatLng
        val movedMeters = if (previous == null) {
            Double.MAX_VALUE
        } else {
            GeoUtils.haversineMeters(previous.latitude, previous.longitude, latestTarget.latitude, latestTarget.longitude)
        }
        if (followLatestPosition && lastCameraMoveAt > 0L && now - lastCameraMoveAt < CAMERA_MIN_INTERVAL_MS && movedMeters < CAMERA_MIN_DISTANCE_M) {
            return@LaunchedEffect
        }
        animateToTarget(latestTarget, 450)
    }
    LaunchedEffect(polyline, fitRouteBounds, isMapLoaded) {
        if (!canFitRouteBounds || !isMapLoaded || polyline.size < 2) return@LaunchedEffect
        val boundsBuilder = GoogleLatLngBounds.Builder()
        polyline.forEach { boundsBuilder.include(it) }
        val paddingPx = (ROUTE_BOUNDS_PADDING_DP * markerDensity).toInt()
        cameraState.move(GoogleCameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), paddingPx))
        lastCameraMoveAt = System.currentTimeMillis()
        lastCameraLatLng = polyline.last()
    }
    LaunchedEffect(lastUserGestureAt, followLatestPosition, mapZoom) {
        if (!followLatestPosition || lastUserGestureAt == 0L) return@LaunchedEffect
        val gestureAt = lastUserGestureAt
        delay(RECENTER_DELAY_MS)
        if (lastUserGestureAt == gestureAt) {
            animateToTarget(latestTarget, 650)
            lastUserGestureAt = 0L
        }
    }
    GoogleMap(
        modifier = modifier.pointerInteropFilter { event ->
            notifyMapTouch(event)
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
        onMapLoaded = { isMapLoaded = true },
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
            if (showEndpointMarkers) {
                val start = polyline.first()
                val finish = polyline.last()
                Marker(
                    state = rememberMarkerState(
                        key = "route-start-${start.latitude},${start.longitude}",
                        position = start,
                    ),
                    anchor = Offset(0.5f, 0.5f),
                    icon = startMarkerIcon,
                    title = "Start",
                    contentDescription = "Route start",
                    zIndex = 10f,
                )
                Marker(
                    state = rememberMarkerState(
                        key = "route-finish-${finish.latitude},${finish.longitude}",
                        position = finish,
                    ),
                    anchor = Offset(FINISH_MARKER_ANCHOR_U, FINISH_MARKER_ANCHOR_V),
                    icon = finishMarkerIcon,
                    title = "Finish",
                    contentDescription = "Route finish",
                    zIndex = 10f,
                )
            }
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
    showEndpointMarkers: Boolean,
    fitRouteBounds: Boolean,
    onMapTouchingChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val canFitRouteBounds = fitRouteBounds && hasDistinctRoutePoints(points)
    var aMap by remember { mutableStateOf<AMap?>(null) }
    var shadowPolyline by remember { mutableStateOf<AmapPolyline?>(null) }
    var routePolyline by remember { mutableStateOf<AmapPolyline?>(null) }
    var startMarker by remember { mutableStateOf<AmapMarker?>(null) }
    var finishMarker by remember { mutableStateOf<AmapMarker?>(null) }
    val followLatestPositionState by rememberUpdatedState(followLatestPosition)
    val onMapTouchingChangedState by rememberUpdatedState(onMapTouchingChanged)
    var hasUserAdjustedCamera by remember { mutableStateOf(false) }
    var lastUserGestureAt by remember { mutableStateOf(0L) }
    var lastCameraMoveAt by remember { mutableStateOf(0L) }
    var lastCameraLatLng by remember { mutableStateOf<AmapLatLng?>(null) }
    val markerDensity = context.resources.displayMetrics.density
    val startMarkerIcon = remember(markerDensity) {
        AmapBitmapDescriptorFactory.fromBitmap(
            startEndpointMarkerBitmap(
                fillColor = VeloColors.accent.toArgb(),
                strokeColor = VeloColors.foreground.toArgb(),
                density = markerDensity,
            ),
        )
    }
    val finishMarkerIcon = remember(markerDensity) {
        AmapBitmapDescriptorFactory.fromBitmap(
            finishEndpointMarkerBitmap(
                flagColor = VeloColors.accent.toArgb(),
                poleColor = VeloColors.foreground.toArgb(),
                density = markerDensity,
            ),
        )
    }
    fun markUserGesture() {
        if (followLatestPositionState) {
            hasUserAdjustedCamera = true
            lastUserGestureAt = System.currentTimeMillis()
        }
    }
    fun notifyMapTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            -> onMapTouchingChangedState(true)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> onMapTouchingChangedState(false)
        }
    }
    fun removeEndpointMarkers() {
        startMarker?.remove()
        finishMarker?.remove()
        startMarker = null
        finishMarker = null
    }

    val mapView = remember {
        MapView(context).apply {
            onCreate(Bundle())
            post {
                val map = map
                aMap = map
                map.uiSettings.isZoomGesturesEnabled = true
                map.uiSettings.isScrollGesturesEnabled = true
                map.setOnMapTouchListener { event ->
                    notifyMapTouch(event)
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_MOVE,
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL,
                        -> markUserGesture()
                    }
                }
                map.mapType = if (darkMode) AMap.MAP_TYPE_NIGHT else AMap.MAP_TYPE_NORMAL
                map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(wgs84ToAmapLatLng(centerLat, centerLng), mapZoom),
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
            removeEndpointMarkers()
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
        if (points.size < 2) {
            shadowPolyline?.remove()
            routePolyline?.remove()
            shadowPolyline = null
            routePolyline = null
            return@LaunchedEffect
        }
        val path = points.map { it.toAmapLatLng() }
        val shadowArgb = Color.Black.copy(alpha = 0.4f).toArgb()
        val argb = VeloColors.polyline.copy(alpha = 1f).toArgb()
        if (shadowPolyline == null || routePolyline == null) {
            shadowPolyline?.remove()
            routePolyline?.remove()
            shadowPolyline = map.addPolyline(
                AmapPolylineOptions()
                    .addAll(path)
                    .color(shadowArgb)
                    .width((polylineWidth + 5f) * pxPerDp),
            )
            routePolyline = map.addPolyline(
                AmapPolylineOptions()
                    .addAll(path)
                    .color(argb)
                    .width(polylineWidth * pxPerDp),
            )
        } else {
            shadowPolyline?.points = path
            routePolyline?.points = path
        }
    }
    LaunchedEffect(points, aMap, fitRouteBounds, markerDensity) {
        val map = aMap ?: return@LaunchedEffect
        if (!canFitRouteBounds || points.size < 2) return@LaunchedEffect
        val boundsBuilder = AmapLatLngBounds.Builder()
        points.forEach { boundsBuilder.include(it.toAmapLatLng()) }
        val paddingPx = (ROUTE_BOUNDS_PADDING_DP * markerDensity).toInt()
        mapView.post {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), paddingPx))
            lastCameraMoveAt = System.currentTimeMillis()
            lastCameraLatLng = points.last().toAmapLatLng()
        }
    }
    LaunchedEffect(points, aMap, showEndpointMarkers, startMarkerIcon, finishMarkerIcon) {
        val map = aMap ?: return@LaunchedEffect
        if (!showEndpointMarkers || points.size < 2) {
            removeEndpointMarkers()
            return@LaunchedEffect
        }
        val start = points.first().toAmapLatLng()
        val finish = points.last().toAmapLatLng()
        val currentStartMarker = startMarker
        if (currentStartMarker == null) {
            startMarker = map.addMarker(
                AmapMarkerOptions()
                    .position(start)
                    .icon(startMarkerIcon)
                    .anchor(0.5f, 0.5f)
                    .title("Start")
                    .zIndex(10f),
            )
        } else {
            currentStartMarker.position = start
            currentStartMarker.setIcon(startMarkerIcon)
            currentStartMarker.setVisible(true)
            currentStartMarker.setZIndex(10f)
        }
        val currentFinishMarker = finishMarker
        if (currentFinishMarker == null) {
            finishMarker = map.addMarker(
                AmapMarkerOptions()
                    .position(finish)
                    .icon(finishMarkerIcon)
                    .anchor(FINISH_MARKER_ANCHOR_U, FINISH_MARKER_ANCHOR_V)
                    .title("Finish")
                    .zIndex(10f),
            )
        } else {
            currentFinishMarker.position = finish
            currentFinishMarker.setIcon(finishMarkerIcon)
            currentFinishMarker.setVisible(true)
            currentFinishMarker.setZIndex(10f)
        }
    }
    val focus = when {
        points.isEmpty() -> null
        followLatestPosition -> null
        else -> points.first()
    }
    val latestTarget by rememberUpdatedState(
        if (followLatestPosition || focus == null) wgs84ToAmapLatLng(centerLat, centerLng) else focus.toAmapLatLng(),
    )
    LaunchedEffect(focus?.lat, focus?.lng, centerLat, centerLng, followLatestPosition, mapZoom, lastUserGestureAt, aMap) {
        val map = aMap ?: return@LaunchedEffect
        if (!followLatestPosition && canFitRouteBounds) return@LaunchedEffect
        if (followLatestPosition && lastUserGestureAt > 0L) return@LaunchedEffect
        val now = System.currentTimeMillis()
        val previous = lastCameraLatLng
        val movedMeters = if (previous == null) {
            Double.MAX_VALUE
        } else {
            GeoUtils.haversineMeters(previous.latitude, previous.longitude, latestTarget.latitude, latestTarget.longitude)
        }
        if (followLatestPosition && lastCameraMoveAt > 0L && now - lastCameraMoveAt < CAMERA_MIN_INTERVAL_MS && movedMeters < CAMERA_MIN_DISTANCE_M) {
            return@LaunchedEffect
        }
        val zoom = if (followLatestPosition && hasUserAdjustedCamera) map.cameraPosition.zoom else mapZoom
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latestTarget, zoom), 450L, null)
        lastCameraMoveAt = now
        lastCameraLatLng = latestTarget
    }
    LaunchedEffect(lastUserGestureAt, followLatestPosition, mapZoom, aMap) {
        val map = aMap ?: return@LaunchedEffect
        if (!followLatestPosition || lastUserGestureAt == 0L) return@LaunchedEffect
        val gestureAt = lastUserGestureAt
        delay(RECENTER_DELAY_MS)
        if (lastUserGestureAt == gestureAt) {
            val zoom = if (hasUserAdjustedCamera) map.cameraPosition.zoom else mapZoom
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(latestTarget, zoom), 650L, null)
            lastCameraMoveAt = System.currentTimeMillis()
            lastCameraLatLng = latestTarget
            lastUserGestureAt = 0L
        }
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
    }
}
