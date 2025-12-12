package com.sahara.user.algorithms

import com.sahara.user.data.model.RouteStopDTO

/**
 * K-Nearest Neighbors Algorithm
 * 
 * Finds the K nearest bus stops to a user's GPS location.
 * Used by AlgorithmAgent to provide nearby stop recommendations.
 * 
 * ALGORITHM:
 * 1. Calculate haversine distance from user to all stops
 * 2. Sort by distance ascending
 * 3. Return top K stops
 * 
 * TIME COMPLEXITY: O(n log k) where n = total stops, k = neighbors
 * SPACE COMPLEXITY: O(n) for distance calculations
 */
object NearestNeighbor {
    
    /**
     * Result for a nearby stop
     */
    data class NearbyStopResult(
        val stopName: String,
        val stopId: String,
        val vehicleId: String,
        val vehicleName: String,
        val distanceKm: Double,
        val walkTimeMinutes: Int,
        val latitude: Double,
        val longitude: Double,
        val stopOrder: Int
    )
    
    /**
     * Find K nearest stops to user's GPS location
     * 
     * @param userLat User's latitude
     * @param userLng User's longitude
     * @param stops List of all route stops
     * @param vehicleNames Map of vehicleId to vehicle name
     * @param k Number of nearest stops to return (default 5)
     * @param maxDistanceKm Maximum distance to consider (default 2.0 km)
     * @return List of nearby stops sorted by distance
     */
    fun findKNearestStops(
        userLat: Double,
        userLng: Double,
        stops: List<RouteStopDTO>,
        vehicleNames: Map<String, String>,
        k: Int = 5,
        maxDistanceKm: Double = 2.0
    ): List<NearbyStopResult> {
        val walkSpeedKmh = 4.5 // Average walking speed
        
        return stops
            .map { stop ->
                val distanceKm = Haversine.distance(userLat, userLng, stop.latitude, stop.longitude)
                val walkTimeMinutes = ((distanceKm / walkSpeedKmh) * 60).toInt()
                
                NearbyStopResult(
                    stopName = stop.name,
                    stopId = stop.id,
                    vehicleId = stop.vehicleId,
                    vehicleName = vehicleNames[stop.vehicleId] ?: "Unknown",
                    distanceKm = (distanceKm * 1000).toLong() / 1000.0, // 3 decimal places
                    walkTimeMinutes = walkTimeMinutes,
                    latitude = stop.latitude,
                    longitude = stop.longitude,
                    stopOrder = stop.stopOrder
                )
            }
            .filter { it.distanceKm <= maxDistanceKm }
            .sortedBy { it.distanceKm }
            .take(k)
    }
    
    /**
     * Find nearest stop for each vehicle
     * Useful for determining which buses are most accessible
     * 
     * @return Map of vehicleId to nearest stop for that vehicle
     */
    fun findNearestStopPerVehicle(
        userLat: Double,
        userLng: Double,
        stops: List<RouteStopDTO>,
        vehicleNames: Map<String, String>
    ): Map<String, NearbyStopResult> {
        val vehicleStops = stops.groupBy { it.vehicleId }
        
        return vehicleStops.mapValues { (vehicleId, vehicleStopList) ->
            vehicleStopList
                .map { stop ->
                    val distanceKm = Haversine.distance(userLat, userLng, stop.latitude, stop.longitude)
                    val walkTimeMinutes = ((distanceKm / 4.5) * 60).toInt()
                    
                    NearbyStopResult(
                        stopName = stop.name,
                        stopId = stop.id,
                        vehicleId = vehicleId,
                        vehicleName = vehicleNames[vehicleId] ?: "Unknown",
                        distanceKm = (distanceKm * 1000).toLong() / 1000.0,
                        walkTimeMinutes = walkTimeMinutes,
                        latitude = stop.latitude,
                        longitude = stop.longitude,
                        stopOrder = stop.stopOrder
                    )
                }
                .minByOrNull { it.distanceKm }!!
        }
    }
    
    /**
     * Find stops within walking distance grouped by vehicle
     * Returns only vehicles that have at least one walkable stop
     */
    fun findWalkableVehicles(
        userLat: Double,
        userLng: Double,
        stops: List<RouteStopDTO>,
        vehicleNames: Map<String, String>,
        maxWalkDistanceKm: Double = 1.0
    ): Map<String, List<NearbyStopResult>> {
        val allNearby = findKNearestStops(
            userLat, userLng, stops, vehicleNames,
            k = stops.size, // Get all
            maxDistanceKm = maxWalkDistanceKm
        )
        
        return allNearby.groupBy { it.vehicleId }
    }
}
