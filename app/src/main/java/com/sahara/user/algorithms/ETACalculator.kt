package com.sahara.user.algorithms

/**
 * ETA Calculator - Arrival Time Estimation Algorithms
 * 
 * Mathematical equations for calculating when a bus will arrive
 * at a specific location given current position, speed, and route.
 * 
 * EQUATIONS USED:
 * 1. Time = Distance / Speed
 * 2. ETA = Current_Time + Travel_Time + Dwell_Time
 * 3. Intercept = User_Position ∩ Bus_Route (at future time)
 */
object ETACalculator {
    
    /**
     * ETA Result with breakdown of calculation
     */
    data class ETAResult(
        val etaMinutes: Double,          // Total estimated time
        val travelTimeMin: Double,       // Pure travel time
        val dwellTimeMin: Double,        // Time at stops
        val rushHourFactor: Double,      // Traffic multiplier
        val confidenceScore: Double,     // 0.0 - 1.0
        val calculationMethod: String,   // Which equation was used
        val breakdown: Map<String, Double> // All components
    )
    
    // Constants
    private const val AVG_BUS_SPEED_KMH = 20.0      // km/h average in city
    private const val MIN_BUS_SPEED_KMH = 10.0     // During rush hour
    private const val MAX_BUS_SPEED_KMH = 35.0     // On highways
    private const val STOP_DWELL_SEC = 45.0        // Seconds per stop
    private const val TRAFFIC_LIGHT_DELAY_SEC = 30.0 // Per 1km average
    
    /**
     * Calculate ETA using distance and average speed
     * 
     * Equation: t = d / v + (n × dwell) + traffic_factor
     * 
     * @param distanceKm Distance in kilometers
     * @param numStops Number of stops along the route
     * @param currentSpeed Current bus speed (km/h), null if unknown
     * @param currentHour Current hour (0-23) for rush hour detection
     */
    fun calculateETA(
        distanceKm: Double,
        numStops: Int,
        currentSpeed: Double? = null,
        currentHour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    ): ETAResult {
        // Determine effective speed
        val isRushHour = currentHour in 7..10 || currentHour in 17..20
        val baseSpeed = currentSpeed ?: if (isRushHour) MIN_BUS_SPEED_KMH else AVG_BUS_SPEED_KMH
        val rushFactor = if (isRushHour) 1.3 else 1.0
        
        // Equation 1: Travel time = Distance / Speed
        val travelTimeHours = distanceKm / baseSpeed
        val travelTimeMin = travelTimeHours * 60
        
        // Equation 2: Dwell time = Stops × Dwell_per_stop
        val dwellTimeMin = numStops * (STOP_DWELL_SEC / 60)
        
        // Equation 3: Traffic delay estimate (30 sec per km)
        val trafficDelayMin = (distanceKm * TRAFFIC_LIGHT_DELAY_SEC / 60) * rushFactor
        
        // Total ETA
        val totalETA = (travelTimeMin + dwellTimeMin + trafficDelayMin) * rushFactor
        
        // Confidence based on data availability
        val confidence = when {
            currentSpeed != null && currentSpeed > 0 -> 0.9  // Have real speed
            !isRushHour -> 0.75                               // Predictable traffic
            else -> 0.6                                       // Rush hour uncertainty
        }
        
        return ETAResult(
            etaMinutes = totalETA,
            travelTimeMin = travelTimeMin,
            dwellTimeMin = dwellTimeMin,
            rushHourFactor = rushFactor,
            confidenceScore = confidence,
            calculationMethod = "time_distance_speed",
            breakdown = mapOf(
                "distance_km" to distanceKm,
                "speed_kmh" to baseSpeed,
                "num_stops" to numStops.toDouble(),
                "travel_min" to travelTimeMin,
                "dwell_min" to dwellTimeMin,
                "traffic_delay_min" to trafficDelayMin,
                "rush_factor" to rushFactor,
                "total_min" to totalETA
            )
        )
    }
    
    /**
     * Calculate bus arrival at user's position (Intercept Point)
     * 
     * Given: User at position P, Bus at position B, Bus moving toward P at speed v
     * Find: Time for bus to reach user's nearest stop
     * 
     * Equation: t_arrival = d(B, nearest_stop) / v_bus
     */
    fun calculateBusArrivalToUser(
        busLat: Double,
        busLng: Double,
        busSpeed: Double, // km/h
        userLat: Double,
        userLng: Double,
        stopsOnRoute: List<Pair<Double, Double>> // (lat, lng) of each stop
    ): Pair<Double, Int>? { // Returns (minutes, stop_index)
        
        if (busSpeed <= 0 || stopsOnRoute.isEmpty()) return null
        
        // Find nearest stop to user
        var nearestStopIndex = -1
        var minDistanceToUser = Double.MAX_VALUE
        
        stopsOnRoute.forEachIndexed { index, (lat, lng) ->
            val dist = Haversine.distance(userLat, userLng, lat, lng)
            if (dist < minDistanceToUser) {
                minDistanceToUser = dist
                nearestStopIndex = index
            }
        }
        
        if (nearestStopIndex < 0) return null
        
        // Find nearest stop to bus (current position)
        var busCurrentStopIndex = -1
        var minDistanceToBus = Double.MAX_VALUE
        
        stopsOnRoute.forEachIndexed { index, (lat, lng) ->
            val dist = Haversine.distance(busLat, busLng, lat, lng)
            if (dist < minDistanceToBus) {
                minDistanceToBus = dist
                busCurrentStopIndex = index
            }
        }
        
        // Bus must be before user's stop (traveling toward user)
        if (busCurrentStopIndex >= nearestStopIndex) {
            return null // Bus has passed user's stop
        }
        
        // Calculate distance from bus position to user's stop
        var totalDistance = minDistanceToBus // Distance to nearest stop
        for (i in busCurrentStopIndex until nearestStopIndex) {
            val (lat1, lng1) = stopsOnRoute[i]
            val (lat2, lng2) = stopsOnRoute[i + 1]
            totalDistance += Haversine.distance(lat1, lng1, lat2, lng2)
        }
        
        // Calculate time: t = d / v
        val timeHours = totalDistance / busSpeed
        val timeMinutes = timeHours * 60
        
        // Add dwell time for intermediate stops
        val numIntermediateStops = nearestStopIndex - busCurrentStopIndex
        val dwellTime = numIntermediateStops * (STOP_DWELL_SEC / 60)
        
        return (timeMinutes + dwellTime) to nearestStopIndex
    }
    
    /**
     * Calculate ETA range (min-max) for user communication
     */
    fun calculateETARange(etaMinutes: Double, confidence: Double): Pair<Int, Int> {
        val variance = 1.0 - confidence // Higher confidence = smaller range
        val minETA = (etaMinutes * (1 - variance * 0.3)).toInt()
        val maxETA = (etaMinutes * (1 + variance * 0.5)).toInt()
        return minETA to maxETA
    }
}
