package com.sahara.user.agents

import com.sahara.user.algorithms.Haversine
import com.sahara.user.algorithms.NearestNeighbor
import com.sahara.user.algorithms.RouteMatcher
import com.sahara.user.algorithms.TravelingSalesman
import com.sahara.user.algorithms.VehicleRoutingOptimizer
import com.sahara.user.data.api.PerplexityClient
import com.sahara.user.data.model.LiveBusLocation
import com.sahara.user.data.model.RouteStopDTO
import com.sahara.user.data.model.VehicleDTO
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Algorithm Agent - Operations Research Algorithms Layer
 * 
 * This agent acts as the computational backbone of the multi-agent system.
 * It executes appropriate OR algorithms based on query context and shares
 * results with other agents via the communication hub.
 * 
 * ALGORITHMS AVAILABLE:
 * 1. KNN (K-Nearest Neighbors) - Find nearby stops
 * 2. TSP (Traveling Salesman) - Optimize multi-destination order
 * 3. Fixed Route Matching - Match journeys to predefined bus paths
 * 4. MCDA (Multi-Criteria Decision Analysis) - Rank bus options
 * 5. ETA Calculation - Estimate arrival times
 * 
 * IMPORTANT: Bus routes are FIXED predefined paths. We do NOT use
 * shortest-path algorithms between stops. The stop order in the
 * database defines the route.
 * 
 * INTER-AGENT COMMUNICATION:
 * - Receives: Data from DataAgent (vehicles, stops, locations)
 * - Shares: Algorithm results with PathAgent, TimeAgent, TrackerAgent
 * - Uses: AgentCommunicationHub for message passing
 */
