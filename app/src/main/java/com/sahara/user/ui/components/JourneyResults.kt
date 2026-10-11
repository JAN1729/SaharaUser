package com.sahara.user.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sahara.user.data.api.*
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun clock(value: String): String = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Kolkata"))
        .format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH))
}.getOrDefault(value)
private fun money(value: Long, currency: String = "INR") = (if (currency == "INR") "₹" else "$currency ") +
    String.format(Locale.ENGLISH, if (value % 100 == 0L) "%,.0f" else "%,.2f", value / 100.0)
private fun readable(value: String) = value.replace('_', ' ')
private fun duration(journey: JourneyOption) = "${journey.durationMinutes / 60}h ${journey.durationMinutes % 60}m"
internal fun fare(journey: JourneyOption) = journey.totalFareMinor?.let { money(it, journey.currency) }
    ?: if (journey.knownFareMinor > 0) "${money(journey.knownFareMinor, journey.currency)} known · total incomplete" else "Fare incomplete"

@Composable
fun JourneyResults(response: ProcessQueryResponse, loadMap: suspend (String) -> JourneyMap, mapAvailable: Boolean = true,
    companion: JourneyFollowState? = null,
    followingId: String? = null, onFollow: (JourneyOption, Boolean) -> Unit = { _, _ -> }) {
    var selectedId by rememberSaveable(response.searchId) { mutableStateOf(response.journeys.firstOrNull()?.id) }
    var compare by rememberSaveable(response.searchId) { mutableStateOf(false) }
    var sources by rememberSaveable(response.searchId) { mutableStateOf(false) }
    var recovered by remember(response.searchId) { mutableStateOf<List<JourneyOption>>(emptyList()) }
    val guidance = companion?.guidance
    val options = (response.journeys + recovered).distinctBy { it.id }
    val selected = options.firstOrNull { it.id == selectedId } ?: options.firstOrNull()
    LaunchedEffect(response.journeys.map { it.id }) {
        if (options.none { it.id == selectedId }) selectedId = options.firstOrNull()?.id
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (response.journeys.isEmpty()) ConversationBubble(response.response, false)
        else {
            ConversationBubble(when (response.dataMode) {
                "sample" -> "Here’s a journey to explore. These schedules and fares are illustrative."
                "live" -> "Here are the options from the sources checked so far. Fares and availability may change."
                else -> "The source mode wasn’t supplied. Treat these journey options as unverified."
            }, false)
            selected?.let { journey ->
                if (mapAvailable) key(journey.id) { JourneyMapPanel(journey.id, loadMap,
                    guidance?.takeIf { it.journeyId == journey.id }?.nearbyFleet?.vehicles.orEmpty(),
                    guidance?.takeIf { it.journeyId == journey.id }?.revalidation?.checkedAt) }
                else Text("Connect to the central server to see journey maps.", style = MaterialTheme.typography.bodySmall)
                JourneyCard(journey, response.placeNames, response.dataMode, journey.id == response.journeys.first().id)
                if (mapAvailable && companion != null) key(journey.id) {
                    JourneyFollowPanel(journey, companion, { alternative ->
                        recovered = (recovered + alternative).distinctBy { it.id }
                        onFollow(journey, false)
                        selectedId = alternative.id
                    }, followingId == journey.id, onFollow)
                }
            }
            if (response.journeys.size > 1) {
                Surface(shape = RoundedCornerShape(16.dp), color = ChatGray, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { compare = !compare }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                        Icon(Icons.Default.CompareArrows, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(if (compare) "Hide other routes" else "Compare other routes", Modifier.weight(1f))
                        Icon(if (compare) Icons.Default.ExpandLess else Icons.Default.ChevronRight, null)
                    }
                }
                if (compare) options.forEachIndexed { index, option ->
                    OutlinedCard(onClick = { selected?.let { onFollow(it, false) }; selectedId = option.id }, modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(if (option.id == selected?.id) 2.dp else 1.dp,
                            if (option.id == selected?.id) MaterialTheme.colorScheme.primary else ChatBorder),
                        colors = CardDefaults.outlinedCardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Route ${index + 1} · ${duration(option)}", fontWeight = FontWeight.SemiBold)
                            Text(fare(option))
                            Text(option.legs.joinToString(" + ") { readable(it.mode) }, style = MaterialTheme.typography.bodySmall)
                            Text("${clock(option.departure)} → ${clock(option.arrival)}", style = MaterialTheme.typography.bodySmall)
                            if (option.id == selected?.id) Text("Selected", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        if (!response.collectionComplete && response.collectionError == null)
            JourneyProgress("More journey options are still loading.", response.pendingProviders)
        response.collectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { sources = !sources }) { Text(if (sources) "Hide source coverage" else "Sources and coverage") }
        if (sources) {
            response.providers.forEach { source ->
                Text("${source.provider}: ${readable(source.status)}${if (source.cached) " (cached)" else ""}", style = MaterialTheme.typography.bodySmall)
                if (source.message.isNotBlank()) Text(source.message, style = MaterialTheme.typography.bodySmall)
            }
            response.coverage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun JourneyCard(journey: JourneyOption, places: Map<String, String>, dataMode: String?, recommended: Boolean) {
    val sample = dataMode == "sample"
    val unknown = dataMode !in listOf("sample", "live")
    var details by rememberSaveable(journey.id) { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, ChatBorder),
        colors = CardDefaults.outlinedCardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (recommended) Surface(shape = RoundedCornerShape(5.dp), color = ChatPeach) {
                Text("Recommended${if (sample) " · demo" else if (unknown) " · unverified" else ""}", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
            Text((if (sample) "Example " else if (unknown) "Unverified " else "") + fare(journey), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${duration(journey)} · ${clock(journey.departure)} → ${clock(journey.arrival)}", style = MaterialTheme.typography.bodySmall)
            journey.connections.filter { it.kind == "access" }.forEach { ConnectionRow(it, places, sample) }
            journey.legs.forEachIndexed { index, leg ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).size(9.dp).background(modeColor(leg.mode), CircleShape))
                    Icon(when (leg.mode) { "train", "metro" -> Icons.Default.Train; "flight" -> Icons.Default.Flight;
                        "bus", "local" -> Icons.Default.DirectionsBus; "auto", "private" -> Icons.Default.DirectionsCar;
                        "ferry" -> Icons.Default.DirectionsBoat; else -> Icons.Default.DirectionsWalk },
                        null, Modifier.size(18.dp), tint = modeColor(leg.mode))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${readable(leg.mode).replaceFirstChar { it.uppercase() }} · ${leg.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("${places[leg.origin] ?: leg.origin} → ${places[leg.destination] ?: leg.destination}", style = MaterialTheme.typography.bodySmall)
                        Text(leg.fareMinor?.let { money(it, leg.currency) + (if (sample) " example" else "") +
                            (if (leg.fareBasis == "party") " for the group" else " per person") } ?: "Fare not available", style = MaterialTheme.typography.bodySmall)
                    }
                }
                journey.legs.getOrNull(index + 1)?.let { next ->
                    journey.connections.filter { it.kind == "transfer" && it.origin == leg.destination && it.destination == next.origin }
                        .forEach { ConnectionRow(it, places, sample) }
                }
            }
            journey.connections.filter { it.kind == "egress" }.forEach { ConnectionRow(it, places, sample) }
            Text(if (sample) "Illustrative schedules, fares and availability." else "Fares and availability need rechecking before travel.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (journey.warnings.isNotEmpty()) Text(journey.warnings.joinToString(" · ") { readable(it) }, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) { Text(if (details) "Hide journey details" else "Journey details and sources") }
            if (details) {
                journey.legs.forEach { leg ->
                    HorizontalDivider(color = ChatBorder)
                    Text(leg.name + (leg.fareClass?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.titleSmall)
                    Text("${clock(leg.estimatedDeparture ?: leg.departure)} → ${clock(leg.estimatedArrival ?: leg.arrival)}", style = MaterialTheme.typography.bodySmall)
                    Text("${readable(leg.availability)}${leg.seats?.let { " · $it seats shown" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                    Text("${leg.observation.provider} · ${readable(leg.observation.evidence)} · fetched ${clock(leg.observation.fetchedAt)}", style = MaterialTheme.typography.bodySmall)
                    Text(leg.observation.observedAt?.let { "Source updated ${clock(it)}" } ?: "Source update time unknown", style = MaterialTheme.typography.bodySmall)
                    val url = leg.observation.sourceUrl ?: leg.bookingUrl
                    if (url?.startsWith("https://") == true) TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text("View source") }
                }
                journey.connections.forEach { connection ->
                    Text("${readable(connection.kind).replaceFirstChar { it.uppercase() }}: ${connection.transferMinutes} min travel, ${connection.processingMinutes} min processing" +
                        if (connection.kind == "transfer") ", ${connection.bufferMinutes} min buffer, ${connection.spareMinutes} min spare" else "", style = MaterialTheme.typography.bodySmall)
                }
                if (journey.warnings.isNotEmpty()) Text(journey.warnings.joinToString("\n") { readable(it) }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ConnectionRow(connection: JourneyConnection, places: Map<String, String>, sample: Boolean) {
    val transfer = connection.transfer
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.CompareArrows, null, Modifier.size(18.dp), tint = Color(0xFF69727F))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${readable(connection.kind).replaceFirstChar { it.uppercase() }} connection · ${connection.transferMinutes} min travel" +
                (if (connection.processingMinutes > 0) " + ${connection.processingMinutes} min processing" else ""), style = MaterialTheme.typography.bodyMedium)
            Text("${places[connection.origin] ?: connection.origin} → ${places[connection.destination] ?: connection.destination}", style = MaterialTheme.typography.bodySmall)
            if (transfer != null) Text("${readable(transfer.mode)} · ${readable(transfer.evidence)} · " +
                (transfer.fareMinor?.let { money(it, transfer.currency) + (if (sample) " example" else "") + " per person" } ?: "fare not available"),
                style = MaterialTheme.typography.bodySmall)
            if (connection.kind == "transfer") Text("${connection.bufferMinutes} min buffer · ${connection.spareMinutes} min spare", style = MaterialTheme.typography.bodySmall)
        }
    }
}
