package com.sahara.user.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahara.user.ui.components.*
import com.sahara.user.ui.viewmodel.ChatViewModel
import com.sahara.user.data.api.CommuteBackend

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThinkingScreen(onNavigateBack: () -> Unit, viewModel: ChatViewModel = hiltViewModel()) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val steps by viewModel.thinkingSteps.collectAsStateWithLifecycle()
    val loading by viewModel.isLoading.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var followedJourney by remember { mutableStateOf<com.sahara.user.data.api.JourneyOption?>(null) }
    val companion = rememberJourneyFollower(followedJourney, viewModel::journeyGuidance, viewModel::revalidateJourney)
    LaunchedEffect(messages.count { it.isUser }) { followedJourney = null }
    val listState = rememberLazyListState()
    val latest = messages.lastOrNull { it.result != null }?.result
    val journey = latest?.journeys?.firstOrNull()
    val title = when {
        loading -> "Finding your journey"
        journey != null -> "${latest.placeNames[journey.origin] ?: "Origin"} → ${latest.placeNames[journey.destination] ?: "Destination"}"
        else -> "Your journey"
    }
    LaunchedEffect(messages.size, loading) {
        if (loading) followedJourney = null
        val count = messages.size + if (loading) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    fun submit() { if (viewModel.sendMessage(input.trim())) input = "" }
    Scaffold(modifier = Modifier.systemBarsPadding().imePadding(), containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { followedJourney = null; viewModel.clearChat(); input = "" }) { Icon(Icons.Default.DeleteOutline, "Clear conversation") } }) },
        bottomBar = { JourneyComposer(input, { input = it }, { submit() }, !loading, if (journey != null) "Refine this journey…" else "Ask Sahara…") }
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (messages.isEmpty()) item { ConversationBubble("Where would you like to go? Tell me the places and when you want to travel.", false) }
            items(messages, key = { it.id }) { message ->
                if (message.isUser) ConversationBubble(message.content, true)
                else message.result?.let { JourneyResults(it, viewModel::journeyMap, CommuteBackend.enabled,
                    companion, followedJourney?.id, { option, active ->
                        if (active) followedJourney = option else if (followedJourney?.id == option.id) followedJourney = null
                    }) } ?: ConversationBubble(message.content, false)
            }
            if (loading) item(key = "progress") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConversationBubble("I’ll compare the full journey, including available connections.", false)
                    JourneyProgress(steps.lastOrNull()?.description ?: "Checking sources and connections…")
                }
            }
        }
    }
}
