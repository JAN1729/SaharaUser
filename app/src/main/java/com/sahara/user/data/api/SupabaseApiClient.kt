package com.sahara.user.data.api

import com.sahara.user.config.SecureConfig
import com.sahara.user.data.model.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.Realtime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supabase API Client
 * 
 * SECURITY:
 * - URL and keys loaded from SecureConfig (BuildConfig)
 * - Uses HTTPS only
 * - Anon key has limited RLS permissions
 * - Never logs sensitive data
 */
@Singleton
class SupabaseApiClient @Inject constructor() {
    
    companion object {
        private const val TAG = "SupabaseClient"
    }
    
    private val client: SupabaseClient by lazy {
        val url = SecureConfig.getSupabaseUrl()
        val key = SecureConfig.getSupabaseAnonKey()
        
        if (url.isBlank() || key.isBlank()) {
            android.util.Log.e(TAG, "Supabase configuration missing. Check local.properties.")
        }
        
        createSupabaseClient(
            supabaseUrl = url,
            supabaseKey = key
        ) {
            install(Postgrest)
            install(Auth)
            install(Realtime)
        }
    }
    
    // ==================== USER OPERATIONS ====================
    
    suspend fun getOrCreateUser(phone: String): Result<User> {
        return try {
            // Try to find existing user
            val users = client.from("users")
                .select {
                    filter { eq("phone", phone) }
                }
                .decodeList<User>()
            
            val existingUser = users.firstOrNull()
            
            if (existingUser != null) {
                return Result.success(existingUser)
            }
            
            // Create new user
            val newUser = User(
                id = User.generateUserId(),
                phone = phone
            )
            client.from("users").insert(newUser)
            
            android.util.Log.d(TAG, "Created new user: ${newUser.id}")
            Result.success(newUser)
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "User error: ${e.message}")
            Result.failure(e)
        }
    }
    
    suspend fun getUser(userId: String): Result<User> {
        return try {
            val user = client.from("users")
                .select {
                    filter { eq("id", userId) }
                }
                .decodeSingleOrNull<User>()
                ?: return Result.failure(Exception("User not found"))
            
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun updateUser(user: User): Result<User> {
        return try {
            client.from("users")
                .update(user) {
                    filter { eq("id", user.id) }
                }
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // ==================== VEHICLE OPERATIONS ====================
    
    suspend fun getActiveVehicles(): Result<List<VehicleDTO>> {
        return try {
            val vehicles = client.from("vehicles")
                .select()
                .decodeList<VehicleDTO>()
            Result.success(vehicles)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Vehicles error: ${e.message}")
            Result.failure(e)
        }
    }
    
    suspend fun getVehicle(vehicleId: String): Result<VehicleDTO> {
        return try {
            val vehicle = client.from("vehicles")
                .select {
                    filter { eq("id", vehicleId) }
                }
                .decodeSingleOrNull<VehicleDTO>()
                ?: return Result.failure(Exception("Vehicle not found"))
            
            Result.success(vehicle)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // ==================== ROUTE STOP OPERATIONS ====================
    
    suspend fun getAllRouteStops(): Result<List<RouteStopDTO>> {
        return try {
            val stops = client.from("route_stops")
                .select {
                    order("vehicle_id", Order.ASCENDING)
                    order("stop_order", Order.ASCENDING)
                }
                .decodeList<RouteStopDTO>()
            Result.success(stops)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Stops error: ${e.message}")
            Result.failure(e)
        }
    }
    
    suspend fun getRouteStops(vehicleId: String): Result<List<RouteStopDTO>> {
        return try {
            val stops = client.from("route_stops")
                .select {
                    filter { eq("vehicle_id", vehicleId) }
                    order("stop_order", Order.ASCENDING)
                }
                .decodeList<RouteStopDTO>()
            Result.success(stops)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // ==================== LIVE LOCATION OPERATIONS ====================
    
    suspend fun getLiveBusLocations(): Result<List<LiveBusLocation>> {
        return try {
            val locations = client.from("locations")
                .select {
                    order("timestamp", Order.DESCENDING)
                }
                .decodeList<LiveBusLocation>()
            
            // Get latest location per bus
            val latestByBus = locations.groupBy { it.busId }
                .mapValues { it.value.first() }
                .values.toList()
            
            Result.success(latestByBus)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Locations error: ${e.message}")
            Result.failure(e)
        }
    }
    
    // ==================== TRIP OPERATIONS ====================
    
    suspend fun saveTrip(trip: UserTrip): Result<UserTrip> {
        return try {
            client.from("user_trips").insert(trip)
            Result.success(trip)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getUserTrips(userId: String): Result<List<UserTrip>> {
        return try {
            val trips = client.from("user_trips")
                .select {
                    filter { eq("user_id", userId) }
                    order("started_at", Order.DESCENDING)
                }
                .decodeList<UserTrip>()
            Result.success(trips)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // ==================== SEARCH HISTORY OPERATIONS ====================
    
    suspend fun saveSearchHistory(
        userId: String,
        query: String,
        origin: String?,
        destination: String?
    ): Result<Unit> {
        return try {
            val history = UserSearchHistory(
                userId = userId,
                query = query,
                originName = origin,
                destinationName = destination
            )
            client.from("user_search_history").insert(history)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getSearchHistory(userId: String, limit: Int = 20): Result<List<UserSearchHistory>> {
        return try {
            val history = client.from("user_search_history")
                .select {
                    filter { eq("user_id", userId) }
                    order("searched_at", Order.DESCENDING)
                    limit(limit.toLong())
                }
                .decodeList<UserSearchHistory>()
            Result.success(history)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // ==================== USER LOCATION TRACKING ====================
    
    /**
     * Update user's tracking ID (u-XXXX format)
     */
    suspend fun updateUserTrackingId(userId: String, trackingId: String): Result<Unit> {
        return try {
            client.from("users")
                .update(mapOf("tracking_id" to trackingId)) {
                    filter { eq("id", userId) }
                }
            android.util.Log.d(TAG, "Updated tracking ID for $userId: $trackingId")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Tracking ID update error: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Get latest location for a user by their tracking ID
     */
    suspend fun getUserLocationByTrackingId(trackingId: String): Result<UserLocationDTO?> {
        return try {
            val location = client.from("user_locations")
                .select {
                    filter { eq("tracking_id", trackingId) }
                    order("recorded_at", Order.DESCENDING)
                    limit(1)
                }
                .decodeSingleOrNull<UserLocationDTO>()
            Result.success(location)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Location fetch error: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Get all recently active user locations (for AI agent coordination)
     */
    suspend fun getFreshUserLocations(maxAgeSeconds: Int = 60): Result<List<UserLocationDTO>> {
        return try {
            val cutoffTime = java.time.Instant.now()
                .minusSeconds(maxAgeSeconds.toLong())
                .toString()
            
            val locations = client.from("user_locations")
                .select {
                    filter { gte("recorded_at", cutoffTime) }
                    order("recorded_at", Order.DESCENDING)
                }
                .decodeList<UserLocationDTO>()
            
            // Get latest location per user
            val latestByUser = locations.groupBy { it.userId }
                .mapValues { it.value.first() }
                .values.toList()
            
            Result.success(latestByUser)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Fresh locations error: ${e.message}")
            Result.failure(e)
        }
    }
}

/**
 * User Location DTO for database queries
 */
@Serializable
data class UserLocationDTO(
    val id: String? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("tracking_id") val trackingId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null,
    val speed: Float? = null,
    val heading: Float? = null,
    val altitude: Float? = null,
    @SerialName("recorded_at") val recordedAt: String? = null,
    @SerialName("synced_at") val syncedAt: String? = null
)
