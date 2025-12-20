package com.sahara.user.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Map command sent by the AI to control the map display.
 * The AI can emit these commands to highlight, zoom, or annotate the map.
 * 
 * Part of the Map-Action Pipeline - Phase 3: Action Protocol
 */
@Serializable
data class MapCommand(
    @SerialName("action") val action: MapAction,
    @SerialName("target_bus_id") val targetBusId: String? = null,
    @SerialName("target_stop_id") val targetStopId: String? = null,
    @SerialName("coordinates") val coordinates: LatLngData? = null,
    @SerialName("geoJSON") val geoJSON: JsonElement? = null,
    @SerialName("reasoning_color") val reasoningColor: String? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("zoom_level") val zoomLevel: Float? = null
)

/**
 * Types of map actions the AI can request.
 * Each action has specific parameters it expects.
 */
@Serializable
enum class MapAction {
    /**
     * Highlight a route path on the map.
     * Expects: geoJSON with LineString, or routePolyline coordinates
     */
    @SerialName("HIGHLIGHT_PATH")
    HIGHLIGHT_PATH,
    
    /**
     * Zoom the map camera to a specific location.
     * Expects: coordinates (lat/lng) and optionally zoomLevel
     */
    @SerialName("ZOOM_TO")
    ZOOM_TO,
    
    /**
     * Add a temporary marker to the map.
     * Expects: coordinates, message (for popup), optionally reasoningColor
     */
    @SerialName("ADD_MARKER")
    ADD_MARKER,
    
    /**
     * Combined action: zoom to target and highlight it.
     * Expects: targetBusId or targetStopId, reasoningColor, message
     */
    @SerialName("FOCUS_AND_HIGHLIGHT")
    FOCUS_AND_HIGHLIGHT,
    
    /**
     * Show a heatmap overlay (e.g., for traffic or incidents).
     * Expects: geoJSON with Points and intensity values
     */
    @SerialName("SHOW_HEATMAP")
    SHOW_HEATMAP,
    
    /**
     * Clear all AI-added overlays from the map.
     */
    @SerialName("CLEAR_OVERLAYS")
    CLEAR_OVERLAYS
}

/**
 * Result of executing a map command.
 */
data class MapCommandResult(
    val success: Boolean,
    val action: MapAction,
    val error: String? = null
)
