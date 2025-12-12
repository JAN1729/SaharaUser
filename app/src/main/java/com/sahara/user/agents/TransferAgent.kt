package com.sahara.user.agents

import com.sahara.user.algorithms.Haversine
import com.sahara.user.data.api.PerplexityClient
import com.sahara.user.data.api.SupabaseApiClient
import com.sahara.user.data.model.RouteStopDTO
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transfer Agent - The Multi-Bus Journey Specialist
 * 
 * PERSONA: I am the transfer optimization specialist. When no direct
 * route exists, I find the best combination of buses with optimal
 * transfer points. I minimize walking and waiting time.
 * 
 * MY ROLE:
 * - Called when PathAgent finds no direct route
 * - Find transfer points between different routes
 * - Minimize walking distance at transfers
 * - Calculate total journey with transfers
 * 
 * I DEPEND ON:
 * - PathAgent: Tells me when no direct route exists
 * - SQLAgent: For route data
 * 
 * I COMMUNICATE WITH:
 * - TimeAgent: Sends multi-leg journey for ETA calculation
 * - MainOrchestrator: Returns transfer plan
 */
@Singleton
class TransferAgent @Inject constructor(
    perplexityClient: PerplexityClient,
    private val supabaseClient: SupabaseApiClient
) : BaseAgent(perplexityClient) {
    
    override val agentName = "TransferAgent"
    
    override val persona = AgentPersona(
        role = "Transfer Optimizer",
        capabilities = listOf(
            "Find transfer points between routes",
            "Minimize walking at transfers",
            "Calculate multi-leg journeys",
            "Optimize for minimal transfers",
            "Consider walking distance limits"
        ),
        dataAccess = listOf(
            "Route data from SQLAgent",
            "All route_stops for transfer matching"
        ),
        canDelegateTo = listOf("TimeAgent")
    )
    
    override val systemPrompt = """
        I am the Transfer Agent - the multi-bus journey specialist.
        
        MY PERSONALITY:
        - I find solutions when direct routes don't exist
        - I minimize inconvenience (walking, waiting)
        - I prefer fewer transfers over shorter distance
        - I ensure practical, walkable transfer points
        
        TRANSFER RULES:
        - Max walking distance at transfer: 500m
        - Prefer same-stop transfers (no walking)
        - Maximum 2 transfers for any journey
        - Consider waiting time for connecting bus
        
        MY ALGORITHM:
        1. Find all routes containing origin
        2. Find all routes containing destination
        3. Find stops where routes intersect or are nearby
        4. Rank transfers by: walking distance, total journey
        5. Return best transfer plan
        
        I OUTPUT:
        - First bus (origin to transfer point)
        - Walking instructions (if any)
        - Second bus (transfer to destination)
        - Total estimated journey
    """.trimIndent()
    
    companion object {
        private const val MAX_WALKING_M = 500.0
        private const val MAX_TRANSFERS = 2
    }
    
    override suspend fun execute(input: Map<String, Any>): AgentResult {
        val origin = input["origin"]?.toString() ?: ""
        val destination = input["destination"]?.toString() ?: ""
        
        android.util.Log.d(agentName, "Finding transfers: $origin → $destination")
        
        return try {
            // Get route data
            val vehiclesResult = supabaseClient.getActiveVehicles()
            val vehicles = vehiclesResult.getOrElse { 
                return createErrorResult("Failed to get vehicles")
            }
            
            val stopsResult = supabaseClient.getAllRouteStops()
            val allStops = stopsResult.getOrElse { 
                return createErrorResult("Failed to get stops")
            }
            
            // Find routes with origin
            val originRoutes = findRoutesWithStop(origin, allStops)
            
            // Find routes with destination
            val destRoutes = findRoutesWithStop(destination, allStops)
            
            // Check for direct route first
            val directRoutes = originRoutes.keys.intersect(destRoutes.keys)
            if (directRoutes.isNotEmpty()) {
                return AgentResult(
                    success = true,
                    agentName = agentName,
                    data = mapOf(
                        "direct_route_available" to true,
                        "direct_vehicles" to directRoutes.toList()
                    ),
                    message = "Direct route available - PathAgent should handle this",
                    confidence = 1.0f,
                    suggestedNextAgent = "PathAgent"
                )
            }
            
            // Find transfer options
            val transfers = mutableListOf<TransferPlan>()
            val vehicleMap = vehicles.associateBy { it.id }
            
            for ((originVehicleId, originStops) in originRoutes) {
                for ((destVehicleId, destStops) in destRoutes) {
                    if (originVehicleId == destVehicleId) continue
                    
                    val transferPoints = findTransferPoints(
                        originVehicleId, originStops,
                        destVehicleId, destStops,
                        origin, destination,
                        allStops
                    )
                    
                    transfers.addAll(transferPoints.map { point ->
                        TransferPlan(
                            firstVehicle = vehicleMap[originVehicleId]?.name ?: originVehicleId,
                            secondVehicle = vehicleMap[destVehicleId]?.name ?: destVehicleId,
                            transferFrom = point.fromStop,
                            transferTo = point.toStop,
                            walkingDistanceM = point.walkingM,
                            leg1Stops = point.leg1Stops,
                            leg2Stops = point.leg2Stops,
                            totalDistanceKm = point.totalKm
                        )
                    })
                }
            }
            
            if (transfers.isEmpty()) {
                return AgentResult(
                    success = false,
                    agentName = agentName,
                    data = mapOf(
                        "origin_routes" to originRoutes.keys.toList(),
                        "dest_routes" to destRoutes.keys.toList()
                    ),
                    error = "No viable transfer found between $origin and $destination",
                    confidence = 0.3f
                )
            }
            
            // Rank: prefer less walking, then shorter total
            val ranked = transfers.sortedWith(compareBy(
                { it.walkingDistanceM },
                { it.totalDistanceKm }
            ))
            
            val best = ranked.first()
            
            // AI explanation
            val explanation = think("""
                Transfer plan from $origin to $destination:
                1. Take ${best.firstVehicle} to ${best.transferFrom}
                2. Walk ${best.walkingDistanceM.toInt()}m to ${best.transferTo}
                3. Take ${best.secondVehicle} to destination
                
                Explain this transfer briefly.
            """.trimIndent()).getOrElse { 
                "Take ${best.firstVehicle}, transfer at ${best.transferFrom}, then ${best.secondVehicle}"
            }
            
            val result = AgentResult(
                success = true,
                agentName = agentName,
                data = mapOf(
                    "transfers_found" to ranked.size,
                    "best_transfer" to mapOf(
                        "first_bus" to best.firstVehicle,
                        "transfer_from" to best.transferFrom,
                        "walking_m" to best.walkingDistanceM.toInt(),
                        "transfer_to" to best.transferTo,
                        "second_bus" to best.secondVehicle,
                        "total_km" to String.format("%.2f", best.totalDistanceKm),
                        "leg1_stops" to best.leg1Stops,
                        "leg2_stops" to best.leg2Stops
                    ),
                    "alternatives" to ranked.take(3).map { t ->
                        "${t.firstVehicle} → ${t.secondVehicle} (${t.walkingDistanceM.toInt()}m walk)"
                    },
                    "explanation" to explanation
                ),
                message = "Transfer: ${best.firstVehicle} → walk ${best.walkingDistanceM.toInt()}m → ${best.secondVehicle}",
                confidence = 0.8f,
                suggestedNextAgent = "TimeAgent"
            )
            
            storeResult(result)
            result
            
        } catch (e: Exception) {
            android.util.Log.e(agentName, "Error: ${e.message}", e)
            createErrorResult(e.message)
        }
    }
    
    private fun findRoutesWithStop(
        stopName: String,
        allStops: List<RouteStopDTO>
    ): Map<String, List<RouteStopDTO>> {
        val matching = allStops.filter { it.name.contains(stopName, ignoreCase = true) }
        return matching.groupBy { it.vehicleId }
            .mapValues { (vehicleId, _) ->
                allStops.filter { it.vehicleId == vehicleId }.sortedBy { it.stopOrder }
            }
    }
    
    private fun findTransferPoints(
        originVehicleId: String,
        originRoute: List<RouteStopDTO>,
        destVehicleId: String,
        destRoute: List<RouteStopDTO>,
        originName: String,
        destName: String,
        allStops: List<RouteStopDTO>
    ): List<TransferPoint> {
        val results = mutableListOf<TransferPoint>()
        
        val originStop = originRoute.find { it.name.contains(originName, ignoreCase = true) }
            ?: return emptyList()
        val destStop = destRoute.find { it.name.contains(destName, ignoreCase = true) }
            ?: return emptyList()
        
        // Stops after origin on first route
        val afterOrigin = originRoute.filter { it.stopOrder > originStop.stopOrder }
        
        // Stops before destination on second route
        val beforeDest = destRoute.filter { it.stopOrder < destStop.stopOrder }
        
        for (from in afterOrigin) {
            for (to in beforeDest) {
                val walkingM = Haversine.distanceMeters(
                    from.latitude, from.longitude,
                    to.latitude, to.longitude
                )
                
                if (walkingM <= MAX_WALKING_M) {
                    // Calculate leg distances
                    val leg1 = originRoute.filter { 
                        it.stopOrder >= originStop.stopOrder && it.stopOrder <= from.stopOrder 
                    }
                    val leg2 = destRoute.filter { 
                        it.stopOrder >= to.stopOrder && it.stopOrder <= destStop.stopOrder 
                    }
                    
                    val leg1Km = calculateDistance(leg1)
                    val leg2Km = calculateDistance(leg2)
                    
                    results.add(TransferPoint(
                        fromStop = from.name,
                        toStop = to.name,
                        walkingM = walkingM,
                        leg1Stops = leg1.size,
                        leg2Stops = leg2.size,
                        totalKm = leg1Km + (walkingM / 1000) + leg2Km
                    ))
                }
            }
        }
        
        return results
    }
    
    private fun calculateDistance(stops: List<RouteStopDTO>): Double {
        var total = 0.0
        for (i in 0 until stops.size - 1) {
            total += Haversine.distance(
                stops[i].latitude, stops[i].longitude,
                stops[i + 1].latitude, stops[i + 1].longitude
            )
        }
        return total
    }
    
    private fun createErrorResult(error: String?): AgentResult {
        return AgentResult(
            success = false,
            agentName = agentName,
            error = error,
            confidence = 0f
        )
    }
}

data class TransferPoint(
    val fromStop: String,
    val toStop: String,
    val walkingM: Double,
    val leg1Stops: Int,
    val leg2Stops: Int,
    val totalKm: Double
)

data class TransferPlan(
    val firstVehicle: String,
    val secondVehicle: String,
    val transferFrom: String,
    val transferTo: String,
    val walkingDistanceM: Double,
    val leg1Stops: Int,
    val leg2Stops: Int,
    val totalDistanceKm: Double
)
