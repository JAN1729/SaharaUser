package com.sahara.user.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Profile Screen
 * 
 * Shows user profile, preferences, and settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBackClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Profile Avatar
            Surface(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(50.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "User",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "USR-TEMPUSER",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Settings List
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    SettingsItem(
                        icon = Icons.Default.Home,
                        title = "Home Location",
                        subtitle = "Not set",
                        onClick = { /* TODO */ }
                    )
                    HorizontalDivider()
                    
                    SettingsItem(
                        icon = Icons.Default.Work,
                        title = "Work Location",
                        subtitle = "Not set",
                        onClick = { /* TODO */ }
                    )
                    HorizontalDivider()
                    
                    SettingsItem(
                        icon = Icons.Default.Route,
                        title = "Preferred Route",
                        subtitle = "Fastest",
                        onClick = { /* TODO */ }
                    )
                    HorizontalDivider()
                    
                    SettingsItem(
                        icon = Icons.Default.Language,
                        title = "Language",
                        subtitle = "English",
                        onClick = { /* TODO */ }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    SettingsItem(
                        icon = Icons.Default.History,
                        title = "Trip History",
                        subtitle = "View past trips",
                        onClick = { /* TODO */ }
                    )
                    HorizontalDivider()
                    
                    SettingsItem(
                        icon = Icons.Default.Star,
                        title = "Saved Places",
                        subtitle = "Manage saved locations",
                        onClick = { /* TODO */ }
                    )
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Logout Button
            OutlinedButton(
                onClick = { /* TODO */ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Logout,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Logout")
            }
        }
    }
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
