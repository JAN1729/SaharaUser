package com.sahara.user.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class JourneyContractTest {
    @Test fun fullJourneyRetainsAccessAndEgressInsteadOfOnlyServiceTerminals() {
        val journey = Json { ignoreUnknownKeys = true }.decodeFromString<JourneyOption>("""
            {"id":"j1","departure":"2026-10-11T01:50:00+05:30","arrival":"2026-10-11T09:55:00+05:30",
             "connections":[{"kind":"access","origin":"kty","destination":"cok","transfer_minutes":90,
                 "transfer":{"mode":"local","fare_minor":null,"evidence":"estimated"}},
                {"kind":"egress","origin":"blr","destination":"sbc","transfer_minutes":60}]}
        """.trimIndent())
        assertEquals("kty", journey.origin)
        assertEquals("sbc", journey.destination)
        assertEquals("local", journey.connections.first().transfer?.mode)
        assertNull(journey.connections.first().transfer?.fareMinor)
    }
    @Test fun partialSearchAndUnknownFareSurviveAndroidDecoding() {
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<ProcessQueryResponse>("""
            {"success":true,"response":"Options","searchId":"search-1","dataMode":"live",
             "collectionComplete":false,"pendingProviders":["ksrtc"],
             "providers":[{"provider":"confirmtkt","status":"partial","message":"More checks pending"}],
             "placeNames":{"ers":"Ernakulam Junction"},
             "journeys":[{"id":"j1","departure":"2026-10-15T08:00:00+05:30","arrival":"2026-10-15T18:00:00+05:30",
               "duration_minutes":600,"total_fare_minor":null,"known_fare_minor":45000,"warnings":["fare_incomplete"],
               "legs":[{"name":"Express","mode":"train","origin":"ers","destination":"sbc",
                 "departure":"2026-10-15T08:00:00+05:30","arrival":"2026-10-15T18:00:00+05:30",
                 "observation":{"provider":"confirmtkt","evidence":"cached","fetched_at":"2026-10-10T08:00:00Z"}}]}]}
        """.trimIndent())
        assertFalse(response.collectionComplete)
        assertEquals(listOf("ksrtc"), response.pendingProviders)
        assertNull(response.journeys.single().totalFareMinor)
        assertEquals(45000L, response.journeys.single().knownFareMinor)
        assertEquals("cached", response.journeys.single().legs.single().observation.evidence)
    }
}
