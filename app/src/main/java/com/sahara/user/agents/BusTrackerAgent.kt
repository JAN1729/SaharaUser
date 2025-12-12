package com.sahara.user.agents

import com.sahara.user.algorithms.Haversine
import com.sahara.user.data.api.PerplexityClient
import com.sahara.user.data.api.SupabaseApiClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bus Tracker Agent - The Live Position Specialist
 * 
 * PERSONA: I am the real-time tracking specialist. I monitor live bus
 * positions, detect movement status, and identify stale data. I help
 * other agents know exactly where buses are right now.
 * 
 * MY ROLE:
 * - Fetch live GPS positions from Supabase
 * - Detect if bus is moving or stopped
 * - Identify stale/old position data
 * - Match GPS to nearest stop on route
 * - Provide position context to other agents
 * 
 * I COMMUNICATE WITH:
 * - PathAgent: Provides current bus position for route calculation
 * - TimeAgent: Provides position for arrival estimation
 * - MainOrchestrator: For "where is my bus" queries
 */
@Singleton
class BusTrackerAgent @Inject constructor(
    perplexityClient: PerplexityClient,
    private val supabaseClient: SupabaseApiClient
) : BaseAgent(perplexityClient) {
    
    override val agentName = "BusTrackerAgent"
    
    override val persona = AgentPersona(
        role = "Live Position Tracker",
        capabilities = listOf(
            "Fetch real-time bus GPS",
            "Detect movement status",
            "Identify stale data",
            "Match GPS to route stops",
            "Calculate distance to stops"
        ),
        dataAccess = listOf(
            "locations table (real-time)",
            "route_stops for matching"
        ),
        canDelegateTo = emptyList()  // BusTracker is a data provider
    )
    
    override val systemPrompt = """
        I am the Bus Tracker Agent - the real-time position specialist.
        
        MY PERSONALITY:
        - I am vigilant and real-time focused
        - I detect issues (stale data, stopped buses)
        - I provide current position context
        - I flag uncertainty in old data
        
        MY ANALYSIS:
        1. Fetch live GPS from locations table
        2. Check timestamp - is data fresh (< 2 min)?
        3. Check speed - is bus moving or stopped?
        4. Match position to nearest stop on route
        5. Calculate distance to next key stop
        
        FRESHNESS RULES:
        - < 2 min: LIVE (green)
        - 2-10 min: RECENT (yellow)
        - > 10 min: STALE (red) - may be inaccurate
        
        I OUTPUT:
        - Current position (lat, lng)
        - Nearest stop on route
        - Movement status
        - Data freshness
        - Speed if available
    """.trimIndent()
    
    companion object {
        private const val FRESH_THRESHOLD_MS = 2 * 60 * 1000L    // 2 minutes
        private const val RECENT_THRESHOLD_MS = 10 * 60 * 1000L  // 10 minutes
    }
    
    override suspend fun execute(input: Map<String, Any>): AgentResult {
        val busId = input["bus_id"]?.toString()
        val vehicleId = input["vehicle_id"]?.toString()
        val targetId = busId ?: vehicleId
        
        android.util.Log.d(agentName, "Tracking bus: $targetId")
        
        return try {
            // Fetch live locations
            val liveResult = supabaseClient.getLiveBusLocations()
            val liveLocations = liveResult.getOrElse { 
                return AgentResult(
                    success = false,
                    agentName = agentName,
                    error = "Failed to fetch live locations",
                    confidence = 0f
                )
            }
            
            if (liveLocations.isEmpty()) {
                return AgentResult(
                    success = true,
                    agentName = agentName,
                    data = mapOf("live_buses" to 0, "message" to "No buses currently live"),
                    message = "No buses are currently sharing location",
                    confidence = 1.0f
                )
            }
            
            // If specific bus requested
            if (!targetId.isNullOrBlank()) {
                val busLocation = liveLocations.find { it.busId == targetId }
                if (busLocation == null) {
                    return AgentResult(
                        success = false,
                        agentName = agentName,
                        data = mapOf(
                            "requested_bus" to targetId,
                            "live_buses" to liveLocations.map { it.busId }
                        ),
                        error = "Bus $targetId is not currently sharing location",
                        confidence = 0.8f
                    )
                }
                
                return analyzeSpecificBus(busLocation, input)
            }
            
            // Return all live positions
            val busAnalysis = liveLocations.map { loc ->
                val freshness = getFreshness(loc.timestamp)
                mapOf<String, Any>(
                    "bus_id" to loc.busId,
                    "lat" to loc.latitude,
                    "lng" to loc.longitude,
                    "speed" to (loc.speed?.toDouble() ?: 0.0),
                    "timestamp" to loc.timestamp,
                    "freshness" to freshness.name,
                    "is_moving" to ((loc.speed?.toDouble() ?: 0.0) > 2.0)
                )
            }
            
            val result = AgentResult(
                success = true,
                agentName = agentName,
                data = mapOf(
                    "live_buses" to liveLocations.size,
                    "buses" to busAnalysis,
                    "fresh_count" to busAnalysis.count { it["freshness"] == "FRESH" },
                    "stale_count" to busAnalysis.count { it["freshness"] == "STALE" }
                ),
                message = "${liveLocations.size} buses currently live",
                confidence = 0.95f
            )
            
            storeResult(result)
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
    
    private suspend fun analyzeSpecificBus(
        busLocation: com.sahara.user.data.model.LiveBusLocation,
        @Suppress("UNUSED_PARAMETER") input: Map<String, Any>
    ): AgentResult {
        val freshness = getFreshness(busLocation.timestamp)
        val isMoving = (busLocation.speed?.toDouble() ?: 0.0) > 2.0
        
        // Try to match to route stop
        var nearestStop: String? = null
        var distanceToStop: Double? = null
        
        val stopsResult = supabaseClient.getRouteStops(busLocation.busId)
        stopsResult.onSuccess { stops ->
            if (stops.isNotEmpty()) {
                val nearest = stops.minByOrNull { stop ->
                    Haversine.distance(
                        busLocation.latitude, busLocation.longitude,
                        stop.latitude, stop.longitude
                    )
                }
                nearest?.let {
                    nearestStop = it.name
                    distanceToStop = Haversine.distanceMeters(
                        busLocation.latitude, busLocation.longitude,
                        it.latitude, it.longitude
                    )
                }
            }
        }
        
        // Generate status message
        val statusMessage = buildString {
            append("Bus ${busLocation.busId} is ")
            if (isMoving) append("moving") else append("stopped")
            nearestStop?.let { append(" near $it") }
            distanceToStop?.let { 
                if (it < 100) append(" (at stop)")
                else append(" (${it.toInt()}m away)")
            }
            append(". Data is ${freshness.name.lowercase()}.")
        }
        
        // AI interpretation
        val interpretation = think("""
            Bus position analysis:
            - Bus: ${busLocation.busId}
            - Position: (${busLocation.latitude}, ${busLocation.longitude})
            - Speed: ${busLocation.speed ?: 0} km/h
            - Moving: $isMoving
            - Nearest stop: $nearestStop (${distanceToStop?.toInt()}m)
            - Data freshness: ${freshness.name}
            
            Provide brief status update for user.
        """.trimIndent()).getOrElse { statusMessage }
        
        return AgentResult(
            success = true,
            agentName = agentName,
            data = mapOf(
                "bus_id" to busLocation.busId,
                "lat" to busLocation.latitude,
                "lng" to busLocation.longitude,
                "speed" to (busLocation.speed ?: 0.0),
                "heading" to (busLocation.heading ?: 0.0),
                "is_moving" to isMoving,
                "freshness" to freshness.name,
                "nearest_stop" to (nearestStop ?: "Unknown"),
                "distance_to_stop_m" to (distanceToStop?.toInt() ?: -1),
                "interpretation" to interpretation
            ),
            message = statusMessage,
            confidence = when (freshness) {
                Freshness.FRESH -> 0.95f
                Freshness.RECENT -> 0.7f
                Freshness.STALE -> 0.4f
            }
        )
    }
    
    private fun getFreshness(timestamp: Long): Freshness {
        val age = System.currentTimeMillis() - timestamp
        return when {
            age < FRESH_THRESHOLD_MS -> Freshness.FRESH
            age < RECENT_THRESHOLD_MS -> Freshness.RECENT
            else -> Freshness.STALE
        }
    }
    
    enum class Freshness { FRESH, RECENT, STALE }
}
