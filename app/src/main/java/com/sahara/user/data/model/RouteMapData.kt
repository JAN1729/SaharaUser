package com.sahara.user.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Route Map Data - Received from backend when AI decides map visualization helps.
 * 
 * This is the data structure for the "show_route_map" tool call that the AI
 * invokes when analyzing route queries. Maps are ephemeral - they vanish when
 * the user sends the next message.
 */
@Serializable
data class RouteMapData(
    @SerialName("vehicleName") val vehicleName: String,
    @SerialName("vehicleId") val vehicleId: String,
    @SerialName("stopsToMark") val stopsToMark: List<MapStopCoordinate>,
    // Route polyline - ordered coordinates for drawing the bus route line
    @SerialName("routePolyline") val routePolyline: List<LatLngData> = emptyList(),
    @SerialName("userLocation") val userLocation: LatLngData? = null,
    @SerialName("busLocation") val busLocation: MapBusLocation? = null,
    @SerialName("walkingPath") val walkingPath: List<LatLngData>? = null,
    @SerialName("walkingDistanceM") val walkingDistanceM: Int? = null,
    @SerialName("walkingTimeMin") val walkingTimeMin: Int? = null,
    @SerialName("mapBounds") val mapBounds: MapBounds,
    @SerialName("etaMinutes") val etaMinutes: Int,
    @SerialName("distanceKm") val distanceKm: Double,
    // Optional GeoJSON for advanced rendering (from get-map-data Edge Function)
    @SerialName("geoJSON") val geoJSON: kotlinx.serialization.json.JsonElement? = null
)

/**
 * Stop coordinate for map marking
 */
@Serializable
data class MapStopCoordinate(
    val name: String,
    val lat: Double,
    val lng: Double,
    val order: Int,
    @SerialName("isOrigin") val isOrigin: Boolean = false,
    @SerialName("isDestination") val isDestination: Boolean = false
)

/**
 * Bus location marker with optional heading for icon rotation
 */
@Serializable
data class MapBusLocation(
    val lat: Double,
    val lng: Double,
    val heading: Double? = null,
    @SerialName("isMoving") val isMoving: Boolean? = null
)

/**
 * Map bounding box for auto-zoom
 */
@Serializable
data class MapBounds(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double
)

/**
 * Simple lat/lng data class
 */
@Serializable
data class LatLngData(
    val lat: Double,
    val lng: Double
)

/**
 * Agent tool call structure - Received from backend when AI invokes a visualization tool.
 * The AI decides when to show these based on query analysis.
 */
@Serializable
data class AgentToolCall(
    val tool: String, // "show_route_map", "show_bus_location", "show_nearby_stops"
    val data: kotlinx.serialization.json.JsonElement? = null
)
