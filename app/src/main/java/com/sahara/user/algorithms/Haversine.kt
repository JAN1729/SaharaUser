package com.sahara.user.algorithms

import kotlin.math.*

/**
 * Haversine Distance Calculator
 * 
 * Calculates the great-circle distance between two points
 * on Earth given their latitude and longitude in degrees.
 * 
 * This is a pre-coded algorithm - NO machine learning.
 */
object Haversine {
    
    // Earth's radius in kilometers
    private const val EARTH_RADIUS_KM = 6371.0
    
    /**
     * Calculate distance between two points in kilometers
     * 
     * @param lat1 Latitude of first point (degrees)
     * @param lon1 Longitude of first point (degrees)
     * @param lat2 Latitude of second point (degrees)
     * @param lon2 Longitude of second point (degrees)
     * @return Distance in kilometers
     */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return EARTH_RADIUS_KM * c
    }
    
    /**
     * Calculate distance in meters
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        return distance(lat1, lon1, lat2, lon2) * 1000
    }
    
    /**
     * Check if a point is within radius of another point
     */
    fun isWithinRadius(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double,
        radiusKm: Double
    ): Boolean {
        return distance(lat1, lon1, lat2, lon2) <= radiusKm
    }
    
    /**
     * Calculate bearing from point 1 to point 2
     * Returns bearing in degrees (0-360)
     */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        
        val bearingRad = atan2(y, x)
        return (Math.toDegrees(bearingRad) + 360) % 360
    }
}
