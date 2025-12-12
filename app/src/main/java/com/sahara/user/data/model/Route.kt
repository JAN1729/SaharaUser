package com.sahara.user.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Route Models
 * 
 * Data classes for route search results and bus information.
 * These read from the shared Sahara Supabase backend.
 */

/**
 * Route Option - A possible route from origin to destination
 */
@Serializable
data class RouteOption(
    val id: String,
    val vehicles: List<VehicleOnRoute>,
    val totalDistanceKm: Double,
    val estimatedTimeMin: Int,
    val transferCount: Int,
    val stops: List<RouteStopInfo>,
    val score: Double = 0.0                              // Ranking score from AI
)

/**
 * Vehicle on a route
 */
@Serializable
data class VehicleOnRoute(
    val vehicleId: String,
    val vehicleName: String,
    val vehicleNumber: String,
    val boardingStop: String,
    val alightingStop: String,
    val stopsCount: Int,
    val estimatedTimeMin: Int
)

/**
 * Stop information in a route
 */
@Serializable
data class RouteStopInfo(
    val stopId: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val order: Int,
    val isBoarding: Boolean = false,
    val isAlighting: Boolean = false,
    val isTransfer: Boolean = false,
    val etaMinutes: Int? = null
)

/**
 * Vehicle DTO - Read from Supabase vehicles table
 */
@Serializable
data class VehicleDTO(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    val name: String,
    @SerialName("vehicle_number") val vehicleNumber: String,
    @SerialName("is_active") val isActive: Boolean = true
)

/**
 * Route Stop DTO - Read from Supabase route_stops table
 */
@Serializable
data class RouteStopDTO(
    val id: String,
    @SerialName("vehicle_id") val vehicleId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("stop_order") val stopOrder: Int
)

/**
 * Live Bus Location - Read from Supabase locations table
 */
@Serializable
data class LiveBusLocation(
    @SerialName("bus_id") val busId: String,
    val latitude: Double,
    val longitude: Double,
    val heading: Float,
    val speed: Float,
    val timestamp: Long,
    @SerialName("trip_status") val tripStatus: String? = null
)
