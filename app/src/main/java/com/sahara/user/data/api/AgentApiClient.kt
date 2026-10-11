package com.sahara.user.data.api

import android.util.Log
import com.sahara.user.config.SecureConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import com.sahara.user.data.model.ViewportState
import com.sahara.user.data.model.MapCommand

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
            level = LogLevel.NONE
            logger = object : Logger {
                override fun log(message: String) {
                    Log.d(TAG, message)
                }
            }
        }
        // Increased timeout for AI processing with OpenRouter
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 60_000  // 60 seconds for full request
            connectTimeoutMillis = 15_000  // 15 seconds to connect
            socketTimeoutMillis = 60_000   // 60 seconds for socket idle
        }
    }
    
    private val baseUrl: String get() = if (CommuteBackend.enabled) CommuteBackend.url else "${SecureConfig.getSupabaseUrl()}/functions/v1"
    
    private fun HttpRequestBuilder.configureAuthentication(useCommute: Boolean = CommuteBackend.enabled) {
        val key = if (useCommute) CommuteBackend.key else SecureConfig.getSupabaseAnonKey()
        if (key.isNotBlank()) header("Authorization", "Bearer $key")
        if (useCommute) header("X-Sahara-Session", CommuteBackend.session())
        else header("apikey", key)
    }

    suspend fun checkServer(): String {
        require(CommuteBackend.enabled) { "Configure your central server first" }
        val response = client.get("$baseUrl/health")
        check(response.status.isSuccess()) { "Server health check failed (${response.status.value})" }
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        check(payload["status"]?.jsonPrimitive?.content == "ok") { "This address is not a Sahara server" }
        val live = payload["live_enabled"]?.jsonPrimitive?.content == "true"
        if (CommuteBackend.dataMode == "live") {
            check(live) { "This server offers demo data only. Enable live access on the computer first." }
            check(CommuteBackend.key.isNotBlank()) { "Enter your server access key in Server settings, then save and check the connection." }
            val access = client.post("$baseUrl/v1/access/check") { configureAuthentication() }
            check(access.status.isSuccess()) { "Server access key was not accepted" }
        }
        return if (CommuteBackend.dataMode == "live") "Connected. Live access enabled; source coverage is shown in results."
            else "Connected for demo searches. Switch to Live web data and check your access key for live journeys."
    }

    suspend fun refreshSearch(searchId: String): ProcessQueryResponse {
        require(CommuteBackend.enabled)
        require(searchId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val response = client.get("$baseUrl/v1/searches/$searchId/response") { configureAuthentication() }
        check(response.status.isSuccess()) { "Search update failed (${response.status.value})" }
        return response.body()
    }

    suspend fun journeyGuidance(journeyId: String, progress: TripProgress? = null): JourneyGuidance {
        require(CommuteBackend.enabled)
        require(journeyId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val response = if (progress == null) client.get("$baseUrl/v1/journeys/$journeyId/guidance") { configureAuthentication() }
        else client.post("$baseUrl/v1/journeys/$journeyId/progress") {
            configureAuthentication()
            contentType(ContentType.Application.Json)
            setBody(progress)
        }
        if (!response.status.isSuccess() && progress != null && response.status.value in listOf(400,401,403,404,422))
            throw TripConfirmationRejected("Travel status was not accepted (${response.status.value}). Check the departure time and confirm progress in order.")
        check(response.status.isSuccess()) { "Journey update unavailable (${response.status.value}). Your confirmation will be checked again." }
        val guidance = response.body<JourneyGuidance>()
        check(guidance.journeyId == journeyId) { "Journey guidance did not match the selected route" }
        return guidance
    }

    suspend fun revalidateJourney(journeyId: String) {
        require(CommuteBackend.enabled)
        require(journeyId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val response = client.post("$baseUrl/v1/journeys/$journeyId/revalidate") {
            configureAuthentication()
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        check(response.status.isSuccess()) { "Journey sources could not be refreshed (${response.status.value})" }
    }

    suspend fun journeyMap(journeyId: String): JourneyMap {
        require(CommuteBackend.enabled) { "Configure the central server to see the journey map" }
        require(journeyId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val response = client.get("$baseUrl/v1/journeys/$journeyId/map") { configureAuthentication() }
        check(response.status.isSuccess()) { "Journey map is unavailable (${response.status.value})" }
        val map = response.body<JourneyMap>()
        check(map.journeyId == journeyId) { "The map does not match this journey" }
        return map
    }
    
    /**
     * Parse SSE streaming response from Edge Function.
     * The Edge Function sends newline-delimited JSON events like:
     * {"type":"thinking","data":"..."}
     * {"type":"reasoning","data":"..."} 
     * {"type":"final_response","data":{...}}
     * 
     * We extract the final_response event and parse its data field.
     * Reasoning events are collected and can be added to the response.
     */
    private fun parseStreamingResponse(responseBody: String): ProcessQueryResponse? {
        try {
            // Split by double newline (SSE delimiter)
            val events = responseBody.split("\n\n").filter { it.isNotBlank() }
            val collectedReasoning = mutableListOf<String>()
            
            for (eventStr in events) {
                val trimmed = eventStr.trim()
                if (trimmed.isEmpty()) continue
                
                try {
                    // Parse the event wrapper
                    val event = json.decodeFromString<SSEEvent>(trimmed)
                    
                    // Collect reasoning events for potential display
                    if (event.type == "reasoning" && event.data != null) {
                        try {
                            val reasoningText = json.decodeFromString<String>(
                                json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), event.data)
                            )
                            collectedReasoning.add(reasoningText)
                            Log.d(TAG, "Reasoning: $reasoningText")
                        } catch (_: Exception) {
                            // Ignore parse errors for reasoning
                        }
                    }
                    
                    if (event.type == "final_response" && event.data != null) {
                        // The data field contains the actual ProcessQueryResponse fields
                        val dataStr = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), event.data)
                        val response = json.decodeFromString<ProcessQueryResponse>(dataStr)
                        
                        // If response doesn't have reasoning details, add collected ones from SSE stream
                        return if (response.reasoningDetails.isNullOrEmpty() && collectedReasoning.isNotEmpty()) {
                            response.copy(
                                reasoningDetails = collectedReasoning.map { 
                                    ReasoningDetail(type = "reasoning.text", text = it) 
                                }
                            )
                        } else {
                            response
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Skipping non-parseable event: ${trimmed.take(100)}")
                }
            }
            
            Log.w(TAG, "No final_response event found in ${events.size} events")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse streaming response: ${e.message}")
            return null
        }
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
                } else null,
                dataMode = if (CommuteBackend.enabled) CommuteBackend.dataMode else "sample"
            )
            
            val response = client.post("$baseUrl/process-query") {
                contentType(ContentType.Application.Json)
                configureAuthentication()
                setBody(request)
            }
            
            // Get raw response body for debugging
            val responseBody = response.bodyAsText()
            Log.d(TAG, "Raw response (first 500 chars): ${responseBody.take(500)}")
            
            if (response.status.isSuccess()) {
                try {
                    // Parse SSE streaming response format
                    // Edge Function sends: {"type":"thinking","data":"..."}\n\n{"type":"final_response","data":{...}}\n\n
                    // We need to extract the final_response event and parse its data
                    val result = parseStreamingResponse(responseBody)
                    if (result != null) {
                        Log.d(TAG, "Query processed: ${result.intent}, ${result.executionTimeMs}ms")
                        Result.success(result)
                    } else {
                        Log.e(TAG, "No final_response found in stream")
                        Result.failure(Exception("No response received from server"))
                    }
                } catch (parseError: Exception) {
                    Log.e(TAG, "JSON parse error: ${parseError.message}")
                    Log.e(TAG, "Response body: $responseBody")
                    // Try to extract at least a message if parse fails
                    Result.failure(Exception("Parse error: ${parseError.message}"))
                }
            } else {
                val error = "API error: ${response.status.value} - $responseBody"
                Log.e(TAG, error)
                Result.failure(Exception(error))
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Process query failed: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Stream events wrapper for real-time updates
     */
    sealed class StreamEvent {
        data class Reasoning(val text: String) : StreamEvent()
        data class Thinking(val text: String) : StreamEvent()
        data class FinalResponse(val response: ProcessQueryResponse) : StreamEvent()
        data class Error(val message: String) : StreamEvent()
    }
    
    /**
     * Process query with REAL-TIME streaming of reasoning tokens.
     * Emits events as they arrive from the server SSE stream.
     */
    fun processQueryStreaming(
        userId: String,
        query: String,
        latitude: Double? = null,
        longitude: Double? = null,
        viewportState: ViewportState? = null,
        resetContext: Boolean = false
    ): Flow<StreamEvent> = flow {
        val request = ProcessQueryRequest(
            userId = userId,
            query = query,
            userLocation = if (latitude != null && longitude != null) UserLocation(latitude, longitude) else null,
            viewportState = viewportState,
            resetContext = resetContext,
            dataMode = if (CommuteBackend.enabled) CommuteBackend.dataMode else "sample"
        )
        fun decode(frame: String): StreamEvent? {
            return try {
                val event = json.decodeFromString<SSEEvent>(frame)
                when (event.type) {
                    "thinking" -> StreamEvent.Thinking(extractTextFromJsonElement(event.data))
                    "reasoning" -> StreamEvent.Reasoning(extractTextFromJsonElement(event.data))
                    "final_response" -> StreamEvent.FinalResponse(
                        json.decodeFromJsonElement(ProcessQueryResponse.serializer(), event.data ?: return null)
                    )
                    "error" -> StreamEvent.Error(extractTextFromJsonElement(event.data).ifBlank { event.error ?: "Server error" })
                    else -> null
                }
            } catch (error: Exception) {
                StreamEvent.Error("Invalid response event: ${error.message}")
            }
        }
        try {
            if (CommuteBackend.enabled && CommuteBackend.dataMode == "live" && CommuteBackend.key.isBlank()) {
                emit(StreamEvent.Error("Live searches need your server access key. Open Server settings, enter it, then save and check the connection."))
                return@flow
            }
            client.preparePost("$baseUrl/process-query") {
                contentType(ContentType.Application.Json)
                configureAuthentication()
                setBody(request)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val message = when (response.status.value) {
                        401, 403 -> "Your server access key was not accepted. Open Server settings and save the correct key."
                        429 -> "Too many searches at once. Please wait a moment and try again."
                        in 500..599 -> "The server could not complete this request. Please try again."
                        else -> "The server rejected this request (${response.status.value}). Check your connection settings."
                    }
                    emit(StreamEvent.Error(message))
                    return@execute
                }
                val frames = EventFrames()
                val channel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    frames.push(line)?.let { decode(it)?.let { event -> emit(event) } }
                }
                frames.finish()?.let { decode(it)?.let { event -> emit(event) } }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            emit(StreamEvent.Error(error.message ?: "Connection failed"))
        }
    }

    /**
     * Helper to extract text from JsonElement - handles both primitive strings and complex objects
     */
    private fun extractTextFromJsonElement(element: JsonElement?): String {
        if (element == null) return ""
        return try {
            when {
                element is kotlinx.serialization.json.JsonPrimitive && element.isString -> {
                    element.content
                }
                element is kotlinx.serialization.json.JsonPrimitive -> {
                    element.content
                }
                else -> {
                    // For complex objects, just convert to string representation
                    json.encodeToString(JsonElement.serializer(), element)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract text from JsonElement: ${e.message}")
            ""
        }
    }
    

    @Serializable
    private data class SSEEvent(
        val type: String,
        val data: JsonElement? = null,
        val error: String? = null
    )
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
            
            val response = client.post("${SecureConfig.getSupabaseUrl()}/functions/v1/track-bus") {
                contentType(ContentType.Application.Json)
                configureAuthentication(useCommute = false)
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
            
            val response = client.post("${SecureConfig.getSupabaseUrl()}/functions/v1/get-nearby-stops") {
                contentType(ContentType.Application.Json)
                configureAuthentication(useCommute = false)
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
    val userLocation: UserLocation? = null,
    // Map-Action Pipeline: Spatial context for AI reasoning
    @SerialName("viewportState") val viewportState: ViewportState? = null,
    val dataMode: String = "sample",
    val resetContext: Boolean = false
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
    val intent: String = "UNKNOWN",
    // Use JsonElement for flexible routeData shapes from different endpoints
    // - find-route returns: {vehicleId, vehicleName, originStop, ...}
    // - get-nearby-stops returns: {stops: [...], count: N}
    @SerialName("routeData") val routeData: JsonElement? = null,
    @SerialName("agentTrace") val agentTrace: List<TraceEvent> = emptyList(),
    @SerialName("executionTimeMs") val executionTimeMs: Long = 0,
    // OpenRouter reasoning details for chain-of-thought display
    @SerialName("reasoningDetails") val reasoningDetails: List<ReasoningDetail>? = null,
    // Agentic tool calls - AI invokes map visualization when it helps
    @SerialName("toolCalls") val toolCalls: List<com.sahara.user.data.model.AgentToolCall>? = null,
    // Map-Action Pipeline: AI-driven map commands (HIGHLIGHT_PATH, ZOOM_TO, etc.)
    @SerialName("map_commands") val mapCommands: List<MapCommand>? = null,
    val error: String? = null,
    val journeys: List<JourneyOption> = emptyList(),
    val searchId: String? = null,
    val dataMode: String? = null,
    val collectionComplete: Boolean = true,
    val pendingProviders: List<String> = emptyList(),
    val providers: List<SourceStatus> = emptyList(),
    val placeNames: Map<String, String> = emptyMap(),
    val coverage: String? = null,
    val collectionError: String? = null
)

/**
 * Reasoning detail from OpenRouter's chain-of-thought.
 * OpenRouter returns type like 'reasoning.text' with 'text' field.
 */
@Serializable
data class ReasoningDetail(
    val type: String,
    val text: String? = null,    // OpenRouter uses 'text'
    val content: String? = null,  // Fallback
    val format: String? = null,
    val index: Int? = null
) {
    /** Get the actual reasoning text from either field */
    val reasoningText: String get() = text ?: content ?: ""
}

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

/**
 * Trace event from the agent pipeline.
 * Matches Edge Function's AgentTraceEntry format.
 */
@Serializable
data class TraceEvent(
    // Edge Function sends 'agent' for the agent name
    val agent: String = "",
    val action: String = "",
    @SerialName("durationMs") val durationMs: Long = 0,
    val success: Boolean = true,
    val timestamp: Long = 0,
    // Legacy fields for backwards compatibility (just in case)
    val type: String = "",
    val message: String = ""
)

/**
 * SSE Event wrapper for streaming responses.
 * The Edge Function sends {"type":"...", "data": ...} format.
 */
@Serializable
data class StreamEvent(
    val type: String,
    val data: kotlinx.serialization.json.JsonElement? = null
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
