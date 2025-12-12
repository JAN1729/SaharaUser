package com.sahara.user.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.sahara.user.ui.components.LiquidGlassCard
import com.sahara.user.ui.components.SaharaSearchBar
import com.sahara.user.ui.components.SaharaTopBar
import com.sahara.user.ui.viewmodel.ChatViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onNavigateToProfile: () -> Unit,
    onNavigateToChat: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    
    // Observables
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val userLocation by viewModel.userLocation.collectAsState()
    
    // State
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    // Permission Launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            viewModel.onLocationPermissionGranted()
        }
    }

    // Initialize OSM Configuration & Request Permission
    LaunchedEffect(Unit) {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))
        locationPermissionLauncher.launch(
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }
    
    // Auto-scroll to bottom of chat
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.systemBarsPadding() // FIX: Status Bar Collision
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            
            // 1. Top Bar (Logo moved down a bit via Spacer)
            Spacer(modifier = Modifier.height(24.dp)) // Push Logo down
            SaharaTopBar(
                onMenuClick = { /* TODO */ },
                onProfileClick = onNavigateToProfile
            )
            
            // 2. Middle Section (Search Bar Centered)
            Box(
                modifier = Modifier
                    .weight(1f) // Takes all available space between Top and Map
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center // Strictly center the search bar
            ) {
                SaharaSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onSearch = {
                        if (searchQuery.isNotBlank()) {
                            viewModel.sendMessage(searchQuery)
                            searchQuery = ""
                            onNavigateToChat() // Navigate to Thinking/Chat screen
                        }
                    },
                    placeholder = "Where do you want to go?",
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
            
            // 3. Map Container (Bottom, reduced height)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp) // Slight adjust to "move up" visually (taller or just positioned?) 
                                    // User said "move the map a bit up". If it's bottom anchored, making it taller moves it up.
                                    // Or maybe they meant padding? I'll keep it distinct.
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp) // Layout "perfect" usually implies breathing room
            ) {
                LiquidGlassCard(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(32.dp)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            MapView(ctx).apply {
                                setTileSource(TileSourceFactory.MAPNIK)
                                setMultiTouchControls(true)
                                controller.setZoom(15.0)
                                if (userLocation != null) {
                                    controller.setCenter(GeoPoint(userLocation!!.latitude, userLocation!!.longitude))
                                } else {
                                    controller.setCenter(GeoPoint(12.9716, 77.5946))
                                }
                                isTilesScaledToDpi = true
                            }
                        },
                        update = { mapView ->
                            userLocation?.let { loc ->
                                val geoPoint = GeoPoint(loc.latitude, loc.longitude)
                                mapView.controller.animateTo(geoPoint)
                                mapView.overlays.clear()
                                val marker = Marker(mapView)
                                marker.position = geoPoint
                                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                marker.title = "Me"
                                mapView.overlays.add(marker)
                            }
                        }
                    )
                }
            }
        }
    }
}
