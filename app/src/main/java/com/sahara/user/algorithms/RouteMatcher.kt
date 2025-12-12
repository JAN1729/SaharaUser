package com.sahara.user.algorithms

/**
 * Route Matcher - Matches user query to available routes
 * 
 * EQUATIONS:
 * 1. Stop Matching Score = string_similarity(query, stop_name)
 * 2. Route Coverage = stops_matched / total_query_stops
 * 3. Direction Check = order(origin) < order(destination)
 */
object RouteMatcher {
    
    /**
     * Match result with confidence score
     */
    data class MatchResult(
        val stopName: String,
        val matchScore: Double,      // 0.0 - 1.0
        val matchType: MatchType,
        val originalQuery: String
    )
    
    enum class MatchType {
        EXACT,           // "MG Road" = "MG Road"
        CONTAINS,        // "MG" ⊂ "MG Road"
        FUZZY,           // "MG Rd" ≈ "MG Road"
        NEARBY,          // Within 500m of location
        NO_MATCH
    }
    
    /**
     * Route match with all stops and direction validation
     */
    data class RouteMatchResult(
        val vehicleId: String,
        val vehicleName: String,
        val originStop: String,
        val originOrder: Int,
        val destinationStop: String,
        val destinationOrder: Int,
        val stopsInBetween: List<String>,
        val matchConfidence: Double,
        val isValidDirection: Boolean  // destination after origin
    )
    
    /**
     * Find best matching stop for a query string
     * 
     * Uses multiple matching strategies ranked by accuracy
     */
    fun findBestStopMatch(
        query: String,
        availableStops: List<String>
    ): MatchResult {
        if (query.isBlank()) {
            return MatchResult(
                stopName = "",
                matchScore = 0.0,
                matchType = MatchType.NO_MATCH,
                originalQuery = query
            )
        }
        
        val queryLower = query.lowercase().trim()
        
        // Strategy 1: Exact match
        availableStops.find { it.lowercase() == queryLower }?.let { stop ->
            return MatchResult(stop, 1.0, MatchType.EXACT, query)
        }
        
        // Strategy 2: Contains match
        val containsMatches = availableStops.filter { stop ->
            stop.lowercase().contains(queryLower) || 
            queryLower.contains(stop.lowercase())
        }
        if (containsMatches.isNotEmpty()) {
            // Pick the one with shortest name (most specific)
            val best = containsMatches.minByOrNull { it.length } ?: containsMatches.first()
            val score = queryLower.length.toDouble() / best.length
            return MatchResult(best, score.coerceIn(0.5, 0.95), MatchType.CONTAINS, query)
        }
        
        // Strategy 3: Fuzzy match using Levenshtein-like scoring
        val fuzzyMatches = availableStops.map { stop ->
            stop to calculateSimilarity(queryLower, stop.lowercase())
        }.filter { it.second > 0.5 }
            .sortedByDescending { it.second }
        
        if (fuzzyMatches.isNotEmpty()) {
            val (best, score) = fuzzyMatches.first()
            return MatchResult(best, score, MatchType.FUZZY, query)
        }
        
        // No match found
        return MatchResult(
            stopName = "",
            matchScore = 0.0,
            matchType = MatchType.NO_MATCH,
            originalQuery = query
        )
    }
    
    /**
     * Find all routes covering origin → destination
     * 
     * ALGORITHM:
     * 1. Match origin to stops
     * 2. Match destination to stops  
     * 3. Find vehicles with both stops
     * 4. Validate direction (dest_order > origin_order)
     * 5. Rank by confidence and distance
     */
    fun findMatchingRoutes(
        origin: String,
        destination: String,
        vehicleStops: Map<String, List<Pair<String, Int>>>, // vehicleId -> [(stopName, order)]
        vehicleNames: Map<String, String> // vehicleId -> vehicleName
    ): List<RouteMatchResult> {
        val results = mutableListOf<RouteMatchResult>()
        
        for ((vehicleId, stops) in vehicleStops) {
            val stopNames = stops.map { it.first }
            
            // Match origin
            val originMatch = findBestStopMatch(origin, stopNames)
            if (originMatch.matchType == MatchType.NO_MATCH) continue
            
            // Match destination
            val destMatch = findBestStopMatch(destination, stopNames)
            if (destMatch.matchType == MatchType.NO_MATCH) continue
            
            // Find orders
            val originOrder = stops.find { it.first == originMatch.stopName }?.second ?: continue
            val destOrder = stops.find { it.first == destMatch.stopName }?.second ?: continue
            
            // Direction validation
            val isValidDirection = destOrder > originOrder
            
            // Get stops in between
            val stopsInBetween = stops
                .filter { it.second > originOrder && it.second < destOrder }
                .sortedBy { it.second }
                .map { it.first }
            
            // Calculate combined confidence
            val confidence = (originMatch.matchScore + destMatch.matchScore) / 2 *
                if (isValidDirection) 1.0 else 0.3
            
            results.add(RouteMatchResult(
                vehicleId = vehicleId,
                vehicleName = vehicleNames[vehicleId] ?: vehicleId,
                originStop = originMatch.stopName,
                originOrder = originOrder,
                destinationStop = destMatch.stopName,
                destinationOrder = destOrder,
                stopsInBetween = stopsInBetween,
                matchConfidence = confidence,
                isValidDirection = isValidDirection
            ))
        }
        
        // Sort by confidence and direction
        return results
            .filter { it.isValidDirection }
            .sortedByDescending { it.matchConfidence }
    }
    
    /**
     * Calculate string similarity (0.0 - 1.0)
     * Uses Jaccard similarity on character bigrams
     */
    private fun calculateSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        
        // Generate bigrams
        val bigrams1 = generateBigrams(s1)
        val bigrams2 = generateBigrams(s2)
        
        // Jaccard similarity = intersection / union
        val intersection = bigrams1.intersect(bigrams2).size
        val union = bigrams1.union(bigrams2).size
        
        return if (union > 0) intersection.toDouble() / union else 0.0
    }
    
    private fun generateBigrams(s: String): Set<String> {
        if (s.length < 2) return setOf(s)
        return (0 until s.length - 1).map { s.substring(it, it + 2) }.toSet()
    }
}
