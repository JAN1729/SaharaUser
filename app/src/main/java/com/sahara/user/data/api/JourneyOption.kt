package com.sahara.user.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JourneyOption(
    val id: String,
    val departure: String,
    val arrival: String,
    @SerialName("duration_minutes") val durationMinutes: Int = 0,
    @SerialName("total_fare_minor") val totalFareMinor: Long? = null,
    @SerialName("known_fare_minor") val knownFareMinor: Long = 0,
    val currency: String = "INR",
    val legs: List<JourneyLeg> = emptyList(),
    val connections: List<JourneyConnection> = emptyList(),
    val warnings: List<String> = emptyList(),
    val recommendations: List<String> = emptyList()
) {
    val origin get() = connections.firstOrNull { it.kind == "access" }?.origin?.takeIf { it.isNotBlank() }
        ?: legs.firstOrNull()?.origin.orEmpty()
    val destination get() = connections.lastOrNull { it.kind == "egress" }?.destination?.takeIf { it.isNotBlank() }
        ?: legs.lastOrNull()?.destination.orEmpty()
}

@Serializable
data class JourneyLeg(
    val name: String,
    val mode: String,
    val origin: String,
    val destination: String,
    val departure: String,
    val arrival: String,
    @SerialName("estimated_departure") val estimatedDeparture: String? = null,
    @SerialName("estimated_arrival") val estimatedArrival: String? = null,
    val availability: String = "unknown",
    val seats: Int? = null,
    @SerialName("fare_minor") val fareMinor: Long? = null,
    @SerialName("fare_basis") val fareBasis: String = "per_person",
    val currency: String = "INR",
    @SerialName("fare_class") val fareClass: String? = null,
    @SerialName("booking_url") val bookingUrl: String? = null,
    val observation: SourceObservation,
    val id: String = "",
    @SerialName("vehicle_id") val vehicleId: String? = null
)

@Serializable
data class SourceObservation(
    val provider: String,
    val evidence: String,
    @SerialName("fetched_at") val fetchedAt: String,
    @SerialName("observed_at") val observedAt: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null
)

@Serializable
data class JourneyConnection(
    val kind: String = "transfer",
    val origin: String = "",
    val destination: String = "",
    val transfer: JourneyTransfer? = null,
    @SerialName("transfer_minutes") val transferMinutes: Int = 0,
    @SerialName("processing_minutes") val processingMinutes: Int = 0,
    @SerialName("buffer_minutes") val bufferMinutes: Int = 0,
    @SerialName("spare_minutes") val spareMinutes: Int = 0
)

@Serializable
data class JourneyTransfer(
    val mode: String = "walk",
    @SerialName("fare_minor") val fareMinor: Long? = null,
    val currency: String = "INR",
    val evidence: String = "estimated"
)

@Serializable
data class SourceStatus(val provider: String, val status: String, val message: String = "", val cached: Boolean = false)
