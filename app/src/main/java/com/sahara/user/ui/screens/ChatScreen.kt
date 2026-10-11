package com.sahara.user.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sahara.user.ui.components.RouteMapCard
import com.sahara.user.ui.viewmodel.ChatViewModel
import com.sahara.user.ui.viewmodel.ThinkingStep
import com.sahara.user.ui.viewmodel.AgentStatus
import com.sahara.user.ui.viewmodel.AgentPipelineStep
import kotlinx.coroutines.launch

/**
 * Chat Screen - Enhanced Cursor-like UI
 * 
 * Features:
 * - Real-time agent status display
 * - Thinking steps progress
 * - Agent pipeline visualization
 * - Smooth animations
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToProfile: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val thinkingSteps by viewModel.thinkingSteps.collectAsState()
    val agentStatus by viewModel.agentStatus.collectAsState()
    val agentPipeline by viewModel.agentPipeline.collectAsState()
    
    // AGENTIC: Ephemeral map tool from AI
    val activeMapTool by viewModel.activeMapTool.collectAsState()
    
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sahara")
                        if (isLoading) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = { viewModel.clearChat() }) {
                        Icon(Icons.Default.Refresh, "Clear chat")
                    }
                    IconButton(onClick = onNavigateToProfile) {
                        Icon(Icons.Default.Person, "Profile")
                    }
                }
            )
        },
        bottomBar = {
            ChatInputBar(
                text = inputText,
                onTextChange = { inputText = it },
                onSend = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                        coroutineScope.launch {
                            listState.animateScrollToItem(messages.size)
                        }
                    }
                },
                enabled = !isLoading
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            state = listState
        ) {
            // Welcome message if empty
            if (messages.isEmpty() && !isLoading) {
                item {
                    WelcomeCard()
                }
            }
            
            // Messages
            items(messages) { message ->
                Spacer(Modifier.height(8.dp))
                ChatMessageBubble(message)
            }
            
            // Thinking Process (Cursor-like display)
            if (isLoading && thinkingSteps.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    ThinkingProcessCard(
                        steps = thinkingSteps,
                        agentStatus = agentStatus,
                        agentPipeline = agentPipeline
                    )
                }
            }
            
            // AGENTIC: Ephemeral Map Tool - appears when AI invokes it
            item {
                AnimatedVisibility(
                    visible = activeMapTool != null,
                    enter = slideInVertically(
                        initialOffsetY = { it / 2 },
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(300)),
                    exit = slideOutVertically(
                        targetOffsetY = { it / 2 },
                        animationSpec = tween(durationMillis = 200)
                    ) + fadeOut(animationSpec = tween(200))
                ) {
                    activeMapTool?.let { mapData ->
                        val mapCommands by viewModel.pendingMapCommands.collectAsState()
                        
                        RouteMapCard(
                            data = mapData,
                            modifier = Modifier.padding(vertical = 8.dp),
                            mapCommands = mapCommands,
                            onViewportChanged = { north, south, east, west, zoom ->
                                viewModel.updateViewportState(north, south, east, west, zoom)
                            },
                            onCommandsExecuted = {
                                viewModel.clearMapCommands()
                            },
                            onDismiss = { viewModel.clearMapTool() }
                        )
                    }
                }
            }
            
            // Scroll anchor
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/**
 * Welcome card shown when chat is empty
 */
