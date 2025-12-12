package com.sahara.user.framework.agents

import com.sahara.user.data.model.LiveBusLocation
import com.sahara.user.data.model.RouteStopDTO
import com.sahara.user.data.model.VehicleDTO
import com.sahara.user.framework.*
import com.sahara.user.framework.tools.SupabaseTools
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data Agent - The Data Retriever
 * 
 * ROLE: Fetch and cache data from Supabase.
 * First agent in most pipelines - loads vehicles, stops, live locations.
 * 
 * STORES IN CONTEXT:
 * - vehicles: List<VehicleDTO>
 * - stops: List<RouteStopDTO>
 * - live_locations: List<LiveBusLocation>
 */
@Singleton
class DataAgent @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val supabaseTools: SupabaseTools
) : Agent {
    
    override val name = "DataAgent"
    override val description = "Fetches and caches data from Supabase database"
    override val tools = supabaseTools.getAllTools()
    override val systemPrompt = """
        I am the Data Agent - the data specialist.
        I fetch vehicles, stops, and live locations from the database.
        I ensure all necessary data is available for other agents.
    """.trimIndent()
    
    override suspend fun onStart(context: AgentContext) {
        context.trace(TraceEvent.Type.AGENT_STARTED, "DataAgent preparing to fetch data")
    }
    
    override suspend fun execute(context: AgentContext, input: AgentInput): AgentOutput {
        val startTime = System.currentTimeMillis()
        val toolsUsed = mutableListOf<String>()
        
        context.updateStatus("Fetching bus data...")
        
        try {
            // Fetch vehicles
            val vehiclesResult = toolRegistry.executeTool(
                "get_vehicles",
                emptyMap(),
                context
            )
            toolsUsed.add("get_vehicles")
            
            if (!vehiclesResult.success) {
                return AgentOutput.failure(name, "Failed to fetch vehicles: ${vehiclesResult.error}")
            }
            
            // Fetch route stops
            val stopsResult = toolRegistry.executeTool(
                "get_route_stops",
                emptyMap(),
                context
            )
            toolsUsed.add("get_route_stops")
            
            if (!stopsResult.success) {
                return AgentOutput.failure(name, "Failed to fetch stops: ${stopsResult.error}")
            }
            
            // Fetch live locations
            val locationsResult = toolRegistry.executeTool(
                "get_live_locations",
                emptyMap(),
                context
            )
            toolsUsed.add("get_live_locations")
            
            // Locations are optional - don't fail if missing
            
            @Suppress("UNCHECKED_CAST")
            val vehicles = context.getData<List<VehicleDTO>>("vehicles") ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val stops = context.getData<List<RouteStopDTO>>("stops") ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val locations = context.getData<List<LiveBusLocation>>("live_locations") ?: emptyList()
            
            val executionTime = System.currentTimeMillis() - startTime
            
            return AgentOutput.success(
                agentName = name,
                data = mapOf(
                    "vehicles_count" to vehicles.size,
                    "stops_count" to stops.size,
                    "live_locations_count" to locations.size
                ),
                message = "Loaded ${vehicles.size} vehicles, ${stops.size} stops, ${locations.size} live",
                confidence = 1.0f,
                toolsUsed = toolsUsed
            ).copy(executionTimeMs = executionTime)
            
        } catch (e: Exception) {
            return AgentOutput.failure(name, "Data fetch error: ${e.message}")
        }
    }
}
