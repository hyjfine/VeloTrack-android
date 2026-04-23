package com.velotrack.velotrack

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.velotrack.pigeon.FlutterError
import com.velotrack.pigeon.GpsPointDto
import com.velotrack.pigeon.RideDto
import com.velotrack.pigeon.TrackingState
import com.velotrack.pigeon.VeloFlutterApi
import com.velotrack.pigeon.VeloNativeApi
import com.velotrack.velotrack.db.AppDatabase
import com.velotrack.velotrack.db.RideEntity
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.BinaryMessenger
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Native implementation of [VeloNativeApi]. Threading: Pigeon handlers run on main thread;
 * Room / HTTP run on [ioExecutor], results posted back to main.
 */
class VeloNativeApiImpl(
    private val activity: FlutterActivity,
    messenger: BinaryMessenger,
) : VeloNativeApi {

    private val flutterApi = VeloFlutterApi(messenger)
    private val fused = LocationServices.getFusedLocationProviderClient(activity)
    private val db = AppDatabase.get(activity)
    private val dao = db.rideDao()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val lock = Any()

    private data class RecordingSnapshot(
        val id: String,
        val title: String,
        val start: Long,
        val pts: List<GpsPointDto>,
        val dist: Double,
        val maxS: Double,
    )

    @Volatile
    private var trackingState: TrackingState = TrackingState.IDLE

    private var currentRideId: String? = null
    private var currentTitle: String? = null
    private var rideStartWallMs: Long = 0L
    private var baseElapsedMs: Long = 0L
    private var lastResumeWallMs: Long = 0L
    private var isPaused: Boolean = false

    private val points = mutableListOf<GpsPointDto>()
    private var totalDistanceM: Double = 0.0
    private var maxSpeedMps: Double = 0.0

    private var lastGoodFixMs: Long = 0L
    private var signalLostReported: Boolean = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val point = synchronized(lock) {
                if (trackingState != TrackingState.TRACKING) return@synchronized null
                if (isPaused) return@synchronized null
                val acc = loc.accuracy.toDouble()
                if (acc > 40.0) return@synchronized null

                lastGoodFixMs = System.currentTimeMillis()
                signalLostReported = false

                val speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null
                val alt = if (loc.hasAltitude()) loc.altitude else null
                val p = GpsPointDto(
                    loc.latitude,
                    loc.longitude,
                    System.currentTimeMillis(),
                    speedMps,
                    alt,
                    acc,
                )

                if (points.isNotEmpty()) {
                    val last = points.last()
                    totalDistanceM += GeoUtils.haversineMeters(
                        last.lat,
                        last.lng,
                        p.lat,
                        p.lng,
                    )
                }
                points.add(p)
                val spd = speedMps ?: 0.0
                if (spd > maxSpeedMps) maxSpeedMps = spd
                p
            } ?: return
            flutterApi.onLocation(point) { }
        }
    }

    private val elapsedRunnable = object : Runnable {
        override fun run() {
            synchronized(lock) {
                if (trackingState != TrackingState.TRACKING) return
                val now = System.currentTimeMillis()
                if (!isPaused) {
                    val elapsed = baseElapsedMs + (now - lastResumeWallMs)
                    flutterApi.onElapsed(elapsed) { }
                }
                if (!isPaused && lastGoodFixMs > 0 && now - lastGoodFixMs > 10_000L && !signalLostReported) {
                    signalLostReported = true
                    flutterApi.onError("GPS_SIGNAL_LOST", "No accurate fix for 10s") { }
                }
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private fun ensureLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            REQ_LOC,
        )
    }

    override fun startTracking(rideId: String, title: String, callback: (Result<Unit>) -> Unit) {
        if (!ensureLocationPermission()) {
            requestLocationPermission()
            callback(Result.failure(FlutterError("GPS_PERMISSION_DENIED", "Location permission required", "")))
            return
        }
        synchronized(lock) {
            if (trackingState == TrackingState.TRACKING) {
                callback(Result.success(Unit))
                return
            }
            trackingState = TrackingState.TRACKING
            currentRideId = rideId
            currentTitle = title
            rideStartWallMs = System.currentTimeMillis()
            baseElapsedMs = 0L
            lastResumeWallMs = rideStartWallMs
            isPaused = false
            points.clear()
            totalDistanceM = 0.0
            maxSpeedMps = 0.0
            lastGoodFixMs = 0L
            signalLostReported = false
        }
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startLocationUpdates()
        mainHandler.removeCallbacks(elapsedRunnable)
        mainHandler.post(elapsedRunnable)
        callback(Result.success(Unit))
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateDelayMillis(2000L)
            .build()
        fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        fused.removeLocationUpdates(locationCallback)
    }

    override fun pauseTracking(callback: (Result<Unit>) -> Unit) {
        synchronized(lock) {
            if (trackingState != TrackingState.TRACKING) {
                callback(Result.success(Unit))
                return
            }
            if (!isPaused) {
                val now = System.currentTimeMillis()
                baseElapsedMs += now - lastResumeWallMs
                isPaused = true
            }
            trackingState = TrackingState.PAUSED
        }
        callback(Result.success(Unit))
    }

    override fun resumeTracking(callback: (Result<Unit>) -> Unit) {
        synchronized(lock) {
            if (trackingState != TrackingState.PAUSED) {
                callback(Result.success(Unit))
                return
            }
            lastResumeWallMs = System.currentTimeMillis()
            isPaused = false
            trackingState = TrackingState.TRACKING
        }
        callback(Result.success(Unit))
    }

    override fun stopTracking(callback: (Result<RideDto>) -> Unit) {
        val snapshot = synchronized(lock) {
            if (trackingState == TrackingState.IDLE) {
                callback(Result.failure(FlutterError("UNKNOWN", "Not recording", "")))
                return
            }
            val snap = RecordingSnapshot(
                id = currentRideId!!,
                title = currentTitle!!,
                start = rideStartWallMs,
                pts = points.toList(),
                dist = totalDistanceM,
                maxS = maxSpeedMps,
            )
            trackingState = TrackingState.IDLE
            currentRideId = null
            currentTitle = null
            isPaused = false
            points.clear()
            totalDistanceM = 0.0
            maxSpeedMps = 0.0
            snap
        }
        mainHandler.removeCallbacks(elapsedRunnable)
        stopLocationUpdates()
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val endTime = System.currentTimeMillis()
        val avgSpeed = if (snapshot.pts.isEmpty()) {
            0.0
        } else {
            snapshot.pts.map { it.speed ?: 0.0 }.average()
        }
        val dto = RideDto(
            snapshot.id,
            snapshot.title,
            snapshot.start,
            endTime,
            snapshot.pts.map { it as GpsPointDto? },
            snapshot.dist,
            avgSpeed,
            snapshot.maxS,
        )

        ioExecutor.execute {
            try {
                val json = pointsToJson(snapshot.pts)
                dao.insertBlocking(
                    RideEntity(
                        id = snapshot.id,
                        title = snapshot.title,
                        startTime = snapshot.start,
                        endTime = endTime,
                        pointsJson = json,
                        totalDistance = snapshot.dist,
                        avgSpeed = avgSpeed,
                        maxSpeed = snapshot.maxS,
                    ),
                )
                mainHandler.post { callback(Result.success(dto)) }
            } catch (e: Exception) {
                mainHandler.post {
                    callback(Result.failure(FlutterError("DB_WRITE_FAILED", e.message ?: "write failed", "")))
                }
            }
        }
    }

    override fun getState(callback: (Result<TrackingState>) -> Unit) {
        val s = synchronized(lock) { trackingState }
        callback(Result.success(s))
    }

    override fun listRides(callback: (Result<List<RideDto>>) -> Unit) {
        ioExecutor.execute {
            try {
                val list = dao.getAllBlocking().map { entityToDto(it) }
                mainHandler.post { callback(Result.success(list)) }
            } catch (e: Exception) {
                mainHandler.post {
                    callback(Result.failure(FlutterError("UNKNOWN", e.message ?: "", "")))
                }
            }
        }
    }

    override fun getRide(id: String, callback: (Result<RideDto?>) -> Unit) {
        ioExecutor.execute {
            try {
                val e = dao.getByIdBlocking(id)
                val dto = e?.let { entityToDto(it) }
                mainHandler.post { callback(Result.success(dto)) }
            } catch (e: Exception) {
                mainHandler.post {
                    callback(Result.failure(FlutterError("UNKNOWN", e.message ?: "", "")))
                }
            }
        }
    }

    override fun deleteRide(id: String, callback: (Result<Unit>) -> Unit) {
        ioExecutor.execute {
            try {
                dao.deleteByIdBlocking(id)
                mainHandler.post { callback(Result.success(Unit)) }
            } catch (e: Exception) {
                mainHandler.post {
                    callback(Result.failure(FlutterError("UNKNOWN", e.message ?: "", "")))
                }
            }
        }
    }

    override fun analyzeRide(id: String, callback: (Result<String>) -> Unit) {
        ioExecutor.execute {
            try {
                val entity = dao.getByIdBlocking(id)
                    ?: throw FlutterError("AI_PROXY_FAILED", "Ride not found", "")
                val ride = entityToDto(entity)
                val prompt = buildPrompt(ride)
                val key = com.velotrack.velotrack.BuildConfig.GEMINI_API_KEY
                val text = GeminiClient.generateContent(key, prompt)
                mainHandler.post { callback(Result.success(text)) }
            } catch (e: FlutterError) {
                mainHandler.post { callback(Result.failure(e)) }
            } catch (e: Exception) {
                mainHandler.post {
                    callback(Result.failure(FlutterError("AI_PROXY_FAILED", e.message ?: "", "")))
                }
            }
        }
    }

    /** Called from [MainActivity.onDestroy] when the process is going away. */
    fun onAppDestroy() {
        val snap = synchronized(lock) {
            if (trackingState == TrackingState.IDLE || currentRideId == null) {
                return@synchronized null
            }
            val s = RecordingSnapshot(
                id = currentRideId!!,
                title = (currentTitle ?: "Ride") + " (interrupted)",
                start = rideStartWallMs,
                pts = points.toList(),
                dist = totalDistanceM,
                maxS = maxSpeedMps,
            )
            trackingState = TrackingState.IDLE
            currentRideId = null
            currentTitle = null
            isPaused = false
            points.clear()
            totalDistanceM = 0.0
            maxSpeedMps = 0.0
            s
        } ?: return

        mainHandler.removeCallbacks(elapsedRunnable)
        stopLocationUpdates()
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val endTime = System.currentTimeMillis()
        val avgSpeed = if (snap.pts.isEmpty()) 0.0 else snap.pts.map { it.speed ?: 0.0 }.average()
        ioExecutor.execute {
            try {
                dao.insertBlocking(
                    RideEntity(
                        id = snap.id,
                        title = snap.title,
                        startTime = snap.start,
                        endTime = endTime,
                        pointsJson = pointsToJson(snap.pts),
                        totalDistance = snap.dist,
                        avgSpeed = avgSpeed,
                        maxSpeed = snap.maxS,
                    ),
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun buildPrompt(ride: RideDto): String {
        val durMs = (ride.endTime ?: ride.startTime) - ride.startTime
        return """
            Analyze this cycling ride and provide professional coaching advice.
            Distance: ${formatDistance(ride.totalDistance)}
            Duration: ${formatDuration(durMs)}
            Avg Speed: ${formatSpeedKmh(ride.avgSpeed)} km/h
            Max Speed: ${formatSpeedKmh(ride.maxSpeed)} km/h
            Data samples: ${ride.points.size}
            Please give a concise analysis in Chinese (suitable for mobile display), including:
            1. Performance summary
            2. One area for improvement
            3. A motivational remark.
        """.trimIndent()
    }

    private fun formatDistance(meters: Double): String =
        if (meters < 1000) "${meters.toInt()}m" else String.format("%.2fkm", meters / 1000.0)

    private fun formatDuration(ms: Long): String {
        val s = (ms / 1000).toInt()
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return String.format("%02d:%02d:%02d", h, m, sec)
    }

    private fun formatSpeedKmh(mps: Double): String = String.format("%.1f", mps * 3.6)

    companion object {
        private const val REQ_LOC = 9101

        private fun pointsToJson(points: List<GpsPointDto>): String {
            val arr = JSONArray()
            for (p in points) {
                val o = JSONObject()
                o.put("lat", p.lat)
                o.put("lng", p.lng)
                o.put("timestamp", p.timestamp)
                if (p.speed == null) o.put("speed", JSONObject.NULL) else o.put("speed", p.speed)
                if (p.altitude == null) o.put("altitude", JSONObject.NULL) else o.put("altitude", p.altitude)
                if (p.accuracy == null) o.put("accuracy", JSONObject.NULL) else o.put("accuracy", p.accuracy)
                arr.put(o)
            }
            return arr.toString()
        }

        private fun parsePoints(json: String): List<GpsPointDto> {
            val arr = JSONArray(json)
            val out = ArrayList<GpsPointDto>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    GpsPointDto(
                        o.getDouble("lat"),
                        o.getDouble("lng"),
                        o.getLong("timestamp"),
                        if (o.isNull("speed")) null else o.getDouble("speed"),
                        if (o.isNull("altitude")) null else o.getDouble("altitude"),
                        if (o.isNull("accuracy")) null else o.getDouble("accuracy"),
                    ),
                )
            }
            return out
        }

        private fun entityToDto(e: RideEntity): RideDto {
            val pts = parsePoints(e.pointsJson)
            return RideDto(
                e.id,
                e.title,
                e.startTime,
                e.endTime,
                pts.map { it as GpsPointDto? },
                e.totalDistance,
                e.avgSpeed,
                e.maxSpeed,
            )
        }
    }
}
