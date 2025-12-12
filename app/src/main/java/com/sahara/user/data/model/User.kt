package com.sahara.user.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * User Model
 * 
 * Represents an end user (passenger) in the SaharaUser app.
 * Stored in Supabase 'users' table.
 */
@Serializable
data class User(
    val id: String,                                      // USR-XXXXXXXX
    val phone: String,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    val email: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("preferred_language") val preferredLanguage: String = "en",
    @SerialName("preferred_route_type") val preferredRouteType: String = "fastest",
    @SerialName("accessibility_needs") val accessibilityNeeds: String? = null,
    @SerialName("home_location") val homeLocation: SavedLocation? = null,
    @SerialName("work_location") val workLocation: SavedLocation? = null,
    @SerialName("total_trips") val totalTrips: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("last_active") val lastActive: String? = null
) {
    companion object {
        /**
         * Generate a unique user ID in format USR-XXXXXXXX
         */
        fun generateUserId(): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val randomPart = (1..8).map { chars.random() }.joinToString("")
            return "USR-$randomPart"
        }
    }
    
    /**
     * Get display name
     */
    fun getDisplayName(): String {
        return when {
            !firstName.isNullOrBlank() && !lastName.isNullOrBlank() -> "$firstName $lastName"
            !firstName.isNullOrBlank() -> firstName
            else -> "User"
        }
    }
}

/**
 * Saved Location (Home, Work, or custom)
 */
@Serializable
data class SavedLocation(
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String? = null
)

/**
 * User Trip
 * 
 * Represents a trip taken or planned by a user.
 */
@Serializable
data class UserTrip(
    val id: String,                                      // TRIP-XXXXXXXX
    @SerialName("user_id") val userId: String,
    @SerialName("origin_name") val originName: String,
    @SerialName("origin_lat") val originLat: Double,
    @SerialName("origin_lng") val originLng: Double,
    @SerialName("destination_name") val destinationName: String,
    @SerialName("destination_lat") val destinationLat: Double,
    @SerialName("destination_lng") val destinationLng: Double,
    @SerialName("route_data") val routeData: String? = null,
    @SerialName("vehicles_used") val vehiclesUsed: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("estimated_duration_min") val estimatedDurationMin: Int? = null,
    @SerialName("actual_duration_min") val actualDurationMin: Int? = null,
    val status: String = "planned",                      // planned, in_progress, completed, cancelled
    @SerialName("created_at") val createdAt: String? = null
) {
    companion object {
        /**
         * Generate a unique trip ID in format TRIP-XXXXXXXX
         */
        fun generateTripId(): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val randomPart = (1..8).map { chars.random() }.joinToString("")
            return "TRIP-$randomPart"
        }
    }
}

/**
 * User Search History
 */
@Serializable
data class UserSearchHistory(
    val id: Int? = null,
    @SerialName("user_id") val userId: String,
    val query: String,
    @SerialName("origin_name") val originName: String? = null,
    @SerialName("destination_name") val destinationName: String? = null,
    @SerialName("searched_at") val searchedAt: String? = null
)
