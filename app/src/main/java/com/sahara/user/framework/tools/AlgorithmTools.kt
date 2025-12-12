package com.sahara.user.framework.tools

import com.sahara.user.algorithms.*
import com.sahara.user.compute.ComputeEngine
import com.sahara.user.data.model.LiveBusLocation
import com.sahara.user.data.model.RouteStopDTO
import com.sahara.user.data.model.VehicleDTO
import com.sahara.user.framework.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Algorithm Tools - Mathematical calculations for agents.
 * 
 * Wraps existing algorithms as tools:
 * - Haversine distance calculation
 * - Route matching
 * - ETA calculation
 * - Vehicle routing optimization
 */
@Singleton
class AlgorithmTools @Inject constructor() {
    
    /**
     * Get all tools provided by this class.
     */
    fun getAllTools(): List<Tool> = listOf(
        haversineDistanceTool,
        routeMatcherTool,
        etaCalculatorTool,
        nearbyStopsTool,
        computeRouteTool,
        rankRoutesTool
    )
    
    // ==================== HAVERSINE DISTANCE TOOL ====================
    
    val haversineDistanceTool = object : BaseTool() {
        override val name = "calculate_distance"
        override val description = "Calculate straight-line distance between two coordinates using Haversine formula"
        override val inputSchema = ToolSchema(
            properties = mapOf(
                "lat1" to PropertySchema("number", "Latitude of first point"),
                "lng1" to PropertySchema("number", "Longitude of first point"),
                "lat2" to PropertySchema("number", "Latitude of second point"),
                "lng2" to PropertySchema("number", "Longitude of second point")
            ),
            required = listOf("lat1", "lng1", "lat2", "lng2")
        )
        override val outputSchema = ToolSchema(
            properties = mapOf(
                "distance_km" to PropertySchema("number", "Distance in kilometers"),
                "distance_m" to PropertySchema("number", "Distance in meters")
            )
        )
        
        override suspend fun doExecute(
            params: Map<String, Any?>,
            context: AgentContext
        ): ToolResult {
            val lat1 = (params["lat1"] as? Number)?.toDouble()
                ?: return ToolResult.failure(name, "lat1 is required")
            val lng1 = (params["lng1"] as? Number)?.toDouble()
                ?: return ToolResult.failure(name, "lng1 is required")
            val lat2 = (params["lat2"] as? Number)?.toDouble()
                ?: return ToolResult.failure(name, "lat2 is required")
            val lng2 = (params["lng2"] as? Number)?.toDouble()
                ?: return ToolResult.failure(name, "lng2 is required")
            
            val distanceKm = Haversine.distance(lat1, lng1, lat2, lng2)
            
            return ToolResult.success(name, mapOf(
                "distance_km" to distanceKm,
                "distance_m" to (distanceKm * 1000)
            ))
        }
    }
    
    // ==================== ROUTE MATCHER TOOL ====================
    
    val routeMatcherTool = object : BaseTool() {
        override val name = "match_routes"
        override val description = "Find bus routes that connect origin and destination stops"
        override val inputSchema = ToolSchema(
            properties = mapOf(
                "origin" to PropertySchema("string", "Origin stop name or search term"),
                "destination" to PropertySchema("string", "Destination stop name or search term")
            ),
            required = listOf("origin", "destination")
        )
        override val outputSchema = ToolSchema(
            properties = mapOf(
                "matches" to PropertySchema("array", "Matching routes with vehicle IDs"),
                "count" to PropertySchema("integer", "Number of matching routes")
            )
        )
        
        override suspend fun doExecute(
            params: Map<String, Any?>,
            context: AgentContext
        ): ToolResult {
            val origin = params["origin"] as? String
                ?: return ToolResult.failure(name, "origin is required")
            val destination = params["destination"] as? String
                ?: return ToolResult.failure(name, "destination is required")
            
            // Get data from context
            @Suppress("UNCHECKED_CAST")
            val vehicles = context.getData<List<VehicleDTO>>("vehicles") ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val stops = context.getData<List<RouteStopDTO>>("stops") ?: emptyList()
            
            if (vehicles.isEmpty() || stops.isEmpty()) {
                return ToolResult.failure(name, "No vehicle/stop data available")
            }
            
            // Build vehicle stops map
            val vehicleStops = stops.groupBy { it.vehicleId }
                .mapValues { (_, stopList) ->
                    stopList.sortedBy { it.stopOrder }.map { it.name to it.stopOrder }
                }
            val vehicleNames = vehicles.associate { it.id to it.name }
            
            // Find matching routes
            val matches = RouteMatcher.findMatchingRoutes(origin, destination, vehicleStops, vehicleNames)
            
            return ToolResult.success(name, mapOf(
                "matches" to matches,
                "count" to matches.size
            ))
        }
    }
    
    // ==================== ETA CALCULATOR TOOL ====================
    
