package com.sahara.user.compute

import com.sahara.user.algorithms.*
import com.sahara.user.data.model.LiveBusLocation
import com.sahara.user.data.model.RouteStopDTO
import com.sahara.user.data.model.VehicleDTO

/**
 * Compute Engine - The Algorithmic Brain
 * 
 * Executes mathematical algorithms on data to produce results.
 * This is the core computational component that:
 * 
 * 1. Takes structured queries from QueryParser
 * 2. Executes appropriate algorithms (Haversine, Dijkstra, ETA, etc.)
 * 3. Produces computed results with confidence scores
 * 4. Enables iterative refinement by agents
 * 
 * FLOW:
 * Query → Parse → Execute Algorithms → Compute Results → Agent Analysis → Final Output
 */
object ComputeEngine {
    
    /**
     * Computed result from algorithm execution
     */
    data class ComputeResult(
        val success: Boolean,
        val resultType: String,
        val data: Map<String, Any>,
        val confidence: Double,
        val computationSteps: List<String>,  // For debugging/explanation
        val suggestedRefinements: List<String> = emptyList()  // For iteration
    )
    
    /**
     * Route computation result
     */
    data class RouteResult(
        val vehicleId: String,
        val vehicleName: String,
        val vehicleNumber: String,
        val originStop: String,
        val destinationStop: String,
        val route: List<String>,
        val distanceKm: Double,
        val etaMinutes: Int,
        val etaRange: Pair<Int, Int>,
        val busStopsAway: Int,
        val isLive: Boolean,
        val confidence: Double
    )
    
