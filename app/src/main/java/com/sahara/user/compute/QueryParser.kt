package com.sahara.user.compute

/**
 * Query Parser - Converts Natural Language to Mathematical Equations
 * 
 * Takes user input like "I need to go from MG Road to Electronic City"
 * and converts it to structured query parameters that can be processed
 * by algorithms.
 * 
 * OUTPUT: StructuredQuery containing:
 * - Intent (what operation to perform)
 * - Parameters (origin, destination, bus_id, etc.)
 * - Required algorithms (Haversine, Dijkstra, ETA, etc.)
 * - Required data (vehicles, stops, locations)
 */
object QueryParser {
    
    /**
     * Intent types mapped to algorithmic operations
     */
    enum class QueryIntent {
        FIND_ROUTE,           // origin → destination path finding
        TRACK_BUS,            // Get bus current position
        CALCULATE_ETA,        // When will bus arrive?
        FIND_NEARBY,          // Buses/stops near location
        FIND_BEST_OPTION,     // Compare multiple routes
        UNKNOWN
    }
    
    /**
     * Algorithms needed for each intent
     */
    val INTENT_ALGORITHMS = mapOf(
        QueryIntent.FIND_ROUTE to listOf("RouteMatcher", "Dijkstra", "Haversine", "ETACalculator"),
        QueryIntent.TRACK_BUS to listOf("Haversine"),
        QueryIntent.CALCULATE_ETA to listOf("ETACalculator", "Haversine"),
        QueryIntent.FIND_NEARBY to listOf("Haversine"),
        QueryIntent.FIND_BEST_OPTION to listOf("RouteMatcher", "Dijkstra", "ETACalculator")
    )
    
    /**
     * Structured query output
     */
    data class StructuredQuery(
        val intent: QueryIntent,
        val confidence: Double,
        val parameters: QueryParameters,
        val requiredAlgorithms: List<String>,
        val requiredData: List<String>,
        val requiredAgents: List<String>
    )
    
    data class QueryParameters(
        val origin: String? = null,
        val destination: String? = null,
        val busId: String? = null,
        val stopName: String? = null,
        val userLat: Double? = null,
        val userLng: Double? = null,
        val timeConstraint: String? = null,  // "now", "in 10 min", etc.
        val additionalFilters: Map<String, Any> = emptyMap()
    )
    
    /**
     * Parse natural language query into structured form
     */
    fun parse(query: String, userLat: Double? = null, userLng: Double? = null): StructuredQuery {
        val normalized = query.lowercase().trim()
        
        // Detect intent
        val (intent, confidence) = detectIntent(normalized)
        
        // Extract parameters based on intent
        val parameters = when (intent) {
            QueryIntent.FIND_ROUTE -> extractRouteParams(normalized, userLat, userLng)
            QueryIntent.TRACK_BUS -> extractBusParams(normalized)
            QueryIntent.CALCULATE_ETA -> extractETAParams(normalized)
            QueryIntent.FIND_NEARBY -> extractNearbyParams(normalized, userLat, userLng)
            QueryIntent.FIND_BEST_OPTION -> extractRouteParams(normalized, userLat, userLng)
            QueryIntent.UNKNOWN -> QueryParameters()
        }
        
        // Determine required algorithms
        val algorithms = INTENT_ALGORITHMS[intent] ?: emptyList()
        
        // Determine required data
        val requiredData = when (intent) {
            QueryIntent.FIND_ROUTE -> listOf("vehicles", "route_stops", "locations")
            QueryIntent.TRACK_BUS -> listOf("vehicles", "locations")
            QueryIntent.CALCULATE_ETA -> listOf("vehicles", "route_stops", "locations")
            QueryIntent.FIND_NEARBY -> listOf("route_stops", "locations")
            QueryIntent.FIND_BEST_OPTION -> listOf("vehicles", "route_stops", "locations")
            QueryIntent.UNKNOWN -> emptyList()
        }
        
        // Determine required agents
        val requiredAgents = when (intent) {
            QueryIntent.FIND_ROUTE -> listOf("SQLAgent", "PathAgent", "TimeAgent", "BusTrackerAgent")
            QueryIntent.TRACK_BUS -> listOf("SQLAgent", "BusTrackerAgent")
            QueryIntent.CALCULATE_ETA -> listOf("SQLAgent", "TimeAgent", "BusTrackerAgent")
            QueryIntent.FIND_NEARBY -> listOf("SQLAgent")
            QueryIntent.FIND_BEST_OPTION -> listOf("SQLAgent", "PathAgent", "TimeAgent", "BusTrackerAgent", "AnalyticsAgent")
            QueryIntent.UNKNOWN -> listOf("CommunicationAgent")
        }
        
        return StructuredQuery(
            intent = intent,
            confidence = confidence,
            parameters = parameters,
            requiredAlgorithms = algorithms,
            requiredData = requiredData,
            requiredAgents = requiredAgents
        )
    }
    
