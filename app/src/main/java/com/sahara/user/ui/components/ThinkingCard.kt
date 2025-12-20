package com.sahara.user.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sahara.user.ui.theme.SaharaOrange
import kotlinx.coroutines.delay

/**
 * Thinking Card with Real-Time AI Reasoning Animation
 * 
 * Displays a dynamic view of the AI's thought process as it reasons through
 * the user's query. Now powered by OpenRouter's reasoning_details, showing
 * authentic AI chain-of-thought.
 */
@Composable
fun ThinkingCard(
    steps: List<String>,
    isVisible: Boolean,
    modifier: Modifier = Modifier
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    
    // Pulsing animation for the AI indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    // Cycle through reasoning steps
    LaunchedEffect(isVisible, steps) {
        if (isVisible && steps.isNotEmpty()) {
            currentIndex = 0
            while (isVisible) {
                delay(1500) // Faster cycling for dynamic feel (1.5s per step)
                if (currentIndex < steps.lastIndex) {
                    currentIndex++
                } else {
                    // Stay on last step (usually "Finalizing response...")
                    // Don't cycle back for authentic reasoning flow
                    break
                }
            }
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(300)) + 
                slideInVertically(initialOffsetY = { it / 2 }, animationSpec = tween(300)),
        exit = fadeOut(animationSpec = tween(200)) + 
               slideOutVertically(targetOffsetY = { -it / 2 }, animationSpec = tween(200))
    ) {
        LiquidGlassCard(
            modifier = modifier
                .wrapContentSize()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Header with pulsing AI indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "AI Reasoning",
                        modifier = Modifier
                            .size(18.dp)
                            .alpha(pulseAlpha),
                        tint = SaharaOrange
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = "AI is thinking...",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp
                        ),
                        color = SaharaOrange
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // Small progress indicator
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = SaharaOrange.copy(alpha = 0.6f),
                        strokeWidth = 1.5.dp
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Animated reasoning text
                AnimatedContent(
                    targetState = if (steps.isNotEmpty() && currentIndex < steps.size) 
                                     steps[currentIndex] 
                                  else "Processing...",
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(200)) + 
                         slideInVertically(animationSpec = tween(200)) { height -> height / 4 }) togetherWith
                        (fadeOut(animationSpec = tween(150)) + 
                         slideOutVertically(animationSpec = tween(150)) { height -> -height / 4 })
                    },
                    label = "ReasoningText"
                ) { targetText ->
                    Text(
                        text = "\"$targetText\"",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontStyle = FontStyle.Italic
                        ),
                        color = Color.Black.copy(alpha = 0.85f),
                        maxLines = 2
                    )
                }
            }
        }
    }
}
