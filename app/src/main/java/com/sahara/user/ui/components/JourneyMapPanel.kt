package com.sahara.user.ui.components

import android.graphics.Paint
import android.graphics.DashPathEffect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import com.sahara.user.BuildConfig
import com.sahara.user.R
import androidx.core.content.ContextCompat
import com.sahara.user.data.api.JourneyMap
import com.sahara.user.data.api.FleetVehicle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

fun modeColor(mode: String) = when (mode) {
    "train" -> Color(0xFF193D74)
    "bus" -> Color(0xFFC84316)
    "metro" -> Color(0xFF23834A)
    else -> Color(0xFF69727F)
}

@Composable
fun JourneyMapPanel(journeyId: String, loadMap: suspend (String) -> JourneyMap, fleet: List<FleetVehicle> = emptyList(), revision: String? = null) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentLoader by rememberUpdatedState(loadMap)
    var data by remember(journeyId) { mutableStateOf<JourneyMap?>(null) }
    var error by remember(journeyId) { mutableStateOf(false) }
    var retry by remember(journeyId) { mutableIntStateOf(0) }
    var loadedRevision by remember(journeyId) { mutableStateOf<String?>(null) }
    // Bound to composition and selection: late responses cannot replace another route's map.
    LaunchedEffect(journeyId, retry, revision, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (data == null || loadedRevision != revision) {
                error = false
                try { data = currentLoader(journeyId); loadedRevision = revision }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = true }
            }
        }
    }
    val map = data
    val visibleFleet by produceState<List<FleetVehicle>>(emptyList(), fleet, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { value = fleet.filter { it.isFresh() }; delay(5_000) }
        }
    }
    if (map != null) {
        if (error) {
            Text("Map refresh failed; showing the previous terminal map.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { retry++ }) { Text("Retry map refresh") }
        }
        key(map.journeyId, map.validPins, map.renderableSegments) { JourneyMapView(map, visibleFleet) }
    } else Surface(Modifier.fillMaxWidth().heightIn(min = 120.dp), color = ChatGray, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (error) {
                Text("The journey map couldn’t load. Check your server connection.")
                TextButton(onClick = { retry++ }) { Text("Retry map") }
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Loading your journey map…")
            }
        }
    }
}

