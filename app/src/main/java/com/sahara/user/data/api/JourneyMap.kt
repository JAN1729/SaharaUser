package com.sahara.user.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JourneyMap(
    @SerialName("journey_id") val journeyId: String,
    @SerialName("data_mode") val dataMode: String = "unknown",
    val segments: List<JourneyMapSegment> = emptyList(),
    val pins: List<JourneyMapPin> = emptyList(),
    @SerialName("geometry_complete") val geometryComplete: Boolean = false,
    @SerialName("guidance_complete") val guidanceComplete: Boolean = false,
    val warnings: List<String> = emptyList()
) {
    // Reject whole corrupt segments; filtering individual points could create false connections.
    val renderableSegments get() = segments.filter {
        (it.status == "mapped" || it.isRoadGuidance) && it.polyline.size >= 2 && it.polyline.all(MapCoordinate::isValid)
    }
    val validPins get() = pins.filter { MapCoordinate(it.lat, it.lng).isValid }
    val hasCompleteGeometry get() = geometryComplete && segments.isNotEmpty() &&
        segments.all { it.status == "same_terminal" || (it.status == "mapped" && it in renderableSegments) }
    val hasRoadGuidance get() = renderableSegments.any { it.isRoadGuidance }
    val hasCompleteGuidance get() = guidanceComplete && segments.isNotEmpty() &&
        segments.all { it.status == "same_terminal" || it in renderableSegments }
}

@Serializable
data class MapCoordinate(val lat: Double, val lng: Double) {
    val isValid get() = lat.isFinite() && lng.isFinite() && lat in -85.05112878..85.05112878 && lng in -180.0..180.0
}

@Serializable
data class JourneyMapPin(val id: String, val name: String, val lat: Double, val lng: Double,
    val evidence: String = "approximate_seed_terminal")

@Serializable
data class JourneyMapSegment(
    val status: String,
    val mode: String = "walk",
    val kind: String = "service",
    val provider: String = "",
    @SerialName("service_id") val serviceId: String = "",
    @SerialName("service_name") val serviceName: String = "",
    val evidence: String = "",
    @SerialName("service_path_verified") val servicePathVerified: Boolean? = null,
    @SerialName("distance_m") val distanceM: Int? = null,
    val origin: String = "",
    val destination: String = "",
    val polyline: List<MapCoordinate> = emptyList(),
    val notice: String = "",
    @SerialName("source_url") val sourceUrl: String? = null,
    val revision: String? = null,
    @SerialName("transfer_minutes") val transferMinutes: Int? = null
) {
    val isRoadGuidance get() = status == "road_guidance" && mode == "bus" && provider == "ksrtc" &&
        evidence == "calculated_road_corridor" && servicePathVerified == false
}
