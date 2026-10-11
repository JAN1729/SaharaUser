package com.sahara.user.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.sahara.user.data.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun confirmedProgress(journey: JourneyOption, guidance: JourneyGuidance, complete: Boolean): TripProgress? {
    val done = guidance.progress.completedLegIds
    if (done != journey.legs.take(done.size).map { it.id }) return null
    val leg = journey.legs.getOrNull(done.size) ?: return null
    if (leg.id.isBlank()) return null
    val onboard = guidance.progress.onboardLegId
    if (onboard != null && onboard != leg.id) return null
    return if (complete && onboard == leg.id) TripProgress(done + leg.id, null)
    else if (!complete && onboard == null) TripProgress(done, leg.id) else null
}

internal fun validTravelStatus(journey: JourneyOption, progress: TripProgress): Boolean {
    val done = progress.completedLegIds
    return done == journey.legs.take(done.size).map { it.id } &&
        (progress.onboardLegId == null || journey.legs.getOrNull(done.size)?.id == progress.onboardLegId)
}

internal fun confirmationApplied(journey: JourneyOption, saved: TripProgress, target: TripProgress): Boolean {
    if (target.repairProgress) return validTravelStatus(journey, saved)
    if (!validTravelStatus(journey, saved) || saved.completedLegIds.take(target.completedLegIds.size) != target.completedLegIds) return false
    if (saved.completedLegIds.size > target.completedLegIds.size) return true
    return saved.completedLegIds.size == target.completedLegIds.size &&
        (target.onboardLegId == null || saved.onboardLegId == target.onboardLegId)
}

internal fun connectionSummary(connection: ConnectionGuidance): String = when {
    connection.canMake && connection.spareMinutes != null -> "${connection.spareMinutes} min connection margin"
    connection.canMake -> "Connection margin unavailable"
    connection.status == "unverified" -> "Connection timing unavailable"
    else -> "Connection at risk"
}

@Stable
class JourneyFollowState(val journeyId: String?) {
    var guidance by mutableStateOf<JourneyGuidance?>(null)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var command by mutableStateOf<TripProgress?>(null)
    var revision by mutableIntStateOf(0)
    var refreshedAt = 0L
    fun confirm(progress: TripProgress) { command = progress; revision++ }
    fun refresh() { refreshedAt = 0L; revision++ }
    fun handleFailure(failure: Exception) {
        if (failure is TripConfirmationRejected) command = null
        error = failure.message ?: "Couldn't update this journey. Try again."
        guidance = guidance?.copy(nearbyFleet = NearbyFleet(notice = "Fleet updates unavailable."))
    }
}

