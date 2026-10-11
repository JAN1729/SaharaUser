package com.sahara.user.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sahara.user.BuildConfig
import com.sahara.user.data.api.CommuteBackend
import com.sahara.user.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(onBack: () -> Unit, viewModel: ChatViewModel) {
    var address by rememberSaveable { mutableStateOf(CommuteBackend.url) }
    var key by remember { mutableStateOf(CommuteBackend.key) }
    var mode by rememberSaveable { mutableStateOf(CommuteBackend.dataMode) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(modifier = Modifier.systemBarsPadding().imePadding(), topBar = {
        TopAppBar(title = { Text("Server connection") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Connect to the computer running Sahara.", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Server address") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                supportingText = { Text(if (BuildConfig.DEBUG) "Emulator: http://10.0.2.2:8787. Phone: your computer’s local IP and port." else "Use your server’s HTTPS address.") })
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Server access key") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true,
                supportingText = { Text("Use the key created by your Sahara server.") })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = mode == "sample", onClick = { mode = "sample" }, label = { Text("Demo") })
                FilterChip(selected = mode == "live", onClick = { mode = "live" }, label = { Text("Live web data") })
            }
            Text(if (mode == "sample") "Demo journeys use illustrative schedules, fares and seats." else
                "Live searches scrape public pages. Missing or blocked sources are shown in results.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                val error = viewModel.saveConnection(address, key, mode)
                if (error != null) status = error else scope.launch {
                    checking = true
                    try { status = viewModel.checkServer() } finally { checking = false }
                }
            }, enabled = !checking, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (checking) "Checking connection…" else "Save and check connection")
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text("Saving a connection clears the current conversation. Your server session is retained on this device. Keep the computer awake and both devices on a reachable network.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
