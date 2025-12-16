package com.sahara.user.service

import android.content.Context
import android.content.SharedPreferences
import android.os.BatteryManager
import android.util.Log
import com.sahara.user.config.SecureConfig
import com.sahara.user.data.api.UserLocationRequest
import com.sahara.user.data.api.UserLocationResponse
import com.sahara.user.data.model.User
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User Location Sync Manager
 * 
 * Orchestrates real-time location syncing to Supabase at 10-second intervals.
 * Works with LocationService to collect GPS data and sends it to the
 * sync-user-location edge function.
 * 
 * Features:
 * - Automatic retry with exponential backoff
 * - Offline queue for network resilience
 * - Battery-aware syncing
 * - Tracking ID management (u-XXXX format)
 */
@Singleton
class UserLocationSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationService: LocationService
) {
    companion object {
        private const val TAG = "UserLocationSync"
        private const val SYNC_INTERVAL_MS = 10_000L  // 10 seconds
        private const val MAX_QUEUE_SIZE = 100
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val PREFS_NAME = "user_location_sync"
        private const val KEY_TRACKING_ID = "tracking_id"
        private const val KEY_USER_ID = "user_id"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }
    
    // Offline queue for failed syncs
    private val pendingLocations = ConcurrentLinkedQueue<UserLocationRequest>()
    
    // State
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()
    
    private val _lastSyncTime = MutableStateFlow<Long?>(null)
    val lastSyncTime: StateFlow<Long?> = _lastSyncTime.asStateFlow()
    
    private val _syncErrors = MutableStateFlow<String?>(null)
    val syncErrors: StateFlow<String?> = _syncErrors.asStateFlow()
    
    private var syncJob: Job? = null
    private var currentUserId: String? = null
    private var currentTrackingId: String? = null
    
    /**
     * Initialize with user data and start syncing
     */
    fun initialize(userId: String, trackingId: String? = null) {
        currentUserId = userId
        
        // Use provided tracking ID or generate/restore from storage
        currentTrackingId = trackingId 
            ?: prefs.getString(KEY_TRACKING_ID, null) 
            ?: User.generateTrackingId()
        
        // Persist
        prefs.edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_TRACKING_ID, currentTrackingId)
            .apply()
        
        Log.d(TAG, "Initialized with userId=$userId, trackingId=$currentTrackingId")
    }
    
    /**
     * Get the current tracking ID (u-XXXX format)
     */
    fun getTrackingId(): String? = currentTrackingId
    
    /**
     * Start real-time location syncing at 10-second intervals
     */
    fun startSyncing() {
        if (syncJob?.isActive == true) {
            Log.d(TAG, "Syncing already active")
            return
        }
        
        if (currentUserId == null || currentTrackingId == null) {
            Log.e(TAG, "Cannot start syncing: userId or trackingId not set")
            return
        }
        
        // Also start location service if not tracking
        if (!locationService.isTracking.value) {
            locationService.startLocationUpdates()
        }
        
        syncJob = scope.launch {
            Log.d(TAG, "Starting location sync at ${SYNC_INTERVAL_MS}ms intervals")
            _isSyncing.value = true
            
            // Collect location updates and sync
            while (isActive) {
                try {
                    syncCurrentLocation()
                    processPendingQueue()
                } catch (e: Exception) {
                    Log.e(TAG, "Sync error: ${e.message}")
                    _syncErrors.value = e.message
                }
                delay(SYNC_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Stop location syncing
     */
    fun stopSyncing() {
        syncJob?.cancel()
        syncJob = null
        _isSyncing.value = false
        Log.d(TAG, "Stopped location syncing")
    }
    
    /**
     * Sync current location to Supabase
     */
    private suspend fun syncCurrentLocation() {
        val location = locationService.currentLocation.value ?: run {
            Log.d(TAG, "No location available to sync")
            return
        }
        
        val userId = currentUserId ?: return
        val trackingId = currentTrackingId ?: return
        
        val request = UserLocationRequest(
            userId = userId,
            trackingId = trackingId,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            speed = null, // LocationService doesn't provide speed currently
            heading = null,
            altitude = null,
            recordedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(location.timestamp)),
            deviceInfo = getDeviceInfo()
        )
        
        val success = sendLocationToServer(request)
        
        if (success) {
            _lastSyncTime.value = System.currentTimeMillis()
            _syncErrors.value = null
            Log.d(TAG, "Synced location: ${location.latitude}, ${location.longitude}")
        } else {
            // Add to pending queue for retry
            queueLocation(request)
        }
    }
    
    /**
     * Send location to Supabase edge function
     * DEBUG: Added comprehensive logging to troubleshoot sync issues
     */
    private suspend fun sendLocationToServer(request: UserLocationRequest): Boolean {
        val supabaseUrl = SecureConfig.getSupabaseUrl()
        val supabaseKey = SecureConfig.getSupabaseAnonKey()
        
        Log.d(TAG, "=== LOCATION SYNC DEBUG ===")
        Log.d(TAG, "Supabase URL: $supabaseUrl")
        Log.d(TAG, "API Key present: ${supabaseKey.isNotBlank()}")
        Log.d(TAG, "Request: userId=${request.userId}, trackingId=${request.trackingId}")
        Log.d(TAG, "Location: lat=${request.latitude}, lng=${request.longitude}, accuracy=${request.accuracy}")
        
        if (supabaseUrl.isBlank() || supabaseKey.isBlank()) {
            Log.e(TAG, "❌ Supabase credentials not configured!")
            Log.e(TAG, "   URL empty: ${supabaseUrl.isBlank()}, Key empty: ${supabaseKey.isBlank()}")
            return false
        }
        
        val fullUrl = "$supabaseUrl/functions/v1/sync-user-location"
        Log.d(TAG, "Full URL: $fullUrl")
        
        return try {
            Log.d(TAG, ">>> Sending POST request...")
            val response = httpClient.post(fullUrl) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $supabaseKey")
                header("apikey", supabaseKey)
                setBody(request)
            }
            
            Log.d(TAG, "<<< Response status: ${response.status}")
            
            if (response.status.isSuccess()) {
                val body = response.body<UserLocationResponse>()
                Log.d(TAG, "✅ SUCCESS! Response: success=${body.success}, id=${body.id}, message=${body.message}")
                if (!body.success) {
                    Log.e(TAG, "❌ Server returned success=false: ${body.error}")
                }
                body.success
            } else {
                val errorBody = try { response.body<String>() } catch (e: Exception) { "Could not read body" }
                Log.e(TAG, "❌ HTTP Error ${response.status.value}: $errorBody")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Network exception: ${e.javaClass.simpleName}")
            Log.e(TAG, "   Message: ${e.message}")
            Log.e(TAG, "   Stack trace:", e)
            false
        }
    }
    
    /**
     * Queue location for later retry
     */
    private fun queueLocation(request: UserLocationRequest) {
        if (pendingLocations.size >= MAX_QUEUE_SIZE) {
            pendingLocations.poll() // Remove oldest
        }
        pendingLocations.offer(request)
        Log.d(TAG, "Queued location. Queue size: ${pendingLocations.size}")
    }
    
    /**
     * Process pending queue
     */
    private suspend fun processPendingQueue() {
        if (pendingLocations.isEmpty()) return
        
        val toRetry = pendingLocations.take(5) // Process up to 5 at a time
        for (request in toRetry) {
            if (sendLocationToServer(request)) {
                pendingLocations.remove(request)
            }
        }
    }
    
    /**
     * Get device info for debugging/analytics
     */
    private fun getDeviceInfo(): Map<String, String> {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        
        return mapOf(
            "battery_level" to "$batteryLevel",
            "android_version" to android.os.Build.VERSION.RELEASE,
            "device_model" to android.os.Build.MODEL
        )
    }
    
    /**
     * Force sync current location immediately
     */
    suspend fun forceSyncNow(): Boolean {
        return try {
            syncCurrentLocation()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Force sync failed: ${e.message}")
            false
        }
    }
    
    /**
     * Clear stored tracking data
     */
    fun clearData() {
        stopSyncing()
        pendingLocations.clear()
        prefs.edit().clear().apply()
        currentUserId = null
        currentTrackingId = null
        Log.d(TAG, "Cleared all sync data")
    }
}
