package com.sahara.user.agents

import com.sahara.user.algorithms.Haversine
import com.sahara.user.data.api.PerplexityClient
import com.sahara.user.data.api.SupabaseApiClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Time Agent - The ETA Calculator
 * 
 * PERSONA: I am the time estimation specialist. I calculate realistic
 * ETAs considering distance, stops, and traffic conditions. I don't
 * find routes - I calculate how long they take.
 * 
 * MY ROLE:
 * - Calculate travel time from distance
 * - Add stop dwell times
 * - Apply rush hour factors
 * - Estimate bus arrival at boarding point
 * - Provide min-max time ranges
 * 
 * I DEPEND ON:
 * - PathAgent: For distance and stop count
 * - BusTrackerAgent: For bus current position
 * 
 * I COMMUNICATE WITH:
 * - MainOrchestrator: Final ETA for user response
 */
@Singleton
class TimeAgent @Inject constructor(
    perplexityClient: PerplexityClient,
    private val supabaseClient: SupabaseApiClient
) : BaseAgent(perplexityClient) {
    
    override val agentName = "TimeAgent"
    
    override val persona = AgentPersona(
        role = "ETA Calculator",
        capabilities = listOf(
            "Calculate travel time from distance",
            "Factor in stop dwell times",
            "Apply rush hour adjustments",
            "Estimate bus-to-passenger arrival",
            "Provide time ranges (min-max)"
        ),
        dataAccess = listOf(
            "Distance from PathAgent",
            "Live position from BusTrackerAgent"
        ),
        canDelegateTo = listOf("BusTrackerAgent")
    )
    
    override val systemPrompt = """
        I am the Time Agent - the ETA calculation specialist.
        
        MY PERSONALITY:
        - I am realistic and practical
        - I provide ranges, not just estimates
        - I consider real-world factors
        - I adjust for traffic conditions
        
        MY CALCULATION METHOD:
        1. Get distance from PathAgent
        2. Base speed: 18 km/h (average city bus)
        3. Stop dwell time: 1.5 min per stop
        4. Rush hour factor: 1.5x during 7-10 AM, 5-8 PM
        5. Bus arrival: Distance from current position to boarding
        
        MY OUTPUT:
        - Base ETA in minutes
        - Adjusted ETA (with traffic)
        - Range: min-max
        - Bus-to-boarding ETA (if live)
        
        I DO NOT:
        - Find routes (PathAgent does that)
        - Track bus positions (BusTrackerAgent does that)
    """.trimIndent()
    
    companion object {
        private const val AVERAGE_SPEED_KMH = 18.0
        private const val STOP_DWELL_MINUTES = 1.5
        private const val RUSH_HOUR_FACTOR = 1.5
    }
    
    override suspend fun execute(input: Map<String, Any>): AgentResult {
        android.util.Log.d(agentName, "Calculating ETA...")
        
        return try {
            // Get path data from PathAgent or input
            val pathResult = getResultFrom("PathAgent")
            val (distanceKm, stopsCount, busStopsAway) = if (pathResult?.success == true) {
                android.util.Log.d(agentName, "Using PathAgent data")
                extractFromPathAgent(pathResult)
            } else {
                android.util.Log.d(agentName, "Using input data")
                extractFromInput(input)
            }
            
            // Calculate base travel time
            val travelTimeMin = if (distanceKm > 0) {
                (distanceKm / AVERAGE_SPEED_KMH) * 60
            } else {
                0.0
            }
            val dwellTimeMin = stopsCount * STOP_DWELL_MINUTES
            val baseEtaMin = travelTimeMin + dwellTimeMin
            
            // Check rush hour
            val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val isRushHour = currentHour in 7..10 || currentHour in 17..20
            val rushFactor = if (isRushHour) RUSH_HOUR_FACTOR else 1.0
            val adjustedEta = baseEtaMin * rushFactor
            
            // Calculate bus arrival at boarding (if we know bus position)
            val busArrivalMin = if (busStopsAway > 0) {
                val avgDistPerStop = if (stopsCount > 1) distanceKm / (stopsCount - 1) else 0.5
                val distToBoarding = busStopsAway * avgDistPerStop
                val travelToBoarding = (distToBoarding / AVERAGE_SPEED_KMH) * 60
                val dwellToBoarding = busStopsAway * STOP_DWELL_MINUTES
                (travelToBoarding + dwellToBoarding) * rushFactor
            } else {
                null
            }
            
            // Calculate range
            val minEta = (adjustedEta * 0.85).toInt()
            val maxEta = (adjustedEta * 1.20).toInt()
            
            // AI analysis for context
            val timeContext = when {
                isRushHour -> "Rush hour - expect higher traffic"
                currentHour in 11..16 -> "Mid-day - normal traffic conditions"
                currentHour >= 21 || currentHour <= 5 -> "Night - light traffic"
                else -> "Moderate traffic expected"
            }
            
            val result = AgentResult(
                success = true,
                agentName = agentName,
                data = mapOf(
                    "distance_km" to String.format("%.2f", distanceKm),
                    "stops_count" to stopsCount,
                    "travel_time_min" to travelTimeMin.toInt(),
                    "dwell_time_min" to dwellTimeMin.toInt(),
                    "base_eta_min" to baseEtaMin.toInt(),
                    "is_rush_hour" to isRushHour,
                    "rush_factor" to rushFactor,
                    "adjusted_eta_min" to adjustedEta.toInt(),
                    "eta_range_min" to minEta,
                    "eta_range_max" to maxEta,
                    "bus_arrival_min" to (busArrivalMin?.toInt() ?: -1),
                    "time_context" to timeContext,
                    "current_hour" to currentHour
                ),
                message = "ETA: ${adjustedEta.toInt()} min ($minEta-$maxEta range). $timeContext",
                confidence = 0.85f
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
    
    private fun extractFromPathAgent(pathResult: AgentResult): Triple<Double, Int, Int> {
        val bestPath = pathResult.data["best_path"] as? Map<*, *>
        val distanceKm = (bestPath?.get("distance_km") as? Number)?.toDouble() ?: 0.0
        val stopsCount = (bestPath?.get("stops_count") as? Number)?.toInt() ?: 0
        val busStopsAway = (bestPath?.get("bus_stops_away") as? Number)?.toInt() ?: -1
        return Triple(distanceKm, stopsCount, busStopsAway)
    }
    
    private fun extractFromInput(input: Map<String, Any>): Triple<Double, Int, Int> {
        val distanceKm = (input["distance_km"] as? Number)?.toDouble() 
            ?: (input["distance_km"] as? String)?.toDoubleOrNull() ?: 0.0
        val stopsCount = (input["stops_count"] as? Number)?.toInt() ?: 0
        val busStopsAway = (input["bus_stops_away"] as? Number)?.toInt() ?: -1
        return Triple(distanceKm, stopsCount, busStopsAway)
    }
}
