package com.sahara.user.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents the current viewport state of the map.
 * Sent with every query to enable spatial-aware AI responses.
 * 
 * Part of the Map-Action Pipeline - Phase 1: Spatial Perception
 */
@Serializable
data class ViewportState(
    @SerialName("boundingBox") val boundingBox: BoundingBox,
    @SerialName("zoomLevel") val zoomLevel: Float,
    @SerialName("centerLat") val centerLat: Double,
    @SerialName("centerLng") val centerLng: Double
)

/**
 * GPS bounding box representing the visible map area.
 * Uses NE/SW corners convention (North-East and South-West).
 */
@Serializable
data class BoundingBox(
    @SerialName("north") val north: Double,  // Max latitude (top of viewport)
    @SerialName("south") val south: Double,  // Min latitude (bottom of viewport)
    @SerialName("east") val east: Double,    // Max longitude (right of viewport)
    @SerialName("west") val west: Double     // Min longitude (left of viewport)
) {
    /**
     * Check if a coordinate is within this bounding box
     */
    fun contains(lat: Double, lng: Double): Boolean {
        return lat in south..north && lng in west..east
    }
    
    /**
     * Calculate the center point of this bounding box
     */
    fun center(): Pair<Double, Double> {
        return Pair((north + south) / 2, (east + west) / 2)
    }
    
    /**
     * Approximate area in square kilometers (simplified)
     */
    fun approximateAreaKm2(): Double {
        val latDiff = north - south
        val lngDiff = east - west
        // Rough approximation: 1 degree ≈ 111 km
        return (latDiff * 111) * (lngDiff * 111 * kotlin.math.cos(Math.toRadians((north + south) / 2)))
    }
}