    val etaCalculatorTool = object : BaseTool() {
        override val name = "calculate_eta"
        override val description = "Calculate estimated time of arrival based on distance and conditions"
        override val inputSchema = ToolSchema(
            properties = mapOf(
                "distance_km" to PropertySchema("number", "Distance in kilometers"),
                "num_stops" to PropertySchema("integer", "Number of stops on the route"),
                "current_speed" to PropertySchema("number", "Current bus speed in km/h (optional)")
            ),
            required = listOf("distance_km", "num_stops")
        )
        override val outputSchema = ToolSchema(
            properties = mapOf(
                "eta_minutes" to PropertySchema("number", "Estimated time in minutes"),
                "eta_range_min" to PropertySchema("number", "Minimum estimate"),
                "eta_range_max" to PropertySchema("number", "Maximum estimate"),
                "traffic_factor" to PropertySchema("number", "Traffic adjustment factor")
            )
        )
        
        override suspend fun doExecute(
            params: Map<String, Any?>,
            context: AgentContext
        ): ToolResult {
            val distanceKm = (params["distance_km"] as? Number)?.toDouble()
                ?: return ToolResult.failure(name, "distance_km is required")
            val numStops = (params["num_stops"] as? Number)?.toInt()
                ?: return ToolResult.failure(name, "num_stops is required")
            val currentSpeed = (params["current_speed"] as? Number)?.toDouble()
            
            val eta = ETACalculator.calculateETA(distanceKm, numStops, currentSpeed)
            val etaRange = ETACalculator.calculateETARange(eta.etaMinutes, eta.confidenceScore)
            
            return ToolResult.success(name, mapOf(
                "eta_minutes" to eta.etaMinutes,
                "eta_range_min" to etaRange.first,
                "eta_range_max" to etaRange.second,
                "traffic_factor" to eta.rushHourFactor
            ))
        }
    }
    
    // ==================== NEARBY STOPS TOOL ====================
    
    val nearbyStopsTool = object : BaseTool() {
        override val name = "find_nearby_stops"
        override val description = "Find bus stops near a given location"
        override val inputSchema = ToolSchema(
            properties = mapOf(
                "lat" to PropertySchema("number", "User latitude"),
                "lng" to PropertySchema("number", "User longitude"),
                "radius_km" to PropertySchema("number", "Search radius in km (default: 1)")
            ),
            required = listOf("lat", "lng")
        )
        override val outputSchema = ToolSchema(
            properties = mapOf(
                "stops" to PropertySchema("array", "Nearby stops with distances"),
                "count" to PropertySchema("integer", "Number of nearby stops")
            )
        )
        
        override suspend fun doExecute(
            params: Map<String, Any?>,
            context: AgentContext
        ): ToolResult {
            val lat = (params["lat"] as? Number)?.toDouble()
                ?: return ToolResult.failure(name, "lat is required")
            val lng = (params["lng"] as? Number)?.toDouble()
                ?: return ToolResult.failure(name, "lng is required")
            val radiusKm = (params["radius_km"] as? Number)?.toDouble() ?: 1.0
            
            // Get stops from context
            @Suppress("UNCHECKED_CAST")
            val allStops = context.getData<List<RouteStopDTO>>("stops") ?: emptyList()
            
            if (allStops.isEmpty()) {
                return ToolResult.failure(name, "No stop data available")
            }
            
            // Calculate distances and filter
            val nearbyStops = allStops.map { stop ->
                val distance = Haversine.distance(lat, lng, stop.latitude, stop.longitude)
                stop to distance
            }
            .filter { it.second <= radiusKm }
            .sortedBy { it.second }
            .map { (stop, distance) ->
                mapOf(
                    "name" to stop.name,
                    "latitude" to stop.latitude,
                    "longitude" to stop.longitude,
                    "distance_km" to distance,
                    "vehicle_id" to stop.vehicleId
                )
            }
            
            return ToolResult.success(name, mapOf(
                "stops" to nearbyStops,
                "count" to nearbyStops.size
            ))
        }
    }
    
    // ==================== COMPUTE ROUTE TOOL ====================
    
