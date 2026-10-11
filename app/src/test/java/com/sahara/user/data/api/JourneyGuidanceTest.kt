package com.sahara.user.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class JourneyGuidanceTest {
    @Test fun guidanceRetainsProgressAndExplicitlyUnverifiedConnectionTiming() {
        val guidance=Json { ignoreUnknownKeys=true }.decodeFromString<JourneyGuidance>("""
            {"journey_id":"j1","data_mode":"live","status":"onboard","current_leg_index":0,
             "boarding":{"place_id":"kty","name":"Kottayam","precision":"approximate","departure":"2026-10-11T08:00:00+05:30"},
             "next_connection":{"status":"unverified","can_make":true,"verified_arrival":false,"spare_minutes":27,"message":"Timetable only"},
             "progress":{"completed_leg_ids":[],"onboard_leg_id":"l1"},"vehicles":[],
             "alerts":["Arrival is not verified"],
             "nearby_fleet":{"status":"empty","vehicles":[],"notice":"No registered vehicle"}}
        """.trimIndent())
        assertEquals("l1",guidance.progress.onboardLegId)
        assertFalse(guidance.nextConnection!!.verifiedArrival)
        assertEquals("approximate",guidance.boarding!!.precision)
        assertTrue(guidance.nearbyFleet.vehicles.isEmpty())
        assertEquals("Arrival is not verified", guidance.alerts.single())
    }

    @Test fun progressRequestIncludesExplicitCompletedPrefixAndOnboardState() {
        val encoded=Json { encodeDefaults=true }.encodeToString(TripProgress.serializer(),TripProgress(listOf("l1"),"l2"))
        assertTrue(encoded.contains("\"completed_leg_ids\":[\"l1\"]"))
        assertTrue(encoded.contains("\"onboard_leg_id\":\"l2\""))
    }

    @Test fun fleetRejectsStaleFutureAndInvalidPositions() {
        val now = java.time.Instant.parse("2026-10-11T10:00:00Z")
        val vehicle = FleetVehicle("v1", "Bus", 9.6, 76.5, "2026-10-11T09:59:00Z")
        assertTrue(vehicle.isFresh(now))
        assertFalse(vehicle.copy(observedAt="2026-10-11T09:58:00Z").isFresh(now))
        assertFalse(vehicle.copy(observedAt="2026-10-11T10:01:00Z").isFresh(now))
        assertFalse(vehicle.copy(lat=Double.NaN).isFresh(now))
    }

    @Test fun absentTransferEvidenceKeepsConnectionTimingUnknown() {
        val guidance = Json { ignoreUnknownKeys=true }.decodeFromString<JourneyGuidance>("""{"journey_id":"j","next_connection":{"status":"unverified","can_make":false,"verified_arrival":false,"spare_minutes":null,"message":"Transfer evidence missing"}}""")
        assertNull(guidance.nextConnection!!.spareMinutes)
        assertFalse(guidance.nextConnection!!.canMake)
    }
}
