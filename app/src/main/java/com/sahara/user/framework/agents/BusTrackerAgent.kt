package com.sahara.user.framework.agents

import com.sahara.user.algorithms.Haversine
import com.sahara.user.data.model.LiveBusLocation
import com.sahara.user.data.model.RouteStopDTO
import com.sahara.user.data.model.VehicleDTO
import com.sahara.user.framework.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bus Tracker Agent - The Live Position Specialist
 * 
 * ROLE: Track real-time bus positions and determine status.
 * Detects movement, matches to nearest stop, checks data freshness.
 * 
 * REQUIRES IN CONTEXT:
 * - vehicles, stops, live_locations (from DataAgent)
 * 
 * STORES IN CONTEXT:
 * - bus_position: Current bus location and status
 */
@Singleton
class BusTrackerAgent @Inject constructor(
    private val toolRegistry: ToolRegistry
) : Agent {
    
    override val name = "BusTrackerAgent"
    override val description = "Tracks real-time bus positions and movement status"
    override val tools = emptyList<Tool>()  // Uses context data directly
    override val systemPrompt = """
        I am the Bus Tracker Agent - the live tracking specialist.
        I monitor real-time bus positions and detect their movement.
        I identify the nearest stop and check data freshness.
    """.trimIndent()
    
    override suspend fun execute(context: AgentContext, input: AgentInput): AgentOutput {
        val startTime = System.currentTimeMillis()
        
        val busId = input.parameters["bus_id"] as? String
        val vehicleName = input.parameters["vehicle_name"] as? String
        
        // Try to get bus ID from context if not in input
        val targetBusId = busId 
            ?: (context.getData<Map<String, Any?>>("best_route")?.get("vehicle_id") as? String)
        
        val targetName = vehicleName
            ?: (context.getData<Map<String, Any?>>("best_route")?.get("vehicle_name") as? String)
        
        context.updateStatus("Tracking bus position...")
        
        try {
            @Suppress("UNCHECKED_CAST")
            val vehicles = context.getData<List<VehicleDTO>>("vehicles") ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val stops = context.getData<List<RouteStopDTO>>("stops") ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val locations = context.getData<List<LiveBusLocation>>("live_locations") ?: emptyList()
            
            if (locations.isEmpty()) {
                return AgentOutput.failure(name, "No live bus data available")
            }
            
            // Find the target bus
            val liveLocation = findBus(targetBusId, targetName, vehicles, locations)
            
            if (liveLocation == null) {
                val executionTime = System.currentTimeMillis() - startTime
                return AgentOutput.failure(
                    agentName = name,
                    error = "Bus not currently sharing location",
                    data = mapOf(
                        "target_bus" to (targetName ?: targetBusId ?: "unknown"),
                        "total_live_buses" to locations.size
                    )
                ).copy(executionTimeMs = executionTime)
            }
            
            // Find nearest stop
            val routeStops = stops.filter { it.vehicleId == liveLocation.busId }
                .sortedBy { it.stopOrder }
            
            val nearestStop = routeStops.minByOrNull { stop ->
                Haversine.distance(
                    liveLocation.latitude, liveLocation.longitude,
                    stop.latitude, stop.longitude
                )
            }
            
            val nearestStopDistance = nearestStop?.let {
                Haversine.distance(
                    liveLocation.latitude, liveLocation.longitude,
                    it.latitude, it.longitude
                )
            } ?: 0.0
            
            // Determine movement and freshness
            val isMoving = (liveLocation.speed?.toDouble() ?: 0.0) > 2.0
            val freshness = calculateFreshness(liveLocation.timestamp)
            val vehicle = vehicles.find { it.id == liveLocation.busId }
            
            // Store in context
            val busPosition = mapOf(
                "bus_id" to liveLocation.busId,
                "bus_name" to (vehicle?.name ?: liveLocation.busId),
                "vehicle_number" to (vehicle?.vehicleNumber ?: ""),
                "latitude" to liveLocation.latitude,
                "longitude" to liveLocation.longitude,
                "speed" to (liveLocation.speed ?: 0f),
                "is_moving" to isMoving,
                "nearest_stop" to (nearestStop?.name ?: "Unknown"),
                "nearest_stop_order" to (nearestStop?.stopOrder ?: -1),
                "nearest_stop_distance_m" to (nearestStopDistance * 1000).toInt(),
                "freshness" to freshness,
                "timestamp" to liveLocation.timestamp
            )
            context.setData("bus_position", busPosition)
            
            val executionTime = System.currentTimeMillis() - startTime
            
            // Confidence based on freshness
            val confidence = when (freshness) {
                "fresh" -> 0.95f
                "recent" -> 0.7f
                else -> 0.4f
            }
            
            return AgentOutput.success(
                agentName = name,
                data = busPosition,
                message = "${vehicle?.name ?: "Bus"} is ${if (isMoving) "moving" else "stopped"} near ${nearestStop?.name ?: "Unknown"}",
                confidence = confidence,
                toolsUsed = emptyList()
            ).copy(executionTimeMs = executionTime)
            
        } catch (e: Exception) {
            return AgentOutput.failure(name, "Tracking error: ${e.message}")
        }
    }
    
    private fun findBus(
        busId: String?,
        vehicleName: String?,
        vehicles: List<VehicleDTO>,
        locations: List<LiveBusLocation>
    ): LiveBusLocation? {
        // First try exact ID match
        if (busId != null) {
            val byId = locations.find { it.busId == busId }
            if (byId != null) return byId
        }
        
        // Try name match
        if (vehicleName != null) {
            return locations.find { loc ->
                val vehicle = vehicles.find { it.id == loc.busId }
                vehicle?.name?.contains(vehicleName, ignoreCase = true) == true ||
                vehicle?.vehicleNumber?.contains(vehicleName, ignoreCase = true) == true
            }
        }
        
        // Try partial match on ID
        if (busId != null) {
            return locations.find { loc ->
                loc.busId.contains(busId, ignoreCase = true) ||
                vehicles.find { it.id == loc.busId }?.name?.contains(busId, ignoreCase = true) == true
            }
        }
        
        return null
    }
    
    private fun calculateFreshness(timestamp: Long): String {
        val age = System.currentTimeMillis() - timestamp
        return when {
            age < 2 * 60 * 1000 -> "fresh"      // < 2 min
            age < 10 * 60 * 1000 -> "recent"    // < 10 min
            else -> "stale"
        }
    }
}
