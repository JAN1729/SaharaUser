package com.sahara.user.algorithms

import com.sahara.user.data.model.RouteStopDTO

/**
 * Traveling Salesman Problem (TSP) Solver
 * 
 * Optimizes the visit order for multiple destinations to minimize total distance.
 * Uses the Nearest Neighbor Heuristic which provides a good approximation in O(n²) time.
 * 
 * USE CASE: User wants to visit multiple places (e.g., "I need to go to Market, Station, and Mall")
 * This algorithm determines the optimal order to visit them.
 * 
 * ALGORITHM: Nearest Neighbor Heuristic
 * 1. Start from user's location (or first destination)
 * 2. Visit the nearest unvisited destination
 * 3. Repeat until all destinations are visited
 * 4. Optionally return to start for circular routes
 * 
 * TIME COMPLEXITY: O(n²)
 * SPACE COMPLEXITY: O(n)
 * 
 * Note: This is an approximation algorithm. For optimal TSP solutions,
 * dynamic programming (O(n² * 2^n)) or branch-and-bound would be needed,
 * but those are impractical for real-time applications.
 */
object TravelingSalesman {
    
    /**
     * Segment between two destinations
     */
    data class RouteSegment(
        val from: String,
        val to: String,
        val distanceKm: Double,
        val vehicleName: String?,
        val hasDirectRoute: Boolean
    )
    
    /**
     * Result of TSP optimization
     */
    data class OptimalOrderResult(
        val orderedDestinations: List<String>,
        val totalDistanceKm: Double,
        val estimatedTimeMinutes: Int,
        val segments: List<RouteSegment>,
        val optimizationSavings: OptimizationSavings,
        val requiresTransfer: Boolean
    )
    
    /**
     * Savings achieved by optimization
     */
    data class OptimizationSavings(
        val distanceSavedKm: Double,
        val timeSavedMinutes: Int,
        val percentageImprovement: Double
    )
    
    /**
     * Destination with resolved stop coordinates
     */
    private data class ResolvedDestination(
        val name: String,
        val stop: RouteStopDTO?,
        val latitude: Double,
        val longitude: Double
    )
    
    /**
     * Optimize visit order using Nearest Neighbor Heuristic
     * 
     * @param startLocation User's current GPS location (optional)
     * @param destinations List of destination names to visit
     * @param stops All available route stops (for resolving destination coordinates)
     * @param vehicleNames Map of vehicleId to vehicle name
     * @return Optimized order with distance and time estimates
     */
    fun optimizeVisitOrder(
        startLocation: Pair<Double, Double>?,
        destinations: List<String>,
        stops: List<RouteStopDTO>,
        vehicleNames: Map<String, String>
    ): OptimalOrderResult {
        if (destinations.isEmpty()) {
            return OptimalOrderResult(
                orderedDestinations = emptyList(),
                totalDistanceKm = 0.0,
                estimatedTimeMinutes = 0,
                segments = emptyList(),
                optimizationSavings = OptimizationSavings(0.0, 0, 0.0),
                requiresTransfer = false
            )
        }
        
        if (destinations.size == 1) {
            return OptimalOrderResult(
                orderedDestinations = destinations,
                totalDistanceKm = 0.0,
                estimatedTimeMinutes = 0,
                segments = emptyList(),
                optimizationSavings = OptimizationSavings(0.0, 0, 0.0),
                requiresTransfer = false
            )
        }
        
        // Resolve destination coordinates
        val resolvedDestinations = resolveDestinations(destinations, stops)
        
        // Apply Nearest Neighbor Heuristic
        val (orderedDestinations, segments, totalDistance) = nearestNeighborTSP(
            startLocation,
            resolvedDestinations,
            vehicleNames
        )
        
        // Calculate original (unoptimized) distance for comparison
        val originalDistance = calculateOriginalDistance(resolvedDestinations)
        
        // Calculate savings
        val distanceSaved = originalDistance - totalDistance
        val avgSpeedKmh = 20.0 // Including traffic and stops
        val estimatedTime = ((totalDistance / avgSpeedKmh) * 60).toInt()
        val originalTime = ((originalDistance / avgSpeedKmh) * 60).toInt()
        val timeSaved = (originalTime - estimatedTime).coerceAtLeast(0)
        val percentageImprovement = if (originalDistance > 0) {
            ((distanceSaved / originalDistance) * 100).coerceIn(0.0, 100.0)
        } else 0.0
        
        // Check if transfers are needed
        val requiresTransfer = checkRequiresTransfer(segments)
        
        return OptimalOrderResult(
            orderedDestinations = orderedDestinations,
            totalDistanceKm = (totalDistance * 100).toLong() / 100.0,
            estimatedTimeMinutes = estimatedTime,
            segments = segments,
            optimizationSavings = OptimizationSavings(
                distanceSavedKm = (distanceSaved * 100).toLong() / 100.0,
                timeSavedMinutes = timeSaved,
                percentageImprovement = (percentageImprovement * 10).toLong() / 10.0
            ),
            requiresTransfer = requiresTransfer
        )
    }
    
    /**
     * Resolve destination names to coordinates using stop matching
     */
    private fun resolveDestinations(
        destinations: List<String>,
        stops: List<RouteStopDTO>
    ): List<ResolvedDestination> {
        return destinations.map { dest ->
            val matchingStop = stops.find { stop ->
                stop.name.lowercase().contains(dest.lowercase()) ||
                dest.lowercase().contains(stop.name.lowercase())
            }
            
            ResolvedDestination(
                name = dest,
                stop = matchingStop,
                latitude = matchingStop?.latitude ?: 0.0,
                longitude = matchingStop?.longitude ?: 0.0
            )
        }
    }
    
