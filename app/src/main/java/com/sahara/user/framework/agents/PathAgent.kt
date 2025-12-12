package com.sahara.user.framework.agents

import com.sahara.user.algorithms.RouteMatcher
import com.sahara.user.data.model.LiveBusLocation
import com.sahara.user.data.model.RouteStopDTO
import com.sahara.user.data.model.VehicleDTO
import com.sahara.user.framework.*
import com.sahara.user.framework.tools.AlgorithmTools
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Path Agent - The Route Optimizer
 * 
 * ROLE: Find the best bus route between origin and destination.
 * Uses route matching and vehicle routing optimization algorithms.
 * 
 * REQUIRES IN CONTEXT:
 * - vehicles, stops, live_locations (from DataAgent)
 * 
 * STORES IN CONTEXT:
 * - best_route: Map with route details
 * - alternatives: List of alternative routes
 */
@Singleton
class PathAgent @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val algorithmTools: AlgorithmTools
) : Agent {
    
    override val name = "PathAgent"
    override val description = "Finds optimal bus routes between origin and destination"
    override val tools = algorithmTools.getAllTools()
    override val systemPrompt = """
        I am the Path Agent - the route optimization specialist.
        I find the best bus routes using mathematical algorithms.
        I consider distance, stops, and live bus positions.
    """.trimIndent()
    
    override suspend fun execute(context: AgentContext, input: AgentInput): AgentOutput {
        val startTime = System.currentTimeMillis()
        val toolsUsed = mutableListOf<String>()
        
        val origin = input.parameters["origin"] as? String
        val destination = input.parameters["destination"] as? String
        val userLat = (input.parameters["user_lat"] as? Number)?.toDouble()
        val userLng = (input.parameters["user_lng"] as? Number)?.toDouble()
        
        if (origin.isNullOrBlank() || destination.isNullOrBlank()) {
            return AgentOutput.failure(name, "Origin and destination are required")
        }
        
        context.updateStatus("Finding routes from $origin to $destination...")
        
        try {
            // Check if data is available
            @Suppress("UNCHECKED_CAST")
            val vehicles = context.getData<List<VehicleDTO>>("vehicles")
            @Suppress("UNCHECKED_CAST")
            val stops = context.getData<List<RouteStopDTO>>("stops")
            
            if (vehicles.isNullOrEmpty() || stops.isNullOrEmpty()) {
                return AgentOutput.failure(
                    name,
                    "No data available. DataAgent must run first.",
                    data = mapOf("needs_data" to true)
                )
            }
            
            // Use compute_route tool
            val computeResult = toolRegistry.executeTool(
                "compute_route",
                mapOf(
                    "origin" to origin,
                    "destination" to destination,
                    "user_lat" to userLat,
                    "user_lng" to userLng
                ),
                context
            )
            toolsUsed.add("compute_route")
            
            if (computeResult.success && computeResult.data["success"] == true) {
                val bestRoute = computeResult.data["best_route"] as? Map<*, *>
                val alternatives = computeResult.data["alternatives"] as? List<*>
                
                // Store in context
                context.setData("best_route", bestRoute)
                context.setData("alternatives", alternatives)
                
                val executionTime = System.currentTimeMillis() - startTime
                
                return AgentOutput.success(
                    agentName = name,
                    data = mapOf(
                        "best_route" to bestRoute,
                        "alternatives" to alternatives,
                        "route_found" to true
                    ),
                    message = "Found route via ${bestRoute?.get("vehicle_name")}",
                    confidence = 0.9f,
                    toolsUsed = toolsUsed
                ).copy(executionTimeMs = executionTime)
                
            } else {
                // No direct route found - suggest transfer
                val executionTime = System.currentTimeMillis() - startTime
                
                return AgentOutput.failure(
                    agentName = name,
                    error = "No direct route found",
                    data = mapOf(
                        "route_found" to false,
                        "origin" to origin,
                        "destination" to destination,
                        "suggestions" to (computeResult.data["suggestions"] ?: listOf("Check for transfer options"))
                    )
                ).copy(
                    suggestedNextAgent = "TransferAgent",
                    executionTimeMs = executionTime
                )
            }
            
        } catch (e: Exception) {
            return AgentOutput.failure(name, "Route finding error: ${e.message}")
        }
    }
}
