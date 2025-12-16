package com.sahara.user.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request payload for syncing user location to Supabase
 * 
 * Sent to sync-user-location edge function at 10-second intervals
 */
@Serializable
data class UserLocationRequest(
    @SerialName("user_id") val userId: String,
    @SerialName("tracking_id") val trackingId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null,
    val speed: Float? = null,
    val heading: Float? = null,
    val altitude: Float? = null,
    @SerialName("recorded_at") val recordedAt: String? = null,
    @SerialName("device_info") val deviceInfo: Map<String, String>? = null
)

/**
 * Response from sync-user-location edge function
 */
@Serializable
data class UserLocationResponse(
    val success: Boolean,
    val id: String? = null,
    @SerialName("recorded_at") val recordedAt: String? = null,
    val message: String? = null,
    val error: String? = null
)
