package com.sahara.user.algorithms

/**
 * Vehicle Routing Problem (VRP) Solver
 * 
 * Operations Research algorithm for optimal route selection
 * when multiple vehicles can serve the same origin-destination pair.
 * 
 * ALGORITHMS:
 * 1. Nearest Vehicle First - Simple greedy approach
 * 2. Minimum Total Time - Considers both waiting and travel
 * 3. Multi-Criteria Ranking - Weighted scoring
 */
object VehicleRoutingOptimizer {
    
    /**
     * Route candidate with all relevant metrics
     */
    data class RouteCandidate(
        val vehicleId: String,
        val vehicleName: String,
        val vehicleNumber: String,
        val originStop: String,
        val originStopOrder: Int,
        val destinationStop: String,
        val destinationStopOrder: Int,
        val stopsInPath: List<String>,   // Ordered list of stops
        val distanceKm: Double,
        val baseTravelTimeMin: Double,
        val busCurrentStopOrder: Int?,   // Where bus is now (null if not live)
        val busStopsAwayFromOrigin: Int?, // How far bus is from user
        val isLive: Boolean,
        val busSpeed: Double?            // Current speed if live
    )
    
    /**
     * Ranked result with scores
     */
    data class RankedRoute(
        val candidate: RouteCandidate,
        val waitingTimeMin: Double,      // Time until bus arrives at origin
        val travelTimeMin: Double,       // Time from origin to destination
        val totalTimeMin: Double,        // Waiting + Travel
        val score: Double,               // 0-100 ranking score
        val rankingFactors: Map<String, Double>
    )
    
    /**
     * Rank routes using Multi-Criteria Decision Analysis (MCDA)
     * 
     * CRITERIA (weights):
     * - Total Time (40%) - Lower is better
     * - Bus Proximity (30%) - Closer live bus is better
     * - Route Directness (20%) - Fewer stops is better  
     * - Reliability (10%) - Live tracked buses preferred
     */
    fun rankRoutes(
        candidates: List<RouteCandidate>,
        userPreference: RoutePreference = RoutePreference.FASTEST
    ): List<RankedRoute> {
        if (candidates.isEmpty()) return emptyList()
        
        // Calculate metrics for each candidate
        val rankedRoutes = candidates.map { candidate ->
            val waitingTime = calculateWaitingTime(candidate)
            val travelTime = calculateTravelTime(candidate)
            val totalTime = waitingTime + travelTime
            
            // Normalize factors (0-1 scale)
            val factors = mutableMapOf<String, Double>()
            
            // Time factor (inverted - lower time = higher score)
            factors["time_normalized"] = 1.0 - (totalTime / 120.0).coerceIn(0.0, 1.0)
            
            // Proximity factor
            factors["proximity"] = when {
                candidate.isLive && candidate.busStopsAwayFromOrigin != null -> {
                    1.0 - (candidate.busStopsAwayFromOrigin / 20.0).coerceIn(0.0, 1.0)
                }
                candidate.isLive -> 0.5
                else -> 0.0
            }
            
            // Directness factor (fewer stops = better)
            factors["directness"] = 1.0 - (candidate.stopsInPath.size / 30.0).coerceIn(0.0, 1.0)
            
            // Reliability factor
            factors["reliability"] = if (candidate.isLive) 1.0 else 0.3
            
            // Apply weights based on preference
            val weights = when (userPreference) {
                RoutePreference.FASTEST -> mapOf(
                    "time_normalized" to 0.50,
                    "proximity" to 0.30,
                    "directness" to 0.15,
                    "reliability" to 0.05
                )
                RoutePreference.LEAST_WAITING -> mapOf(
                    "time_normalized" to 0.20,
                    "proximity" to 0.50,
                    "directness" to 0.20,
                    "reliability" to 0.10
                )
                RoutePreference.MOST_DIRECT -> mapOf(
                    "time_normalized" to 0.30,
                    "proximity" to 0.20,
                    "directness" to 0.40,
                    "reliability" to 0.10
                )
            }
            
            // Calculate weighted score
            val score = factors.entries.sumOf { (key, value) ->
                value * (weights[key] ?: 0.0)
            } * 100
            
            RankedRoute(
                candidate = candidate,
                waitingTimeMin = waitingTime,
                travelTimeMin = travelTime,
                totalTimeMin = totalTime,
                score = score,
                rankingFactors = factors
            )
        }
        
        return rankedRoutes.sortedByDescending { it.score }
    }
    
    /**
     * Calculate waiting time until bus arrives at origin stop
     */
    private fun calculateWaitingTime(candidate: RouteCandidate): Double {
        if (!candidate.isLive || candidate.busStopsAwayFromOrigin == null) {
            // No live data - assume average wait of 10-15 min
            return 12.0
        }
        
        // Bus is approaching
        if (candidate.busStopsAwayFromOrigin > 0) {
            val avgSpeedKmh = candidate.busSpeed ?: 18.0
            val avgDistancePerStop = 0.5 // km
            val distanceToOrigin = candidate.busStopsAwayFromOrigin * avgDistancePerStop
            val travelTime = (distanceToOrigin / avgSpeedKmh) * 60
            val dwellTime = candidate.busStopsAwayFromOrigin * 0.75 // 45 sec per stop
            return travelTime + dwellTime
        }
        
        // Bus is at or past origin - can board immediately
        return 0.0
    }
    
    /**
     * Calculate travel time from origin to destination
     */
    private fun calculateTravelTime(candidate: RouteCandidate): Double {
        val avgSpeedKmh = candidate.busSpeed ?: 18.0
        val travelTime = (candidate.distanceKm / avgSpeedKmh) * 60
        val dwellTime = candidate.stopsInPath.size * 0.75
        
        // Rush hour factor
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val rushFactor = if (hour in 7..10 || hour in 17..20) 1.3 else 1.0
        
        return (travelTime + dwellTime) * rushFactor
    }
    
    /**
     * Find optimal vehicle using Greedy Nearest approach
     */
    fun findNearestVehicle(candidates: List<RouteCandidate>): RouteCandidate? {
        return candidates
            .filter { it.isLive && it.busStopsAwayFromOrigin != null }
            .minByOrNull { it.busStopsAwayFromOrigin!! }
            ?: candidates.minByOrNull { it.distanceKm }
    }
    
    enum class RoutePreference {
        FASTEST,        // Minimize total time
        LEAST_WAITING,  // Minimize waiting time
        MOST_DIRECT     // Minimize stops
    }
}
