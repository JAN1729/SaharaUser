package com.sahara.user.ui.components

import android.graphics.Color
import android.graphics.Paint
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sahara.user.data.model.RouteMapData
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * RouteMapCard - Ephemeral map visualization for agentic AI responses.
 * 
 * This component appears when the AI decides a map would help the user.
 * It shows:
 * - Route polyline connecting all stops (blue)
 * - Walking path from user to origin stop (dashed green)
 * - Stop markers with origin/destination highlighted
 * - Bus icon at live position (if available)
 * - User location marker
 * 
 * The map is ephemeral - it vanishes when the user sends the next message.
 * 
 * Map-Action Pipeline Integration:
 * - Reports viewport changes via onViewportChanged callback
 * - Executes AI-driven map commands via mapCommands parameter
 * 
 * @param data Route map data from the AI agent
 * @param mapCommands Optional list of AI-driven map commands to execute
 * @param onViewportChanged Callback when user pans/zooms the map (for spatial awareness)
 * @param onDismiss Callback when user dismisses the map
 */
@Composable
fun RouteMapCard(
    data: RouteMapData,
    modifier: Modifier = Modifier,
    mapCommands: List<com.sahara.user.data.model.MapCommand> = emptyList(),
    onViewportChanged: ((north: Double, south: Double, east: Double, west: Double, zoom: Float) -> Unit)? = null,
    onCommandsExecuted: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()
    val surfaceColor = MaterialTheme.colorScheme.surface
    
    // Initialize OSMDroid configuration
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = "SaharaUser/1.0"
    }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = surfaceColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column {
            // Header with glass morphism effect
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.DirectionsBus,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            data.vehicleName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${data.stopsToMark.size} stops • ${data.etaMinutes} min",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
                
                // Dismiss button
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close map",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            
            // Map View
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(14.0)
                            
                            // Disable unnecessary map controls for cleaner look
                            setBuiltInZoomControls(false)
                            
                            // Map-Action Pipeline: Report viewport changes for spatial awareness
                            onViewportChanged?.let { callback ->
                                addMapListener(object : org.osmdroid.events.MapListener {
                                    override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                                        val bounds = boundingBox
                                        callback(
                                            bounds.latNorth,
                                            bounds.latSouth,
                                            bounds.lonEast,
                                            bounds.lonWest,
                                            zoomLevelDouble.toFloat()
                                        )
                                        return false
                                    }
                                    
                                    override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                                        val bounds = boundingBox
                                        callback(
                                            bounds.latNorth,
                                            bounds.latSouth,
                                            bounds.lonEast,
                                            bounds.lonWest,
                                            zoomLevelDouble.toFloat()
                                        )
                                        return false
                                    }
                                })
                            }
                        }
                    },
                    update = { mapView ->
                        // Helper to find or create a marker with a specific tag
                        fun getOrCreateMarker(id: String): Marker {
                            val existing = mapView.overlays.filterIsInstance<Marker>()
                                .find { it.id == id }
                            return if (existing != null) {
                                existing
                            } else {
                                Marker(mapView).apply { 
                                    this.id = id
                                    mapView.overlays.add(this)
                                }
                            }
                        }


                        // 1. Static Overlays (Clear and rebuild if needed, or keep)
                        // For simplicity in OSMDroid, we often clear and rebuild
                        // but we can be selective
                        
                        val aiOverlays = mapView.overlays.filterIsInstance<Polyline>()
                        mapView.overlays.removeAll(aiOverlays)
                        
                        // Add route polyline (uses pre-ordered coordinates from backend)
                        if (data.routePolyline.isNotEmpty()) {
                            val routePolyline = Polyline().apply {
                                outlinePaint.color = primaryColor
                                outlinePaint.strokeWidth = 8f
                                outlinePaint.strokeCap = Paint.Cap.ROUND
                                outlinePaint.strokeJoin = Paint.Join.ROUND
                                outlinePaint.isAntiAlias = true
                            }
                            
                            // Use routePolyline from backend (already ordered)
                            data.routePolyline.forEach { point ->
                                routePolyline.addPoint(GeoPoint(point.lat, point.lng))
                            }
                            
                            mapView.overlays.add(routePolyline)
                        }
                        
                        // Add walking path polyline (dashed green)
                        data.walkingPath?.let { path ->
                            if (path.isNotEmpty()) {
                                val walkingPolyline = Polyline().apply {
                                    outlinePaint.color = Color.parseColor("#4CAF50")
                                    outlinePaint.strokeWidth = 6f
                                    outlinePaint.strokeCap = Paint.Cap.ROUND
                                    outlinePaint.pathEffect = android.graphics.DashPathEffect(
                                        floatArrayOf(20f, 10f), 0f
                                    )
                                    outlinePaint.isAntiAlias = true
                                }
                                
                                path.forEach { point ->
                                    walkingPolyline.addPoint(GeoPoint(point.lat, point.lng))
                                }
                                
                                mapView.overlays.add(walkingPolyline)
                            }
                        }
                        
                        // Add stop markers
                        data.stopsToMark.forEach { stop ->
                            val marker = Marker(mapView).apply {
                                position = GeoPoint(stop.lat, stop.lng)
                                title = stop.name
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                
                                // Highlight origin/destination
                                when {
                                    stop.isOrigin -> {
                                        snippet = "Origin"
                                    }
                                    stop.isDestination -> {
                                        snippet = "Destination"
                                    }
                                    else -> {
                                        snippet = "Stop ${stop.order}"
                                    }
                                }
                            }
                            mapView.overlays.add(marker)
                        }
                        
                        // Add bus location marker (Persistent for smooth updates)
                        data.busLocation?.let { bus ->
                            getOrCreateMarker("bus_marker").apply {
                                val destination = GeoPoint(bus.lat, bus.lng)
                                // If position changed, we could animate here
                                position = destination
                                title = "${data.vehicleName} (${data.vehicleId})"
                                snippet = if (bus.isMoving == true) "Moving" else "Stopped"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                rotation = (bus.heading ?: 0.0).toFloat()
                                // In a real app, use a bus icon resource
                                // icon = context.getDrawable(R.drawable.ic_bus_marker)
                            }
                        }
                        
                        // Add user location marker
                        data.userLocation?.let { user ->
                            getOrCreateMarker("user_marker").apply {
                                position = GeoPoint(user.lat, user.lng)
                                title = "Your Location"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            }
                        }
                        
                        // Set map bounds to show all markers
                        val boundingBox = BoundingBox(
                            data.mapBounds.north,
                            data.mapBounds.east,
                            data.mapBounds.south,
                            data.mapBounds.west
                        )
                        mapView.post {
                            mapView.zoomToBoundingBox(boundingBox, true, 50)
                        }
                        
                        // Map-Action Pipeline: Execute any pending AI map commands
                        if (mapCommands.isNotEmpty()) {
                            val executor = MapCommandExecutor(mapView)
                            val results = executor.executeCommands(mapCommands)
                            results.forEach { result ->
                                android.util.Log.d("RouteMapCard", "Command ${result.action}: ${if (result.success) "OK" else result.error}")
                            }
                            // Signal completion to prevent re-execution
                            onCommandsExecuted?.invoke()
                        }
                        
                        mapView.invalidate()
                    }
                )
                
                // Walking info overlay (bottom left)
                data.walkingDistanceM?.let { distance ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .background(
                                surfaceColor.copy(alpha = 0.9f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.DirectionsWalk,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = ComposeColor(0xFF4CAF50)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "${distance}m • ${data.walkingTimeMin ?: "?"} min",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                
                // Live bus indicator (top right)
                data.busLocation?.let { bus ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(
                                if (bus.isMoving == true) ComposeColor(0xFF4CAF50) else ComposeColor(0xFFFF9800),
                                CircleShape
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(ComposeColor.White, CircleShape)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (bus.isMoving == true) "LIVE" else "STOPPED",
                                style = MaterialTheme.typography.labelSmall,
                                color = ComposeColor.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
