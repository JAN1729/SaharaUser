package com.sahara.user.data.repository

import com.sahara.user.data.api.SupabaseApiClient
import com.sahara.user.data.model.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Route Repository
 * 
 * Provides route data with caching and transformation.
 */
@Singleton
class RouteRepository @Inject constructor(
    private val supabaseClient: SupabaseApiClient
) {
    
    // Cache for vehicles and stops
    private var vehiclesCache: List<VehicleDTO>? = null
    private var stopsCache: List<RouteStopDTO>? = null
    private var lastCacheTime: Long = 0
    
    companion object {
        private const val CACHE_DURATION_MS = 5 * 60 * 1000L  // 5 minutes
    }
    
    /**
     * Get all active vehicles with caching
     */
    suspend fun getVehicles(forceRefresh: Boolean = false): List<VehicleDTO> {
        if (!forceRefresh && isCacheValid() && vehiclesCache != null) {
            return vehiclesCache!!
        }
        
        val result = supabaseClient.getActiveVehicles()
        return result.fold(
            onSuccess = { vehicles ->
                vehiclesCache = vehicles
                lastCacheTime = System.currentTimeMillis()
                vehicles
            },
            onFailure = { vehiclesCache ?: emptyList() }
        )
    }
    
    /**
     * Get all route stops with caching
     */
    suspend fun getAllStops(forceRefresh: Boolean = false): List<RouteStopDTO> {
        if (!forceRefresh && isCacheValid() && stopsCache != null) {
            return stopsCache!!
        }
        
        val result = supabaseClient.getAllRouteStops()
        return result.fold(
            onSuccess = { stops ->
                stopsCache = stops
                lastCacheTime = System.currentTimeMillis()
                stops
            },
            onFailure = { stopsCache ?: emptyList() }
        )
    }
    
    /**
     * Get stops for a specific vehicle
     */
    suspend fun getVehicleStops(vehicleId: String): List<RouteStopDTO> {
        return getAllStops()
            .filter { it.vehicleId == vehicleId }
            .sortedBy { it.stopOrder }
    }
    
    /**
     * Search stops by name
     */
    suspend fun searchStops(query: String): List<RouteStopDTO> {
        if (query.isBlank()) return emptyList()
        
        return getAllStops()
            .filter { it.name.contains(query, ignoreCase = true) }
            .distinctBy { it.name }  // Remove duplicates by name
    }
    
    /**
     * Get vehicle by ID
     */
    suspend fun getVehicle(vehicleId: String): VehicleDTO? {
        return getVehicles().find { it.id == vehicleId }
    }
    
    /**
     * Find vehicles that cover both origin and destination
     */
    suspend fun findMatchingVehicles(origin: String, destination: String): List<VehicleWithRoute> {
        val allStops = getAllStops()
        val vehicles = getVehicles()
        
        val results = mutableListOf<VehicleWithRoute>()
        
        for (vehicle in vehicles) {
            val vehicleStops = allStops
                .filter { it.vehicleId == vehicle.id }
                .sortedBy { it.stopOrder }
            
            val originStop = vehicleStops.find { it.name.contains(origin, ignoreCase = true) }
            val destStop = vehicleStops.find { it.name.contains(destination, ignoreCase = true) }
            
            if (originStop != null && destStop != null && destStop.stopOrder > originStop.stopOrder) {
                val segment = vehicleStops.filter { 
                    it.stopOrder >= originStop.stopOrder && it.stopOrder <= destStop.stopOrder 
                }
                
                results.add(VehicleWithRoute(
                    vehicle = vehicle,
                    originStop = originStop,
                    destinationStop = destStop,
                    stops = segment
                ))
            }
        }
        
        return results
    }
    
    /**
     * Clear cache
     */
    fun clearCache() {
        vehiclesCache = null
        stopsCache = null
        lastCacheTime = 0
    }
    
    private fun isCacheValid(): Boolean {
        return System.currentTimeMillis() - lastCacheTime < CACHE_DURATION_MS
    }
}

/**
 * Vehicle with its route segment
 */
data class VehicleWithRoute(
    val vehicle: VehicleDTO,
    val originStop: RouteStopDTO,
    val destinationStop: RouteStopDTO,
    val stops: List<RouteStopDTO>
)
