package com.sahara.user

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.sahara.user.ui.navigation.SaharaUserNavGraph
import com.sahara.user.ui.theme.SaharaUserTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * MainActivity - Entry point for SaharaUser app
 * 
 * This is the main activity that hosts the Compose navigation.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            SaharaUserTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SaharaUserNavGraph()
                }
            }
        }
    }
}