    val computeRouteTool = object : BaseTool() {
        override val name = "compute_route"
        override val description = "Compute the best bus route from origin to destination"
        override val inputSchema = ToolSchema(
            properties = mapOf(
                "origin" to PropertySchema("string", "Origin stop name"),
                "destination" to PropertySchema("string", "Destination stop name"),
                "user_lat" to PropertySchema("number", "User latitude (optional)"),
                "user_lng" to PropertySchema("number", "User longitude (optional)")
            ),
            required = listOf("origin", "destination")
        )
        override val outputSchema = ToolSchema(
            properties = mapOf(
                "best_route" to PropertySchema("object", "Best route details"),
                "alternatives" to PropertySchema("array", "Alternative routes"),
                "success" to PropertySchema("boolean", "Whether a route was found")
            )
        )
        
        override suspend fun doExecute(
            params: Map<String, Any?>,
            context: AgentContext
        ): ToolResult {
            val origin = params["origin"] as? String
                ?: return ToolResult.failure(name, "origin is required")
            val destination = params["destination"] as? String
                ?: return ToolResult.failure(name, "destination is required")
            val userLat = (params["user_lat"] as? Number)?.toDouble()
            val userLng = (params["user_lng"] as? Number)?.toDouble()
            
            // Get data from context
            @Suppress("UNCHECKED_CAST")
            val vehicles = context.getData<List<VehicleDTO>>("vehicles") ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val stops = context.getData<List<RouteStopDTO>>("stops") ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val locations = context.getData<List<LiveBusLocation>>("live_locations") ?: emptyList()
            
            if (vehicles.isEmpty() || stops.isEmpty()) {
                return ToolResult.failure(name, "No data available. Fetch data first.")
            }
            
            // Use ComputeEngine
            val result = ComputeEngine.computeRoute(
                origin = origin,
                destination = destination,
                vehicles = vehicles,
                allStops = stops,
                liveLocations = locations,
                userLat = userLat,
                userLng = userLng
            )
            
            return if (result.success) {
                ToolResult.success(name, mapOf(
                    "best_route" to result.data["best_route"],
                    "alternatives" to (result.data["alternatives"] ?: emptyList<Any>()),
                    "success" to true,
                    "computation_steps" to result.computationSteps
                ))
            } else {
                ToolResult.success(name, mapOf(
                    "success" to false,
                    "error" to (result.data["error"] ?: "No route found"),
                    "suggestions" to result.suggestedRefinements
                ))
            }
        }
    }
    
    // ==================== RANK ROUTES TOOL ====================
    
    val rankRoutesTool = object : BaseTool() {
        override val name = "rank_routes"
        override val description = "Rank multiple route options based on distance, time, and bus proximity"
        override val inputSchema = ToolSchema(
            properties = mapOf(
                "routes" to PropertySchema("array", "List of route match results to rank")
            ),
            required = listOf("routes")
        )
        override val outputSchema = ToolSchema(
            properties = mapOf(
                "ranked" to PropertySchema("array", "Routes ranked by score"),
                "best" to PropertySchema("object", "Best route")
            )
        )
        
        override suspend fun doExecute(
            params: Map<String, Any?>,
            context: AgentContext
        ): ToolResult {
            @Suppress("UNCHECKED_CAST")
            val routes = params["routes"] as? List<RouteMatcher.RouteMatchResult>
                ?: return ToolResult.failure(name, "routes list is required")
            
            if (routes.isEmpty()) {
                return ToolResult.failure(name, "No routes to rank")
            }
            
            // Get additional data for ranking
            @Suppress("UNCHECKED_CAST")
            val vehicles = context.getData<List<VehicleDTO>>("vehicles") ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val stops = context.getData<List<RouteStopDTO>>("stops") ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val locations = context.getData<List<LiveBusLocation>>("live_locations") ?: emptyList()
            
            // Convert to candidates and rank
            val candidates = routes.mapNotNull { match ->
                val vehicleStops = stops
                    .filter { it.vehicleId == match.vehicleId }
                    .sortedBy { it.stopOrder }
                
                val segment = vehicleStops.filter { 
                    it.stopOrder in match.originOrder..match.destinationOrder 
                }
                
                if (segment.isEmpty()) return@mapNotNull null
                
                // Calculate distance
                var distance = 0.0
                for (i in 0 until segment.size - 1) {
                    distance += Haversine.distance(
                        segment[i].latitude, segment[i].longitude,
                        segment[i + 1].latitude, segment[i + 1].longitude
                    )
                }
                
                val liveLocation = locations.find { it.busId == match.vehicleId }
                val busCurrentStop = if (liveLocation != null) {
                    vehicleStops.minByOrNull { stop ->
                        Haversine.distance(
                            liveLocation.latitude, liveLocation.longitude,
                            stop.latitude, stop.longitude
                        )
                    }?.stopOrder
                } else null
                
                val busStopsAway = if (busCurrentStop != null && busCurrentStop < match.originOrder) {
                    match.originOrder - busCurrentStop
                } else null
                
                val vehicle = vehicles.find { it.id == match.vehicleId }
                val eta = ETACalculator.calculateETA(distance, segment.size - 1)
                
                VehicleRoutingOptimizer.RouteCandidate(
                    vehicleId = match.vehicleId,
                    vehicleName = match.vehicleName,
                    vehicleNumber = vehicle?.vehicleNumber ?: "",
                    originStop = match.originStop,
                    originStopOrder = match.originOrder,
                    destinationStop = match.destinationStop,
                    destinationStopOrder = match.destinationOrder,
                    stopsInPath = listOf(match.originStop) + match.stopsInBetween + listOf(match.destinationStop),
                    distanceKm = distance,
                    baseTravelTimeMin = eta.etaMinutes,
                    busCurrentStopOrder = busCurrentStop,
                    busStopsAwayFromOrigin = busStopsAway,
                    isLive = liveLocation != null,
                    busSpeed = liveLocation?.speed?.toDouble()
                )
            }
            
            val ranked = VehicleRoutingOptimizer.rankRoutes(candidates)
            
            return ToolResult.success(name, mapOf(
                "ranked" to ranked,
                "best" to ranked.firstOrNull()
            ))
        }
    }
}
