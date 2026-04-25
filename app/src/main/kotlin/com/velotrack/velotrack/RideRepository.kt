package com.velotrack.velotrack

import com.velotrack.velotrack.db.RideDao
import com.velotrack.velotrack.db.RideEntity
import org.json.JSONArray
import org.json.JSONObject

class RideRepository(
    private val dao: RideDao,
) {
    fun listRides(): List<Ride> = dao.getAllBlocking().map(::entityToRide)

    fun getRide(id: String): Ride? = dao.getByIdBlocking(id)?.let(::entityToRide)

    fun saveRide(ride: Ride) {
        dao.insertBlocking(
            RideEntity(
                id = ride.id,
                title = ride.title,
                startTime = ride.startTime,
                endTime = ride.endTime,
                pointsJson = pointsToJson(ride.points),
                totalDistance = ride.totalDistance,
                avgSpeed = ride.avgSpeed,
                maxSpeed = ride.maxSpeed,
            ),
        )
    }

    fun deleteRide(id: String) {
        dao.deleteByIdBlocking(id)
    }

    private fun entityToRide(entity: RideEntity): Ride {
        val points = parsePoints(entity.pointsJson)
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

    private fun pointsToJson(points: List<GpsPoint>): String {
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(
                JSONObject()
                    .put("lat", p.lat)
                    .put("lng", p.lng)
                    .put("timestamp", p.timestamp)
                    .put("speed", p.speedMps)
                    .put("altitude", p.altitude ?: JSONObject.NULL)
                    .put("accuracy", p.accuracy),
            )
        }
        return arr.toString()
    }

    private fun parsePoints(raw: String): List<GpsPoint> {
        if (raw.isBlank()) return emptyList()
        val arr = JSONArray(raw)
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    GpsPoint(
                        lat = o.optDouble("lat"),
                        lng = o.optDouble("lng"),
                        timestamp = o.optLong("timestamp"),
                        speedMps = o.optDouble("speed"),
                        altitude = if (o.isNull("altitude")) null else o.optDouble("altitude"),
                        accuracy = o.optDouble("accuracy", 0.0),
                    ),
                )
            }
        }
    }
}
