package com.sahara.user.agents

import com.sahara.user.data.api.PerplexityClient
import com.sahara.user.data.api.SupabaseApiClient
import com.sahara.user.service.LocationService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User Context Agent - The Personalization Specialist
 * 
 * PERSONA: I understand user preferences, travel history, and current location.
 * I help personalize recommendations based on past behavior and GPS.
 * 
 * MY ROLE:
 * - Provide user's current GPS location
 * - Fetch user preferences (home, work, preferred routes)
 * - Retrieve search history for personalization
 * - Identify frequent routes
 */
@Singleton
class UserContextAgent @Inject constructor(
    perplexityClient: PerplexityClient,
    private val supabaseClient: SupabaseApiClient,
    private val locationService: LocationService
) : BaseAgent(perplexityClient) {
    
    override val agentName = "UserContextAgent"
    
    override val persona = AgentPersona(
        role = "Personalization Specialist",
        capabilities = listOf(
            "Get user's current GPS location",
            "Fetch user preferences",
            "Retrieve search history",
            "Identify frequent routes",
            "Personalize recommendations"
        ),
        dataAccess = listOf("users", "user_trips", "user_search_history", "GPS"),
        canDelegateTo = listOf("AnalyticsAgent")
    )
    
    override val systemPrompt = """
        I am the User Context Agent - I provide user-specific context.
        I include real GPS location when available.
        I respect user privacy and never share sensitive data.
    """.trimIndent()
    
    override suspend fun execute(input: Map<String, Any>): AgentResult {
        val userId = input["user_id"]?.toString() ?: ""
        val origin = input["origin"]?.toString() ?: ""
        val destination = input["destination"]?.toString() ?: ""
        
        android.util.Log.d(agentName, "Getting context for user: $userId")
        
        if (userId.isBlank()) {
            return AgentResult(
                success = false,
                agentName = agentName,
                error = "No user ID provided",
                confidence = 0f
            )
        }
        
        return try {
            // Get user's current location
            val location = locationService.getCurrentLocation()
            
            // Get user profile
            val userResult = supabaseClient.getUser(userId)
            val user = userResult.getOrNull()
            
            // Get search history
            val historyResult = supabaseClient.getSearchHistory(userId, 20)
            val history = historyResult.getOrElse { emptyList() }
            
            // Get trip history
            val tripsResult = supabaseClient.getUserTrips(userId)
            val trips = tripsResult.getOrElse { emptyList() }
            
            // Analyze patterns
            val patternAnalysis = analyzePatterns(origin, destination, history, trips)
            
            // Check saved locations
            val savedLocationMatch = checkSavedLocations(origin, destination, user)
            
            // Build result with all context including location
            val resultData = mutableMapOf<String, Any>(
                // User profile
                "user_name" to (user?.firstName ?: "User"),
                "preferred_route_type" to (user?.preferredRouteType ?: "fastest"),
                "preferred_language" to (user?.preferredLanguage ?: "en"),
                
                // Saved locations
                "has_home" to (user?.homeLocation != null),
                "has_work" to (user?.workLocation != null),
                "saved_location_match" to savedLocationMatch,
                
                // History
                "total_trips" to trips.size,
                "total_searches" to history.size,
                
                // Pattern analysis
                "is_frequent_route" to (patternAnalysis["is_frequent_route"] ?: false),
                "similar_searches" to (patternAnalysis["similar_searches"] ?: 0),
                "pattern_insight" to (patternAnalysis["insight"] ?: ""),
                
                // Location permissions
                "has_location_permission" to locationService.hasLocationPermission()
            )
            
            // Add GPS if available
            location?.let {
                resultData["user_lat"] = it.latitude
                resultData["user_lng"] = it.longitude
                resultData["location_accuracy"] = it.accuracy
            }
            
            val result = AgentResult(
                success = true,
                agentName = agentName,
                data = resultData,
                message = buildMessage(user, location, trips.size),
                confidence = calculateConfidence(user, location)
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
    
    private fun buildMessage(
        user: com.sahara.user.data.model.User?,
        location: com.sahara.user.service.UserLocation?,
        tripCount: Int
    ): String {
        val parts = mutableListOf<String>()
        
        user?.firstName?.let { parts.add("User: $it") }
        if (location != null) parts.add("GPS: ✓")
        if (tripCount > 0) parts.add("$tripCount past trips")
        
        return if (parts.isEmpty()) "Anonymous user" else parts.joinToString(" • ")
    }
    
    private fun calculateConfidence(
        user: com.sahara.user.data.model.User?,
        location: com.sahara.user.service.UserLocation?
    ): Float {
        return when {
            user != null && location != null -> 0.95f
            user != null -> 0.8f
            location != null -> 0.7f
            else -> 0.5f
        }
    }
    
    private fun analyzePatterns(
        origin: String,
        destination: String,
        history: List<com.sahara.user.data.model.UserSearchHistory>,
        trips: List<com.sahara.user.data.model.UserTrip>
    ): Map<String, Any?> {
        val similarSearches = history.count { search ->
            (search.originName?.contains(origin, ignoreCase = true) == true ||
             search.destinationName?.contains(destination, ignoreCase = true) == true)
        }
        
        val similarTrips = trips.count { trip ->
            trip.originName.contains(origin, ignoreCase = true) ||
            trip.destinationName.contains(destination, ignoreCase = true)
        }
        
        val isFrequent = similarSearches >= 3 || similarTrips >= 2
        
        val insight = when {
            isFrequent -> "Frequent route"
            similarSearches > 0 -> "Searched before"
            else -> "New route"
        }
        
        return mapOf(
            "is_frequent_route" to isFrequent,
            "similar_searches" to similarSearches,
            "similar_trips" to similarTrips,
            "insight" to insight
        )
    }
    
    private fun checkSavedLocations(
        origin: String,
        destination: String,
        user: com.sahara.user.data.model.User?
    ): Map<String, Boolean> {
        if (user == null) return emptyMap()
        
        // Extract "name" field from JSONB objects safely
        val homeName = user.homeLocation?.get("name")?.let { 
            try { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } catch (e: Exception) { null }
        } ?: ""
        val workName = user.workLocation?.get("name")?.let { 
            try { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } catch (e: Exception) { null }
        } ?: ""
        
        return mapOf(
            "origin_is_home" to (origin.contains("home", ignoreCase = true) || 
                               homeName.contains(origin, ignoreCase = true)),
            "origin_is_work" to (origin.contains("work", ignoreCase = true) || 
                               workName.contains(origin, ignoreCase = true)),
            "dest_is_home" to (destination.contains("home", ignoreCase = true) || 
                             homeName.contains(destination, ignoreCase = true)),
            "dest_is_work" to (destination.contains("work", ignoreCase = true) || 
                             workName.contains(destination, ignoreCase = true))
        )
    }
}