    /**
     * Execute route finding computation
     * 
     * ALGORITHM PIPELINE:
     * 1. RouteMatcher → Find matching routes
     * 2. Haversine → Calculate distances
     * 3. ETACalculator → Estimate arrival times
     * 4. Rank by (live_bus_proximity, distance, eta)
     */
    fun computeRoute(
        origin: String,
        destination: String,
        vehicles: List<VehicleDTO>,
        allStops: List<RouteStopDTO>,
        liveLocations: List<LiveBusLocation>,
        userLat: Double? = null,
        userLng: Double? = null
    ): ComputeResult {
        val steps = mutableListOf<String>()
        steps.add("Step 1: Building route graph from ${vehicles.size} vehicles, ${allStops.size} stops")
        
        // Step 1: Build vehicle-stops map
        val vehicleStops = allStops.groupBy { it.vehicleId }
            .mapValues { (_, stops) -> 
                stops.sortedBy { it.stopOrder }.map { it.name to it.stopOrder }
            }
        val vehicleNames = vehicles.associate { it.id to it.name }
        
        // Step 2: Find matching routes using RouteMatcher
        steps.add("Step 2: Matching '$origin' → '$destination' using RouteMatcher")
        val matchedRoutes = RouteMatcher.findMatchingRoutes(
            origin = origin,
            destination = destination,
            vehicleStops = vehicleStops,
            vehicleNames = vehicleNames
        )
        
        if (matchedRoutes.isEmpty()) {
            steps.add("No matching routes found")
            return ComputeResult(
                success = false,
                resultType = "route",
                data = mapOf("error" to "No route found from '$origin' to '$destination'"),
                confidence = 0.0,
                computationSteps = steps,
                suggestedRefinements = listOf(
                    "Try alternative stop names",
                    "Check for transfer options"
                )
            )
        }
        
        steps.add("Found ${matchedRoutes.size} matching routes")
        
        // Step 3: Calculate distances using Haversine
        steps.add("Step 3: Calculating distances using Haversine formula")
        val routeResults = matchedRoutes.map { match ->
            val vehicleStopList = allStops
                .filter { it.vehicleId == match.vehicleId }
                .sortedBy { it.stopOrder }
            
            // Get stops in segment
            val segment = vehicleStopList.filter { 
                it.stopOrder >= match.originOrder && it.stopOrder <= match.destinationOrder 
            }
            
            // Calculate distance: sum of Haversine between consecutive stops
            var totalDistance = 0.0
            for (i in 0 until segment.size - 1) {
                totalDistance += Haversine.distance(
                    segment[i].latitude, segment[i].longitude,
                    segment[i + 1].latitude, segment[i + 1].longitude
                )
            }
            
            // Find live bus position
            val liveLocation = liveLocations.find { it.busId == match.vehicleId }
            var busStopsAway = -1
            
            if (liveLocation != null) {
                // Find which stop bus is at
                val nearestStop = vehicleStopList.minByOrNull { stop ->
                    Haversine.distance(
                        liveLocation.latitude, liveLocation.longitude,
                        stop.latitude, stop.longitude
                    )
                }
                nearestStop?.let { stop ->
                    busStopsAway = match.originOrder - stop.stopOrder
                    if (busStopsAway < 0) busStopsAway = -1 // Bus has passed
                }
            }
            
            // Step 4: Calculate ETA
            val eta = ETACalculator.calculateETA(
                distanceKm = totalDistance,
                numStops = segment.size - 1,
                currentSpeed = liveLocation?.speed?.toDouble()
            )
            val etaRange = ETACalculator.calculateETARange(eta.etaMinutes, eta.confidenceScore)
            
            val vehicle = vehicles.find { it.id == match.vehicleId }
            
            RouteResult(
                vehicleId = match.vehicleId,
                vehicleName = match.vehicleName,
                vehicleNumber = vehicle?.vehicleNumber ?: "",
                originStop = match.originStop,
                destinationStop = match.destinationStop,
                route = listOf(match.originStop) + match.stopsInBetween + listOf(match.destinationStop),
                distanceKm = totalDistance,
                etaMinutes = eta.etaMinutes.toInt(),
                etaRange = etaRange,
                busStopsAway = busStopsAway,
                isLive = liveLocation != null && busStopsAway >= 0,
                confidence = match.matchConfidence * eta.confidenceScore
            )
        }
        
        // Step 5: Rank routes
        steps.add("Step 4: Ranking ${routeResults.size} routes by live bus proximity and ETA")
        val rankedRoutes = routeResults.sortedWith(compareBy(
            // Prefer live buses that are close
            { if (it.isLive && it.busStopsAway >= 0) it.busStopsAway else Int.MAX_VALUE },
            // Then by ETA
            { it.etaMinutes },
            // Then by distance
            { it.distanceKm }
        ))
        
        val bestRoute = rankedRoutes.first()
        steps.add("Best route: ${bestRoute.vehicleName} (${bestRoute.distanceKm.format(2)} km, ~${bestRoute.etaMinutes} min)")
        
        return ComputeResult(
            success = true,
            resultType = "route",
            data = mapOf(
                "best_route" to bestRoute.toMap(),
                "all_routes" to rankedRoutes.map { it.toMap() },
                "routes_count" to rankedRoutes.size
            ),
            confidence = bestRoute.confidence,
            computationSteps = steps
        )
    }
    
    /**
     * Compute nearest stops to user location
     */
    fun computeNearbyStops(
        userLat: Double,
        userLng: Double,
        allStops: List<RouteStopDTO>,
        radiusKm: Double = 1.0
    ): ComputeResult {
        val steps = mutableListOf<String>()
        steps.add("Step 1: Finding stops within ${radiusKm}km of ($userLat, $userLng)")
        
        // Calculate distance to each stop using Haversine
        val nearbyStops = allStops.mapNotNull { stop ->
            val distance = Haversine.distance(userLat, userLng, stop.latitude, stop.longitude)
            if (distance <= radiusKm) {
                mapOf(
                    "name" to stop.name,
                    "vehicle_id" to stop.vehicleId,
                    "distance_m" to (distance * 1000).toInt(),
                    "lat" to stop.latitude,
                    "lng" to stop.longitude
                )
            } else null
        }.sortedBy { it["distance_m"] as Int }
        
        steps.add("Found ${nearbyStops.size} stops within radius")
        
        return ComputeResult(
            success = nearbyStops.isNotEmpty(),
            resultType = "nearby_stops",
            data = mapOf(
                "stops" to nearbyStops,
                "count" to nearbyStops.size,
                "search_radius_km" to radiusKm
            ),
            confidence = if (nearbyStops.isNotEmpty()) 0.95 else 0.0,
            computationSteps = steps
        )
    }
    
