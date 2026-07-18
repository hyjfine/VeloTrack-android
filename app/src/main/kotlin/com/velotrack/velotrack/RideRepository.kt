package com.velotrack.velotrack

import com.velotrack.velotrack.db.GpsPointEntity
import com.velotrack.velotrack.db.RideDao
import com.velotrack.velotrack.db.RideEntity
import com.velotrack.velotrack.speed.TrackDataFilter

class RideRepository(
    private val dao: RideDao,
) {
    /** 历史列表只读取 rides 摘要，不触发每条骑行的 gps_points 查询。 */
    fun listRides(): List<Ride> = dao.getAllBlocking().map(::entityToSummaryRide)

    fun getRide(id: String): Ride? = dao.getByIdBlocking(id)?.let(::entityToRide)

    fun saveRide(ride: Ride) {
        dao.saveRideWithPointsBlocking(
            RideEntity(
                id = ride.id,
                title = ride.title,
                startTime = ride.startTime,
                endTime = ride.endTime,
                totalDistance = ride.totalDistance,
                avgSpeed = ride.avgSpeed,
                maxSpeed = ride.maxSpeed,
                movingDurationSec = ride.movingDurationSec,
            ),
            ride.points.mapIndexed { index, point ->
                pointToEntity(ride.id, index, point)
            },
        )
    }

    fun deleteRide(id: String) {
        dao.deleteByIdBlocking(id)
    }

    fun beginDraftRide(rideId: String, title: String, startTime: Long) {
        dao.insertDraftRideBlocking(
            RideEntity(
                id = rideId,
                title = title,
                startTime = startTime,
                endTime = null,
                totalDistance = 0.0,
                avgSpeed = 0.0,
                maxSpeed = 0.0,
                movingDurationSec = 0.0,
            ),
        )
    }

    fun appendTrackPoints(rideId: String, startIndex: Int, points: List<GpsPoint>) {
        if (points.isEmpty()) return
        dao.appendPointsBlocking(
            rideId,
            points.mapIndexed { offset, point ->
                pointToEntity(rideId, startIndex + offset, point)
            },
        )
    }

    fun finalizeRide(ride: Ride) {
        dao.finalizeRideBlocking(
            RideEntity(
                id = ride.id,
                title = ride.title,
                startTime = ride.startTime,
                endTime = ride.endTime,
                totalDistance = ride.totalDistance,
                avgSpeed = ride.avgSpeed,
                maxSpeed = ride.maxSpeed,
                movingDurationSec = ride.movingDurationSec,
            ),
            ride.points.mapIndexed { index, point -> pointToEntity(ride.id, index, point) },
        )
    }

    fun deleteDraftRide(id: String) {
        dao.deleteByIdBlocking(id)
    }

    fun getActiveDraftRide(): Ride? =
        dao.getActiveDraftRideBlocking()?.let { entity ->
            entityToRide(entity)
        }

    /** 一次性重算旧骑行：切断不可能跨段，并同步修复持久化摘要。 */
    fun repairHistoricalRides(): Int {
        var repaired = 0
        dao.getAllBlocking().forEach { entity ->
            val original = dao.getPointsForRideBlocking(entity.id).map(::pointEntityToModel)
            if (original.isEmpty()) return@forEach
            val normalized = TrackDataFilter.withInferredSegments(original)
            val stats = TrackDataFilter.summarize(normalized)
            val segmentChanged = original.indices.any { original[it].segmentId != normalized[it].segmentId }
            val summaryChanged = entity.totalDistance != stats.totalDistanceM ||
                entity.avgSpeed != stats.avgSpeedMps ||
                entity.maxSpeed != stats.maxSpeedMps ||
                entity.movingDurationSec != stats.movingDurationSec
            if (segmentChanged || summaryChanged) {
                dao.saveRideWithPointsBlocking(
                    entity.copy(
                        totalDistance = stats.totalDistanceM,
                        avgSpeed = stats.avgSpeedMps,
                        maxSpeed = stats.maxSpeedMps,
                        movingDurationSec = stats.movingDurationSec,
                    ),
                    normalized.mapIndexed { index, point -> pointToEntity(entity.id, index, point) },
                )
                repaired++
            }
        }
        return repaired
    }

    private fun entityToRide(entity: RideEntity): Ride {
        val points = dao.getPointsForRideBlocking(entity.id).map(::pointEntityToModel)
        val stats = if (points.isNotEmpty()) TrackDataFilter.summarize(points) else null
        val wallDurationSec = entity.endTime?.let { end ->
            ((end - entity.startTime).coerceAtLeast(0L) / 1000.0)
        } ?: 0.0
        return Ride(
            id = entity.id,
            title = entity.title,
            startTime = entity.startTime,
            endTime = entity.endTime,
            points = points,
            totalDistance = stats?.totalDistanceM ?: entity.totalDistance,
            avgSpeed = stats?.avgSpeedMps ?: entity.avgSpeed,
            maxSpeed = stats?.maxSpeedMps ?: entity.maxSpeed,
            movingDurationSec = stats?.movingDurationSec?.takeIf { it > 0.0 }
                ?: entity.movingDurationSec.takeIf { it > 0.0 }
                ?: wallDurationSec,
        )
    }

    private fun entityToSummaryRide(entity: RideEntity): Ride {
        val wallDurationSec = entity.endTime?.let { end ->
            ((end - entity.startTime).coerceAtLeast(0L) / 1000.0)
        } ?: 0.0
        return Ride(
            id = entity.id,
            title = entity.title,
            startTime = entity.startTime,
            endTime = entity.endTime,
            points = emptyList(),
            totalDistance = entity.totalDistance,
            avgSpeed = entity.avgSpeed,
            maxSpeed = entity.maxSpeed,
            movingDurationSec = entity.movingDurationSec.takeIf { it > 0.0 } ?: wallDurationSec,
        )
    }

    private fun pointToEntity(rideId: String, index: Int, point: GpsPoint): GpsPointEntity =
        GpsPointEntity(
            rideId = rideId,
            pointIndex = index,
            lat = point.lat,
            lng = point.lng,
            timestamp = point.timestamp,
            speedMps = point.speedMps,
            altitude = point.altitude,
            accuracy = point.accuracy,
            segmentId = point.segmentId,
            monotonicMs = point.monotonicMs,
        )

    private fun pointEntityToModel(entity: GpsPointEntity): GpsPoint =
        GpsPoint(
            lat = entity.lat,
            lng = entity.lng,
            timestamp = entity.timestamp,
            speedMps = entity.speedMps,
            altitude = entity.altitude,
            accuracy = entity.accuracy,
            segmentId = entity.segmentId,
            monotonicMs = entity.monotonicMs,
        )
}
