package com.sahara.user.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Sahara Brand Colors - Daylight / Liquid Glass
val SaharaOrange = Color(0xFFFF5722)      // Bright Orange
val SaharaBlack = Color(0xFF000000)       // Pure Black
val SaharaWhite = Color(0xFFFFFFFF)       // Pure White
val SaharaGlassSurface = Color(0xCCFFFFFF) // High alpha white for glass

// We are enforcing Light Mode for now, but keeping the structure valid.
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFC84316), // Accessible white button text while retaining the orange identity.
    onPrimary = SaharaWhite,
    secondary = SaharaBlack,
    onSecondary = SaharaWhite,
    tertiary = SaharaOrange,
    background = SaharaWhite,
    surface = SaharaWhite,
    onBackground = SaharaBlack,
    onSurface = SaharaBlack,
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = SaharaBlack
)

// Dark scheme kept but unused (or mirrored) to prevent crashes if system forces dark
private val DarkColorScheme = LightColorScheme // Force light for now

@Composable
fun SaharaUserTheme(
    darkTheme: Boolean = isSystemInDarkTheme(), // Ignored for now as per requirements
    dynamicColor: Boolean = false, // Disable dynamic color to enforce brand
    content: @Composable () -> Unit
) {
    // FORCE LIGHT MODE / DAYLIGHT
    val colorScheme = LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb() // Transparent for glass effect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
