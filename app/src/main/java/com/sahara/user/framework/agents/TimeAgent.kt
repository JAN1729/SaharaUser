package com.sahara.user.framework.agents

import com.sahara.user.framework.*
import com.sahara.user.framework.tools.AlgorithmTools
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Time Agent - The ETA Calculator
 * 
 * ROLE: Calculate estimated time of arrival.
 * Considers distance, stops, traffic, and current bus speed.
 * 
 * REQUIRES IN CONTEXT:
 * - best_route or distance data
 * 
 * STORES IN CONTEXT:
 * - eta_minutes, eta_range_min, eta_range_max
 */
@Singleton
class TimeAgent @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val algorithmTools: AlgorithmTools
) : Agent {
    
    override val name = "TimeAgent"
    override val description = "Calculates estimated time of arrival with traffic adjustments"
    override val tools = algorithmTools.getAllTools()
    override val systemPrompt = """
        I am the Time Agent - the ETA specialist.
        I calculate realistic arrival times considering traffic and stops.
        I provide time ranges to account for variability.
    """.trimIndent()
    
    override suspend fun execute(context: AgentContext, input: AgentInput): AgentOutput {
        val startTime = System.currentTimeMillis()
        val toolsUsed = mutableListOf<String>()
        
        context.updateStatus("Calculating ETA...")
        
        try {
            // Get route data from context or input
            val bestRoute = context.getData<Map<String, Any?>>("best_route")
            
            val distanceKm = bestRoute?.get("distance_km") as? Double
                ?: (input.parameters["distance_km"] as? Number)?.toDouble()
            
            val numStops = bestRoute?.get("stops_count") as? Int
                ?: (input.parameters["num_stops"] as? Number)?.toInt()
                ?: 5 // Default assumption
            
            val busSpeed = bestRoute?.get("bus_speed") as? Double
                ?: (input.parameters["bus_speed"] as? Number)?.toDouble()
            
            if (distanceKm == null) {
                // Try to estimate from route
                val pathResult = context.getAgentResult("PathAgent")
                val pathRoute = pathResult?.data?.get("best_route") as? Map<*, *>
                val pathDistance = (pathRoute?.get("distance_km") as? Number)?.toDouble()
                
                if (pathDistance == null) {
                    return AgentOutput.failure(
                        name,
                        "Cannot calculate ETA without distance. PathAgent must run first."
                    )
                }
                
                return calculateETA(context, pathDistance, numStops, busSpeed, toolsUsed, startTime)
            }
            
            return calculateETA(context, distanceKm, numStops, busSpeed, toolsUsed, startTime)
            
        } catch (e: Exception) {
            return AgentOutput.failure(name, "ETA calculation error: ${e.message}")
        }
    }
    
    private suspend fun calculateETA(
        context: AgentContext,
        distanceKm: Double,
        numStops: Int,
        busSpeed: Double?,
        toolsUsed: MutableList<String>,
        startTime: Long
    ): AgentOutput {
        val etaResult = toolRegistry.executeTool(
            "calculate_eta",
            mapOf(
                "distance_km" to distanceKm,
                "num_stops" to numStops,
                "current_speed" to busSpeed
            ),
            context
        )
        toolsUsed.add("calculate_eta")
        
        if (!etaResult.success) {
            return AgentOutput.failure(name, "ETA calculation failed: ${etaResult.error}")
        }
        
        val etaMinutes = etaResult.data["eta_minutes"] as? Double ?: 0.0
        val etaRangeMin = etaResult.data["eta_range_min"] as? Double ?: etaMinutes
        val etaRangeMax = etaResult.data["eta_range_max"] as? Double ?: etaMinutes
        val trafficFactor = etaResult.data["traffic_factor"] as? Double ?: 1.0
        
        // Store in context
        context.setData("eta_minutes", etaMinutes)
        context.setData("eta_range_min", etaRangeMin)
        context.setData("eta_range_max", etaRangeMax)
        
        val executionTime = System.currentTimeMillis() - startTime
        
        // Determine confidence based on live data availability
        val hasLiveSpeed = busSpeed != null && busSpeed > 0
        val confidence = if (hasLiveSpeed) 0.9f else 0.7f
        
        return AgentOutput.success(
            agentName = name,
            data = mapOf(
                "eta_minutes" to etaMinutes.toInt(),
                "eta_range_min" to etaRangeMin.toInt(),
                "eta_range_max" to etaRangeMax.toInt(),
                "traffic_factor" to trafficFactor,
                "has_live_data" to hasLiveSpeed
            ),
            message = "ETA: ${etaRangeMin.toInt()}-${etaRangeMax.toInt()} min" +
                    if (trafficFactor > 1.2) " (heavy traffic)" else "",
            confidence = confidence,
            toolsUsed = toolsUsed
        ).copy(executionTimeMs = executionTime)
    }
}
