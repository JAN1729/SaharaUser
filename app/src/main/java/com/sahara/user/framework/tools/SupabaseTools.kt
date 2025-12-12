package com.sahara.user.framework.tools

import com.sahara.user.data.api.SupabaseApiClient
import com.sahara.user.data.model.LiveBusLocation
import com.sahara.user.data.model.RouteStopDTO
import com.sahara.user.data.model.VehicleDTO
import com.sahara.user.framework.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supabase Tools - Database access for agents.
 * 
 * Provides standardized tools for:
 * - Fetching vehicles
 * - Fetching route stops
 * - Fetching live locations
 * - User data operations
 */
@Singleton
class SupabaseTools @Inject constructor(
    private val supabaseClient: SupabaseApiClient
) {
    
    /**
     * Get all tools provided by this class.
     */
    fun getAllTools(): List<Tool> = listOf(
        getVehiclesTool,
        getRouteStopsTool,
        getLiveLocationsTool,
        getVehicleByIdTool,
        getStopsByVehicleTool,
        searchStopsTool
    )
    
    // ==================== VEHICLES TOOL ====================
    
    val getVehiclesTool = object : BaseTool() {
        override val name = "get_vehicles"
        override val description = "Fetch all active vehicles/buses from the database"
        override val inputSchema = ToolSchema()
        override val outputSchema = ToolSchema(
            properties = mapOf(
                "vehicles" to PropertySchema("array", "List of vehicles"),
                "count" to PropertySchema("integer", "Number of vehicles")
            )
        )
        
        override suspend fun doExecute(
            params: Map<String, Any?>,
            context: AgentContext
        ): ToolResult {
            val vehicles = supabaseClient.getActiveVehicles().getOrElse { emptyList() }
            
            // Store in context for other agents
            context.setData("vehicles", vehicles)
            
            return ToolResult.success(name, mapOf(
                "vehicles" to vehicles,
                "count" to vehicles.size
            ))
        }
    }
    
    // ==================== ROUTE STOPS TOOL ====================
    
    val getRouteStopsTool = object : BaseTool() {
        override val name = "get_route_stops"
        override val description = "Fetch all route stops from the database"
        override val inputSchema = ToolSchema()
        override val outputSchema = ToolSchema(
            properties = mapOf(
                "stops" to PropertySchema("array", "List of route stops"),
                "count" to PropertySchema("integer", "Number of stops")
            )
        )
        
        override suspend fun doExecute(
            params: Map<String, Any?>,
            context: AgentContext
        ): ToolResult {
            val stops = supabaseClient.getAllRouteStops().getOrElse { emptyList() }
            
            // Store in context for other agents
            context.setData("stops", stops)
            
            return ToolResult.success(name, mapOf(
                "stops" to stops,
                "count" to stops.size
            ))
        }
    }
    
    // ==================== LIVE LOCATIONS TOOL ====================
    
    val getLiveLocationsTool = object : BaseTool() {
        override val name = "get_live_locations"
        override val description = "Fetch current live GPS positions of all buses"
        override val inputSchema = ToolSchema()
        override val outputSchema = ToolSchema(
            properties = mapOf(
                "locations" to PropertySchema("array", "List of live bus locations"),
                "count" to PropertySchema("integer", "Number of live buses")
            )
        )
        
        override suspend fun doExecute(
            params: Map<String, Any?>,
            context: AgentContext
        ): ToolResult {
            val locations = supabaseClient.getLiveBusLocations().getOrElse { emptyList() }
            
            // Store in context for other agents
            context.setData("live_locations", locations)
            
            return ToolResult.success(name, mapOf(
                "locations" to locations,
                "count" to locations.size
            ))
        }
    }
    
    // ==================== VEHICLE BY ID TOOL ====================
    
    val getVehicleByIdTool = object : BaseTool() {
        override val name = "get_vehicle_by_id"
        override val description = "Fetch a specific vehicle by its ID"
        override val inputSchema = ToolSchema(
            properties = mapOf(
                "vehicle_id" to PropertySchema("string", "The vehicle ID to fetch")
            ),
            required = listOf("vehicle_id")
        )
        override val outputSchema = ToolSchema(
            properties = mapOf(
                "vehicle" to PropertySchema("object", "The vehicle details"),
                "found" to PropertySchema("boolean", "Whether the vehicle was found")
            )
        )
        
        override suspend fun doExecute(
            params: Map<String, Any?>,
            context: AgentContext
        ): ToolResult {
            val vehicleId = params["vehicle_id"] as? String
                ?: return ToolResult.failure(name, "vehicle_id is required")
            
            val vehicle = supabaseClient.getVehicle(vehicleId).getOrNull()
            
            return if (vehicle != null) {
                ToolResult.success(name, mapOf(
                    "vehicle" to vehicle,
                    "found" to true
                ))
            } else {
                ToolResult.success(name, mapOf(
                    "vehicle" to null,
                    "found" to false
                ))
            }
        }
    }
    
    // ==================== STOPS BY VEHICLE TOOL ====================
    
    val getStopsByVehicleTool = object : BaseTool() {
        override val name = "get_stops_by_vehicle"
        override val description = "Fetch all stops for a specific vehicle/bus route"
        override val inputSchema = ToolSchema(
            properties = mapOf(
                "vehicle_id" to PropertySchema("string", "The vehicle ID")
            ),
            required = listOf("vehicle_id")
        )
        override val outputSchema = ToolSchema(
            properties = mapOf(
                "stops" to PropertySchema("array", "Ordered list of stops"),
                "count" to PropertySchema("integer", "Number of stops")
            )
        )
        
        override suspend fun doExecute(
            params: Map<String, Any?>,
            context: AgentContext
        ): ToolResult {
            val vehicleId = params["vehicle_id"] as? String
                ?: return ToolResult.failure(name, "vehicle_id is required")
            
            val stops = supabaseClient.getRouteStops(vehicleId).getOrElse { emptyList() }
            
            return ToolResult.success(name, mapOf(
                "stops" to stops,
                "count" to stops.size
            ))
        }
    }
    
    // ==================== SEARCH STOPS TOOL ====================
    
    val searchStopsTool = object : BaseTool() {
        override val name = "search_stops"
        override val description = "Search for stops matching a name pattern"
        override val inputSchema = ToolSchema(
            properties = mapOf(
                "query" to PropertySchema("string", "Search query for stop name")
            ),
            required = listOf("query")
        )
        override val outputSchema = ToolSchema(
            properties = mapOf(
                "matches" to PropertySchema("array", "Matching stops"),
                "count" to PropertySchema("integer", "Number of matches")
            )
        )
        
        override suspend fun doExecute(
            params: Map<String, Any?>,
            context: AgentContext
        ): ToolResult {
            val query = params["query"] as? String
                ?: return ToolResult.failure(name, "query is required")
            
            // Get all stops (from context or fetch)
            @Suppress("UNCHECKED_CAST")
            val allStops = context.getData<List<RouteStopDTO>>("stops")
                ?: supabaseClient.getAllRouteStops().getOrElse { emptyList() }
            
            // Filter by name
            val matches = allStops.filter { stop ->
                stop.name.contains(query, ignoreCase = true)
            }
            
            return ToolResult.success(name, mapOf(
                "matches" to matches,
                "count" to matches.size
            ))
        }
    }
}
