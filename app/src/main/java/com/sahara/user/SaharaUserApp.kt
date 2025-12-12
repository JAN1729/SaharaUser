package com.sahara.user

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * SaharaUser Application
 * 
 * Main application class for the AI-powered route assistant.
 * Uses Hilt for dependency injection.
 */
@HiltAndroidApp
class SaharaUserApp : Application() {
    
    override fun onCreate() {
        super.onCreate()
        // Initialize any app-level components here
    }
}