    /**
     * Compute bus arrival time at user's location
     */
    fun computeBusArrival(
        busId: String,
        userLat: Double,
        userLng: Double,
        vehicles: List<VehicleDTO>,
        allStops: List<RouteStopDTO>,
        liveLocations: List<LiveBusLocation>
    ): ComputeResult {
        val steps = mutableListOf<String>()
        steps.add("Step 1: Finding bus '$busId'")
        
        // Find the bus
        val liveLocation = liveLocations.find { 
            it.busId.contains(busId, ignoreCase = true) 
        } ?: liveLocations.find { location ->
            vehicles.find { it.id == location.busId }?.name?.contains(busId, ignoreCase = true) == true
        }
        
        if (liveLocation == null) {
            return ComputeResult(
                success = false,
                resultType = "bus_arrival",
                data = mapOf("error" to "Bus '$busId' not found or not sharing location"),
                confidence = 0.0,
                computationSteps = steps
            )
        }
        
        steps.add("Step 2: Bus found at (${liveLocation.latitude}, ${liveLocation.longitude})")
        
        // Get route stops for this bus
        val routeStops = allStops
            .filter { it.vehicleId == liveLocation.busId }
            .sortedBy { it.stopOrder }
            .map { it.latitude to it.longitude }
        
        if (routeStops.isEmpty()) {
            return ComputeResult(
                success = false,
                resultType = "bus_arrival",
                data = mapOf("error" to "No route information for this bus"),
                confidence = 0.0,
                computationSteps = steps
            )
        }
        
        steps.add("Step 3: Calculating intercept using ETACalculator")
        
        // Calculate arrival using ETACalculator
        val arrivalResult = ETACalculator.calculateBusArrivalToUser(
            busLat = liveLocation.latitude,
            busLng = liveLocation.longitude,
            busSpeed = liveLocation.speed?.toDouble() ?: 20.0,
            userLat = userLat,
            userLng = userLng,
            stopsOnRoute = routeStops
        )
        
        if (arrivalResult == null) {
            return ComputeResult(
                success = false,
                resultType = "bus_arrival",
                data = mapOf("error" to "Bus has already passed your location or is not heading your way"),
                confidence = 0.0,
                computationSteps = steps
            )
        }
        
        val (arrivalMinutes, stopIndex) = arrivalResult
        val nearestStop = allStops
            .filter { it.vehicleId == liveLocation.busId }
            .sortedBy { it.stopOrder }
            .getOrNull(stopIndex)
        
        steps.add("Step 4: ETA calculated: ${arrivalMinutes.toInt()} minutes to stop ${nearestStop?.name}")
        
        val vehicle = vehicles.find { it.id == liveLocation.busId }
        
        return ComputeResult(
            success = true,
            resultType = "bus_arrival",
            data = mapOf(
                "bus_id" to liveLocation.busId,
                "bus_name" to (vehicle?.name ?: busId),
                "arrival_minutes" to arrivalMinutes.toInt(),
                "nearest_stop" to (nearestStop?.name ?: "Unknown"),
                "bus_speed" to (liveLocation.speed ?: 0.0),
                "bus_lat" to liveLocation.latitude,
                "bus_lng" to liveLocation.longitude
            ),
            confidence = 0.85,
            computationSteps = steps
        )
    }
    
    // Helper extensions
    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
    
    private fun RouteResult.toMap(): Map<String, Any> = mapOf(
        "vehicle_id" to vehicleId,
        "vehicle_name" to vehicleName,
        "vehicle_number" to vehicleNumber,
        "origin_stop" to originStop,
        "destination_stop" to destinationStop,
        "route" to route,
        "distance_km" to distanceKm.format(2),
        "eta_minutes" to etaMinutes,
        "eta_range_min" to etaRange.first,
        "eta_range_max" to etaRange.second,
        "bus_stops_away" to busStopsAway,
        "is_live" to isLive,
        "confidence" to confidence
    )
}