@Singleton
class AlgorithmAgent @Inject constructor(
    perplexityClient: PerplexityClient
) : BaseAgent(perplexityClient) {
    
    override val agentName = "AlgorithmAgent"
    
    override val systemPrompt = """
        You are the Algorithm Selector for a bus transportation system.
        Your role is to analyze the user's query and determine which 
        Operations Research algorithms should be executed.
        
        Available algorithms:
        - KNN: For finding nearby stops (requires user GPS)
        - TSP: For optimizing multi-destination visit order
        - FIXED_ROUTE_MATCH: For matching origin/destination to bus routes
        - MCDA_RANKING: For ranking multiple bus options
        - ETA_CALCULATION: For computing arrival times
        
        Consider:
        - Does the user have GPS location?
        - Is this a single or multi-destination query?
        - Are they asking about nearby stops or routes?
        
        Respond with algorithm recommendations.
    """.trimIndent()
    
    override val persona = AgentPersona(
        role = "Algorithm Executor",
        capabilities = listOf(
            "Execute KNN for nearby stops",
            "Execute TSP for multi-destination optimization",
            "Execute Fixed Route Matching for bus selection",
            "Execute MCDA for ranking options",
            "Share algorithm results with other agents"
        ),
        dataAccess = listOf("vehicles", "stops", "locations", "user_location"),
        canDelegateTo = listOf("PathAgent", "TimeAgent", "BusTrackerAgent")
    )
    
    // Algorithm types
    enum class AlgorithmType {
        KNN,
        TSP,
        FIXED_ROUTE_MATCH,
        MCDA_RANKING,
        ETA_CALCULATION
    }
    
    // Algorithm selection result
    data class AlgorithmSelection(
        val algorithm: AlgorithmType,
        val reason: String,
        val parameters: Map<String, Any>,
        val priority: Int
    )
    
    // Algorithm execution result
    data class AlgorithmExecutionResult(
        val algorithm: AlgorithmType,
        val success: Boolean,
        val data: Map<String, Any>,
        val executionTimeMs: Long
    )
    
    override suspend fun execute(input: Map<String, Any>): AgentResult {
        val startTime = System.currentTimeMillis()
        
        // Extract input parameters
        val intent = input["intent"] as? String ?: "FIND_ROUTE"
        val origin = input["origin"] as? String
        val destination = input["destination"] as? String
        val destinations = input["destinations"] as? List<*>
        val userLat = input["user_lat"] as? Double
        val userLng = input["user_lng"] as? Double
        
        // Get shared data from communication hub
        val sharedData = communicationHub?.getSharedData() as? Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val vehicles = (sharedData?.get("vehicles") as? List<*>)?.filterIsInstance<VehicleDTO>() ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val stops = (sharedData?.get("stops") as? List<*>)?.filterIsInstance<RouteStopDTO>() ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val liveLocations = (sharedData?.get("liveLocations") as? List<*>)?.filterIsInstance<LiveBusLocation>() ?: emptyList()
        
        // Select algorithms based on context
        val hasUserLocation = userLat != null && userLng != null
        val destinationList = destinations?.filterIsInstance<String>() ?: emptyList()
        val destinationCount = destinationList.size.takeIf { it > 0 } 
            ?: if (origin != null && destination != null) 1 else 0
        
        val selections = selectAlgorithms(intent, origin, destination, hasUserLocation, destinationCount)
        
        // Execute selected algorithms
        val results = mutableListOf<AlgorithmExecutionResult>()
        val algorithmData = mutableMapOf<String, Any>()
        
        for (selection in selections) {
            val algoStart = System.currentTimeMillis()
            
            try {
                when (selection.algorithm) {
                    AlgorithmType.KNN -> {
                        if (hasUserLocation && userLat != null && userLng != null) {
                            val vehicleNames = vehicles.associate { it.id to it.name }
                            val nearbyStops = NearestNeighbor.findKNearestStops(
                                userLat, userLng, stops, vehicleNames, k = 5
                            )
                            algorithmData["nearby_stops"] = nearbyStops
                            
                            // Store in hub for other agents
                            communicationHub?.storeAlgorithmResult("knn_nearby_stops", nearbyStops)
                            
                            results.add(AlgorithmExecutionResult(
                                algorithm = AlgorithmType.KNN,
                                success = true,
                                data = mapOf(
                                    "count" to nearbyStops.size,
                                    "nearest" to (nearbyStops.firstOrNull()?.stopName ?: "None")
                                ),
                                executionTimeMs = System.currentTimeMillis() - algoStart
                            ))
                        }
                    }
                    
                    AlgorithmType.TSP -> {
                        if (destinationList.size > 1) {
                            val startLoc = if (hasUserLocation && userLat != null && userLng != null) {
                                Pair(userLat, userLng)
                            } else null
                            
                            val vehicleNames = vehicles.associate { it.id to it.name }
                            val tspResult = TravelingSalesman.optimizeVisitOrder(
                                startLoc, destinationList, stops, vehicleNames
                            )
                            algorithmData["tsp_result"] = tspResult
                            
                            // Store in hub
                            communicationHub?.storeAlgorithmResult("tsp_optimized_order", tspResult)
                            
                            results.add(AlgorithmExecutionResult(
                                algorithm = AlgorithmType.TSP,
                                success = true,
                                data = mapOf(
                                    "order" to tspResult.orderedDestinations,
                                    "saved_km" to tspResult.optimizationSavings.distanceSavedKm
                                ),
                                executionTimeMs = System.currentTimeMillis() - algoStart
                            ))
                        }
                    }
                    
                    AlgorithmType.FIXED_ROUTE_MATCH -> {
                        if (origin != null && destination != null) {
                            val matches = matchFixedRoutes(origin, destination, vehicles, stops)
                            algorithmData["route_matches"] = matches
                            
                            // Store in hub
                            communicationHub?.storeAlgorithmResult("fixed_route_matches", matches)
                            
                            results.add(AlgorithmExecutionResult(
                                algorithm = AlgorithmType.FIXED_ROUTE_MATCH,
                                success = matches.isNotEmpty(),
                                data = mapOf(
                                    "match_count" to matches.size,
                                    "best_match" to (matches.firstOrNull()?.vehicleName ?: "None")
                                ),
                                executionTimeMs = System.currentTimeMillis() - algoStart
                            ))
                        }
                    }
                    
                    AlgorithmType.MCDA_RANKING -> {
                        @Suppress("UNCHECKED_CAST")
                        val routeMatches = algorithmData["route_matches"] as? List<FixedRouteMatch> ?: emptyList()
                        
                        if (routeMatches.isNotEmpty()) {
                            val rankedOptions = rankWithMCDA(routeMatches, vehicles, stops, liveLocations)
                            algorithmData["mcda_ranking"] = rankedOptions
                            
                            // Store in hub
                            communicationHub?.storeAlgorithmResult("mcda_ranked_buses", rankedOptions)
                            
                            results.add(AlgorithmExecutionResult(
                                algorithm = AlgorithmType.MCDA_RANKING,
                                success = true,
                                data = mapOf(
                                    "ranked_count" to rankedOptions.size,
                                    "best_option" to (rankedOptions.firstOrNull()?.vehicleName ?: "None")
                                ),
                                executionTimeMs = System.currentTimeMillis() - algoStart
                            ))
                        }
                    }
                    
                    AlgorithmType.ETA_CALCULATION -> {
                        // ETA is handled by TimeAgent, just mark as delegated
                        results.add(AlgorithmExecutionResult(
                            algorithm = AlgorithmType.ETA_CALCULATION,
                            success = true,
                            data = mapOf("delegated_to" to "TimeAgent"),
                            executionTimeMs = 0
                        ))
                    }
                }
            } catch (e: Exception) {
                results.add(AlgorithmExecutionResult(
                    algorithm = selection.algorithm,
                    success = false,
                    data = mapOf("error" to (e.message ?: "Unknown error")),
                    executionTimeMs = System.currentTimeMillis() - algoStart
                ))
            }
        }
        
        // Store all results in hub for other agents
        communicationHub?.storeAlgorithmResult("algorithm_results", algorithmData)
        
        val successCount = results.count { it.success }
        val totalTime = System.currentTimeMillis() - startTime
        
        val result = AgentResult(
            success = successCount > 0,
            agentName = agentName,
            data = mapOf(
                "algorithms_executed" to results.map { it.algorithm.name },
                "success_count" to successCount,
                "total_count" to results.size,
                "results" to algorithmData
            ),
            message = "Executed $successCount/${results.size} algorithms successfully",
            confidence = successCount.toFloat() / results.size.coerceAtLeast(1),
            needsMoreData = false,
            suggestedNextAgent = "PathAgent"
        )
        
        // Store our result
        storeResult(result)
        
        // Notify other agents
        notifyAgents(mapOf(
            "algorithm_complete" to true,
            "algorithms" to results.map { it.algorithm.name },
            "execution_time_ms" to totalTime
        ))
        
        return result
    }
    
    /**
     * Select which algorithms to run based on context
     */
    private fun selectAlgorithms(
        intent: String,
        origin: String?,
        destination: String?,
        hasUserLocation: Boolean,
        destinationCount: Int
    ): List<AlgorithmSelection> {
        val selections = mutableListOf<AlgorithmSelection>()
        
        // Always run KNN if user has GPS
        if (hasUserLocation) {
            selections.add(AlgorithmSelection(
                algorithm = AlgorithmType.KNN,
                reason = "User has GPS - find nearby stops for context",
                parameters = mapOf("k" to 5),
                priority = 1
            ))
        }
        
        // Multi-destination? Use TSP
        if (destinationCount > 1) {
            selections.add(AlgorithmSelection(
                algorithm = AlgorithmType.TSP,
                reason = "Optimizing order for $destinationCount destinations",
                parameters = mapOf("destination_count" to destinationCount),
                priority = 2
            ))
        }
        
        // Route finding? Use Fixed Route Matcher
        if (intent == "FIND_ROUTE" && origin != null && destination != null) {
            selections.add(AlgorithmSelection(
                algorithm = AlgorithmType.FIXED_ROUTE_MATCH,
                reason = "Matching journey to predefined bus routes",
                parameters = mapOf("origin" to origin, "destination" to destination),
                priority = 3
            ))
        }
        
        // Multiple bus options? Use MCDA to rank
        if (intent == "FIND_ROUTE") {
            selections.add(AlgorithmSelection(
                algorithm = AlgorithmType.MCDA_RANKING,
                reason = "Ranking bus options by multiple criteria",
                parameters = mapOf("preference" to "FASTEST"),
                priority = 4
            ))
        }
        
        // Always include ETA for route/tracking queries
        if (intent == "FIND_ROUTE" || intent == "TRACK_BUS") {
            selections.add(AlgorithmSelection(
                algorithm = AlgorithmType.ETA_CALCULATION,
                reason = "Computing arrival times",
                parameters = emptyMap(),
                priority = 5
            ))
        }
        
        return selections.sortedBy { it.priority }
    }
    
    // ========================================================================
    // Fixed Route Matching - For predefined bus paths
    // ========================================================================
    
    data class FixedRouteMatch(
        val vehicleId: String,
        val vehicleName: String,
        val vehicleNumber: String,
        val originStop: String,
        val originStopOrder: Int,
        val destinationStop: String,
        val destinationStopOrder: Int,
        val stopsInPath: List<String>,
        val pathDistanceKm: Double,
        val isValidDirection: Boolean,
        val matchConfidence: Double
    )
    
    /**
     * Match user's origin/destination to predefined fixed bus routes
     * Validates that stops appear in correct order on the fixed path
     */
    private fun matchFixedRoutes(
        origin: String,
        destination: String,
        vehicles: List<VehicleDTO>,
        stops: List<RouteStopDTO>
    ): List<FixedRouteMatch> {
        val vehicleMap = vehicles.associateBy { it.id }
        val matches = mutableListOf<FixedRouteMatch>()
        
        // Group stops by vehicle
        val vehicleStopsMap = stops.groupBy { it.vehicleId }
        
        // Find matches for each vehicle
        for ((vehicleId, vehicleStops) in vehicleStopsMap) {
            val vehicle = vehicleMap[vehicleId] ?: continue
            
            // Sort by stop_order (the fixed path)
            val sortedStops = vehicleStops.sortedBy { it.stopOrder }
            
            // Find origin stop
            val originStop = sortedStops.find { stop ->
                stop.name.lowercase().contains(origin.lowercase()) ||
                origin.lowercase().contains(stop.name.lowercase())
            } ?: continue
            
            // Find destination stop
            val destStop = sortedStops.find { stop ->
                stop.name.lowercase().contains(destination.lowercase()) ||
                destination.lowercase().contains(stop.name.lowercase())
            } ?: continue
            
            // Check direction: destination must come AFTER origin on fixed path
            val isValidDirection = destStop.stopOrder > originStop.stopOrder
            
            // Extract stops in path
            val stopsInPath = sortedStops
                .filter { it.stopOrder >= originStop.stopOrder && it.stopOrder <= destStop.stopOrder }
                .map { it.name }
            
            // Calculate path distance
            var pathDistance = 0.0
            val pathStops = sortedStops.filter { 
                it.stopOrder >= originStop.stopOrder && it.stopOrder <= destStop.stopOrder 
            }
            for (i in 0 until pathStops.size - 1) {
                pathDistance += Haversine.distance(
                    pathStops[i].latitude, pathStops[i].longitude,
                    pathStops[i + 1].latitude, pathStops[i + 1].longitude
                )
            }
            
            // Calculate match confidence
            val originSimilarity = calculateSimilarity(origin.lowercase(), originStop.name.lowercase())
            val destSimilarity = calculateSimilarity(destination.lowercase(), destStop.name.lowercase())
            val baseConfidence = (originSimilarity + destSimilarity) / 2
            val matchConfidence = if (isValidDirection) baseConfidence else baseConfidence * 0.3
            
            matches.add(FixedRouteMatch(
                vehicleId = vehicleId,
                vehicleName = vehicle.name,
                vehicleNumber = vehicle.vehicleNumber,
                originStop = originStop.name,
                originStopOrder = originStop.stopOrder,
                destinationStop = destStop.name,
                destinationStopOrder = destStop.stopOrder,
                stopsInPath = stopsInPath,
                pathDistanceKm = (pathDistance * 100).toLong() / 100.0,
                isValidDirection = isValidDirection,
                matchConfidence = matchConfidence
            ))
        }
        
        // Return only valid direction matches, sorted by confidence
        return matches
            .filter { it.isValidDirection }
            .sortedByDescending { it.matchConfidence }
    }
    
    /**
     * String similarity using bigram Jaccard coefficient
     */
    private fun calculateSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        
        val getBigrams = { s: String ->
            if (s.length < 2) setOf(s)
            else (0 until s.length - 1).map { s.substring(it, it + 2) }.toSet()
        }
        
        val bigrams1 = getBigrams(s1)
        val bigrams2 = getBigrams(s2)
        
        val intersection = bigrams1.intersect(bigrams2).size
        val union = bigrams1.union(bigrams2).size
        
        return if (union > 0) intersection.toDouble() / union else 0.0
    }
    
    // ========================================================================
    // MCDA Ranking - Multi-Criteria Decision Analysis
    // ========================================================================
    
    data class MCDARankedOption(
        val vehicleId: String,
        val vehicleName: String,
        val score: Double,
        val ranking: Int,
        val factors: Map<String, Double>,
        val recommendation: String
    )
    
    /**
     * Rank bus options using Multi-Criteria Decision Analysis
     */
    private fun rankWithMCDA(
        routeMatches: List<FixedRouteMatch>,
        vehicles: List<VehicleDTO>,
        stops: List<RouteStopDTO>,
        liveLocations: List<LiveBusLocation>,
        preference: VehicleRoutingOptimizer.RoutePreference = VehicleRoutingOptimizer.RoutePreference.FASTEST
    ): List<MCDARankedOption> {
        val liveLocationMap = liveLocations.associateBy { it.busId }
        
        // Define weights based on preference
        val weights = when (preference) {
            VehicleRoutingOptimizer.RoutePreference.FASTEST -> mapOf(
                "totalTime" to 0.50, "proximity" to 0.30, "directness" to 0.15, "reliability" to 0.05
            )
            VehicleRoutingOptimizer.RoutePreference.LEAST_WAITING -> mapOf(
                "totalTime" to 0.20, "proximity" to 0.50, "directness" to 0.20, "reliability" to 0.10
            )
            VehicleRoutingOptimizer.RoutePreference.MOST_DIRECT -> mapOf(
                "totalTime" to 0.30, "proximity" to 0.20, "directness" to 0.40, "reliability" to 0.10
            )
        }
        
        val rankedOptions = routeMatches.mapIndexed { index, match ->
            val liveLocation = liveLocationMap[match.vehicleId]
            val isLive = liveLocation != null
            
            // Calculate bus stops away from origin
            val busStopsAway = if (isLive && liveLocation != null) {
                val vehicleStops = stops.filter { it.vehicleId == match.vehicleId }
                    .sortedBy { it.stopOrder }
                
                var nearestStopOrder = 0
                var minDist = Double.MAX_VALUE
                for (s in vehicleStops) {
                    val d = Haversine.distance(
                        liveLocation.latitude, liveLocation.longitude,
                        s.latitude, s.longitude
                    )
                    if (d < minDist) {
                        minDist = d
                        nearestStopOrder = s.stopOrder
                    }
                }
                (match.originStopOrder - nearestStopOrder).coerceAtLeast(0)
            } else 5 // Default
            
            // Calculate factors
            val waitTime = if (isLive && busStopsAway > 0) busStopsAway * 2.0 else 10.0
            val travelTime = (match.pathDistanceKm / 18.0) * 60 // 18 km/h avg
            val totalTime = waitTime + travelTime
            
            val factors = mapOf(
                "totalTime" to (1.0 - (totalTime / 120.0)).coerceIn(0.0, 1.0),
                "proximity" to if (isLive) (1.0 - (busStopsAway / 20.0)).coerceIn(0.0, 1.0) else 0.0,
                "directness" to (1.0 - (match.stopsInPath.size / 30.0)).coerceIn(0.0, 1.0),
                "reliability" to if (isLive) 1.0 else 0.3
            )
            
            // Calculate weighted score
            val score = factors.entries.sumOf { (key, value) ->
                value * (weights[key] ?: 0.0)
            } * 100
            
            // Generate recommendation
            val recommendation = when {
                isLive && busStopsAway <= 2 -> 
                    "🚌 ${match.vehicleName} arriving soon - $busStopsAway stops away"
                factors["directness"]!! > 0.7 -> 
                    "🎯 ${match.vehicleName} - Direct route with ${match.stopsInPath.size} stops"
                else -> 
                    "${match.vehicleName} - ${totalTime.toInt()} min total"
            }
            
            MCDARankedOption(
                vehicleId = match.vehicleId,
                vehicleName = match.vehicleName,
                score = (score * 10).toLong() / 10.0,
                ranking = 0, // Set after sorting
                factors = factors,
                recommendation = recommendation
            )
        }
        
        // Sort by score and assign rankings
        return rankedOptions
            .sortedByDescending { it.score }
            .mapIndexed { index, option ->
                option.copy(ranking = index + 1)
            }
    }
}
