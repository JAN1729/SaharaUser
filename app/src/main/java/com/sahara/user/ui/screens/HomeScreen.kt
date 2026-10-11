package com.sahara.user.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahara.user.data.api.CommuteBackend
import com.sahara.user.ui.components.JourneyComposer
import com.sahara.user.ui.components.ChatGray
import com.sahara.user.ui.components.ChatBorder
import com.sahara.user.ui.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(onNavigateToSettings: () -> Unit, onNavigateToChat: () -> Unit,
               viewModel: ChatViewModel = hiltViewModel()) {
    var query by rememberSaveable { mutableStateOf("") }
    val loading by viewModel.isLoading.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val version by viewModel.connectionVersion.collectAsStateWithLifecycle()
    val mode = remember(version) { CommuteBackend.dataMode }
    fun submit() { if (viewModel.sendMessage(query.trim())) { query = ""; onNavigateToChat() } }
    Scaffold(modifier = Modifier.systemBarsPadding().imePadding(), containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text("Sahara", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            actions = { IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, "Server settings") } }) },
        bottomBar = { JourneyComposer(query, { query = it }, { submit() }, !loading) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Spacer(Modifier.height(64.dp))
            Text("Where are we heading?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Tell me your journey. I’ll find the connections.", style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SuggestionChip(onClick = { query = "Kottayam to Bengaluru tomorrow, arrive before 8 pm" }, label = { Text("Arrive before evening") },
                    icon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) }, shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, ChatBorder), colors = SuggestionChipDefaults.suggestionChipColors(containerColor = ChatGray))
                SuggestionChip(onClick = { query = "Kottayam to Bengaluru tomorrow, find the lowest fare" }, label = { Text("Find the lowest fare") },
                    icon = { Icon(Icons.Default.LocalOffer, null, Modifier.size(18.dp)) }, shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, ChatBorder), colors = SuggestionChipDefaults.suggestionChipColors(containerColor = ChatGray))
            }
            Text(if (mode == "sample") "Demo mode · illustrative journeys" else "Web sources · checked during your search",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Try “Kottayam to Bengaluru tomorrow”. Add a budget or arrival time to help me compare.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (messages.isNotEmpty()) TextButton(onClick = onNavigateToChat) { Text("Return to your journey") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