    /**
     * Detect query intent from keywords
     */
    private fun detectIntent(query: String): Pair<QueryIntent, Double> {
        // Route patterns
        val routePatterns = listOf(
            "from .* to", "go to", "get to", "reach", "route to",
            "travel to", "need to go", "want to go", "take me to", "how to go"
        )
        if (routePatterns.any { query.contains(Regex(it)) }) {
            return QueryIntent.FIND_ROUTE to 0.9
        }
        
        // Best route patterns
        val bestPatterns = listOf("best route", "fastest", "shortest", "quickest", "best way")
        if (bestPatterns.any { query.contains(it) }) {
            return QueryIntent.FIND_BEST_OPTION to 0.85
        }
        
        // Bus tracking patterns
        val trackPatterns = listOf("where is", "location of", "track", "find bus", "locate")
        if (trackPatterns.any { query.contains(it) }) {
            return QueryIntent.TRACK_BUS to 0.85
        }
        
        // ETA patterns
        val etaPatterns = listOf("when", "how long", "arrive", "eta", "time to", "reach by", "minutes")
        if (etaPatterns.any { query.contains(it) }) {
            return QueryIntent.CALCULATE_ETA to 0.85
        }
        
        // Nearby patterns
        val nearbyPatterns = listOf("near me", "nearby", "close to", "around me", "closest")
        if (nearbyPatterns.any { query.contains(it) }) {
            return QueryIntent.FIND_NEARBY to 0.9
        }
        
        // If has "from" and "to" anywhere
        if (query.contains("from") && query.contains("to")) {
            return QueryIntent.FIND_ROUTE to 0.7
        }
        
        return QueryIntent.UNKNOWN to 0.3
    }
    
    /**
     * Extract origin and destination from route query
     */
    private fun extractRouteParams(
        query: String,
        userLat: Double?,
        userLng: Double?
    ): QueryParameters {
        var origin: String? = null
        var destination: String? = null
        
        // Pattern: "from X to Y"
        val fromToRegex = Regex("from\\s+(.+?)\\s+to\\s+(.+?)(?:\\?|\$|\\s*how|\\s*when)")
        fromToRegex.find(query)?.let { match ->
            origin = match.groupValues[1].trim()
            destination = match.groupValues[2].trim().removeSuffix("?")
        }
        
        // Pattern: "to Y from X"
        if (origin == null || destination == null) {
            val toFromRegex = Regex("to\\s+(.+?)\\s+from\\s+(.+?)(?:\\?|\$)")
            toFromRegex.find(query)?.let { match ->
                destination = match.groupValues[1].trim()
                origin = match.groupValues[2].trim()
            }
        }
        
        // Pattern: "go to Y" (origin = current location)
        if (destination == null && query.contains("go to")) {
            val goToRegex = Regex("go\\s+to\\s+(.+?)(?:\\?|\$)")
            goToRegex.find(query)?.let { match ->
                destination = match.groupValues[1].trim()
                origin = "current location"
            }
        }
        
        // Clean up extracted values
        origin = origin?.replace(Regex("[^a-zA-Z0-9\\s]"), "")?.trim()
        destination = destination?.replace(Regex("[^a-zA-Z0-9\\s]"), "")?.trim()
        
        return QueryParameters(
            origin = origin,
            destination = destination,
            userLat = userLat,
            userLng = userLng
        )
    }
    
    /**
     * Extract bus ID from tracking query
     */
    private fun extractBusParams(query: String): QueryParameters {
        var busId: String? = null
        
        // Pattern: "where is [bus name/number]"
        val whereIsRegex = Regex("where\\s+is\\s+(?:the\\s+)?(?:bus\\s+)?(.+?)(?:\\?|\$)")
        whereIsRegex.find(query)?.let { match ->
            busId = match.groupValues[1].trim()
        }
        
        // Pattern: "bus [number]" 
        if (busId == null) {
            val busNumRegex = Regex("bus\\s+([a-zA-Z0-9-]+)")
            busNumRegex.find(query)?.let { match ->
                busId = match.groupValues[1].trim()
            }
        }
        
        // Pattern: vehicle plate "KA-XX-XXXX"
        if (busId == null) {
            val plateRegex = Regex("([A-Z]{2}[-\\s]?\\d{2}[-\\s]?[A-Z]{1,2}[-\\s]?\\d{4})")
            plateRegex.find(query.uppercase())?.let { match ->
                busId = match.groupValues[1]
            }
        }
        
        return QueryParameters(busId = busId)
    }
    
    /**
     * Extract ETA parameters
     */
    private fun extractETAParams(query: String): QueryParameters {
        var stopName: String? = null
        var busId: String? = null
        
        // Pattern: "when will bus reach [stop]"
        val whenReachRegex = Regex("when\\s+.*(?:reach|arrive|get to)\\s+(.+?)(?:\\?|\$)")
        whenReachRegex.find(query)?.let { match ->
            stopName = match.groupValues[1].trim()
        }
        
        // Pattern: "bus to [stop]"
        if (stopName == null) {
            val busToRegex = Regex("bus\\s+to\\s+(.+?)(?:\\?|\$)")
            busToRegex.find(query)?.let { match ->
                stopName = match.groupValues[1].trim()
            }
        }
        
        // Extract bus name if mentioned
        val busMatch = Regex("([a-zA-Z]+\\s+line|bus\\s+\\d+)").find(query)
        busId = busMatch?.value
        
        return QueryParameters(
            stopName = stopName,
            busId = busId
        )
    }
    
    /**
     * Extract nearby search parameters
     */
    private fun extractNearbyParams(
        query: String,
        userLat: Double?,
        userLng: Double?
    ): QueryParameters {
        return QueryParameters(
            userLat = userLat,
            userLng = userLng,
            additionalFilters = mapOf("search_type" to "nearby")
        )
    }
}
