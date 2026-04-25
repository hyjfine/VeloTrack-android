package com.velotrack.velotrack

import com.velotrack.velotrack.db.RideDao
import com.velotrack.velotrack.db.GpsPointEntity
import com.velotrack.velotrack.db.RideEntity

class RideRepository(
    private val dao: RideDao,
) {
    fun listRides(): List<Ride> = dao.getAllBlocking().map(::entityToRide)

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
            ),
            ride.points.mapIndexed { index, point ->
                pointToEntity(ride.id, index, point)
            },
        )
    }

    fun deleteRide(id: String) {
        dao.deleteByIdBlocking(id)
    }

    private fun entityToRide(entity: RideEntity): Ride {
        val points = dao.getPointsForRideBlocking(entity.id).map(::pointEntityToModel)
        return Ride(
            id = entity.id,
            title = entity.title,
            startTime = entity.startTime,
            endTime = entity.endTime,
            points = points,
            totalDistance = entity.totalDistance,
            avgSpeed = entity.avgSpeed,
            maxSpeed = entity.maxSpeed,
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
        )

    private fun pointEntityToModel(entity: GpsPointEntity): GpsPoint =
        GpsPoint(
            lat = entity.lat,
            lng = entity.lng,
            timestamp = entity.timestamp,
            speedMps = entity.speedMps,
            altitude = entity.altitude,
            accuracy = entity.accuracy,
        )
}
