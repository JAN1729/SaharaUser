package com.sahara.user.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahara.user.data.api.SupabaseApiClient
import com.sahara.user.data.model.User
import com.sahara.user.data.repository.AgentRepository
import com.sahara.user.service.LocationService
import com.sahara.user.ui.screens.ChatMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val locationService: LocationService
) : ViewModel() {
    
    companion object {
        private const val TAG = "ChatViewModel"
        private const val TEMP_USER_ID = "USR-TEMPUSER"
    }
    
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
            
            // Show loading
            _isLoading.value = true
            _statusMessage.value = "Processing with AI agents..."
            _activeAgents.value = listOf("DataAgent", "PathAgent", "TimeAgent")
            
            // Initialize thinking steps for UI
            _thinkingSteps.value = listOf(
                ThinkingStep("Parsing your request", isComplete = false, isActive = true),
                ThinkingStep("Fetching bus data", isComplete = false, isActive = false),
                ThinkingStep("Calculating routes", isComplete = false, isActive = false),
                ThinkingStep("Preparing response", isComplete = false, isActive = false)
            )
            _agentStatus.value = AgentStatus("DataAgent", "Initializing...", isActive = true)
            _agentPipeline.value = listOf(
                AgentPipelineStep("Data", "active"),
                AgentPipelineStep("Path", "pending"),
                AgentPipelineStep("Time", "pending")
            )
            
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
                
                // Call Backend Edge Function via AgentRepository
                val result = agentRepository.processQuery(
                    userId = userId,
                    query = content,
                    userLat = location?.latitude,
                    userLng = location?.longitude
                )
                
                result.onSuccess { response ->
                    // Update status with agents called
                    _lastAgentsCalled.value = response.agentTrace.map { it.message }
                    _statusMessage.value = null
                    
                    // Add AI response
                    val aiMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        content = response.response,
                        isUser = false,
                        metadata = mapOf(
                            "intent" to response.intent,
                            "execution_time_ms" to response.executionTimeMs.toString(),
                            "success" to response.success.toString()
                        )
                    )
                    _messages.value = _messages.value + aiMessage
                    
                    // Log for debugging
                    android.util.Log.d(TAG, "Query: $content")
                    android.util.Log.d(TAG, "Intent: ${response.intent}")
                    android.util.Log.d(TAG, "Response: ${response.response}")
                    android.util.Log.d(TAG, "Execution time: ${response.executionTimeMs}ms")
                    
                }.onFailure { error ->
                    android.util.Log.e(TAG, "API Error: ${error.message}", error)
                    
                    _statusMessage.value = null
                    
                    val errorMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        content = "Sorry, couldn't connect to the server. Please try again.",
                        isUser = false
                    )
                    _messages.value = _messages.value + errorMessage
                }
                
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error: ${e.message}", e)
                
                _statusMessage.value = null
                
                val errorMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    content = "Sorry, something went wrong. Please try again.",
                    isUser = false
                )
                _messages.value = _messages.value + errorMessage
                
            } finally {
                _isLoading.value = false
                _statusMessage.value = null
                _activeAgents.value = emptyList()
                _thinkingSteps.value = emptyList()
                _agentStatus.value = null
                _agentPipeline.value = emptyList()
            }
        }
    }
    
    // ==================== UI ACTIONS ====================
    
    fun clearChat() {
        _messages.value = emptyList()
        _statusMessage.value = null
        _activeAgents.value = emptyList()
        _lastAgentsCalled.value = emptyList()
        _thinkingSteps.value = emptyList()
        _agentStatus.value = null
        _agentPipeline.value = emptyList()
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

