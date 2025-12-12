package com.sahara.user.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sahara.user.ui.components.SaharaTopBar
import com.sahara.user.ui.theme.SaharaOrange
import com.sahara.user.ui.viewmodel.ChatViewModel
import com.sahara.user.ui.viewmodel.ThinkingStep
import kotlinx.coroutines.launch

@Composable
fun ThinkingScreen(
    onNavigateBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val thinkingSteps by viewModel.thinkingSteps.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll on new messages
    LaunchedEffect(messages.size, thinkingSteps.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size + if (isLoading) 1 else 0)
        }
    }

    Scaffold(
        containerColor = Color.White,
        modifier = Modifier.systemBarsPadding(), // FIX: Status Bar Collision
        topBar = {
            SaharaTopBar(
                onMenuClick = { /* TODO */ },
                onProfileClick = { /* TODO */ }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Chat History Area
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                
                // Render all messages
                items(messages) { message ->
                    Spacer(modifier = Modifier.height(16.dp))
                    if (message.isUser) {
                        UserMessageBubble(message.content)
                    } else {
                        AgentMessageBubble(message.content)
                    }
                }

                // Render "Thinking" Block if loading
                if (isLoading) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        ThinkingProcessBlock(thinkingSteps)
                    }
                } else if (messages.isNotEmpty() && !messages.last().isUser) {
                    // Optionally show collapsed thinking for the last AI response
                    // For now, let's keep it clean as requested.
                }
            }

            // Input Area
            ChatInputArea(
                text = inputText,
                onTextChange = { inputText = it },
                onSend = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    }
                },
                enabled = !isLoading
            )
        }
    }
}

@Composable
fun UserMessageBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp))
                .background(Color.White) 
                .border(1.dp, SaharaOrange, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)) // Orange Outline
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = text,
                color = Color.Black, // Black text on White
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun AgentMessageBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        // Maybe an avatar?
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp) // Don't span full width for readability
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = text,
                color = Color.Black,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

/**
 * The "Deep Reference Box" for Thinking Process
 */
@Composable
fun ThinkingProcessBlock(steps: List<ThinkingStep>) {
    var expanded by remember { mutableStateOf(true) }
    
    // Animate height or just toggle visibility
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Header
        Row(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Thinking Process...",
                style = MaterialTheme.typography.labelLarge,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(16.dp)
            )
        }

        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.LightGray, RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Column {
                    steps.forEach { step ->
                        ThinkingProcessItem(step)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ThinkingProcessItem(step: ThinkingStep) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Checkbox or Loading Spinner
        if (step.isActive && !step.isComplete) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = SaharaOrange
            )
        } else {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (step.isComplete) SaharaOrange else Color.Gray
            )
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Text(
            text = step.description,
            style = MaterialTheme.typography.bodyMedium,
            color = if (step.isActive || step.isComplete) Color.Black else Color.Gray
        )
    }
}

@Composable
fun ChatInputArea(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean
) {
    // Divider
    HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Text Field
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFFF5F5F5)) // Light Gray background
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (text.isEmpty()) {
                Text("Ask anything...", color = Color.Gray)
            }
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = onTextChange,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Black),
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // Send Button
        IconButton(
            onClick = onSend,
            enabled = enabled && text.isNotBlank(),
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, if (enabled && text.isNotBlank()) SaharaOrange else Color.LightGray, RoundedCornerShape(12.dp))
        ) {
            Icon(
                Icons.Default.ArrowUpward,
                contentDescription = "Send",
                tint = if (enabled && text.isNotBlank()) SaharaOrange else Color.LightGray
            )
        }
    }
}
