package com.sahara.user.agents

import com.sahara.user.compute.ComputeEngine
import com.sahara.user.compute.QueryParser
import com.sahara.user.data.api.PerplexityClient
import com.sahara.user.data.api.SupabaseApiClient
import com.sahara.user.data.model.LiveBusLocation
import com.sahara.user.data.model.RouteStopDTO
import com.sahara.user.data.model.VehicleDTO
import com.sahara.user.service.LocationService
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Main Orchestrator - The Intelligent Brain
 * 
 * ARCHITECTURE:
 * 1. Parse Query  → Convert natural language to mathematical equations
 * 2. Execute Algorithms → Run Haversine, Dijkstra, ETA, RouteMatcher
 * 3. Agent Iteration → Agents communicate, refine, and validate results
 * 4. Best Result → Analyze outputs and generate optimal response
 * 
 * EXAMPLE FLOW:
 * User: "I need to go from MG Road to Electronic City"
 * 
 * Step 1: QueryParser extracts {origin: "MG Road", destination: "Electronic City"}
 * Step 2: SQLAgent fetches vehicles, stops, live locations
 * Step 3: ComputeEngine runs:
 *         - RouteMatcher → Find matching routes
 *         - Haversine → Calculate distances
 *         - ETACalculator → Estimate arrival times
 * Step 4: PathAgent validates route direction
 * Step 5: TimeAgent refines ETA with traffic
 * Step 6: BusTrackerAgent adds live bus position
 * Step 7: Iterate if needed (transfer search, etc.)
 * Step 8: Generate SHORT response with computed data
 */
