package com.sahara.user.ui.components

import android.util.Log
import com.sahara.user.data.model.MapAction
import com.sahara.user.data.model.MapCommand
import com.sahara.user.data.model.MapCommandResult
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlinx.serialization.json.*
import android.graphics.Color

/**
 * MapCommandExecutor - Executes AI-driven map commands
 * 
 * Part of the Map-Action Pipeline - Phase 3: Action Protocol
 * 
 * This class interprets map commands sent by the AI and executes them
 * on the OSMDroid MapView. Supported actions:
 * - HIGHLIGHT_PATH: Draw a colored polyline on the map
 * - ZOOM_TO: Animate the camera to a location
 * - ADD_MARKER: Add a temporary marker with a popup
 * - FOCUS_AND_HIGHLIGHT: Combined zoom + highlight
 * - CLEAR_OVERLAYS: Remove all AI-added overlays
 */
class MapCommandExecutor(
    private val mapView: MapView
) {
    companion object {
        private const val TAG = "MapCommandExecutor"
        private const val AI_OVERLAY_TAG = "ai_command_overlay"
    }
    
    // Track AI-added overlays for cleanup
    private val aiOverlays = mutableListOf<Any>()
    
    /**
     * Execute a list of map commands from the AI.
     * Commands are executed in order.
     */
    fun executeCommands(commands: List<MapCommand>): List<MapCommandResult> {
        Log.d(TAG, "Executing ${commands.size} map commands")
        return commands.map { executeCommand(it) }
    }
    
    /**
     * Execute a single map command.
     */
    fun executeCommand(command: MapCommand): MapCommandResult {
        return try {
            Log.d(TAG, "Executing command: ${command.action}")
            
            when (command.action) {
                MapAction.HIGHLIGHT_PATH -> executeHighlightPath(command)
                MapAction.ZOOM_TO -> executeZoomTo(command)
                MapAction.ADD_MARKER -> executeAddMarker(command)
                MapAction.FOCUS_AND_HIGHLIGHT -> executeFocusAndHighlight(command)
                MapAction.SHOW_HEATMAP -> executeShowHeatmap(command)
                MapAction.CLEAR_OVERLAYS -> executeClearOverlays()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed: ${e.message}", e)
            MapCommandResult(
                success = false,
                action = command.action,
                error = e.message
            )
        }
    }
    
    // ==================== COMMAND IMPLEMENTATIONS ====================
    
    private fun executeHighlightPath(command: MapCommand): MapCommandResult {
        // Parse color from hex string (default to Material Blue)
        val color = parseColor(command.reasoningColor ?: "#2196F3")
        
        val points = mutableListOf<GeoPoint>()
        
        // Try to parse from GeoJSON (primary)
        val geoJSON = command.geoJSON
        if (geoJSON != null) {
            try {
                val geoObj = geoJSON.jsonObject
                val geometry = geoObj["geometry"]?.jsonObject
                val type = geometry?.get("type")?.jsonPrimitive?.content
                
                when (type) {
                    "LineString" -> {
                        val coords = geometry["coordinates"]?.jsonArray
                        coords?.forEach { coord ->
                            val pointArr = coord.jsonArray
                            val lng = pointArr[0].jsonPrimitive.double
                            val lat = pointArr[1].jsonPrimitive.double
                            points.add(GeoPoint(lat, lng))
                        }
                    }
                    else -> {
                        Log.w(TAG, "Unsupported GeoJSON type: $type")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse GeoJSON: ${e.message}")
            }
        }

        
        // TODO: Fallback to command.coordinates if geoJSON is missing?
        // For now, only HIGHLIGHT_PATH if we have multiple points
        
        if (points.isEmpty()) {
            return MapCommandResult(
                success = false,
                action = MapAction.HIGHLIGHT_PATH,
                error = "No path coordinates found in GeoJSON"
            )
        }
        
        val polyline = Polyline(mapView).apply {
            setPoints(points)
            outlinePaint.color = color
            outlinePaint.strokeWidth = 10f
            title = command.message ?: "AI Highlighted Path"
        }
        
        mapView.overlays.add(polyline)
        aiOverlays.add(polyline)
        
        Log.d(TAG, "HIGHLIGHT_PATH: Added polyline with ${points.size} points")
        
        return MapCommandResult(
            success = true,
            action = MapAction.HIGHLIGHT_PATH
        )
    }
    
    private fun executeZoomTo(command: MapCommand): MapCommandResult {
        val coords = command.coordinates
            ?: return MapCommandResult(
                success = false,
                action = MapAction.ZOOM_TO,
                error = "No coordinates provided"
            )
        
        val geoPoint = GeoPoint(coords.lat, coords.lng)
        val zoomLevel = command.zoomLevel?.toDouble() ?: 16.0
        
        mapView.controller.animateTo(geoPoint, zoomLevel, 500L)
        
        Log.d(TAG, "ZOOM_TO: (${coords.lat}, ${coords.lng}) at zoom $zoomLevel")
        
        return MapCommandResult(
            success = true,
            action = MapAction.ZOOM_TO
        )
    }
    
    private fun executeAddMarker(command: MapCommand): MapCommandResult {
        val coords = command.coordinates
            ?: return MapCommandResult(
                success = false,
                action = MapAction.ADD_MARKER,
                error = "No coordinates provided"
            )
        
        val marker = Marker(mapView).apply {
            position = GeoPoint(coords.lat, coords.lng)
            title = command.message ?: "AI Marker"
            snippet = command.targetBusId ?: command.targetStopId
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            
            // Set marker color based on reasoningColor
            // Note: In a real app, you'd use a Tinted icon or custom Drawable
            // For now, we use a simple placeholder logic
            val colorStr = command.reasoningColor ?: "#2196F3"
            Log.d(TAG, "Adding marker with color: $colorStr")
        }
        
        mapView.overlays.add(marker)
        aiOverlays.add(marker)
        mapView.invalidate()
        
        Log.d(TAG, "ADD_MARKER at (${coords.lat}, ${coords.lng}): ${command.message}")
        
        return MapCommandResult(
            success = true,
            action = MapAction.ADD_MARKER
        )
    }
    
    private fun executeFocusAndHighlight(command: MapCommand): MapCommandResult {
        // Combined action: zoom to target and highlight
        
        // 1. Zoom/Animate to location
        val coords = command.coordinates
        if (coords != null) {
            mapView.controller.animateTo(
                GeoPoint(coords.lat, coords.lng),
                command.zoomLevel?.toDouble() ?: 17.0,
                500L
            )
        }
        
        // 2. Add an "AI Highlight" marker if it's a specific entity
        if (command.targetBusId != null || command.targetStopId != null) {
            coords?.let {
                val highlightMarker = Marker(mapView).apply {
                    position = GeoPoint(it.lat, it.lng)
                    title = command.message ?: "Highlighted Entity"
                    // Use a specific "highlight" icon or color
                    val color = parseColor(command.reasoningColor ?: "#4CAF50")
                    // Note: OSMDroid markers are usually bitmaps, 
                    // coloring them requires specialized icons or canvas drawing
                }
                mapView.overlays.add(highlightMarker)
                aiOverlays.add(highlightMarker)
            }
        }
        
        // 3. If there's path data included, draw it too
        if (command.geoJSON != null) {
            executeHighlightPath(command)
        }
        
        mapView.invalidate()
        return MapCommandResult(
            success = true,
            action = MapAction.FOCUS_AND_HIGHLIGHT
        )
    }
    
    private fun executeShowHeatmap(command: MapCommand): MapCommandResult {
        // Heatmap implementation would require additional library
        Log.d(TAG, "SHOW_HEATMAP: Not yet implemented")
        
        return MapCommandResult(
            success = true,
            action = MapAction.SHOW_HEATMAP
        )
    }
    
    private fun executeClearOverlays(): MapCommandResult {
        Log.d(TAG, "CLEAR_OVERLAYS: Removing ${aiOverlays.size} AI overlays")
        
        aiOverlays.forEach { overlay ->
            mapView.overlays.remove(overlay)
        }
        aiOverlays.clear()
        mapView.invalidate()
        
        return MapCommandResult(
            success = true,
            action = MapAction.CLEAR_OVERLAYS
        )
    }
    
    // ==================== HELPERS ====================
    
    private fun parseColor(hexColor: String): Int {
        return try {
            android.graphics.Color.parseColor(hexColor)
        } catch (e: Exception) {
            android.graphics.Color.RED
        }
    }
}