@Composable
private fun JourneyMapView(data: JourneyMap, fleet: List<FleetVehicle>) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val uri = LocalUriHandler.current
    val pins = data.validPins
    val segments = data.renderableSegments
    val points = pins.map { GeoPoint(it.lat, it.lng) } + segments.flatMap { segment -> segment.polyline.map { GeoPoint(it.lat, it.lng) } }
    if (points.isEmpty()) {
        Text("Terminal locations and route shapes are unavailable for this journey.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val map = remember(data.journeyId, pins, segments) {
        Configuration.getInstance().apply {
            userAgentValue = "Sahara/${BuildConfig.VERSION_NAME} (com.sahara.user)"
            osmdroidBasePath = File(context.filesDir, "maps").apply { mkdirs() }
            osmdroidTileCache = File(osmdroidBasePath, "tiles").apply { mkdirs() }
        }
        MapView(context).apply {
            setTileSource(XYTileSource("SaharaOSM", 0, 19, 256, ".png", arrayOf("https://tile.openstreetmap.org/")))
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            controller.setZoom(6.0)
            controller.setCenter(points.first())
            segments.forEach { segment ->
                overlays.add(Polyline(this).apply {
                    setPoints(segment.polyline.map { GeoPoint(it.lat, it.lng) })
                    outlinePaint.color = modeColor(segment.mode).toArgb()
                    outlinePaint.strokeWidth = 5f * context.resources.displayMetrics.density
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.isAntiAlias = true
                    if (segment.isRoadGuidance) outlinePaint.pathEffect = DashPathEffect(floatArrayOf(12f, 8f).map { it * context.resources.displayMetrics.density }.toFloatArray(), 0f)
                    title = if (segment.provider == "ksrtc") "KSRTC · ${segment.serviceName}" else "${segment.mode.replaceFirstChar { it.uppercase() }} · ${segment.kind}"
                    snippet = segment.notice
                })
            }
            pins.forEach { pin -> overlays.add(Marker(this).apply {
                icon = ContextCompat.getDrawable(context, R.drawable.ic_terminal_pin)
                position = GeoPoint(pin.lat, pin.lng)
                title = pin.name
                snippet = "Approximate terminal location"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }) }
            val gesture = MapGestureState(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) gesture.begin(event.x, event.y)
                val ended = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
                if (ended) gesture.end()
                view.parent?.requestDisallowInterceptTouchEvent(!ended && gesture.capture(event.x, event.y, event.pointerCount))
                false
            }
        }
    }
    val fleetMarkers = remember(map) { mutableListOf<Marker>() }
    LaunchedEffect(map, fleet) {
        map.overlays.removeAll(fleetMarkers.toSet())
        fleetMarkers.clear()
        fleet.filter { it.isFresh() }.forEach { vehicle ->
            fleetMarkers.add(Marker(map).apply {
                icon = ContextCompat.getDrawable(context, R.drawable.ic_terminal_pin)
                position = GeoPoint(vehicle.lat, vehicle.lng)
                title = vehicle.name
                snippet = "Nearby operator GPS · not a confirmed itinerary service"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            })
        }
        map.overlays.addAll(fleetMarkers)
        map.invalidate()
    }
    fun fit() {
        if (points.distinct().size == 1) { map.controller.setCenter(points.first()); map.controller.setZoom(14.0) }
        else map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, (32 * context.resources.displayMetrics.density).toInt(), 16.0, null)
    }
    DisposableEffect(map, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
        onDispose { lifecycle.removeObserver(observer); map.onPause(); map.onDetach() }
    }
    val connectivity = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    fun connected() = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    var hasInternet by remember(connectivity) { mutableStateOf(connected()) }
    DisposableEffect(connectivity) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { hasInternet = connected() }
            override fun onLost(network: Network) { hasInternet = connected() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { hasInternet = connected() }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        onDispose { connectivity.unregisterNetworkCallback(callback) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(16.dp)).background(ChatGray)) {
            AndroidView(factory = { map }, modifier = Modifier.fillMaxSize().semantics {
                contentDescription = "Journey map. Terminals: ${pins.joinToString { it.name }}. " +
                    (if (data.hasCompleteGeometry) "Route shapes available." else "Some route shapes are unavailable.")
            })
            // Fit after the native view has measured, once per selected payload.
            DisposableEffect(map) {
                val listener = android.view.View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) fit()
                }
                map.addOnLayoutChangeListener(listener)
                onDispose { map.removeOnLayoutChangeListener(listener) }
            }
            Surface(Modifier.align(Alignment.TopStart).padding(10.dp), shape = RoundedCornerShape(8.dp), color = Color.White) {
                Text(when (data.dataMode) {
                    "sample" -> if (data.hasCompleteGeometry) "Demo · route shapes" else "Demo · terminal map"
                    "live" -> if (data.hasRoadGuidance) "KSRTC · road guidance" else if (data.hasCompleteGeometry) "Source route shapes" else "Terminal locations"
                    else -> "Unverified map data"
                },
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
            }
            FilledTonalIconButton(onClick = { fit() }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                Icon(Icons.Default.CenterFocusStrong, "Fit journey to map")
            }
            Surface(Modifier.align(Alignment.BottomEnd), color = Color.White.copy(alpha = 0.96f)) {
                TextButton(onClick = { uri.openUri("https://www.openstreetmap.org/copyright") }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall, color = Color.Black)
                }
            }
        }
        if (!hasInternet) Text("You’re offline. Previously viewed map tiles may be available.", style = MaterialTheme.typography.bodySmall)
        if (data.hasRoadGuidance) Text("Dashed orange: calculated KSRTC road guidance. The operator’s exact bus path and live position are unverified.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!data.hasCompleteGeometry && !data.hasRoadGuidance) Text("Approximate terminal pins only for unmapped legs. Route shapes are not available for the full journey.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (data.hasRoadGuidance && !data.hasCompleteGuidance) Text("Some journey legs or transfers are unmapped; those segments show terminal pins only.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Use two fingers to move or zoom the map.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (segments.isNotEmpty()) Text(segments.map { it.mode }.distinct().joinToString(" · ") { it.replaceFirstChar { c -> c.uppercase() } },
            style = MaterialTheme.typography.labelSmall)
    }
}
