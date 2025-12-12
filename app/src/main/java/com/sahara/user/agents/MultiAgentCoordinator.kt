package com.sahara.user.agents

import com.sahara.user.algorithms.*
import com.sahara.user.compute.ComputeEngine
import com.sahara.user.compute.QueryParser
import com.sahara.user.data.api.SupabaseApiClient
import com.sahara.user.data.model.LiveBusLocation
import com.sahara.user.data.model.RouteStopDTO
import com.sahara.user.data.model.VehicleDTO
import com.sahara.user.service.LocationService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-Agent Coordinator
 * 
 * Orchestrates proper agent calling with full context sharing.
 * 
 * KEY PRINCIPLE: AI agents ANALYZE, algorithms CALCULATE
 * 
 * FLOW:
 * 1. Parse query → Extract intent and parameters
 * 2. Fetch data → Load vehicles, stops, locations from Supabase
 * 3. Store in shared context → All agents can access
 * 4. Execute algorithms → Haversine, ETA, RouteMatcher, VRP
 * 5. Call appropriate agents → Each agent analyzes algorithm results
 * 6. Agents communicate → Share insights, request more data
 * 7. Iterate if needed → Refine results
 * 8. Generate response → Based on analyzed results
 */
@Singleton
class MultiAgentCoordinator @Inject constructor(
    private val communicationHub: AgentCommunicationHub,
    private val supabaseClient: SupabaseApiClient,
    private val locationService: LocationService,
    // Inject specialized agents
    private val dataAgent: SQLAgent,
    private val algorithmAgent: AlgorithmAgent,
    private val pathAgent: PathAgent,
    private val timeAgent: TimeAgent,
    private val busTrackerAgent: BusTrackerAgent,
    private val transferAgent: TransferAgent,
    private val userContextAgent: UserContextAgent,
    private val analyticsAgent: AnalyticsAgent
) {
    
    companion object {
        private const val TAG = "MultiAgentCoordinator"
    }
    
    init {
        // Register all agents with their personas
        registerAgents()
    }
    
    private fun registerAgents() {
        dataAgent.register(communicationHub)
        algorithmAgent.register(communicationHub)
        pathAgent.register(communicationHub)
        timeAgent.register(communicationHub)
        busTrackerAgent.register(communicationHub)
        transferAgent.register(communicationHub)
        userContextAgent.register(communicationHub)
        analyticsAgent.register(communicationHub)
    }
    
    /**
     * Process query with proper multi-agent coordination
     */
    suspend fun processQuery(userId: String, query: String): CoordinatorResult {
        android.util.Log.d(TAG, "=== Starting Query Processing ===")
        android.util.Log.d(TAG, "Query: $query")
        
        // Start new task in communication hub
        communicationHub.startNewTask(query, userId)
        
        try {
            // ========== STEP 1: Get User Location ==========
            val userLocation = locationService.getCurrentLocation()
            android.util.Log.d(TAG, "User location: ${userLocation?.let { "(${it.latitude}, ${it.longitude})" } ?: "unavailable"}")
            
            // ========== STEP 2: Parse Query (NL → Equations) ==========
            val parsedQuery = QueryParser.parse(
                query = query,
                userLat = userLocation?.latitude,
                userLng = userLocation?.longitude
            )
            android.util.Log.d(TAG, "Intent: ${parsedQuery.intent}, Confidence: ${parsedQuery.confidence}")
            
            // Store parsed query in shared context
            communicationHub.storeSharedData(
                userLocation = userLocation?.let { it.latitude to it.longitude },
                parsedQuery = mapOf(
                    "intent" to parsedQuery.intent.name,
                    "confidence" to parsedQuery.confidence,
                    "origin" to (parsedQuery.parameters.origin ?: ""),
                    "destination" to (parsedQuery.parameters.destination ?: ""),
                    "bus_id" to (parsedQuery.parameters.busId ?: ""),
                    "stop_name" to (parsedQuery.parameters.stopName ?: "")
                )
            )
            
            // Handle low confidence
            if (parsedQuery.confidence < 0.5) {
                return handleLowConfidence(query, parsedQuery)
            }
            
            // ========== STEP 3: Call DataAgent to Fetch from Supabase ==========
            val dataResult = callDataAgent(parsedQuery)
            if (!dataResult.success) {
                return createErrorResult(query, "Could not fetch bus data. Please try again.")
            }
            
            // Extract data and store in shared context
            @Suppress("UNCHECKED_CAST")
            val vehicles = dataResult.data["vehicles"] as? List<VehicleDTO> ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val stops = dataResult.data["stops"] as? List<RouteStopDTO> ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val locations = dataResult.data["locations"] as? List<LiveBusLocation> ?: emptyList()
            
            communicationHub.storeSharedData(
                vehicles = vehicles,
                stops = stops,
                liveLocations = locations
            )
            
            android.util.Log.d(TAG, "Data loaded: ${vehicles.size} vehicles, ${stops.size} stops, ${locations.size} live")
            
            // ========== STEP 4: Run AlgorithmAgent (OR Algorithms) ==========
            android.util.Log.d(TAG, "Running AlgorithmAgent for OR algorithms")
            val algorithmAgentInput = mutableMapOf<String, Any>(
                "intent" to parsedQuery.intent.name,
                "origin" to (parsedQuery.parameters.origin ?: ""),
                "destination" to (parsedQuery.parameters.destination ?: ""),
                "bus_id" to (parsedQuery.parameters.busId ?: "")
            )
            userLocation?.let {
                algorithmAgentInput["user_lat"] = it.latitude
                algorithmAgentInput["user_lng"] = it.longitude
            }
            
            communicationHub.recordAgentCall(
                calledBy = "Coordinator",
                agentName = "AlgorithmAgent",
                purpose = "Execute OR algorithms (KNN, TSP, Fixed Route Match, MCDA)",
                inputSummary = "Intent: ${parsedQuery.intent}"
            )
            
            val algorithmAgentResult = algorithmAgent.execute(algorithmAgentInput)
            
            communicationHub.completeAgentCall(
                agentName = "AlgorithmAgent",
                success = algorithmAgentResult.success,
                resultSummary = algorithmAgentResult.message ?: "Algorithms executed"
            )
            
            android.util.Log.d(TAG, "AlgorithmAgent: ${algorithmAgentResult.message}")
            
            // ========== STEP 5: Execute Additional Algorithms (CALCULATIONS) ==========
            val algorithmResults = executeAlgorithms(parsedQuery, vehicles, stops, locations, userLocation)
            
            // Merge AlgorithmAgent results with legacy algorithm results
            @Suppress("UNCHECKED_CAST")
            val orAlgorithmResults = algorithmAgentResult.data["results"] as? Map<String, Any> ?: emptyMap()
            algorithmResults.putAll(orAlgorithmResults)
            
            // Store algorithm results for agent analysis
            algorithmResults.forEach { (name, result) ->
                communicationHub.storeAlgorithmResult(name, result)
            }
            
            // ========== STEP 5: Call Appropriate Agents (ANALYSIS) ==========
            val agentAnalysis = callAgentsForAnalysis(parsedQuery, algorithmResults)
            
            // ========== STEP 6: Check if Transfer Needed ==========
            val needsTransfer = checkNeedsTransfer(algorithmResults, agentAnalysis)
            if (needsTransfer) {
                android.util.Log.d(TAG, "No direct route - calling TransferAgent")
                val transferResult = callTransferAgent(parsedQuery, vehicles, stops, locations)
                agentAnalysis["TransferAgent"] = transferResult
            }
            
            // ========== STEP 7: Generate Response from Analysis ==========
            val response = generateResponseFromAnalysis(parsedQuery.intent, algorithmResults, agentAnalysis)
            
            // End task and get summary
            val taskSummary = communicationHub.endTask()
            
            android.util.Log.d(TAG, "=== Query Processing Complete ===")
            android.util.Log.d(TAG, "Agents called: ${taskSummary.agentsCalled}")
            android.util.Log.d(TAG, "Messages: ${taskSummary.messagesExchanged}")
            
            return CoordinatorResult(
                query = query,
                success = true,
                response = response,
                intent = parsedQuery.intent.name,
                agentsCalled = taskSummary.agentsCalled,
                algorithmResults = algorithmResults.keys.toList(),
                taskSummary = taskSummary
            )
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error: ${e.message}", e)
            return createErrorResult(query, "Something went wrong. Please try again.")
        }
    }
    
    // ==================== AGENT CALLING ====================
    
    private suspend fun callDataAgent(query: QueryParser.StructuredQuery): AgentResult {
        communicationHub.recordAgentCall(
            calledBy = "Coordinator",
            agentName = "SQLAgent",
            purpose = "Fetch vehicles, stops, and live locations",
            inputSummary = "Intent: ${query.intent}"
        )
        
        // Build input for data agent
        val input = mutableMapOf<String, Any>(
            "intent" to query.intent.name,
            "origin" to (query.parameters.origin ?: ""),
            "destination" to (query.parameters.destination ?: ""),
            "bus_id" to (query.parameters.busId ?: ""),
            "fetch_all" to true  // Fetch all data for shared context
        )
        
        val result = dataAgent.execute(input)
        
        communicationHub.completeAgentCall(
            agentName = "SQLAgent",
            success = result.success,
            resultSummary = result.message ?: "Data fetched"
        )
        
        return result
    }
    
    private suspend fun callAgentsForAnalysis(
        query: QueryParser.StructuredQuery,
        algorithmResults: Map<String, Any>
    ): MutableMap<String, AgentResult> {
        val results = mutableMapOf<String, AgentResult>()
        
        // Determine which agents to call based on intent
        val agentsToCall = query.requiredAgents.filter { it != "SQLAgent" }
        
        for (agentName in agentsToCall) {
            val input = buildAgentInput(agentName, query, algorithmResults)
            
            communicationHub.recordAgentCall(
                calledBy = "Coordinator",
                agentName = agentName,
                purpose = "Analyze ${getAgentPurpose(agentName)}",
                inputSummary = "Algorithm results available"
            )
            
            val result = when (agentName) {
                "PathAgent" -> pathAgent.execute(input)
                "TimeAgent" -> timeAgent.execute(input)
                "BusTrackerAgent" -> busTrackerAgent.execute(input)
                "UserContextAgent" -> userContextAgent.execute(input)
                "AnalyticsAgent" -> analyticsAgent.execute(input)
                else -> continue
            }
            
            results[agentName] = result
            
            communicationHub.completeAgentCall(
                agentName = agentName,
                success = result.success,
                resultSummary = result.message ?: "Analysis complete"
            )
            
            // Agent can suggest next agent
            result.suggestedNextAgent?.let { nextAgent ->
                if (nextAgent !in results && nextAgent in communicationHub.getRegisteredAgents()) {
                    android.util.Log.d(TAG, "$agentName suggests calling $nextAgent")
                    // This enables agent-to-agent recommendation
                }
            }
        }
        
        return results
    }
    
    private suspend fun callTransferAgent(
        query: QueryParser.StructuredQuery,
        vehicles: List<VehicleDTO>,
        stops: List<RouteStopDTO>,
        locations: List<LiveBusLocation>
    ): AgentResult {
        communicationHub.recordAgentCall(
            calledBy = "Coordinator",
            agentName = "TransferAgent",
            purpose = "Find multi-bus route with transfer",
            inputSummary = "Origin: ${query.parameters.origin}, Dest: ${query.parameters.destination}"
        )
        
        val input = mapOf<String, Any>(
            "origin" to (query.parameters.origin ?: ""),
            "destination" to (query.parameters.destination ?: ""),
            "vehicles" to vehicles,
            "stops" to stops,
            "locations" to locations
        )
        
        val result = transferAgent.execute(input)
        
        communicationHub.completeAgentCall(
            agentName = "TransferAgent",
            success = result.success,
            resultSummary = result.message ?: "Transfer search complete"
        )
        
        return result
    }
    
    // ==================== ALGORITHM EXECUTION ====================
    
    private fun executeAlgorithms(
        query: QueryParser.StructuredQuery,
        vehicles: List<VehicleDTO>,
        stops: List<RouteStopDTO>,
        locations: List<LiveBusLocation>,
        userLocation: com.sahara.user.service.UserLocation?
    ): MutableMap<String, Any> {
        val results = mutableMapOf<String, Any>()
        
        when (query.intent) {
            QueryParser.QueryIntent.FIND_ROUTE, QueryParser.QueryIntent.FIND_BEST_OPTION -> {
                // Run RouteMatcher
                val matchResult = runRouteMatcher(
                    query.parameters.origin ?: "",
                    query.parameters.destination ?: "",
                    vehicles, stops
                )
                results["RouteMatcher"] = matchResult
                
                // Run VehicleRoutingOptimizer if matches found
                if (matchResult.isNotEmpty()) {
                    val rankedRoutes = runVehicleRoutingOptimizer(matchResult, vehicles, stops, locations)
                    results["VehicleRouting"] = rankedRoutes
                }
                
                // Run ComputeEngine for full route computation
                val computeResult = ComputeEngine.computeRoute(
                    origin = query.parameters.origin ?: "",
                    destination = query.parameters.destination ?: "",
                    vehicles = vehicles,
                    allStops = stops,
                    liveLocations = locations,
                    userLat = userLocation?.latitude,
                    userLng = userLocation?.longitude
                )
                results["ComputeEngine"] = computeResult
            }
            
            QueryParser.QueryIntent.FIND_NEARBY -> {
                if (userLocation != null) {
                    val nearbyResult = ComputeEngine.computeNearbyStops(
                        userLat = userLocation.latitude,
                        userLng = userLocation.longitude,
                        allStops = stops
                    )
                    results["NearbySearch"] = nearbyResult
                }
            }
            
            QueryParser.QueryIntent.TRACK_BUS -> {
                query.parameters.busId?.let { busId ->
                    val trackResult = trackBusWithAlgorithms(busId, vehicles, stops, locations)
                    results["BusTracking"] = trackResult
                }
            }
            
            QueryParser.QueryIntent.CALCULATE_ETA -> {
                if (userLocation != null && query.parameters.busId != null) {
                    val etaResult = ComputeEngine.computeBusArrival(
                        busId = query.parameters.busId!!,
                        userLat = userLocation.latitude,
                        userLng = userLocation.longitude,
                        vehicles = vehicles,
                        allStops = stops,
                        liveLocations = locations
                    )
                    results["ETACalculation"] = etaResult
                }
            }
            
            else -> {}
        }
        
        android.util.Log.d(TAG, "Algorithms executed: ${results.keys}")
        return results
    }
    
    private fun runRouteMatcher(
        origin: String,
        destination: String,
        vehicles: List<VehicleDTO>,
        stops: List<RouteStopDTO>
    ): List<RouteMatcher.RouteMatchResult> {
        val vehicleStops = stops.groupBy { it.vehicleId }
            .mapValues { (_, stopList) ->
                stopList.sortedBy { it.stopOrder }.map { it.name to it.stopOrder }
            }
        val vehicleNames = vehicles.associate { it.id to it.name }
        
        return RouteMatcher.findMatchingRoutes(origin, destination, vehicleStops, vehicleNames)
    }
    
    private fun runVehicleRoutingOptimizer(
        matches: List<RouteMatcher.RouteMatchResult>,
        vehicles: List<VehicleDTO>,
        stops: List<RouteStopDTO>,
        locations: List<LiveBusLocation>
    ): List<VehicleRoutingOptimizer.RankedRoute> {
        // Convert matches to candidates
        val candidates = matches.map { match ->
            val vehicleStops = stops
                .filter { it.vehicleId == match.vehicleId }
                .sortedBy { it.stopOrder }
            
            // Calculate distance using Haversine
            val segment = vehicleStops.filter { 
                it.stopOrder in match.originOrder..match.destinationOrder 
            }
            var distance = 0.0
            for (i in 0 until segment.size - 1) {
                distance += Haversine.distance(
                    segment[i].latitude, segment[i].longitude,
                    segment[i + 1].latitude, segment[i + 1].longitude
                )
            }
            
            // Check live location
            val liveLocation = locations.find { it.busId == match.vehicleId }
            val busCurrentStop = if (liveLocation != null) {
                vehicleStops.minByOrNull { stop ->
                    Haversine.distance(liveLocation.latitude, liveLocation.longitude, stop.latitude, stop.longitude)
                }?.stopOrder
            } else null
            
            val busStopsAway = if (busCurrentStop != null && busCurrentStop < match.originOrder) {
                match.originOrder - busCurrentStop
            } else null
            
            val vehicle = vehicles.find { it.id == match.vehicleId }
            val baseTravelTime = ETACalculator.calculateETA(distance, segment.size - 1)
            
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
                baseTravelTimeMin = baseTravelTime.etaMinutes,
                busCurrentStopOrder = busCurrentStop,
                busStopsAwayFromOrigin = busStopsAway,
                isLive = liveLocation != null,
                busSpeed = liveLocation?.speed?.toDouble()
            )
        }
        
        return VehicleRoutingOptimizer.rankRoutes(candidates)
    }
    
    private fun trackBusWithAlgorithms(
        busId: String,
        vehicles: List<VehicleDTO>,
        stops: List<RouteStopDTO>,
        locations: List<LiveBusLocation>
    ): Map<String, Any> {
        // Find the bus
        val liveLocation = locations.find { loc ->
            loc.busId.contains(busId, ignoreCase = true) ||
            vehicles.find { it.id == loc.busId }?.name?.contains(busId, ignoreCase = true) == true
        }
        
        if (liveLocation == null) {
            return mapOf("found" to false, "error" to "Bus not found")
        }
        
        // Find nearest stop using Haversine
        val routeStops = stops.filter { it.vehicleId == liveLocation.busId }.sortedBy { it.stopOrder }
        val nearestStop = routeStops.minByOrNull { stop ->
            Haversine.distance(liveLocation.latitude, liveLocation.longitude, stop.latitude, stop.longitude)
        }
        
        val vehicle = vehicles.find { it.id == liveLocation.busId }
        
        return mapOf(
            "found" to true,
            "bus_id" to liveLocation.busId,
            "bus_name" to (vehicle?.name ?: busId),
            "vehicle_number" to (vehicle?.vehicleNumber ?: ""),
            "lat" to liveLocation.latitude,
            "lng" to liveLocation.longitude,
            "speed" to (liveLocation.speed ?: 0f),
            "is_moving" to ((liveLocation.speed ?: 0f) > 2f),
            "nearest_stop" to (nearestStop?.name ?: "Unknown"),
            "nearest_stop_order" to (nearestStop?.stopOrder ?: -1)
        )
    }
    
    // ==================== HELPER METHODS ====================
    
    private fun buildAgentInput(
        agentName: String,
        query: QueryParser.StructuredQuery,
        algorithmResults: Map<String, Any>
    ): MutableMap<String, Any> {
        return mutableMapOf<String, Any>(
            "intent" to query.intent.name,
            "origin" to (query.parameters.origin ?: ""),
            "destination" to (query.parameters.destination ?: ""),
            "bus_id" to (query.parameters.busId ?: ""),
            "algorithm_results" to algorithmResults,
            "shared_data" to communicationHub.getSharedData()
        )
    }
    
    private fun getAgentPurpose(agentName: String): String = when (agentName) {
        "AlgorithmAgent" -> "OR algorithm execution (KNN, TSP, MCDA)"
        "PathAgent" -> "route optimization"
        "TimeAgent" -> "ETA refinement"
        "BusTrackerAgent" -> "live bus status"
        "UserContextAgent" -> "user preferences"
        "AnalyticsAgent" -> "usage patterns"
        else -> "analysis"
    }
    
    private fun checkNeedsTransfer(
        algorithmResults: Map<String, Any>,
        agentAnalysis: Map<String, AgentResult>
    ): Boolean {
        val computeResult = algorithmResults["ComputeEngine"] as? ComputeEngine.ComputeResult
        return computeResult?.success == false &&
               computeResult.suggestedRefinements.contains("Check for transfer options")
    }
    
    private fun generateResponseFromAnalysis(
        intent: QueryParser.QueryIntent,
        algorithmResults: Map<String, Any>,
        agentAnalysis: Map<String, AgentResult>
    ): String {
        // Get the primary algorithm result
        val computeResult = algorithmResults["ComputeEngine"] as? ComputeEngine.ComputeResult
        val routingResult = algorithmResults["VehicleRouting"] as? List<*>
        
        return when (intent) {
            QueryParser.QueryIntent.FIND_ROUTE, QueryParser.QueryIntent.FIND_BEST_OPTION -> {
                // Use VehicleRouting if available (better ranking)
                if (!routingResult.isNullOrEmpty()) {
                    val best = routingResult.first() as VehicleRoutingOptimizer.RankedRoute
                    val c = best.candidate
                    buildString {
                        append("🚌 **${c.vehicleName}")
                        if (c.vehicleNumber.isNotBlank()) append(" (${c.vehicleNumber})")
                        append("**")
                        append(" • ${"%.1f".format(c.distanceKm)} km")
                        append(" • ~${best.totalTimeMin.toInt()} min")
                        if (c.isLive && c.busStopsAwayFromOrigin != null && c.busStopsAwayFromOrigin >= 0) {
                            append(" • Bus is ${c.busStopsAwayFromOrigin} stops away")
                        }
                    }
                } else if (computeResult?.success == true) {
                    val route = computeResult.data["best_route"] as? Map<*, *>
                    if (route != null) {
                        val name = route["vehicle_name"] ?: "Bus"
                        val num = route["vehicle_number"] ?: ""
                        val dist = route["distance_km"] ?: "?"
                        val eta = route["eta_minutes"] ?: "?"
                        "🚌 **$name${if (num.toString().isNotBlank()) " ($num)" else ""}** • $dist km • ~$eta min"
                    } else {
                        "No route found. Please check the stop names."
                    }
                } else {
                    // Check TransferAgent result
                    val transferResult = agentAnalysis["TransferAgent"]
                    if (transferResult?.success == true) {
                        transferResult.message ?: "Transfer route available"
                    } else {
                        "No route found between those stops."
                    }
                }
            }
            
            QueryParser.QueryIntent.TRACK_BUS -> {
                val trackResult = algorithmResults["BusTracking"] as? Map<*, *>
                if (trackResult?.get("found") == true) {
                    val name = trackResult["bus_name"] ?: "Bus"
                    val stop = trackResult["nearest_stop"] ?: "Unknown"
                    val moving = trackResult["is_moving"] == true
                    "📍 **$name** is ${if (moving) "moving" else "stopped"} near **$stop**"
                } else {
                    "Could not find that bus."
                }
            }
            
            QueryParser.QueryIntent.CALCULATE_ETA -> {
                val etaResult = algorithmResults["ETACalculation"] as? ComputeEngine.ComputeResult
                if (etaResult?.success == true) {
                    val arrival = etaResult.data["arrival_minutes"] ?: "?"
                    val busName = etaResult.data["bus_name"] ?: "Bus"
                    "⏱️ **$busName** arriving in ~$arrival min"
                } else {
                    "Could not calculate ETA."
                }
            }
            
            QueryParser.QueryIntent.FIND_NEARBY -> {
                val nearbyResult = algorithmResults["NearbySearch"] as? ComputeEngine.ComputeResult
                if (nearbyResult?.success == true) {
                    @Suppress("UNCHECKED_CAST")
                    val stops = nearbyResult.data["stops"] as? List<Map<String, Any>> ?: emptyList()
                    if (stops.isNotEmpty()) {
                        val names = stops.take(3).mapNotNull { it["name"]?.toString() }
                        "📍 Nearby: ${names.joinToString(", ")}"
                    } else {
                        "No stops found within 1km."
                    }
                } else {
                    "Enable location to find nearby stops."
                }
            }
            
            else -> "I can help with routes, bus locations, or ETAs."
        }
    }
    
    private fun handleLowConfidence(
        query: String,
        parsedQuery: QueryParser.StructuredQuery
    ): CoordinatorResult {
        val response = when {
            query.lowercase().contains("hi") || query.lowercase().contains("hello") ->
                "Hello! I can help you find bus routes, track buses, or get ETAs."
            query.lowercase().contains("thank") ->
                "You're welcome! 🚌"
            query.lowercase().contains("help") ->
                "Try: \"Route from A to B\", \"Where is bus X?\", or \"Next bus to Y?\""
            else ->
                "Could you be more specific? I can help with routes, bus locations, or arrival times."
        }
        
        return CoordinatorResult(
            query = query,
            success = true,
            response = response,
            intent = "clarification",
            agentsCalled = emptyList(),
            algorithmResults = emptyList(),
            taskSummary = null
        )
    }
    
    private fun createErrorResult(query: String, error: String): CoordinatorResult {
        return CoordinatorResult(
            query = query,
            success = false,
            response = error,
            intent = "error",
            agentsCalled = emptyList(),
            algorithmResults = emptyList(),
            taskSummary = null
        )
    }
}

/**
 * Result from the Multi-Agent Coordinator
 */
data class CoordinatorResult(
    val query: String,
    val success: Boolean,
    val response: String,
    val intent: String,
    val agentsCalled: List<String>,
    val algorithmResults: List<String>,
    val taskSummary: TaskSummary?
)
