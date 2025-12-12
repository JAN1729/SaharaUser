package com.sahara.user.agents

import com.sahara.user.algorithms.Haversine
import com.sahara.user.data.api.PerplexityClient
import com.sahara.user.data.api.SupabaseApiClient
import com.sahara.user.data.model.LiveBusLocation
import com.sahara.user.data.model.RouteStopDTO
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Path Agent - The Route Optimizer
 * 
 * PERSONA: I am the route finding specialist. I analyze predefined routes
 * and find the shortest path between two points. I use algorithms like
 * Haversine to calculate distances accurately.
 * 
 * MY ROLE:
 * - Find vehicles whose predefined route covers origin → destination
 * - Calculate distances along the route segments
 * - Match live bus positions to nearest stops
 * - Rank routes by distance/efficiency
 * - Pass distance data to TimeAgent for ETA calculation
 * 
 * I DEPEND ON:
 * - SQLAgent: For route stops and vehicle data
 * 
 * I COMMUNICATE WITH:
 * - TimeAgent: Sends distance and stops data for ETA calculation
 * - BusTrackerAgent: Requests live bus position if needed
 */
@Singleton
class PathAgent @Inject constructor(
    perplexityClient: PerplexityClient,
    private val supabaseClient: SupabaseApiClient
) : BaseAgent(perplexityClient) {
    
    override val agentName = "PathAgent"
    
    override val persona = AgentPersona(
        role = "Route Optimizer",
        capabilities = listOf(
            "Find shortest predefined path",
            "Calculate route distances",
            "Match bus to nearest stop",
            "Rank multiple route options",
            "Use Haversine for GPS calculations"
        ),
        dataAccess = listOf(
            "Route geometry from SQLAgent",
            "Live positions from BusTrackerAgent"
        ),
        canDelegateTo = listOf("TimeAgent", "BusTrackerAgent")
    )
    
    override val systemPrompt = """
        I am the Path Agent - the route optimization specialist.
        
        MY PERSONALITY:
        - I am analytical and precise
        - I focus on PREDEFINED routes only
        - I calculate, measure, and compare
        - I find the optimal path, not create new ones
        
        IMPORTANT CONTEXT:
        - Routes are PREDEFINED by drivers with stops in order
        - Each vehicle travels from stop 1 to last stop sequentially
        - Destination MUST come AFTER origin in the stop order
        - I find the best EXISTING path, not create new paths
        
        MY ALGORITHM:
        1. Get data from SQLAgent (vehicles, stops)
        2. Find routes where destination.order > origin.order
        3. Calculate segment distances using Haversine
        4. Match live bus position to nearest stop on route
        5. Rank paths by total distance + bus proximity
        6. Pass distance to TimeAgent for ETA
        
        I OUTPUT:
        - Best path with vehicle details
        - Route segment (list of stops)
        - Total distance in km
        - Bus current position (if live)
    """.trimIndent()
    
    override suspend fun execute(input: Map<String, Any>): AgentResult {
        val origin = input["origin"]?.toString() ?: ""
        val destination = input["destination"]?.toString() ?: ""
        
        android.util.Log.d(agentName, "Finding path: $origin → $destination")
        
        return try {
            // Check if SQLAgent already provided data
            val sqlResult = getResultFrom("SQLAgent")
            val (vehicles, allStops, liveLocations) = if (sqlResult?.success == true) {
                android.util.Log.d(agentName, "Using data from SQLAgent")
                parseDataFromSQLAgent(sqlResult)
            } else {
                android.util.Log.d(agentName, "Fetching fresh data")
                fetchFreshData()
            }
            
            // Find all valid predefined paths
            val validPaths = mutableListOf<PathOption>()
            
            for (vehicle in vehicles) {
                val vehicleStops = allStops
                    .filter { it.vehicleId == vehicle.id }
                    .sortedBy { it.stopOrder }
                
                if (vehicleStops.isEmpty()) continue
                
                // Find origin and destination on this route
                val originStop = vehicleStops.find { it.name.contains(origin, ignoreCase = true) }
                val destStop = vehicleStops.find { it.name.contains(destination, ignoreCase = true) }
                
                if (originStop == null || destStop == null) continue
                
                // CRITICAL: Destination must come AFTER origin
                if (destStop.stopOrder <= originStop.stopOrder) {
                    android.util.Log.d(agentName, "Skipping ${vehicle.name}: wrong direction")
                    continue
                }
                
                // Get route segment
                val segment = vehicleStops.filter { 
                    it.stopOrder >= originStop.stopOrder && it.stopOrder <= destStop.stopOrder 
                }
                
                // Calculate distance using Haversine
                val distance = calculateRouteDistance(segment)
                
                // Find bus current position
                val liveLocation = liveLocations.find { it.busId == vehicle.id }
                val busPosition = liveLocation?.let { findNearestStop(it, vehicleStops) }
                val stopsAway = busPosition?.let { 
                    originStop.stopOrder - it.stopOrder 
                } ?: -1
                
                validPaths.add(PathOption(
                    vehicleId = vehicle.id,
                    vehicleName = vehicle.name,
                    vehicleNumber = vehicle.vehicleNumber,
                    originStop = originStop,
                    destinationStop = destStop,
                    segment = segment,
                    distanceKm = distance,
                    busCurrentStop = busPosition,
                    busStopsAway = stopsAway,
                    isLive = liveLocation != null
                ))
            }
            
            if (validPaths.isEmpty()) {
                // Request TransferAgent if no direct path
                return AgentResult(
                    success = false,
                    agentName = agentName,
                    error = "No direct path found from $origin to $destination",
                    needsMoreData = true,
                    suggestedNextAgent = "TransferAgent",
                    confidence = 0.2f
                )
            }
            
            // Rank paths: prefer live buses closer to origin, then by distance
            val rankedPaths = validPaths.sortedWith(compareBy(
                { if (it.isLive && it.busStopsAway >= 0) it.busStopsAway else Int.MAX_VALUE },
                { it.distanceKm }
            ))
            
            val bestPath = rankedPaths.first()
            
            // Analyze with AI
            val analysisPrompt = """
                Found ${rankedPaths.size} routes from $origin to $destination.
                
                Best route:
                - Vehicle: ${bestPath.vehicleName} (${bestPath.vehicleNumber})
                - Stops: ${bestPath.segment.joinToString(" → ") { it.name }}
                - Distance: ${String.format("%.2f", bestPath.distanceKm)} km
                ${if (bestPath.isLive) "- Bus is ${bestPath.busStopsAway} stops away" else "- Bus position unknown"}
                
                Why is this the optimal choice?
            """.trimIndent()
            
            val analysis = think(analysisPrompt).getOrElse { 
                "Best path based on shortest distance and live bus availability." 
            }
            
            val result = AgentResult(
                success = true,
                agentName = agentName,
                data = mapOf(
                    "best_path" to mapOf(
                        "vehicle_id" to bestPath.vehicleId,
                        "vehicle_name" to bestPath.vehicleName,
                        "vehicle_number" to bestPath.vehicleNumber,
                        "origin_stop" to bestPath.originStop.name,
                        "destination_stop" to bestPath.destinationStop.name,
                        "route" to bestPath.segment.map { it.name },
                        "distance_km" to bestPath.distanceKm,
                        "stops_count" to bestPath.segment.size,
                        "bus_is_live" to bestPath.isLive,
                        "bus_stops_away" to bestPath.busStopsAway,
                        "bus_current_stop" to (bestPath.busCurrentStop?.name ?: "Unknown")
                    ),
                    "all_paths" to rankedPaths.map { 
                        mapOf(
                            "vehicle" to it.vehicleName,
                            "distance_km" to it.distanceKm,
                            "stops" to it.segment.size,
                            "is_live" to it.isLive
                        )
                    },
                    "paths_found" to rankedPaths.size,
                    "analysis" to analysis
                ),
                message = "Best: ${bestPath.vehicleName} (${String.format("%.2f", bestPath.distanceKm)} km, ${bestPath.segment.size} stops)",
                confidence = 0.9f,
                suggestedNextAgent = "TimeAgent"
            )
            
            // Store for other agents and notify TimeAgent
            storeResult(result)
            notifyAgents(mapOf(
                "path_found" to true,
                "distance_km" to bestPath.distanceKm,
                "stops_count" to bestPath.segment.size
            ))
            
            result
            
        } catch (e: Exception) {
            android.util.Log.e(agentName, "Error: ${e.message}", e)
            AgentResult(
                success = false,
                agentName = agentName,
                error = e.message,
                confidence = 0f
            )
        }
    }
    
    data class VehicleInfo(val id: String, val name: String, val vehicleNumber: String)
    
    private suspend fun parseDataFromSQLAgent(sqlResult: AgentResult): Triple<List<VehicleInfo>, List<RouteStopDTO>, List<LiveBusLocation>> {
        // Parse from SQLAgent's structured output
        val matchingVehicles = (sqlResult.data["matching_vehicles"] as? List<*>)?.mapNotNull { v ->
            val map = v as? Map<*, *> ?: return@mapNotNull null
            VehicleInfo(
                id = map["vehicle_id"]?.toString() ?: "",
                name = map["vehicle_name"]?.toString() ?: "",
                vehicleNumber = map["vehicle_number"]?.toString() ?: ""
            )
        } ?: emptyList()
        
        // Still need to fetch full data for distance calculation
        return fetchFreshData()
    }
    
    private suspend fun fetchFreshData(): Triple<List<VehicleInfo>, List<RouteStopDTO>, List<LiveBusLocation>> {
        val vehiclesResult = supabaseClient.getActiveVehicles()
        val vehicles = vehiclesResult.getOrElse { emptyList() }
            .map { VehicleInfo(it.id, it.name, it.vehicleNumber) }
        
        val stopsResult = supabaseClient.getAllRouteStops()
        val stops = stopsResult.getOrElse { emptyList() }
        
        val liveResult = supabaseClient.getLiveBusLocations()
        val live = liveResult.getOrElse { emptyList() }
        
        return Triple(vehicles, stops, live)
    }
    
    private fun calculateRouteDistance(stops: List<RouteStopDTO>): Double {
        var total = 0.0
        for (i in 0 until stops.size - 1) {
            total += Haversine.distance(
                stops[i].latitude, stops[i].longitude,
                stops[i + 1].latitude, stops[i + 1].longitude
            )
        }
        return total
    }
    
    private fun findNearestStop(location: LiveBusLocation, stops: List<RouteStopDTO>): RouteStopDTO? {
        return stops.minByOrNull { stop ->
            Haversine.distance(
                location.latitude, location.longitude,
                stop.latitude, stop.longitude
            )
        }
    }
}

data class PathOption(
    val vehicleId: String,
    val vehicleName: String,
    val vehicleNumber: String,
    val originStop: RouteStopDTO,
    val destinationStop: RouteStopDTO,
    val segment: List<RouteStopDTO>,
    val distanceKm: Double,
    val busCurrentStop: RouteStopDTO?,
    val busStopsAway: Int,
    val isLive: Boolean
)