@Composable
fun WelcomeCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.DirectionsBus,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Spacer(Modifier.height(16.dp))
            
            Text(
                "Welcome to Sahara",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(Modifier.height(8.dp))
            
            Text(
                "Your AI-powered bus route assistant",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            
            Spacer(Modifier.height(24.dp))
            
            Column(horizontalAlignment = Alignment.Start) {
                FeatureRow("🚌", "Find bus routes between locations")
                FeatureRow("📍", "Track live bus positions")
                FeatureRow("⏱️", "Get accurate arrival estimates")
                FeatureRow("🔄", "Plan multi-bus journeys")
            }
            
            Spacer(Modifier.height(24.dp))
            
            Text(
                "Try: \"Take me from MG Road to Electronic City\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun FeatureRow(emoji: String, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Thinking Process Card - Shows agent activity
 */
@Composable
fun ThinkingProcessCard(
    steps: List<ThinkingStep>,
    agentStatus: AgentStatus?,
    agentPipeline: List<AgentPipelineStep>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                PulsingDot()
                Spacer(Modifier.width(8.dp))
                Text(
                    "Thinking...",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
            }
            
            // Steps
            steps.forEach { step ->
                ThinkingStepRow(step)
            }
            
            // Current Agent Status
            AnimatedVisibility(
                visible = agentStatus != null,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                agentStatus?.let { status ->
                    Spacer(Modifier.height(12.dp))
                    AgentStatusCard(status)
                }
            }
            
            // Agent Pipeline (optional - can be enabled for more detail)
            // AgentPipelineView(agentPipeline)
        }
    }
}

/**
 * Individual thinking step
 */
@Composable
fun ThinkingStepRow(step: ThinkingStep) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status indicator
        Box(
            modifier = Modifier
                .size(20.dp)
                .padding(2.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                step.isComplete -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(16.dp)
                    )
                }
                step.isActive -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    )
                }
            }
        }
        
        Spacer(Modifier.width(12.dp))
        
        Text(
            step.description,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                step.isComplete -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                step.isActive -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            },
            fontWeight = if (step.isActive) FontWeight.Medium else FontWeight.Normal
        )
    }
}

/**
 * Current agent status card
 */
@Composable
fun AgentStatusCard(status: AgentStatus) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Agent icon
        Icon(
            Icons.Default.SmartToy,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                status.agentName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                status.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        // Animation indicator
        if (status.isActive) {
            ThreeDotsLoading()
        }
    }
}

/**
 * Agent pipeline visualization
 */
@Composable
fun AgentPipelineView(pipeline: List<AgentPipelineStep>) {
    if (pipeline.isEmpty()) return
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        pipeline.forEachIndexed { index, step ->
            AgentChip(
                name = step.agentName.take(3),  // Abbreviate
                status = step.status
            )
            
            if (index < pipeline.size - 1) {
                Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.CenterVertically),
                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
fun AgentChip(name: String, status: String) {
    val bgColor = when (status) {
        "active" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        "complete" -> Color(0xFF4CAF50).copy(alpha = 0.2f)
        "error" -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
    }
    
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

/**
 * Pulsing dot animation
 */
@Composable
fun PulsingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
    )
}

/**
 * Three dots loading animation
 */
@Composable
fun ThreeDotsLoading() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    
    Row {
        repeat(3) { index ->
            val delay = index * 200
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = delay),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            
            Box(
                modifier = Modifier
                    .padding(2.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
            )
        }
    }
}

/**
 * Chat message bubble
 */
@Composable
fun ChatMessageBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 320.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isUser) 16.dp else 4.dp,
                bottomEnd = if (message.isUser) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (message.isUser) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                message.content,
                modifier = Modifier.padding(12.dp),
                color = if (message.isUser) 
                    MaterialTheme.colorScheme.onPrimary 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * Chat input bar
 */
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask about routes, buses...") },
                shape = RoundedCornerShape(24.dp),
                enabled = enabled,
                singleLine = true
            )
            
            Spacer(Modifier.width(8.dp))
            
            FilledIconButton(
                onClick = onSend,
                enabled = enabled && text.isNotBlank()
            ) {
                Icon(Icons.Default.Send, "Send")
            }
        }
    }
}

data class ChatMessage(
    val id: String,
    val content: String,
    val isUser: Boolean,
    val metadata: Map<String, String>? = null,
    val thinkingSteps: List<String> = emptyList(),
    val thinkingDurationMs: Long = 0,
    val result: com.sahara.user.data.api.ProcessQueryResponse? = null
)
