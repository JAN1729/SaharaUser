package com.sahara.user.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahara.user.data.api.SupabaseApiClient
import com.sahara.user.data.model.RouteMapData
import com.sahara.user.data.model.ViewportState
import com.sahara.user.data.model.BoundingBox
import com.sahara.user.data.model.MapCommand
import com.sahara.user.data.model.LatLngData
import com.sahara.user.data.model.User
import com.sahara.user.data.repository.AgentRepository
import com.sahara.user.service.LocationService
import com.sahara.user.service.UserLocationSyncManager
import com.sahara.user.ui.screens.ChatMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

/**
 * Chat ViewModel - Multi-Agent Backend Integration
 * 
 * KEY PRINCIPLE:
 * - Edge Functions handle all AI agent orchestration
 * - This ViewModel is a thin UI layer
 * - Algorithms calculate on backend, Agents analyze on backend
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val supabaseClient: SupabaseApiClient,
    private val locationService: LocationService,
    private val userLocationSyncManager: UserLocationSyncManager,
    private val realtimeBusManager: com.sahara.user.service.RealtimeBusManager
) : ViewModel() {
    
    companion object {
        private const val TAG = "ChatViewModel"
        private const val TEMP_USER_ID = "USR-TEMPUSER"
    }
    
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    
    // ==================== UI STATE ====================
    
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()
    
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()
    
    private val _hasLocationPermission = MutableStateFlow(false)
    val hasLocationPermission: StateFlow<Boolean> = _hasLocationPermission.asStateFlow()
    
    // ==================== EPHEMERAL MAP TOOL (Agentic Feature) ====================
    // Map visualization that the AI invokes. Ephemeral - clears when user sends new message.
    
    private val _activeMapTool = MutableStateFlow<RouteMapData?>(null)
    val activeMapTool: StateFlow<RouteMapData?> = _activeMapTool.asStateFlow()
    
    /** Clear the active map tool (called on new message or dismiss) */
    fun clearMapTool() {
        _activeMapTool.value = null
        stopRealtimeTracking()
    }
    
    // ==================== MAP CONTEXT (Spatial Awareness - Map-Action Pipeline) ====================
    // Viewport state captures what the user currently sees on the map.
    // Sent with every query to enable spatial-aware AI responses.
    
    private val _viewportState = MutableStateFlow<ViewportState?>(null)
    val viewportState: StateFlow<ViewportState?> = _viewportState.asStateFlow()
    
    private val _pendingMapCommands = MutableStateFlow<List<MapCommand>>(emptyList())
    val pendingMapCommands: StateFlow<List<MapCommand>> = _pendingMapCommands.asStateFlow()
    
    /**
     * Update viewport state when user pans/zooms the map.
     * Call this from the map view's camera change listener.
     */
    fun updateViewportState(north: Double, south: Double, east: Double, west: Double, zoom: Float) {
        val bounds = BoundingBox(north, south, east, west)
        val center = bounds.center()
        _viewportState.value = ViewportState(
            boundingBox = bounds,
            zoomLevel = zoom,
            centerLat = center.first,
            centerLng = center.second
        )
        android.util.Log.d(TAG, "ViewportState updated: N=$north, S=$south, E=$east, W=$west, zoom=$zoom")
    }
    
    /** Clear pending map commands after execution */
    fun clearMapCommands() {
        _pendingMapCommands.value = emptyList()
    }
    
    // ==================== REAL-TIME BUS TRACKING ====================
    
    private var realtimeJob: kotlinx.coroutines.Job? = null
    
    /**
     * Start listening for real-time movement updates for a specific bus.
     * Updates the activeMapTool state reactively.
     */
    private fun startRealtimeTracking(busId: String) {
        realtimeJob?.cancel()
        realtimeJob = viewModelScope.launch {
            android.util.Log.d(TAG, "Starting real-time tracking for bus: $busId")
            realtimeBusManager.listenForMovements(busId).collect { update ->
                // Only update if it matches our bus_id (RealtimeBusManager might broadcast for all)
                if (update.bus_id == busId) {
                    _activeMapTool.value = _activeMapTool.value?.copy(
                        busLocation = com.sahara.user.data.model.MapBusLocation(
                            lat = update.latitude,
                            lng = update.longitude,
                            heading = update.heading.toDouble(),
                            isMoving = true
                        )
                    )
                }
            }
        }
    }
    
    private fun stopRealtimeTracking() {
        realtimeJob?.cancel()
        realtimeJob = null
    }
    
    // ==================== THINKING STEPS (for UI animation) ====================
    
    private val _thinkingSteps = MutableStateFlow<List<ThinkingStep>>(emptyList())
    val thinkingSteps: StateFlow<List<ThinkingStep>> = _thinkingSteps.asStateFlow()
    
    private val _agentStatus = MutableStateFlow<AgentStatus?>(null)
    val agentStatus: StateFlow<AgentStatus?> = _agentStatus.asStateFlow()
    
    private val _agentPipeline = MutableStateFlow<List<AgentPipelineStep>>(emptyList())
    val agentPipeline: StateFlow<List<AgentPipelineStep>> = _agentPipeline.asStateFlow()
    
    // ==================== AGENT STATUS ====================
    
    private val _activeAgents = MutableStateFlow<List<String>>(emptyList())
    val activeAgents: StateFlow<List<String>> = _activeAgents.asStateFlow()
    
    private val _lastAgentsCalled = MutableStateFlow<List<String>>(emptyList())
    val lastAgentsCalled: StateFlow<List<String>> = _lastAgentsCalled.asStateFlow()
    
    // ==================== INITIALIZATION ====================
    
    init {
        viewModelScope.launch {
            initializeUser()
            checkLocationPermission()
        }
    }
    
    private suspend fun initializeUser() {
        val result = supabaseClient.getOrCreateUser("0000000000")
        result.onSuccess { user ->
            _currentUser.value = user
            android.util.Log.d(TAG, "User initialized: ${user.id}")
            
            // Initialize location sync with user ID
            userLocationSyncManager.initialize(
                userId = user.id,
                trackingId = user.trackingId
            )
            android.util.Log.d(TAG, "Location sync initialized with trackingId: ${userLocationSyncManager.getTrackingId()}")
            
            // If location permission is already granted, start syncing immediately
            if (locationService.hasLocationPermission()) {
                locationService.startLocationUpdates()
                userLocationSyncManager.startSyncing()
                _hasLocationPermission.value = true
                android.util.Log.d(TAG, "Location permission already granted, started sync automatically")
            }
        }.onFailure { error ->
            android.util.Log.e(TAG, "Failed to initialize user: ${error.message}")
        }
    }
    
    private fun checkLocationPermission() {
        _hasLocationPermission.value = locationService.hasLocationPermission()
    }
    
    fun onLocationPermissionGranted() {
        _hasLocationPermission.value = true
        locationService.startLocationUpdates()
        
        // Start real-time location syncing to Supabase (every 10 seconds)
        if (_currentUser.value != null) {
            userLocationSyncManager.startSyncing()
            android.util.Log.d(TAG, "Started real-time location sync to Supabase")
        }
        android.util.Log.d(TAG, "Location permission granted, starting updates")
    }

    // Expose continuous location updates for Map UI
    val userLocation = locationService.currentLocation
    
    // ==================== MESSAGE HANDLING ====================
    
    /**
     * Send message to Backend Edge Functions
     * 
     * FLOW:
     * 1. Send query to Supabase Edge Function (process-query)
     * 2. Backend orchestrates agents: DataAgent → PathAgent → TimeAgent
     * 3. Backend algorithms calculate: Haversine, ETA, RouteMatcher
     * 4. Receive SHORT, data-grounded response
     */
    fun sendMessage(content: String) {
        if (content.isBlank()) return
        
        viewModelScope.launch {
            // Add user message
            val userMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                content = content,
                isUser = true
            )
            _messages.value = _messages.value + userMessage
            
            // EPHEMERAL: Clear previous map visualization on new query
            _activeMapTool.value = null
            
            // Show loading with empty thinking steps (will be filled by stream)
            _isLoading.value = true
            _statusMessage.value = "Processing..."
            _thinkingSteps.value = listOf(ThinkingStep("Connecting to AI...", isActive = true))
            
            // Track thinking duration
            val thinkingStartTime = System.currentTimeMillis()
            
            try {
                val userId = _currentUser.value?.id ?: TEMP_USER_ID
                
                // Get current location if available
                val location = try {
                    if (_hasLocationPermission.value) {
                        locationService.getCurrentLocation()
                    } else null
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Could not get location: ${e.message}")
                    null
                }
                
                // Use STREAMING API for real-time reasoning display
                var finalResponse: com.sahara.user.data.api.ProcessQueryResponse? = null
                val reasoningSteps = mutableListOf<String>()
                
                android.util.Log.d(TAG, ">>> Starting stream collection...")
                
                agentRepository.processQueryStreaming(
                    userId = userId,
                    query = content,
                    userLat = location?.latitude,
                    userLng = location?.longitude,
                    viewportState = _viewportState.value
                ).collect { event ->
                    android.util.Log.d(TAG, ">>> Received event: ${event::class.simpleName}")
                    when (event) {
                        is com.sahara.user.data.api.AgentApiClient.StreamEvent.Reasoning -> {
                            // Add new reasoning step in real-time
                            android.util.Log.d(TAG, ">>> REASONING EVENT: ${event.text}")
                            reasoningSteps.add(event.text)
                            _thinkingSteps.value = reasoningSteps.mapIndexed { index, text ->
                                ThinkingStep(
                                    description = text,
                                    isComplete = index < reasoningSteps.size - 1,
                                    isActive = index == reasoningSteps.size - 1
                                )
                            }
                            android.util.Log.d(TAG, ">>> Updated thinkingSteps: ${_thinkingSteps.value.size} items")
                        }
                        is com.sahara.user.data.api.AgentApiClient.StreamEvent.Thinking -> {
                            // Show thinking status
                            android.util.Log.d(TAG, ">>> THINKING EVENT: ${event.text}")
                            _thinkingSteps.value = listOf(
                                ThinkingStep(event.text, isActive = true)
                            )
                        }
                        is com.sahara.user.data.api.AgentApiClient.StreamEvent.FinalResponse -> {
                            android.util.Log.d(TAG, ">>> FINAL RESPONSE EVENT received")
                            finalResponse = event.response
                        }
                        is com.sahara.user.data.api.AgentApiClient.StreamEvent.Error -> {
                            android.util.Log.e(TAG, ">>> ERROR EVENT: ${event.message}")
                        }
                    }
                }
                
                android.util.Log.d(TAG, ">>> Stream collection complete. Total reasoning steps: ${reasoningSteps.size}")
                
                // Mark processing complete
                agentRepository.markProcessingComplete(finalResponse)
                
                // Handle final response
                if (finalResponse != null) {
                    val response = finalResponse!!
                    _lastAgentsCalled.value = response.agentTrace.map { it.message }
                    
                    // Add AI response to chat
                    val thinkingDuration = System.currentTimeMillis() - thinkingStartTime
                    val aiMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        content = response.response,
                        isUser = false,
                        metadata = mapOf(
                            "intent" to response.intent,
                            "execution_time_ms" to response.executionTimeMs.toString(),
                            "success" to response.success.toString()
                        ),
                        thinkingSteps = reasoningSteps.toList(),
                        thinkingDurationMs = thinkingDuration
                    )
                    _messages.value = _messages.value + aiMessage
                    
                    // AGENTIC: Set map tool if AI invoked it
                    response.toolCalls?.firstOrNull()?.let { toolCall ->
                        if (toolCall.tool in listOf("show_route_map", "show_bus_location", "show_nearby_stops")) {
                            try {
                                toolCall.data?.let { dataElement ->
                                    val mapData = json.decodeFromJsonElement(
                                        RouteMapData.serializer(),
                                        dataElement
                                    )
                                    _activeMapTool.value = mapData
                                    android.util.Log.d(TAG, ">>> MAP TOOL ACTIVATED: ${toolCall.tool} with ${mapData.stopsToMark.size} stops")
                                    
                                    // Start real-time tracking if we have a vehicle ID
                                    if (mapData.vehicleId.isNotBlank()) {
                                        startRealtimeTracking(mapData.vehicleId)
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.w(TAG, "Failed to parse map tool data: ${e.message}")
                            }
                        }
                    }
                    
                    // MAP-ACTION PIPELINE: Handle AI-driven map commands
                    response.mapCommands?.let { commands ->
                        if (commands.isNotEmpty()) {
                            _pendingMapCommands.value = commands
                            android.util.Log.d(TAG, ">>> MAP COMMANDS RECEIVED: ${commands.size} commands")
                            commands.forEach { cmd ->
                                android.util.Log.d(TAG, "    - ${cmd.action}: ${cmd.message ?: cmd.targetBusId ?: "no target"}")
                            }
                        }
                    }
                    
                    android.util.Log.d(TAG, "Query: $content")
                    android.util.Log.d(TAG, "Intent: ${response.intent}")
                    android.util.Log.d(TAG, "Response: ${response.response.take(100)}...")
                } else {
                    // No response received
                    val errorMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        content = "Sorry, couldn't get a response. Please try again.",
                        isUser = false
                    )
                    _messages.value = _messages.value + errorMessage
                }
                
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error: ${e.message}", e)
                
                val errorMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    content = "Sorry, something went wrong. Please try again.",
                    isUser = false
                )
                _messages.value = _messages.value + errorMessage
                
            } finally {
                _isLoading.value = false
                _statusMessage.value = null
                _thinkingSteps.value = emptyList()
            }
        }
    }

    // ==================== UI ACTIONS ====================
    
    override fun onCleared() {
        super.onCleared()
        userLocationSyncManager.stopSyncing()
        locationService.stopLocationUpdates()
        android.util.Log.d(TAG, "ViewModel cleared, stopped location sync")
    }
    
    fun clearChat() {
        _messages.value = emptyList()
        _statusMessage.value = null
        _activeAgents.value = emptyList()
        _lastAgentsCalled.value = emptyList()
        _thinkingSteps.value = emptyList()
        _agentStatus.value = null
        _agentPipeline.value = emptyList()
        _activeMapTool.value = null  // Clear ephemeral map
    }
    
    suspend fun getCurrentLocation() = locationService.getCurrentLocation()
    
    fun trackBus(busName: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "Tracking $busName..."
            
            try {
                val result = agentRepository.trackBus(busName = busName)
                
                result.onSuccess { response ->
                    val message = if (response.success) {
                        "📍 **${response.busName}** is ${if (response.isMoving) "moving" else "stopped"} near **${response.nearestStop}**"
                    } else {
                        "Could not find bus $busName"
                    }
                    
                    val chatMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        content = message,
                        isUser = false
                    )
                    _messages.value = _messages.value + chatMessage
                    
                }.onFailure { error ->
                    val chatMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        content = "Could not track bus: ${error.message}",
                        isUser = false
                    )
                    _messages.value = _messages.value + chatMessage
                }
                
            } finally {
                _isLoading.value = false
                _statusMessage.value = null
            }
        }
    }
    
    fun findNearbyStops() {
        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "Finding nearby stops..."
            
            try {
                val location = locationService.getCurrentLocation()
                if (location == null) {
                    val chatMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        content = "📍 Could not get your location. Please enable location services.",
                        isUser = false
                    )
                    _messages.value = _messages.value + chatMessage
                    return@launch
                }
                
                val result = agentRepository.getNearbyStops(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    radiusKm = 1.0
                )
                
                result.onSuccess { response ->
                    val message = if (response.stops.isNotEmpty()) {
                        val stopNames = response.stops.take(3).joinToString(", ") { it.name }
                        "📍 Nearby stops: $stopNames" + if (response.count > 3) " (+${response.count - 3} more)" else ""
                    } else {
                        "No bus stops found within 1km of your location."
                    }
                    
                    val chatMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        content = message,
                        isUser = false
                    )
                    _messages.value = _messages.value + chatMessage
                    
                }.onFailure { error ->
                    val chatMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        content = "Could not find nearby stops: ${error.message}",
                        isUser = false
                    )
                    _messages.value = _messages.value + chatMessage
                }
                
            } finally {
                _isLoading.value = false
                _statusMessage.value = null
            }
        }
    }
}

// ==================== DATA CLASSES FOR UI ====================

/**
 * Represents a thinking step shown during processing
 */
data class ThinkingStep(
    val description: String,
    val isComplete: Boolean = false,
    val isActive: Boolean = false
)

/**
 * Represents current agent status
 */
data class AgentStatus(
    val agentName: String,
    val status: String,
    val isActive: Boolean = true
)

/**
 * Represents a step in the agent pipeline
 */
data class AgentPipelineStep(
    val agentName: String,
    val status: String  // "pending", "active", "complete", "error"
)