    /**
     * Nearest Neighbor Heuristic implementation
     */
    private fun nearestNeighborTSP(
        startLocation: Pair<Double, Double>?,
        destinations: List<ResolvedDestination>,
        vehicleNames: Map<String, String>
    ): Triple<List<String>, List<RouteSegment>, Double> {
        val orderedDestinations = mutableListOf<String>()
        val segments = mutableListOf<RouteSegment>()
        val remaining = destinations.toMutableList()
        var totalDistance = 0.0
        
        // Current position
        var currentLat = startLocation?.first ?: destinations.firstOrNull()?.latitude ?: 0.0
        var currentLng = startLocation?.second ?: destinations.firstOrNull()?.longitude ?: 0.0
        
        // If no start location, begin with first destination
        if (startLocation == null && remaining.isNotEmpty()) {
            val first = remaining.removeAt(0)
            orderedDestinations.add(first.name)
            currentLat = first.latitude
            currentLng = first.longitude
        }
        
        // Greedily select nearest unvisited destination
        while (remaining.isNotEmpty()) {
            val nearest = remaining
                .filter { it.latitude != 0.0 && it.longitude != 0.0 }
                .minByOrNull { 
                    Haversine.distance(currentLat, currentLng, it.latitude, it.longitude)
                }
            
            if (nearest != null) {
                val distance = Haversine.distance(currentLat, currentLng, nearest.latitude, nearest.longitude)
                
                val prevName = orderedDestinations.lastOrNull() ?: "Start"
                segments.add(RouteSegment(
                    from = prevName,
                    to = nearest.name,
                    distanceKm = (distance * 100).toLong() / 100.0,
                    vehicleName = nearest.stop?.let { vehicleNames[it.vehicleId] },
                    hasDirectRoute = nearest.stop != null
                ))
                
                orderedDestinations.add(nearest.name)
                remaining.remove(nearest)
                totalDistance += distance
                currentLat = nearest.latitude
                currentLng = nearest.longitude
            } else {
                // Fallback: add remaining in original order
                remaining.forEach { orderedDestinations.add(it.name) }
                break
            }
        }
        
        return Triple(orderedDestinations, segments, totalDistance)
    }
    
    /**
     * Calculate distance if visiting in original order (for comparison)
     */
    private fun calculateOriginalDistance(destinations: List<ResolvedDestination>): Double {
        if (destinations.size < 2) return 0.0
        
        var totalDistance = 0.0
        for (i in 0 until destinations.size - 1) {
            val current = destinations[i]
            val next = destinations[i + 1]
            
            if (current.latitude != 0.0 && next.latitude != 0.0) {
                totalDistance += Haversine.distance(
                    current.latitude, current.longitude,
                    next.latitude, next.longitude
                )
            }
        }
        
        return totalDistance
    }
    
    /**
     * Check if the route requires transfers between vehicles
     */
    private fun checkRequiresTransfer(segments: List<RouteSegment>): Boolean {
        val vehicles = segments.mapNotNull { it.vehicleName }.distinct()
        return vehicles.size > 1
    }
    
    /**
     * 2-opt improvement for refining TSP solution
     * Tries swapping edges to find better solutions
     * 
     * @param orderedDestinations Initial tour order
     * @param stops Stop data for distance calculations
     * @return Improved tour order
     */
    fun twoOptImprove(
        orderedDestinations: List<String>,
        stops: List<RouteStopDTO>
    ): List<String> {
        if (orderedDestinations.size < 4) return orderedDestinations
        
        val destinations = orderedDestinations.toMutableList()
        var improved = true
        
        while (improved) {
            improved = false
            
            for (i in 0 until destinations.size - 2) {
                for (j in i + 2 until destinations.size) {
                    val newTour = twoOptSwap(destinations, i, j)
                    
                    val currentDistance = calculateTourDistance(destinations, stops)
                    val newDistance = calculateTourDistance(newTour, stops)
                    
                    if (newDistance < currentDistance) {
                        destinations.clear()
                        destinations.addAll(newTour)
                        improved = true
                    }
                }
            }
        }
        
        return destinations
    }
    
    /**
     * Perform 2-opt swap
     */
    private fun twoOptSwap(tour: List<String>, i: Int, j: Int): List<String> {
        val newTour = mutableListOf<String>()
        
        // Take first segment as is
        for (k in 0..i) {
            newTour.add(tour[k])
        }
        
        // Reverse the segment between i+1 and j
        for (k in j downTo i + 1) {
            newTour.add(tour[k])
        }
        
        // Take the rest as is
        for (k in j + 1 until tour.size) {
            newTour.add(tour[k])
        }
        
        return newTour
    }
    
    /**
     * Calculate total tour distance
     */
    private fun calculateTourDistance(tour: List<String>, stops: List<RouteStopDTO>): Double {
        if (tour.size < 2) return 0.0
        
        var totalDistance = 0.0
        
        for (i in 0 until tour.size - 1) {
            val currentStop = stops.find { it.name.lowercase().contains(tour[i].lowercase()) }
            val nextStop = stops.find { it.name.lowercase().contains(tour[i + 1].lowercase()) }
            
            if (currentStop != null && nextStop != null) {
                totalDistance += Haversine.distance(
                    currentStop.latitude, currentStop.longitude,
                    nextStop.latitude, nextStop.longitude
                )
            }
        }
        
        return totalDistance
    }
}
