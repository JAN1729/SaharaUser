package com.sahara.user.agents

import com.sahara.user.data.api.PerplexityClient
import com.sahara.user.data.api.SupabaseApiClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Analytics Agent - The Pattern Analyst
 * 
 * PERSONA: I am the analytics specialist. I analyze usage patterns,
 * route popularity, and provide insights. I help improve recommendations
 * over time based on aggregate data.
 * 
 * MY ROLE:
 * - Analyze user travel patterns
 * - Track route popularity
 * - Identify time-based trends
 * - Provide optimization insights
 * 
 * I COMMUNICATE WITH:
 * - UserContextAgent: Shares pattern data
 * - MainOrchestrator: Provides insights for recommendations
 */
@Singleton
class AnalyticsAgent @Inject constructor(
    perplexityClient: PerplexityClient,
    private val supabaseClient: SupabaseApiClient
) : BaseAgent(perplexityClient) {
    
    override val agentName = "AnalyticsAgent"
    
    override val persona = AgentPersona(
        role = "Pattern Analyst",
        capabilities = listOf(
            "Analyze travel patterns",
            "Track route popularity",
            "Identify time trends",
            "Provide optimization insights",
            "Generate usage statistics"
        ),
        dataAccess = listOf(
            "user_trips for patterns",
            "user_search_history for trends",
            "locations for fleet analysis"
        ),
        canDelegateTo = emptyList()
    )
    
    override val systemPrompt = """
        I am the Analytics Agent - the pattern analysis specialist.
        
        MY PERSONALITY:
        - I look for patterns in data
        - I provide actionable insights
        - I track trends over time
        - I help optimize recommendations
        
        ANALYSIS TYPES:
        1. User Patterns: Frequent routes, time preferences
        2. Route Popularity: Most used routes
        3. Time Analysis: Peak hours, traffic patterns
        4. Fleet Status: Active vs inactive buses
        
        I OUTPUT:
        - Statistical summaries
        - Pattern insights
        - Recommendations for optimization
        - Time-based suggestions
    """.trimIndent()
    
    override suspend fun execute(input: Map<String, Any>): AgentResult {
        val analysisType = input["analysis_type"]?.toString() ?: "time_analysis"
        val userId = input["user_id"]?.toString() ?: ""
        
        android.util.Log.d(agentName, "Running analysis: $analysisType")
        
        return when (analysisType) {
            "user_patterns" -> analyzeUserPatterns(userId)
            "route_popularity" -> analyzeRoutePopularity()
            "time_analysis" -> analyzeTimePatterns()
            "fleet_status" -> analyzeFleetStatus()
            else -> analyzeTimePatterns()
        }
    }
    
    private suspend fun analyzeUserPatterns(userId: String): AgentResult {
        if (userId.isBlank()) {
            return AgentResult(
                success = false,
                agentName = agentName,
                error = "User ID required for pattern analysis",
                confidence = 0f
            )
        }
        
        val tripsResult = supabaseClient.getUserTrips(userId)
        val trips = tripsResult.getOrElse { emptyList() }
        
        val searchResult = supabaseClient.getSearchHistory(userId, 50)
        val searches = searchResult.getOrElse { emptyList() }
        
        // Analyze origins and destinations
        val origins = trips.groupingBy { it.originName }.eachCount()
        val destinations = trips.groupingBy { it.destinationName }.eachCount()
        
        val topOrigins = origins.entries.sortedByDescending { it.value }.take(3)
        val topDestinations = destinations.entries.sortedByDescending { it.value }.take(3)
        
        // Generate insights
        val insights = think("""
            User travel analysis:
            - Total trips: ${trips.size}
            - Total searches: ${searches.size}
            - Top origins: ${topOrigins.map { "${it.key}(${it.value})" }}
            - Top destinations: ${topDestinations.map { "${it.key}(${it.value})" }}
            
            What patterns do you see? Provide 2-3 insights.
        """.trimIndent()).getOrElse { "Analysis complete" }
        
        return AgentResult(
            success = true,
            agentName = agentName,
            data = mapOf(
                "total_trips" to trips.size,
                "total_searches" to searches.size,
                "top_origins" to topOrigins.map { it.key },
                "top_destinations" to topDestinations.map { it.key },
                "insights" to insights
            ),
            message = "Analyzed ${trips.size} trips, ${searches.size} searches",
            confidence = 0.85f
        )
    }
    
    private suspend fun analyzeRoutePopularity(): AgentResult {
        val vehiclesResult = supabaseClient.getActiveVehicles()
        val vehicles = vehiclesResult.getOrElse { emptyList() }
        
        val liveResult = supabaseClient.getLiveBusLocations()
        val live = liveResult.getOrElse { emptyList() }
        
        val activeIds = live.map { it.busId }.toSet()
        val activeVehicles = vehicles.filter { it.id in activeIds }
        
        return AgentResult(
            success = true,
            agentName = agentName,
            data = mapOf(
                "total_vehicles" to vehicles.size,
                "active_vehicles" to activeVehicles.size,
                "active_percentage" to if (vehicles.isNotEmpty()) 
                    (activeVehicles.size * 100 / vehicles.size) else 0,
                "active_vehicle_names" to activeVehicles.map { it.name }
            ),
            message = "${activeVehicles.size} of ${vehicles.size} vehicles active",
            confidence = 0.9f
        )
    }
    
    private fun analyzeTimePatterns(): AgentResult {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val dayOfWeek = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
        
        val timeSlot = when (hour) {
            in 6..9 -> "morning_rush"
            in 10..16 -> "midday"
            in 17..20 -> "evening_rush"
            in 21..23 -> "night"
            else -> "late_night"
        }
        
        val isWeekend = dayOfWeek == java.util.Calendar.SATURDAY || 
                        dayOfWeek == java.util.Calendar.SUNDAY
        
        val trafficFactor = when {
            isWeekend -> 0.8
            timeSlot == "morning_rush" || timeSlot == "evening_rush" -> 1.5
            timeSlot == "midday" -> 1.0
            else -> 0.7
        }
        
        val recommendation = when {
            isWeekend -> "Weekend - lighter traffic expected"
            timeSlot == "morning_rush" -> "Morning rush - expect 50% longer travel times"
            timeSlot == "evening_rush" -> "Evening rush - consider leaving 15 mins earlier"
            timeSlot == "midday" -> "Good time to travel - normal conditions"
            else -> "Light traffic - fast travel expected"
        }
        
        return AgentResult(
            success = true,
            agentName = agentName,
            data = mapOf(
                "current_hour" to hour,
                "time_slot" to timeSlot,
                "is_weekend" to isWeekend,
                "traffic_factor" to trafficFactor,
                "recommendation" to recommendation
            ),
            message = recommendation,
            confidence = 0.9f
        )
    }
    
    private suspend fun analyzeFleetStatus(): AgentResult {
        val vehiclesResult = supabaseClient.getActiveVehicles()
        val vehicles = vehiclesResult.getOrElse { emptyList() }
        
        val liveResult = supabaseClient.getLiveBusLocations()
        val live = liveResult.getOrElse { emptyList() }
        
        // Check freshness
        val now = System.currentTimeMillis()
        val freshThreshold = 2 * 60 * 1000L
        
        val freshBuses = live.filter { location ->
            (now - location.timestamp) < freshThreshold
        }
        
        return AgentResult(
            success = true,
            agentName = agentName,
            data = mapOf(
                "total_fleet" to vehicles.size,
                "broadcasting" to live.size,
                "fresh_data" to freshBuses.size,
                "stale_data" to (live.size - freshBuses.size),
                "offline" to (vehicles.size - live.size)
            ),
            message = "Fleet: ${freshBuses.size} live, ${vehicles.size - live.size} offline",
            confidence = 0.95f
        )
    }
}
