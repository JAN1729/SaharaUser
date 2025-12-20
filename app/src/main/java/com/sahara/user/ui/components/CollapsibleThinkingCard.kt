package com.sahara.user.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sahara.user.ui.theme.SaharaOrange
import kotlinx.coroutines.delay

/**
 * Collapsible Thinking Card - REAL-TIME AI Reasoning Display
 * 
 * Shows reasoning tokens LIVE as they stream in from the AI.
 * Each step appears immediately when received, with animated entry.
 * 
 * States:
 * 1. ACTIVE: Shows "Thinking..." + each reasoning step animating in one by one
 * 2. COLLAPSED: Shows "Thought for X seconds ▶" (click to expand)
 * 3. EXPANDED: Shows full reasoning chain
 */
@Composable
fun CollapsibleThinkingCard(
    steps: List<String>,
    isThinking: Boolean,
    thinkingDurationMs: Long = 0,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    
    // Pulsing animation for active state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    
    // Chevron rotation animation
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(200),
        label = "chevronRotation"
    )
    
    // Auto-scroll to latest step when new step arrives (during thinking)
    LaunchedEffect(steps.size, isThinking) {
        if (isThinking && steps.isNotEmpty()) {
            listState.animateScrollToItem(steps.lastIndex)
        }
    }
    
    // Reset expansion when starting new thinking
    LaunchedEffect(isThinking) {
        if (isThinking) {
            isExpanded = false
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (!isThinking) {
                    Modifier.clickable { isExpanded = !isExpanded }
                } else Modifier
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isThinking) Color(0xFFFFFBF5) else Color(0xFFF5F5F5)
        ),
        border = if (isThinking) {
            androidx.compose.foundation.BorderStroke(1.dp, SaharaOrange.copy(alpha = 0.3f))
        } else null
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // AI Sparkle icon with pulse when active
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "AI Thinking",
                    modifier = Modifier
                        .size(16.dp)
                        .alpha(if (isThinking) pulseAlpha else 0.7f),
                    tint = SaharaOrange
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                if (isThinking) {
                    // Active state: "Thinking..."
                    Text(
                        text = "Thinking",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = Color.Black.copy(alpha = 0.8f)
                    )
                    
                    // Animated dots
                    ThinkingDots()
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    // Live step count badge
                    if (steps.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(SaharaOrange.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${steps.size} step${if (steps.size > 1) "s" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = SaharaOrange,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    
                    // Spinner
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = SaharaOrange,
                        strokeWidth = 1.5.dp
                    )
                } else {
                    // Collapsed state: "Thought for X seconds"
                    val seconds = (thinkingDurationMs / 1000.0).let { 
                        if (it < 1) "< 1" else "${it.toInt()}"
                    }
                    
                    Text(
                        text = "Thought for $seconds second${if (seconds != "1" && seconds != "< 1") "s" else ""}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = Color.Black.copy(alpha = 0.6f)
                    )
                    
                    if (steps.isNotEmpty()) {
                        Text(
                            text = " • ${steps.size} steps",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Black.copy(alpha = 0.4f)
                        )
                    }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    // Chevron indicator
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(chevronRotation),
                        tint = Color.Black.copy(alpha = 0.5f)
                    )
                }
            }
            
            // Content area - LIVE STREAMING DISPLAY
            AnimatedVisibility(
                visible = isThinking || isExpanded,
                enter = fadeIn(animationSpec = tween(150)) + 
                        expandVertically(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(100)) + 
                       shrinkVertically(animationSpec = tween(150))
            ) {
                Column(
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    // Divider
                    HorizontalDivider(
                        color = Color.Black.copy(alpha = 0.08f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    if (steps.isEmpty() && isThinking) {
                        // Waiting for first step
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = SaharaOrange.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Analyzing your query...",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontStyle = FontStyle.Italic
                                ),
                                color = Color.Black.copy(alpha = 0.5f)
                            )
                        }
                    } else {
                        // Show steps in a scrollable list (max height for long reasoning)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.heightIn(max = 250.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = steps.size,
                                key = { index -> "step_${index}_${steps[index].take(20).hashCode()}" }
                            ) { index ->
                                val step = steps[index]
                                val isLatest = index == steps.lastIndex && isThinking
                                
                                ReasoningStepCard(
                                    text = step,
                                    stepNumber = index + 1,
                                    isLatest = isLatest,
                                    isThinking = isThinking
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Individual reasoning step displayed as a mini-card
 * The latest step has a pulsing highlight effect
 */
@Composable
private fun ReasoningStepCard(
    text: String,
    stepNumber: Int,
    isLatest: Boolean,
    isThinking: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "stepPulse")
    val highlightAlpha by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "highlightAlpha"
    )
    
    // Entry animation
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(50) // Small delay for staggered effect
        isVisible = true
    }
    
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(200)) + slideInHorizontally(tween(200)) { -20 }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isLatest && isThinking) {
                        Color(0xFFFFF3E0).copy(alpha = highlightAlpha * 5)
                    } else {
                        Color.Transparent
                    }
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Step number badge
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (isLatest && isThinking) SaharaOrange.copy(alpha = 0.2f)
                        else Color.Black.copy(alpha = 0.08f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$stepNumber",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    ),
                    color = if (isLatest && isThinking) SaharaOrange else Color.Black.copy(alpha = 0.5f)
                )
            }
            
            Spacer(modifier = Modifier.width(10.dp))
            
            // Reasoning text
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        lineHeight = 18.sp
                    ),
                    color = Color.Black.copy(alpha = if (isLatest) 0.9f else 0.7f)
                )
                
                // Typing cursor for latest active step
                if (isLatest && isThinking) {
                    Spacer(modifier = Modifier.height(2.dp))
                    TypingCursor()
                }
            }
        }
    }
}

/**
 * Animated typing cursor
 */
@Composable
private fun TypingCursor() {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )
    
    Box(
        modifier = Modifier
            .width(8.dp)
            .height(2.dp)
            .alpha(alpha)
            .background(SaharaOrange)
    )
}

/**
 * Animated "..." dots for thinking state
 */
@Composable
private fun ThinkingDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    
    Row {
        repeat(3) { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 500,
                        delayMillis = index * 120,
                        easing = EaseInOut
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_$index"
            )
            
            Text(
                text = ".",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = Color.Black.copy(alpha = alpha * 0.8f)
            )
        }
    }
}
