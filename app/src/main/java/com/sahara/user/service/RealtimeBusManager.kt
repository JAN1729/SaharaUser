package com.sahara.user.service

import android.util.Log
import com.sahara.user.data.api.SupabaseApiClient
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.broadcastFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BusMovementUpdate(
    val bus_id: String,
    val latitude: Double,
    val longitude: Double,
    val heading: Float = 0f,
    val speed: Float = 0f,
    val timestamp: Long
)

/**
 * RealtimeBusManager - Handles live bus movement via Supabase Realtime Channels.
 * 
 * Part of Phase 5: Real-time Movement Integration.
 * Uses 'bus-movements' broadcast channel for high-performance updates.
 */
@Singleton
class RealtimeBusManager @Inject constructor(
    private val supabaseApiClient: SupabaseApiClient
) {
    companion object {
        private const val TAG = "RealtimeBusManager"
        private const val CHANNEL_BUS_MOVEMENTS = "bus-movements"
        private const val EVENT_LOCATION_UPDATE = "location_update"
    }

    private val client = supabaseApiClient.client

    /**
     * Subscribe to live bus movement updates.
     * 
     * @param filterBusId Optional specific bus ID to filter for (client-side)
     * @return Flow of movement updates
     */
    fun listenForMovements(filterBusId: String? = null): Flow<BusMovementUpdate> {
        // Use the client.channel() extension function (supabase-kt 2.x/3.x pattern)
        val channel = client.channel(CHANNEL_BUS_MOVEMENTS)
        
        Log.d(TAG, "Subscribing to $CHANNEL_BUS_MOVEMENTS${filterBusId?.let { " (filter: $it)" } ?: ""}")
        
        return channel.broadcastFlow<BusMovementUpdate>(EVENT_LOCATION_UPDATE)

            .onStart {
                channel.subscribe()
                Log.d(TAG, "Channel $CHANNEL_BUS_MOVEMENTS subscribed")
            }
            .onCompletion {
                channel.unsubscribe()
                Log.d(TAG, "Channel $CHANNEL_BUS_MOVEMENTS unsubscribed")
            }
    }
}
