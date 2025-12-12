package com.sahara.user.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Location Service - Provides user's REAL-TIME GPS location
 * 
 * Used by:
 * - ChatViewModel for sending location with queries
 * - Backend UserContextAgent for proximity-based analysis
 * 
 * Supports both one-shot and continuous location updates.
 */
@Singleton
class LocationService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "LocationService"
        private const val LOCATION_UPDATE_INTERVAL = 10000L  // 10 seconds
        private const val LOCATION_FASTEST_INTERVAL = 5000L  // 5 seconds minimum
    }
    
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    
    private val _currentLocation = MutableStateFlow<UserLocation?>(null)
    val currentLocation: StateFlow<UserLocation?> = _currentLocation.asStateFlow()
    
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()
    
    private var locationCallback: LocationCallback? = null
    
    /**
     * Check if location permission is granted
     */
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Get current location (one-shot) - Uses cached if available, otherwise fresh
     */
    suspend fun getCurrentLocation(): UserLocation? {
        // If we have a recent location (< 30 seconds), return it
        val cached = _currentLocation.value
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < 30000) {
            android.util.Log.d(TAG, "Using cached location (age: ${System.currentTimeMillis() - cached.timestamp}ms)")
            return cached
        }
        
        // Otherwise get fresh location
        return getFreshLocation()
    }
    
    /**
     * Get fresh location (always requests new GPS fix)
     * Use this for critical operations that need accurate, current position
     */
    suspend fun getFreshLocation(): UserLocation? {
        if (!hasLocationPermission()) {
            android.util.Log.w(TAG, "Location permission not granted")
            return null
        }
        
        return try {
            val location = requestNewLocation()
            location?.let {
                val userLocation = UserLocation(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    accuracy = it.accuracy,
                    timestamp = System.currentTimeMillis()
                )
                _currentLocation.value = userLocation
                android.util.Log.d(TAG, "Fresh location: ${it.latitude}, ${it.longitude} (accuracy: ${it.accuracy}m)")
                userLocation
            }
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "Security exception: ${e.message}")
            null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error getting location: ${e.message}")
            null
        }
    }
    
    @Suppress("MissingPermission")
    private suspend fun requestNewLocation(): Location? = suspendCancellableCoroutine { cont ->
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                cont.resume(location)
            }
            .addOnFailureListener { e ->
                android.util.Log.e(TAG, "Failed to get location: ${e.message}")
                cont.resume(null)
            }
    }
    
    /**
     * Start continuous location updates
     * Call this when user opens the app for real-time tracking
     */
    @Suppress("MissingPermission")
    fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            android.util.Log.w(TAG, "Cannot start updates: no permission")
            return
        }
        
        if (_isTracking.value) {
            android.util.Log.d(TAG, "Already tracking location")
            return
        }
        
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL)
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL)
            .build()
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val userLocation = UserLocation(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        timestamp = System.currentTimeMillis()
                    )
                    _currentLocation.value = userLocation
                    android.util.Log.d(TAG, "Location update: ${location.latitude}, ${location.longitude}")
                }
            }
        }
        
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            Looper.getMainLooper()
        )
        
        _isTracking.value = true
        android.util.Log.d(TAG, "Started continuous location updates (${LOCATION_UPDATE_INTERVAL}ms interval)")
    }
    
    /**
     * Stop continuous location updates
     * Call this when user leaves the app
     */
    fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
        }
        _isTracking.value = false
        android.util.Log.d(TAG, "Stopped location updates")
    }
    
    /**
     * Clear cached location
     */
    fun clearLocation() {
        _currentLocation.value = null
    }
}

/**
 * User's current location with metadata
 */
data class UserLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Check if this location is recent (default: within 60 seconds)
     */
    fun isFresh(maxAgeMs: Long = 60000): Boolean {
        return System.currentTimeMillis() - timestamp < maxAgeMs
    }
}
