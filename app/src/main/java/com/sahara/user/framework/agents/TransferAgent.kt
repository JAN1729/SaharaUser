package com.sahara.user.framework.agents

import com.sahara.user.algorithms.Haversine
import com.sahara.user.algorithms.ETACalculator
import com.sahara.user.data.model.LiveBusLocation
import com.sahara.user.data.model.RouteStopDTO
import com.sahara.user.data.model.VehicleDTO
import com.sahara.user.framework.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transfer Agent - The Multi-Bus Journey Specialist
 * 
 * ROLE: Find routes requiring transfers when no direct route exists.
 * Identifies common transfer points and minimizes walking distance.
 * 
 * REQUIRES IN CONTEXT:
 * - vehicles, stops, live_locations (from DataAgent)
 * 
 * CALLED WHEN: PathAgent fails to find a direct route
 */
@Singleton
class TransferAgent @Inject constructor(
    private val toolRegistry: ToolRegistry
) : Agent {
    
    override val name = "TransferAgent"
    override val description = "Finds multi-bus routes with transfer points"
    override val tools = emptyList<Tool>()
    override val systemPrompt = """
        I am the Transfer Agent - the multi-bus journey specialist.
        I find routes requiring transfers when no direct route exists.
        I minimize walking distance at transfer points (max 500m).
    """.trimIndent()
    
    companion object {
        private const val MAX_WALKING_DISTANCE_KM = 0.5 // 500 meters
        private const val MAX_TRANSFERS = 2
    }
    
    override suspend fun execute(context: AgentContext, input: AgentInput): AgentOutput {
        val startTime = System.currentTimeMillis()
        
        val origin = input.parameters["origin"] as? String
        val destination = input.parameters["destination"] as? String
        
        if (origin.isNullOrBlank() || destination.isNullOrBlank()) {
            return AgentOutput.failure(name, "Origin and destination are required")
        }
        
        context.updateStatus("Searching for transfer routes...")
        
        try {
            @Suppress("UNCHECKED_CAST")
            val vehicles = context.getData<List<VehicleDTO>>("vehicles") ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val stops = context.getData<List<RouteStopDTO>>("stops") ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val locations = context.getData<List<LiveBusLocation>>("live_locations") ?: emptyList()
            
            if (vehicles.isEmpty() || stops.isEmpty()) {
                return AgentOutput.failure(name, "No data available for transfer search")
            }
            
            // Find routes containing origin
            val originRoutes = findRoutesWithStop(origin, vehicles, stops)
            
            // Find routes containing destination
            val destRoutes = findRoutesWithStop(destination, vehicles, stops)
            
            if (originRoutes.isEmpty()) {
                return AgentOutput.failure(name, "No routes found serving '$origin'")
            }
            
            if (destRoutes.isEmpty()) {
                return AgentOutput.failure(name, "No routes found serving '$destination'")
            }
            
            // Find transfer points
            val transferOptions = findTransferPoints(
                originRoutes,
                destRoutes,
                stops,
                origin,
                destination
            )
            
            if (transferOptions.isEmpty()) {
                val executionTime = System.currentTimeMillis() - startTime
                return AgentOutput.failure(
                    agentName = name,
                    error = "No transfer route found within walking distance",
                    data = mapOf(
                        "origin_routes_count" to originRoutes.size,
                        "dest_routes_count" to destRoutes.size
                    )
                ).copy(executionTimeMs = executionTime)
            }
            
            // Rank transfer options
            val best = transferOptions.minByOrNull { it.totalTime + it.walkingDistance * 10 }!!
            
            val executionTime = System.currentTimeMillis() - startTime
            
            // Build response message
            val message = buildTransferMessage(best, vehicles)
            
            return AgentOutput.success(
                agentName = name,
                data = mapOf(
                    "transfer_route" to best,
                    "alternatives" to transferOptions.take(3),
                    "first_bus" to best.firstBus,
                    "transfer_point" to best.transferStop,
                    "second_bus" to best.secondBus,
                    "walking_distance_m" to (best.walkingDistance * 1000).toInt(),
                    "total_time_min" to best.totalTime.toInt()
                ),
                message = message,
                confidence = 0.8f,
                toolsUsed = emptyList()
            ).copy(executionTimeMs = executionTime)
            
        } catch (e: Exception) {
            return AgentOutput.failure(name, "Transfer search error: ${e.message}")
        }
    }
    
    private data class RouteWithStop(
        val vehicleId: String,
        val vehicleName: String,
        val matchedStop: RouteStopDTO,
        val allStops: List<RouteStopDTO>
    )
    
    private data class TransferOption(
        val firstBus: String,
        val firstBusId: String,
        val firstStops: List<String>,
        val transferStop: String,
        val walkingDistance: Double,
        val secondBus: String,
        val secondBusId: String,
        val secondStops: List<String>,
        val totalTime: Double
    )
    
    private fun findRoutesWithStop(
        stopName: String,
        vehicles: List<VehicleDTO>,
        stops: List<RouteStopDTO>
    ): List<RouteWithStop> {
        val matchingStops = stops.filter { 
            it.name.contains(stopName, ignoreCase = true) 
        }
        
        return matchingStops.mapNotNull { stop ->
            val vehicle = vehicles.find { it.id == stop.vehicleId }
            val allVehicleStops = stops.filter { it.vehicleId == stop.vehicleId }
                .sortedBy { it.stopOrder }
            
            if (vehicle != null) {
                RouteWithStop(
                    vehicleId = stop.vehicleId,
                    vehicleName = vehicle.name,
                    matchedStop = stop,
                    allStops = allVehicleStops
                )
            } else null
        }
    }
    
    private fun findTransferPoints(
        originRoutes: List<RouteWithStop>,
        destRoutes: List<RouteWithStop>,
        allStops: List<RouteStopDTO>,
        origin: String,
        destination: String
    ): List<TransferOption> {
        val transferOptions = mutableListOf<TransferOption>()
        
        for (originRoute in originRoutes) {
            // Get stops AFTER the origin on this route
            val originStopOrder = originRoute.matchedStop.stopOrder
            val stopsAfterOrigin = originRoute.allStops.filter { it.stopOrder > originStopOrder }
            
            for (destRoute in destRoutes) {
                // Get stops BEFORE the destination on this route
                val destStopOrder = destRoute.matchedStop.stopOrder
                val stopsBeforeDest = destRoute.allStops.filter { it.stopOrder < destStopOrder }
                
                // Find transfer points (stops that are close between the two routes)
                for (stopFrom in stopsAfterOrigin) {
                    for (stopTo in stopsBeforeDest) {
                        val walkingDistance = Haversine.distance(
                            stopFrom.latitude, stopFrom.longitude,
                            stopTo.latitude, stopTo.longitude
                        )
                        
                        if (walkingDistance <= MAX_WALKING_DISTANCE_KM) {
                            // Calculate total time
                            val firstLegStops = originRoute.allStops.filter {
                                it.stopOrder in originStopOrder..stopFrom.stopOrder
                            }
                            val secondLegStops = destRoute.allStops.filter {
                                it.stopOrder in stopTo.stopOrder..destStopOrder
                            }
                            
                            val firstLegEta = ETACalculator.calculateETA(
                                distanceKm = estimateDistance(firstLegStops),
                                numStops = firstLegStops.size
                            )
                            
                            val secondLegEta = ETACalculator.calculateETA(
                                distanceKm = estimateDistance(secondLegStops),
                                numStops = secondLegStops.size
                            )
                            
                            val walkingTime = (walkingDistance / 5.0) * 60 // 5 km/h walking speed
                            val waitingTime = 5.0 // Assume 5 min wait for second bus
                            
                            val totalTime = firstLegEta.etaMinutes + walkingTime + waitingTime + secondLegEta.etaMinutes
                            
                            transferOptions.add(TransferOption(
                                firstBus = originRoute.vehicleName,
                                firstBusId = originRoute.vehicleId,
                                firstStops = firstLegStops.map { it.name },
                                transferStop = stopFrom.name,
                                walkingDistance = walkingDistance,
                                secondBus = destRoute.vehicleName,
                                secondBusId = destRoute.vehicleId,
                                secondStops = secondLegStops.map { it.name },
                                totalTime = totalTime
                            ))
                        }
                    }
                }
            }
        }
        
        return transferOptions.sortedBy { it.totalTime }
    }
    
    private fun estimateDistance(stops: List<RouteStopDTO>): Double {
        var distance = 0.0
        for (i in 0 until stops.size - 1) {
            distance += Haversine.distance(
                stops[i].latitude, stops[i].longitude,
                stops[i + 1].latitude, stops[i + 1].longitude
            )
        }
        return distance
    }
    
    private fun buildTransferMessage(option: TransferOption, vehicles: List<VehicleDTO>): String {
        val walkingMeters = (option.walkingDistance * 1000).toInt()
        
        return buildString {
            appendLine("🔄 **Transfer Required**")
            appendLine("1️⃣ Take **${option.firstBus}** to ${option.transferStop}")
            if (walkingMeters > 50) {
                appendLine("🚶 Walk ${walkingMeters}m")
            }
            appendLine("2️⃣ Take **${option.secondBus}** to destination")
            append("⏱️ Total: ~${option.totalTime.toInt()} min")
        }
    }
}
