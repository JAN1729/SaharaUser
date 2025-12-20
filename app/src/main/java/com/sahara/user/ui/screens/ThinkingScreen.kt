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
import com.sahara.user.ui.components.CollapsibleThinkingCard
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
                        // Show collapsible thinking card above AI response (if has thinking steps)
                        if (message.thinkingSteps.isNotEmpty()) {
                            CollapsibleThinkingCard(
                                steps = message.thinkingSteps,
                                isThinking = false,
                                thinkingDurationMs = message.thinkingDurationMs
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        AgentMessageBubble(message.content)
                    }
                }

                // Render "Thinking" Block if loading
                if (isLoading) {
                    item {
                         Spacer(modifier = Modifier.height(16.dp))
                         // Collapsible thinking card in active state
                         CollapsibleThinkingCard(
                             steps = thinkingSteps.map { it.description },
                             isThinking = true,
                             thinkingDurationMs = 0
                         )
                    }
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
