package com.sahara.user.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class TripConfirmationRejected(message: String) : IllegalStateException(message)

@Serializable
data class TripProgress(
    @SerialName("completed_leg_ids") val completedLegIds: List<String> = emptyList(),
    @SerialName("onboard_leg_id") val onboardLegId: String? = null,
    @SerialName("repair_progress") val repairProgress: Boolean = false
)

@Serializable
data class JourneyGuidance(
    @SerialName("journey_id") val journeyId: String,
    @SerialName("data_mode") val dataMode: String = "unknown",
    val status: String = "unverified",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("current_leg_index") val currentLegIndex: Int = 0,
    val boarding: BoardingGuidance? = null,
    @SerialName("next_connection") val nextConnection: ConnectionGuidance? = null,
    val progress: TripProgress = TripProgress(),
    @SerialName("nearby_fleet") val nearbyFleet: NearbyFleet = NearbyFleet(),
    val alerts: List<String> = emptyList(),
    val alternatives: List<JourneyOption> = emptyList(),
    val revalidation: TripRevalidation? = null
)

@Serializable
data class BoardingGuidance(
    val name: String = "",
    val precision: String = "approximate",
    val instructions: String = "",
    val departure: String? = null,
    @SerialName("leg_id") val legId: String? = null
)

@Serializable
data class ConnectionGuidance(
    val status: String = "unverified",
    @SerialName("can_make") val canMake: Boolean = false,
    @SerialName("verified_arrival") val verifiedArrival: Boolean = false,
    @SerialName("spare_minutes") val spareMinutes: Int? = null,
    val message: String = ""
)

@Serializable
data class GuidanceAlert(val message: String = "", val severity: String = "info")

@Serializable
data class TripRevalidation(
    @SerialName("original_valid") val originalValid: Boolean? = null,
    @SerialName("checked_at") val checkedAt: String? = null,
    val message: String = ""
)

@Serializable
data class NearbyFleet(val status: String = "unconfigured", val notice: String = "", val vehicles: List<FleetVehicle> = emptyList())

@Serializable
data class FleetVehicle(
    @SerialName("vehicle_id") val vehicleId: String,
    val name: String = "Vehicle",
    val lat: Double,
    val lng: Double,
    @SerialName("observed_at") val observedAt: String,
    @SerialName("distance_m") val distanceM: Int = 0
) {
    fun isFresh(now: java.time.Instant = java.time.Instant.now()): Boolean = runCatching {
        val age = java.time.Duration.between(java.time.OffsetDateTime.parse(observedAt).toInstant(), now).seconds
        lat.isFinite() && lng.isFinite() && lat in -85.05112878..85.05112878 && lng in -180.0..180.0 && age in -15..90
    }.getOrDefault(false)
}