// Owned by the screen above virtualized message content; scrolling cannot stop a trip.
@Composable
fun rememberJourneyFollower(journey: JourneyOption?, load: suspend (String, TripProgress?) -> JourneyGuidance,
    revalidate: suspend (String) -> Unit): JourneyFollowState {
    val state = remember(journey?.id) { JourneyFollowState(journey?.id) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val loader by rememberUpdatedState(load)
    val refresher by rememberUpdatedState(revalidate)
    LaunchedEffect(journey?.id, state.revision, lifecycle) {
        if (journey == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                state.busy = true
                try {
                    val progress = state.command
                    state.guidance = loader(journey.id, null)
                    if (progress != null) {
                        if (!confirmationApplied(journey, state.guidance!!.progress, progress)) {
                            state.guidance = loader(journey.id, progress)
                        }
                        state.command = null
                        state.refreshedAt = System.nanoTime() // /progress already refreshes sources.
                    }
                    if (state.refreshedAt == 0L || System.nanoTime() - state.refreshedAt >= 90_000_000_000L) {
                        refresher(journey.id)
                        state.refreshedAt = System.nanoTime()
                        state.guidance = loader(journey.id, null)
                    }
                    state.error = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { state.handleFailure(failure) }
                finally { state.busy = false }
                delay(10_000)
            }
        }
    }
    return state
}

@Composable
fun JourneyFollowPanel(journey: JourneyOption, state: JourneyFollowState,
    onChooseAlternative: (JourneyOption) -> Unit, following: Boolean, onFollow: (JourneyOption, Boolean) -> Unit) {
    val guidance = state.guidance.takeIf { following }
    val error = state.error.takeIf { following }
    val busy = state.busy && following
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val updateFollowing by rememberUpdatedState(onFollow)
    val visibleFleet by produceState<List<FleetVehicle>>(emptyList(), guidance?.nearbyFleet?.vehicles, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { value = guidance?.nearbyFleet?.vehicles.orEmpty().filter { it.isFresh() }; delay(5_000) }
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Travel companion", fontWeight = FontWeight.SemiBold)
            if (!following) {
                Text("Follow this route for boarding guidance, connection checks and refreshed alternatives while this screen is open.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { updateFollowing(journey, true) }) { Text("Follow this journey") }
            } else {
                Text(if (guidance?.dataMode == "sample") "Demo guidance · not live travel updates" else "Updates while this screen is open", style = MaterialTheme.typography.labelMedium)
                guidance?.let { current ->
                    Text(current.status.replace('_', ' ').replaceFirstChar { it.uppercase() }, color = MaterialTheme.colorScheme.primary)
                    if (current.updatedAt.isNotBlank()) Text("Guidance checked: " + runCatching {
                        OffsetDateTime.parse(current.updatedAt).atZoneSameInstant(ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))
                    }.getOrDefault(current.updatedAt), style = MaterialTheme.typography.bodySmall)
                    current.boarding?.let { board ->
                        Text("Board at ${board.name}${if (board.precision != "exact") " · approximate terminal" else ""}")
                        board.departure?.let { value -> Text("Departure: " + runCatching {
                            OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))
                        }.getOrDefault(value), style = MaterialTheme.typography.bodySmall) }
                        Text(board.instructions, style = MaterialTheme.typography.bodySmall)
                    }
                    current.nextConnection?.let { connection ->
                        Text(connectionSummary(connection), fontWeight = FontWeight.Medium)
                        Text(connection.message, style = MaterialTheme.typography.bodySmall)
                        if (!connection.verifiedArrival) Text("Based on timetable timing; arrival is unverified.", style = MaterialTheme.typography.bodySmall)
                    }
                    current.alerts.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    current.revalidation?.let { check -> Text(check.message, style = MaterialTheme.typography.bodySmall) }
                    if (visibleFleet.isEmpty()) Text(current.nearbyFleet.notice, style = MaterialTheme.typography.bodySmall)
                    else {
                        Text("Nearby participating vehicles", fontWeight = FontWeight.Medium)
                        visibleFleet.forEach { vehicle -> Text("${vehicle.name} · ${vehicle.distanceM} m from terminal · operator GPS", style = MaterialTheme.typography.bodySmall) }
                        Text("Nearby vehicles are not confirmed services on this itinerary.", style = MaterialTheme.typography.bodySmall)
                    }
                    val completing = current.progress.onboardLegId != null
                    val progress = confirmedProgress(journey, current, completing)
                    if (progress != null) TextButton(onClick = { state.confirm(progress) }, enabled = !busy) {
                        Text(if (completing) "I’ve completed this leg" else "I’ve boarded this leg")
                    }
                    if (progress == null && current.status == "needs_progress" && !validTravelStatus(journey, current.progress)) {
                        TextButton(onClick = { state.confirm(TripProgress(repairProgress=true)) }, enabled = !busy) { Text("Confirm travel status again") }
                    }
                    current.alternatives.forEachIndexed { index, alternative ->
                        TextButton(onClick = { onChooseAlternative(alternative) }, enabled = !busy) {
                            Text("Alternative ${index + 1}: ${fare(alternative)} · ${alternative.durationMinutes} min")
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = { state.refresh() }, enabled = !busy) { Text("Refresh") }
                    TextButton(onClick = { updateFollowing(journey, false); state.command = null }) { Text("Stop following") }
                }
            }
        }
    }
}
