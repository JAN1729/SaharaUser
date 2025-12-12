package com.sahara.user.agents

import com.sahara.user.data.api.PerplexityClient
import com.sahara.user.data.api.SupabaseApiClient
import com.sahara.user.data.model.LiveBusLocation
import com.sahara.user.data.model.RouteStopDTO
import com.sahara.user.data.model.VehicleDTO
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SQL Agent - The Data Retriever
 * 
 * PERSONA: I am the data specialist. I fetch and organize data
 * from Supabase. I DO NOT calculate - I retrieve.
 * 
 * MY ROLE:
 * - Fetch active vehicles from Supabase
 * - Get all route stops with proper ordering
 * - Retrieve live bus locations
 * - Provide structured data to other agents
 * 
 * I PROVIDE DATA TO:
 * - PathAgent: Vehicles and stops for route finding
 * - TimeAgent: Distance data for ETA calculations  
 * - BusTrackerAgent: Live locations for tracking
 * 
 * ALL CALCULATIONS ARE DONE BY ALGORITHMS, NOT ME.
 */
@Singleton
class SQLAgent @Inject constructor(
    perplexityClient: PerplexityClient,
    private val supabaseClient: SupabaseApiClient
) : BaseAgent(perplexityClient) {
    
    override val agentName = "SQLAgent"
    
    override val persona = AgentPersona(
        role = "Data Retriever",
        capabilities = listOf(
            "Fetch active vehicles",
            "Get route stops with ordering",
            "Retrieve live bus locations",
            "Search stops by name",
            "Get user data"
        ),
        dataAccess = listOf("vehicles", "route_stops", "locations", "users"),
        canDelegateTo = listOf("PathAgent", "BusTrackerAgent")
    )
    
    override val systemPrompt = """
        I am the SQL Agent - the data retrieval specialist.
        
        MY CORE PRINCIPLES:
        - I retrieve data, I do NOT calculate
        - All calculations are done by algorithms
        - I provide structured, ordered data
        - I validate data before passing to other agents
        
        MY DATA SOURCES (Supabase):
        - vehicles: Bus fleet information
        - route_stops: Stops with order (path sequence)
        - locations: Live GPS positions
        
        IMPORTANT: Stops are ordered by stop_order within each vehicle.
        This ordering defines the path sequence.
    """.trimIndent()
    
    /**
     * Execute data retrieval
     * Returns typed data for other agents
     */
    override suspend fun execute(input: Map<String, Any>): AgentResult {
        android.util.Log.d(agentName, "Fetching data from Supabase...")
        
        return try {
            // Fetch all required data
            val vehiclesResult = supabaseClient.getActiveVehicles()
            val stopsResult = supabaseClient.getAllRouteStops()
            val locationsResult = supabaseClient.getLiveBusLocations()
            
            // Check for errors
            val vehicles = vehiclesResult.getOrElse { 
                reportError("Failed to fetch vehicles: ${it.message}")
                return createErrorResult("Failed to fetch vehicles")
            }
            
            val stops = stopsResult.getOrElse {
                reportError("Failed to fetch stops: ${it.message}")
                return createErrorResult("Failed to fetch stops")
            }
            
            // Locations are optional (some buses may not be live)
            val locations = locationsResult.getOrElse { emptyList() }
            
            android.util.Log.d(agentName, "Fetched: ${vehicles.size} vehicles, ${stops.size} stops, ${locations.size} live")
            
            // Build result with typed data
            val resultData = mutableMapOf<String, Any>(
                // Typed data for direct use
                "vehicles" to vehicles,
                "stops" to stops,
                "locations" to locations,
                
                // Summary counts
                "vehicle_count" to vehicles.size,
                "stop_count" to stops.size,
                "live_count" to locations.size,
                
                // Vehicle IDs for quick lookup
                "vehicle_ids" to vehicles.map { it.id },
                "live_bus_ids" to locations.map { it.busId },
                
                // Organized data
                "stops_by_vehicle" to stops.groupBy { it.vehicleId }
                    .mapValues { (_, stopList) -> stopList.sortedBy { it.stopOrder } },
                
                // Vehicle names for display
                "vehicle_names" to vehicles.associate { it.id to it.name },
                "vehicle_numbers" to vehicles.associate { it.id to it.vehicleNumber }
            )
            
            // Filter by origin/destination if provided
            val origin = input["origin"]?.toString() ?: ""
            val destination = input["destination"]?.toString() ?: ""
            
            if (origin.isNotBlank() || destination.isNotBlank()) {
                val matchInfo = findMatchingInfo(origin, destination, stops, vehicles)
                resultData.putAll(matchInfo)
            }
            
            // Build message
            val message = buildString {
                append("Data ready: ")
                append("${vehicles.size} vehicles, ")
                append("${stops.size} stops, ")
                append("${locations.size} live locations")
            }
            
            val result = AgentResult(
                success = true,
                agentName = agentName,
                data = resultData,
                message = message,
                confidence = 0.95f,
                suggestedNextAgent = determinNextAgent(input)
            )
            
            // Store result for other agents
            storeResult(result)
            
            // Notify other agents that data is ready
            notifyAgents(mapOf(
                "data_ready" to true,
                "vehicle_count" to vehicles.size,
                "stop_count" to stops.size,
                "live_count" to locations.size
            ))
            
            result
            
        } catch (e: Exception) {
            android.util.Log.e(agentName, "Error: ${e.message}", e)
            createErrorResult(e.message)
        }
    }
    
    /**
     * Find stops matching origin/destination
     * NOTE: This is string matching, not distance calculation
     */
    private fun findMatchingInfo(
        origin: String,
        destination: String,
        stops: List<RouteStopDTO>,
        vehicles: List<VehicleDTO>
    ): Map<String, Any> {
        val info = mutableMapOf<String, Any>()
        
        // Find origin stops (case-insensitive contains)
        if (origin.isNotBlank()) {
            val originStops = stops.filter { 
                it.name.contains(origin, ignoreCase = true) 
            }
            info["origin_stops"] = originStops
            info["origin_match_count"] = originStops.size
            info["origin_vehicle_ids"] = originStops.map { it.vehicleId }.distinct()
        }
        
        // Find destination stops
        if (destination.isNotBlank()) {
            val destStops = stops.filter { 
                it.name.contains(destination, ignoreCase = true) 
            }
            info["destination_stops"] = destStops
            info["destination_match_count"] = destStops.size
            info["destination_vehicle_ids"] = destStops.map { it.vehicleId }.distinct()
        }
        
        // Find vehicles serving both stops (in correct order)
        if (origin.isNotBlank() && destination.isNotBlank()) {
            val originVehicles = (info["origin_vehicle_ids"] as? List<*>)?.toSet() ?: emptySet()
            val destVehicles = (info["destination_vehicle_ids"] as? List<*>)?.toSet() ?: emptySet()
            val commonVehicles = originVehicles.intersect(destVehicles)
            
            info["common_vehicle_ids"] = commonVehicles.toList()
            
            // For each common vehicle, verify direction (origin before destination)
            val validRoutes = commonVehicles.mapNotNull { vehicleId ->
                val vehicleStops = stops.filter { it.vehicleId == vehicleId }.sortedBy { it.stopOrder }
                val originStop = vehicleStops.find { it.name.contains(origin, ignoreCase = true) }
                val destStop = vehicleStops.find { it.name.contains(destination, ignoreCase = true) }
                
                if (originStop != null && destStop != null && destStop.stopOrder > originStop.stopOrder) {
                    mapOf(
                        "vehicle_id" to vehicleId,
                        "vehicle_name" to (vehicles.find { it.id == vehicleId }?.name ?: ""),
                        "origin_stop" to originStop.name,
                        "origin_order" to originStop.stopOrder,
                        "destination_stop" to destStop.name,
                        "destination_order" to destStop.stopOrder,
                        "stops_between" to (destStop.stopOrder - originStop.stopOrder - 1)
                    )
                } else null
            }
            
            info["valid_routes"] = validRoutes
            info["valid_route_count"] = validRoutes.size
        }
        
        return info
    }
    
    /**
     * Determine which agent should be called next based on intent
     */
    private fun determinNextAgent(input: Map<String, Any>): String? {
        val intent = input["intent"]?.toString()?.lowercase() ?: ""
        
        return when {
            intent.contains("route") || intent.contains("find") -> "PathAgent"
            intent.contains("track") || intent.contains("location") -> "BusTrackerAgent"
            intent.contains("eta") || intent.contains("time") -> "TimeAgent"
            else -> "PathAgent"
        }
    }
    
    private fun createErrorResult(error: String?): AgentResult {
        reportError(error ?: "Unknown error")
        return AgentResult(
            success = false,
            agentName = agentName,
            error = error,
            confidence = 0f
        )
    }
}
