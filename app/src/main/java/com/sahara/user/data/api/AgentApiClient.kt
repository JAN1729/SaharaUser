package com.sahara.user.data.api

import android.util.Log
import com.sahara.user.config.SecureConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent API Client
 * 
 * Calls the Supabase Edge Functions that host the multi-agent system.
 * This is the ONLY interface the Android app needs to interact with agents.
 * 
 * All agent orchestration, LLM calls, and complex logic happens on the server.
 * The app is a thin UI layer.
 */
@Singleton
class AgentApiClient @Inject constructor() {
    
    companion object {
        private const val TAG = "AgentApiClient"
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
    
    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(json)
        }
        install(Logging) {
            level = LogLevel.BODY
            logger = object : Logger {
                override fun log(message: String) {
                    Log.d(TAG, message)
                }
            }
        }
    }
    
    private val baseUrl: String by lazy {
        "${SecureConfig.getSupabaseUrl()}/functions/v1"
    }
    
    private val anonKey: String by lazy {
        SecureConfig.getSupabaseAnonKey()
    }
    
    // ==================== PROCESS QUERY ====================
    
    /**
     * Send a natural language query to the multi-agent system.
     * 
     * This is the main method for all user interactions.
     * The backend agents will:
     * - Parse the query
     * - Select appropriate agents
     * - Execute in parallel/sequence
     * - Return a formatted response
     * 
     * @param userId User identifier
     * @param query Natural language query
     * @param latitude User's current latitude (optional)
     * @param longitude User's current longitude (optional)
     * @return ProcessQueryResponse with agent response and trace
     */
    suspend fun processQuery(
        userId: String,
        query: String,
        latitude: Double? = null,
        longitude: Double? = null
    ): Result<ProcessQueryResponse> {
        return try {
            Log.d(TAG, "Processing query: ${query.take(50)}...")
            
            val request = ProcessQueryRequest(
                userId = userId,
                query = query,
                userLocation = if (latitude != null && longitude != null) {
                    UserLocation(latitude, longitude)
                } else null
            )
            
            val response = client.post("$baseUrl/process-query") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $anonKey")
                header("apikey", anonKey)
                setBody(request)
            }
            
            if (response.status.isSuccess()) {
                val result = response.body<ProcessQueryResponse>()
                Log.d(TAG, "Query processed: ${result.intent}, ${result.executionTimeMs}ms")
                Result.success(result)
            } else {
                val error = "API error: ${response.status.value}"
                Log.e(TAG, error)
                Result.failure(Exception(error))
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Process query failed: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    // ==================== TRACK BUS ====================
    
    /**
     * Get real-time bus position.
     * 
     * @param busId Bus ID to track
     * @param busName Bus name to track (if ID unknown)
     * @return TrackBusResponse with position and status
     */
    suspend fun trackBus(
        busId: String? = null,
        busName: String? = null
    ): Result<TrackBusResponse> {
        return try {
            Log.d(TAG, "Tracking bus: ${busId ?: busName}")
            
            val request = TrackBusRequest(
                busId = busId,
                busName = busName
            )
            
            val response = client.post("$baseUrl/track-bus") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $anonKey")
                header("apikey", anonKey)
                setBody(request)
            }
            
            if (response.status.isSuccess()) {
                val result = response.body<TrackBusResponse>()
                Result.success(result)
            } else {
                val error = "Track bus failed: ${response.status.value}"
                Log.e(TAG, error)
                Result.failure(Exception(error))
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Track bus failed: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    // ==================== NEARBY STOPS ====================
    
    /**
     * Find bus stops near a location.
     * 
     * @param latitude Latitude coordinate
     * @param longitude Longitude coordinate
     * @param radiusKm Search radius in kilometers (default: 1.0)
     * @return NearbyStopsResponse with list of stops
     */
    suspend fun getNearbyStops(
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 1.0
    ): Result<NearbyStopsResponse> {
        return try {
            Log.d(TAG, "Finding nearby stops at $latitude, $longitude")
            
            val request = NearbyStopsRequest(
                latitude = latitude,
                longitude = longitude,
                radiusKm = radiusKm
            )
            
            val response = client.post("$baseUrl/get-nearby-stops") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $anonKey")
                header("apikey", anonKey)
                setBody(request)
            }
            
            if (response.status.isSuccess()) {
                val result = response.body<NearbyStopsResponse>()
                Result.success(result)
            } else {
                val error = "Nearby stops failed: ${response.status.value}"
                Log.e(TAG, error)
                Result.failure(Exception(error))
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Nearby stops failed: ${e.message}", e)
            Result.failure(e)
        }
    }
}

// ============================================================================
// REQUEST / RESPONSE MODELS
// ============================================================================

@Serializable
data class ProcessQueryRequest(
    val userId: String,
    val query: String,
    val userLocation: UserLocation? = null
)

@Serializable
data class UserLocation(
    val latitude: Double,
    val longitude: Double
)

@Serializable
data class ProcessQueryResponse(
    val success: Boolean,
    val response: String,
    val intent: String,
    @SerialName("routeData") val routeData: RouteData? = null,
    @SerialName("agentTrace") val agentTrace: List<TraceEvent> = emptyList(),
    @SerialName("executionTimeMs") val executionTimeMs: Long,
    val error: String? = null
)

@Serializable
data class RouteData(
    @SerialName("vehicleId") val vehicleId: String,
    @SerialName("vehicleName") val vehicleName: String,
    @SerialName("vehicleNumber") val vehicleNumber: String? = null,
    @SerialName("originStop") val originStop: String,
    @SerialName("destinationStop") val destinationStop: String,
    @SerialName("distanceKm") val distanceKm: Double,
    @SerialName("etaMinutes") val etaMinutes: Int,
    @SerialName("stopsCount") val stopsCount: Int,
    @SerialName("isLive") val isLive: Boolean,
    @SerialName("busStopsAway") val busStopsAway: Int? = null
)

@Serializable
data class TraceEvent(
    val type: String,
    val message: String,
    val timestamp: Long
)

@Serializable
data class TrackBusRequest(
    val busId: String? = null,
    val busName: String? = null
)

@Serializable
data class TrackBusResponse(
    val success: Boolean,
    val busId: String,
    val busName: String,
    val position: BusPosition,
    val speed: Float,
    @SerialName("isMoving") val isMoving: Boolean,
    @SerialName("nearestStop") val nearestStop: String,
    @SerialName("nearestStopDistance") val nearestStopDistance: Int,
    val freshness: String,
    val timestamp: String,
    val error: String? = null
)

@Serializable
data class BusPosition(
    val latitude: Double,
    val longitude: Double
)

@Serializable
data class NearbyStopsRequest(
    val latitude: Double,
    val longitude: Double,
    @SerialName("radiusKm") val radiusKm: Double = 1.0
)

@Serializable
data class NearbyStopsResponse(
    val success: Boolean,
    val stops: List<NearbyStop>,
    val count: Int
)

@Serializable
data class NearbyStop(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("distanceKm") val distanceKm: Double,
    @SerialName("vehicleId") val vehicleId: String,
    @SerialName("vehicleName") val vehicleName: String
)