@Singleton
class MainOrchestrator @Inject constructor(
    private val perplexityClient: PerplexityClient,
    private val communicationHub: AgentCommunicationHub,
    private val supabaseClient: SupabaseApiClient,
    private val locationService: LocationService
) {
    
    companion object {
        private const val TAG = "MainOrchestrator"
        private const val MAX_ITERATIONS = 3  // Max agent iterations for refinement
    }
    
    // Cached data to avoid repeated fetches
    private var cachedVehicles: List<VehicleDTO>? = null
    private var cachedStops: List<RouteStopDTO>? = null
    private var cachedLocations: List<LiveBusLocation>? = null
    private var cacheTimestamp: Long = 0
    private val CACHE_DURATION_MS = 30_000L // 30 seconds
    
    /**
     * Process user query through algorithmic pipeline
     */
    suspend fun processQuery(userId: String, query: String): OrchestratorResult {
        android.util.Log.d(TAG, "Processing: ${query.take(50)}...")
        communicationHub.startNewTask(query, userId)
        
        try {
            // Step 1: Get user location if available
            val userLocation = locationService.getCurrentLocation()
            android.util.Log.d(TAG, "User location: ${userLocation?.let { "(${it.latitude}, ${it.longitude})" } ?: "unavailable"}")
            
            // Step 2: Parse query into structured mathematical form
            val structuredQuery = QueryParser.parse(
                query = query,
                userLat = userLocation?.latitude,
                userLng = userLocation?.longitude
            )
            android.util.Log.d(TAG, "Intent: ${structuredQuery.intent}, Confidence: ${structuredQuery.confidence}")
            
            // Step 3: Handle low confidence or unknown intent
            if (structuredQuery.confidence < 0.5 || structuredQuery.intent == QueryParser.QueryIntent.UNKNOWN) {
                return handleClarificationNeeded(query, structuredQuery)
            }
            
            // Step 4: Fetch required data
            val (vehicles, stops, locations) = fetchRequiredData()
            if (vehicles.isEmpty() || stops.isEmpty()) {
                return createErrorResult(query, "Could not fetch bus data. Please try again.")
            }
            
            // Step 5: Execute algorithms based on intent
            val computeResult = executeAlgorithms(structuredQuery, vehicles, stops, locations, userLocation)
            
            // Step 6: Agent iteration for refinement
            val refinedResult = iterateAgents(structuredQuery, computeResult, vehicles, stops, locations)
            
            // Step 7: Generate SHORT, data-grounded response
            val finalResponse = generateResponse(structuredQuery.intent, refinedResult)
            
            val taskSummary = communicationHub.endTask()
            
            return OrchestratorResult(
                query = query,
                success = refinedResult.success,
                agentResults = emptyMap(), // Results are in computeResult
                finalResponse = finalResponse,
                intent = structuredQuery.intent.name.lowercase(),
                taskSummary = taskSummary
            )
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error: ${e.message}", e)
            return createErrorResult(query, "Something went wrong. Please try again.")
        }
    }
    
    /**
     * Execute appropriate algorithms based on query intent
     */
    private fun executeAlgorithms(
        query: QueryParser.StructuredQuery,
        vehicles: List<VehicleDTO>,
        stops: List<RouteStopDTO>,
        locations: List<LiveBusLocation>,
        userLocation: com.sahara.user.service.UserLocation?
    ): ComputeEngine.ComputeResult {
        
        return when (query.intent) {
            QueryParser.QueryIntent.FIND_ROUTE, QueryParser.QueryIntent.FIND_BEST_OPTION -> {
                val origin = query.parameters.origin ?: return noParamError("origin")
                val destination = query.parameters.destination ?: return noParamError("destination")
                
                android.util.Log.d(TAG, "Computing route: $origin → $destination")
                
                ComputeEngine.computeRoute(
                    origin = origin,
                    destination = destination,
                    vehicles = vehicles,
                    allStops = stops,
                    liveLocations = locations,
                    userLat = userLocation?.latitude,
                    userLng = userLocation?.longitude
                )
            }
            
            QueryParser.QueryIntent.FIND_NEARBY -> {
                val lat = userLocation?.latitude ?: query.parameters.userLat
                val lng = userLocation?.longitude ?: query.parameters.userLng
                
                if (lat == null || lng == null) {
                    return ComputeEngine.ComputeResult(
                        success = false,
                        resultType = "error",
                        data = mapOf("error" to "Location not available. Please enable GPS."),
                        confidence = 0.0,
                        computationSteps = listOf("Location required for nearby search")
                    )
                }
                
                android.util.Log.d(TAG, "Computing nearby stops at ($lat, $lng)")
                ComputeEngine.computeNearbyStops(lat, lng, stops)
            }
            
            QueryParser.QueryIntent.TRACK_BUS -> {
                val busId = query.parameters.busId ?: return noParamError("bus name")
                
                android.util.Log.d(TAG, "Tracking bus: $busId")
                computeBusLocation(busId, vehicles, stops, locations)
            }
            
            QueryParser.QueryIntent.CALCULATE_ETA -> {
                val lat = userLocation?.latitude ?: query.parameters.userLat
                val lng = userLocation?.longitude ?: query.parameters.userLng
                val busId = query.parameters.busId
                
                if (busId != null && lat != null && lng != null) {
                    ComputeEngine.computeBusArrival(busId, lat, lng, vehicles, stops, locations)
                } else if (query.parameters.stopName != null) {
                    // ETA to a specific stop
                    computeETAToStop(query.parameters.stopName!!, vehicles, stops, locations)
                } else {
                    noParamError("stop name or bus ID")
                }
            }
            
            else -> noParamError("valid query")
        }
    }
    
    /**
     * Compute current bus location
     */
    private fun computeBusLocation(
        busId: String,
        vehicles: List<VehicleDTO>,
        stops: List<RouteStopDTO>,
        locations: List<LiveBusLocation>
    ): ComputeEngine.ComputeResult {
        // Find matching bus
        val liveLocation = locations.find { loc ->
            loc.busId.contains(busId, ignoreCase = true) ||
            vehicles.find { it.id == loc.busId }?.name?.contains(busId, ignoreCase = true) == true ||
            vehicles.find { it.id == loc.busId }?.vehicleNumber?.contains(busId, ignoreCase = true) == true
        }
        
        if (liveLocation == null) {
            return ComputeEngine.ComputeResult(
                success = false,
                resultType = "bus_location",
                data = mapOf("error" to "Bus '$busId' is not currently sharing location."),
                confidence = 0.0,
                computationSteps = listOf("Bus not found in live locations")
            )
        }
        
        // Find nearest stop
        val routeStops = stops.filter { it.vehicleId == liveLocation.busId }.sortedBy { it.stopOrder }
        val nearestStop = routeStops.minByOrNull { stop ->
            com.sahara.user.algorithms.Haversine.distance(
                liveLocation.latitude, liveLocation.longitude,
                stop.latitude, stop.longitude
            )
        }
        
        val vehicle = vehicles.find { it.id == liveLocation.busId }
        val isMoving = (liveLocation.speed?.toDouble() ?: 0.0) > 2.0
        val freshness = calculateFreshness(liveLocation.timestamp)
        
        return ComputeEngine.ComputeResult(
            success = true,
            resultType = "bus_location",
            data = mapOf(
                "bus_id" to liveLocation.busId,
                "bus_name" to (vehicle?.name ?: busId),
                "vehicle_number" to (vehicle?.vehicleNumber ?: ""),
                "lat" to liveLocation.latitude,
                "lng" to liveLocation.longitude,
                "speed" to (liveLocation.speed ?: 0.0),
                "is_moving" to isMoving,
                "nearest_stop" to (nearestStop?.name ?: "Unknown"),
                "freshness" to freshness
            ),
            confidence = when (freshness) {
                "fresh" -> 0.95
                "recent" -> 0.7
                else -> 0.4
            },
            computationSteps = listOf(
                "Found bus at (${liveLocation.latitude}, ${liveLocation.longitude})",
                "Nearest stop: ${nearestStop?.name}",
                "Status: ${if (isMoving) "Moving" else "Stopped"}"
            )
        )
    }
    
    /**
     * Compute ETA to a specific stop
     */
    private fun computeETAToStop(
        stopName: String,
        vehicles: List<VehicleDTO>,
        stops: List<RouteStopDTO>,
        locations: List<LiveBusLocation>
    ): ComputeEngine.ComputeResult {
        // Find stops matching the name
        val matchedStops = stops.filter { it.name.contains(stopName, ignoreCase = true) }
        
        if (matchedStops.isEmpty()) {
            return ComputeEngine.ComputeResult(
                success = false,
                resultType = "eta",
                data = mapOf("error" to "Stop '$stopName' not found."),
                confidence = 0.0,
                computationSteps = emptyList()
            )
        }
        
        // Find buses heading to these stops
        val busesWithETA = matchedStops.mapNotNull { stop ->
            val liveLocation = locations.find { it.busId == stop.vehicleId } ?: return@mapNotNull null
            val routeStops = stops.filter { it.vehicleId == stop.vehicleId }.sortedBy { it.stopOrder }
            
            // Find bus current stop
            val busCurrentStop = routeStops.minByOrNull { rs ->
                com.sahara.user.algorithms.Haversine.distance(
                    liveLocation.latitude, liveLocation.longitude,
                    rs.latitude, rs.longitude
                )
            } ?: return@mapNotNull null
            
            // Bus must be before target stop
            if (busCurrentStop.stopOrder >= stop.stopOrder) return@mapNotNull null
            
            val stopsAway = stop.stopOrder - busCurrentStop.stopOrder
            val eta = com.sahara.user.algorithms.ETACalculator.calculateETA(
                distanceKm = stopsAway * 0.5, // Approximate 500m per stop
                numStops = stopsAway,
                currentSpeed = liveLocation.speed?.toDouble()
            )
            
            val vehicle = vehicles.find { it.id == stop.vehicleId }
            
            mapOf(
                "bus_name" to (vehicle?.name ?: stop.vehicleId),
                "stops_away" to stopsAway,
                "eta_minutes" to eta.etaMinutes.toInt()
            )
        }.sortedBy { it["eta_minutes"] as Int }
        
        if (busesWithETA.isEmpty()) {
            return ComputeEngine.ComputeResult(
                success = true,
                resultType = "eta",
                data = mapOf("message" to "No buses currently heading to '$stopName'."),
                confidence = 0.7,
                computationSteps = listOf("No live buses found heading to this stop")
            )
        }
        
        val nextBus = busesWithETA.first()
        
        return ComputeEngine.ComputeResult(
            success = true,
            resultType = "eta",
            data = mapOf(
                "stop_name" to stopName,
                "next_bus" to nextBus,
                "all_buses" to busesWithETA,
                "buses_count" to busesWithETA.size
            ),
            confidence = 0.85,
            computationSteps = listOf(
                "Found ${busesWithETA.size} buses heading to $stopName",
                "Next: ${nextBus["bus_name"]} in ${nextBus["eta_minutes"]} min"
            )
        )
    }
    
    /**
     * Iterate agents for refinement
     */
    private suspend fun iterateAgents(
        query: QueryParser.StructuredQuery,
        initialResult: ComputeEngine.ComputeResult,
        vehicles: List<VehicleDTO>,
        stops: List<RouteStopDTO>,
        locations: List<LiveBusLocation>
    ): ComputeEngine.ComputeResult {
        var currentResult = initialResult
        var iteration = 0
        
        // Iterate if needed (e.g., no direct route found)
        while (iteration < MAX_ITERATIONS && currentResult.suggestedRefinements.isNotEmpty()) {
            android.util.Log.d(TAG, "Iteration ${iteration + 1}: ${currentResult.suggestedRefinements}")
            
            // Check for transfer suggestion
            if (currentResult.suggestedRefinements.contains("Check for transfer options") &&
                query.intent == QueryParser.QueryIntent.FIND_ROUTE) {
                
                // Try to find transfer route
                // This would call TransferAgent in full implementation
                android.util.Log.d(TAG, "Attempting transfer route search...")
                break // For now, exit loop
            }
            
            iteration++
        }
        
        return currentResult
    }
    
    /**
     * Generate SHORT response from computed result
     */
    private fun generateResponse(
        intent: QueryParser.QueryIntent,
        result: ComputeEngine.ComputeResult
    ): String {
        if (!result.success) {
            return (result.data["error"] as? String) ?: "No results found."
        }
        
        return when (intent) {
            QueryParser.QueryIntent.FIND_ROUTE, QueryParser.QueryIntent.FIND_BEST_OPTION -> {
                val route = result.data["best_route"] as? Map<*, *> ?: return "No route found."
                val vehicleName = route["vehicle_name"] ?: "Bus"
                val vehicleNum = route["vehicle_number"] ?: ""
                val distanceKm = route["distance_km"] ?: "?"
                val etaMin = route["eta_minutes"] ?: "?"
                val etaRangeMin = route["eta_range_min"] ?: etaMin
                val etaRangeMax = route["eta_range_max"] ?: etaMin
                val stopsAway = route["bus_stops_away"] as? Int ?: -1
                val isLive = route["is_live"] == true
                
                buildString {
                    append("🚌 **$vehicleName")
                    if (vehicleNum.toString().isNotBlank()) append(" ($vehicleNum)")
                    append("** • $distanceKm km • ~$etaRangeMin-$etaRangeMax min")
                    if (isLive && stopsAway >= 0) {
                        append(" • Bus is $stopsAway stops away")
                    }
                }
            }
            
            QueryParser.QueryIntent.TRACK_BUS -> {
                val busName = result.data["bus_name"] ?: "Bus"
                val nearestStop = result.data["nearest_stop"] ?: "Unknown"
                val isMoving = result.data["is_moving"] == true
                val freshness = result.data["freshness"] as? String ?: "stale"
                
                val status = if (isMoving) "moving" else "stopped"
                val note = if (freshness == "stale") " (last update >10min ago)" else ""
                
                "📍 **$busName** is $status near **$nearestStop**$note"
            }
            
            QueryParser.QueryIntent.CALCULATE_ETA -> {
                val nextBus = result.data["next_bus"] as? Map<*, *>
                if (nextBus != null) {
                    val busName = nextBus["bus_name"] ?: "Bus"
                    val eta = nextBus["eta_minutes"] ?: "?"
                    val stopsAway = nextBus["stops_away"] ?: "?"
                    "⏱️ **$busName** arriving in ~$eta min ($stopsAway stops away)"
                } else {
                    result.data["message"] as? String ?: "No ETA available."
                }
            }
            
            QueryParser.QueryIntent.FIND_NEARBY -> {
                val stops = result.data["stops"] as? List<*> ?: return "No nearby stops found."
                val stopNames = stops.take(3).mapNotNull { (it as? Map<*, *>)?.get("name")?.toString() }
                if (stopNames.isEmpty()) return "No bus stops within 1km of your location."
                "📍 Nearby: ${stopNames.joinToString(", ")}"
            }
            
            else -> "I couldn't process that request. Try asking about routes, bus locations, or ETAs."
        }
    }
    
    // ==================== Helper Methods ====================
    
    private suspend fun fetchRequiredData(): Triple<List<VehicleDTO>, List<RouteStopDTO>, List<LiveBusLocation>> {
        val now = System.currentTimeMillis()
        
        // Use cache if valid
        if (now - cacheTimestamp < CACHE_DURATION_MS && 
            cachedVehicles != null && cachedStops != null && cachedLocations != null) {
            return Triple(cachedVehicles!!, cachedStops!!, cachedLocations!!)
        }
        
        // Fetch fresh data
        val vehicles = supabaseClient.getActiveVehicles().getOrElse { emptyList() }
        val stops = supabaseClient.getAllRouteStops().getOrElse { emptyList() }
        val locations = supabaseClient.getLiveBusLocations().getOrElse { emptyList() }
        
        // Update cache
        cachedVehicles = vehicles
        cachedStops = stops
        cachedLocations = locations
        cacheTimestamp = now
        
        android.util.Log.d(TAG, "Data fetched: ${vehicles.size} vehicles, ${stops.size} stops, ${locations.size} live")
        
        return Triple(vehicles, stops, locations)
    }
    
    private fun calculateFreshness(timestamp: Long): String {
        val age = System.currentTimeMillis() - timestamp
        return when {
            age < 2 * 60 * 1000 -> "fresh"      // < 2 min
            age < 10 * 60 * 1000 -> "recent"    // < 10 min
            else -> "stale"
        }
    }
    
    private fun noParamError(param: String): ComputeEngine.ComputeResult {
        return ComputeEngine.ComputeResult(
            success = false,
            resultType = "error",
            data = mapOf("error" to "Please specify $param."),
            confidence = 0.0,
            computationSteps = listOf("Missing required parameter: $param")
        )
    }
    
    private fun handleClarificationNeeded(
        query: String,
        structuredQuery: QueryParser.StructuredQuery
    ): OrchestratorResult {
        val message = when {
            query.lowercase().contains("hi") || query.lowercase().contains("hello") ->
                "Hello! I can help you find bus routes, track buses, or get ETAs."
            query.lowercase().contains("thank") ->
                "You're welcome! 🚌"
            query.lowercase().contains("help") ->
                "Ask me: \"Route from A to B\", \"Where is bus X?\", or \"Next bus to Y?\""
            else ->
                "Could you be more specific? I can help with routes, bus locations, or arrival times."
        }
        
        return OrchestratorResult(
            query = query,
            success = true,
            agentResults = emptyMap(),
            finalResponse = message,
            intent = "clarification"
        )
    }
    
    private fun createErrorResult(query: String, error: String): OrchestratorResult {
        return OrchestratorResult(
            query = query,
            success = false,
            agentResults = emptyMap(),
            finalResponse = error,
            intent = "error"
        )
    }
}

// ==================== Data Classes ====================

data class OrchestratorResult(
    val query: String,
    val success: Boolean,
    val agentResults: Map<String, AgentResult>,
    val finalResponse: String,
    val needsClarification: Boolean = false,
    val intent: String? = null,
    val taskSummary: TaskSummary? = null
)
