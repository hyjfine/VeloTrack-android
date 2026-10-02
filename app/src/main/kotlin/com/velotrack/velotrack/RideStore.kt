package com.velotrack.velotrack

/** Blocking storage operations. Callers select an IO dispatcher; implementations own transactions. */
interface RideHistoryStore {
    fun listRides(): List<Ride>
    fun getRide(id: String): Ride?
    fun deleteRide(id: String)
    fun repairHistoricalRides(): Int
}

interface RecordingRideStore {
    fun beginDraftRide(rideId: String, title: String, startTime: Long)
    fun appendTrackPoints(rideId: String, startIndex: Int, points: List<GpsPoint>, elapsedMs: Long)
    fun finalizeRide(ride: Ride, acknowledgedPointCount: Int = 0)
    fun getActiveDraftRide(): Ride?
}
