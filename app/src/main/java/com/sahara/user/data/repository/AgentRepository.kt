package com.sahara.user.data.repository

import android.util.Log
import com.sahara.user.data.api.AgentApiClient
import com.sahara.user.data.api.NearbyStop
import com.sahara.user.data.api.NearbyStopsResponse
import com.sahara.user.data.api.ProcessQueryResponse
import com.sahara.user.data.api.RouteData
import com.sahara.user.data.api.TrackBusResponse
import com.sahara.user.service.LocationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent Repository
 * 
 * High-level interface for the multi-agent system.
 * Wraps the AgentApiClient and provides additional features:
 * - State management
 * - Location integration
 * - Caching
 * - Error handling
 */
@Singleton
class AgentRepository @Inject constructor(
    private val agentApiClient: AgentApiClient,
    private val locationService: LocationService
) {
    
    companion object {
        private const val TAG = "AgentRepository"
    }
    
    // ==================== STATE ====================
    
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()
    
    private val _lastResponse = MutableStateFlow<ProcessQueryResponse?>(null)
    val lastResponse: StateFlow<ProcessQueryResponse?> = _lastResponse.asStateFlow()
    
    private val _lastRouteData = MutableStateFlow<RouteData?>(null)
    val lastRouteData: StateFlow<RouteData?> = _lastRouteData.asStateFlow()
    
    // ==================== MAIN METHODS ====================
    
    /**
     * Process a user query through the multi-agent system.
     * 
     * Automatically includes user location if available.
     * 
     * @param userId User identifier
     * @param query Natural language query
     * @return Response from the agent system
     */
    suspend fun processQuery(userId: String, query: String): Result<ProcessQueryResponse> {
        _isProcessing.value = true
        
        return try {
            // Get current location
            val location = locationService.getCurrentLocation()
            
            Log.d(TAG, "Processing: $query")
            
            val result = agentApiClient.processQuery(
                userId = userId,
                query = query,
                latitude = location?.latitude,
                longitude = location?.longitude
            )
            
            result.onSuccess { response ->
                _lastResponse.value = response
                _lastRouteData.value = response.routeData
                Log.d(TAG, "Success: ${response.response.take(50)}...")
            }
            
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "Query failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            _isProcessing.value = false
        }
    }
    
    /**
     * Process a user query with explicit location.
     */
    suspend fun processQuery(
        userId: String,
        query: String,
        userLat: Double?,
        userLng: Double?
    ): Result<ProcessQueryResponse> {
        _isProcessing.value = true
        
        return try {
            Log.d(TAG, "Processing: $query (lat=$userLat, lng=$userLng)")
            
            val result = agentApiClient.processQuery(
                userId = userId,
                query = query,
                latitude = userLat,
                longitude = userLng
            )
            
            result.onSuccess { response ->
                _lastResponse.value = response
                _lastRouteData.value = response.routeData
                Log.d(TAG, "Success: ${response.response.take(50)}...")
            }
            
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "Query failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            _isProcessing.value = false
        }
    }
    
    /**
     * Track a specific bus in real-time.
     * 
     * @param busId Bus ID to track
     * @param busName Bus name to track (if ID unknown)
     * @return Bus tracking response
     */
    suspend fun trackBus(
        busId: String? = null,
        busName: String? = null
    ): Result<TrackBusResponse> {
        return try {
            agentApiClient.trackBus(busId, busName)
        } catch (e: Exception) {
            Log.e(TAG, "Track bus failed: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Find stops near a specific location.
     * 
     * @param latitude User's latitude
     * @param longitude User's longitude
     * @param radiusKm Search radius in kilometers
     * @return Response with nearby stops
     */
    suspend fun getNearbyStops(
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 1.0
    ): Result<NearbyStopsResponse> {
        return try {
            agentApiClient.getNearbyStops(
                latitude = latitude,
                longitude = longitude,
                radiusKm = radiusKm
            )
        } catch (e: Exception) {
            Log.e(TAG, "Nearby stops failed: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Find stops near the user's current location.
     * 
     * @param radiusKm Search radius in kilometers
     * @return List of nearby stops
     */
    suspend fun getNearbyStopsFromCurrentLocation(radiusKm: Double = 1.0): Result<List<NearbyStop>> {
        return try {
            val location = locationService.getCurrentLocation()
                ?: return Result.failure(Exception("Location not available"))
            
            val result = agentApiClient.getNearbyStops(
                latitude = location.latitude,
                longitude = location.longitude,
                radiusKm = radiusKm
            )
            
            result.map { it.stops }
            
        } catch (e: Exception) {
            Log.e(TAG, "Nearby stops failed: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Clear the last response and route data.
     */
    fun clearLastResponse() {
        _lastResponse.value = null
        _lastRouteData.value = null
    }
}
