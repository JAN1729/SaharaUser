package com.sahara.user.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Login Screen
 * 
 * Phone-based login for user identification.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit
) {
    var phoneNumber by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo/Title
            Text(
                text = "🚌",
                style = MaterialTheme.typography.displayLarge
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Sahara",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Text(
                text = "Your AI Bus Assistant",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Phone Number Input
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { 
                    if (it.length <= 10 && it.all { char -> char.isDigit() }) {
                        phoneNumber = it
                        errorMessage = null
                    }
                },
                label = { Text("Phone Number") },
                placeholder = { Text("10-digit number") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone
                ),
                singleLine = true,
                isError = errorMessage != null
            )
            
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Login Button
            Button(
                onClick = {
                    if (phoneNumber.length == 10) {
                        isLoading = true
                        // For now, just proceed to chat
                        // TODO: Implement OTP verification
                        onLoginSuccess()
                    } else {
                        errorMessage = "Please enter a valid 10-digit phone number"
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = phoneNumber.length == 10 && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Continue")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Terms text
            Text(
                text = "By continuing, you agree to our Terms of Service and Privacy Policy",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}
