package com.sahara.user.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

val ChatGray = Color(0xFFF4F5F7)
val ChatPeach = Color(0xFFFCEBDE)
val ChatBorder = Color(0xFFE4E6EA)

@Composable
fun JourneyComposer(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit,
                    canSend: Boolean, placeholder: String = "Ask Sahara…") {
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(Modifier.weight(1f), shape = RoundedCornerShape(28.dp), color = ChatGray, border = BorderStroke(1.dp, ChatBorder)) {
            Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(20.dp), tint = Color(0xFF596170))
                BasicTextField(value = value, onValueChange = onValueChange,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Journey message" }, maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Black),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend && value.isNotBlank()) onSend() }),
                    decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, color = Color(0xFF596170)); inner() } })
            }
        }
        FilledIconButton(onClick = onSend, enabled = canSend && value.isNotBlank(), modifier = Modifier.size(52.dp), shape = CircleShape) {
            Icon(Icons.Default.Send, "Send journey request", Modifier.size(22.dp))
        }
    }
}

@Composable
fun ConversationBubble(text: String, isUser: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top) {
        if (!isUser) {
            Surface(Modifier.padding(top = 4.dp).size(34.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                Box(contentAlignment = Alignment.Center) { Text("S", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.width(10.dp))
        } else Spacer(Modifier.width(48.dp))
        Surface(Modifier.widthIn(max = 340.dp).weight(1f, fill = false), shape = RoundedCornerShape(20.dp),
            color = if (isUser) ChatPeach else ChatGray) {
            Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        }
        if (!isUser) Spacer(Modifier.width(16.dp))
    }
}

@Composable
fun JourneyProgress(description: String, pending: List<String> = emptyList()) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, ChatBorder),
        colors = CardDefaults.outlinedCardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Checking routes and connections", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
            if (pending.isNotEmpty()) Text("Still checking ${pending.joinToString { it.replace('_', ' ') }}.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = ChatBorder)
            Text("I’m comparing fares and checking that each connection leaves enough time.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
