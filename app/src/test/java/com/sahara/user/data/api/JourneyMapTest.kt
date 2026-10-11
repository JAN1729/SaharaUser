package com.sahara.user.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class JourneyMapTest {
    @Test fun ksrtcRoadGuidanceIsRenderableWithoutBeingVerifiedOperatorGeometry() {
        val guide = JourneyMapSegment(status = "road_guidance", mode = "bus", provider = "ksrtc",
            evidence = "calculated_road_corridor", servicePathVerified = false,
            polyline = listOf(MapCoordinate(11.0, 75.0), MapCoordinate(11.001, 75.001)))
        val map = JourneyMap("j1", dataMode = "live", segments = listOf(guide), geometryComplete = true, guidanceComplete = true)
        assertEquals(listOf(guide), map.renderableSegments)
        assertTrue(map.hasRoadGuidance)
        assertTrue(map.hasCompleteGuidance)
        assertFalse(map.hasCompleteGeometry)
        assertTrue(map.copy(segments = listOf(guide.copy(mode = "train"))).renderableSegments.isEmpty())
        assertTrue(map.copy(segments = listOf(guide.copy(provider = "unverified"))).renderableSegments.isEmpty())
        assertTrue(map.copy(segments = listOf(guide.copy(servicePathVerified = true))).renderableSegments.isEmpty())
    }

    @Test fun absentProvenanceCannotBePresentedAsLive() {
        val map = Json { ignoreUnknownKeys = true }.decodeFromString<JourneyMap>("""{"journey_id":"j1"}""")
        assertEquals("unknown", map.dataMode)
    }
    @Test fun missingGeometryShowsPinsWithoutInventingLines() {
        val map = Json { ignoreUnknownKeys = true }.decodeFromString<JourneyMap>("""
          {"journey_id":"j1","data_mode":"sample","geometry_complete":false,
           "pins":[{"id":"sbc","name":"Bengaluru","lat":12.97,"lng":77.59,"evidence":"approximate_seed_terminal"}],
           "segments":[{"status":"unavailable","mode":"train","origin":"sbc","destination":"csmt","polyline":[],"notice":"No source shape"}],"vehicles":[]}
        """.trimIndent())
        assertEquals("sample", map.dataMode)
        assertEquals(1, map.validPins.size)
        assertTrue(map.renderableSegments.isEmpty())
        assertFalse(map.geometryComplete)
    }

    @Test fun corruptOrUnavailableSegmentIsRejectedInFull() {
        val good = JourneyMapSegment(status = "mapped", mode = "train",
            polyline = listOf(MapCoordinate(12.0, 77.0), MapCoordinate(13.0, 78.0)))
        val corrupt = good.copy(polyline = good.polyline + MapCoordinate(91.0, 78.0))
        val map = JourneyMap("j1", segments = listOf(good, corrupt, good.copy(status = "unavailable")),
            pins = listOf(JourneyMapPin("bad", "Bad", Double.NaN, 77.0)))
        assertEquals(listOf(good), map.renderableSegments)
        assertTrue(map.validPins.isEmpty())
    }

    @Test fun onePointIsNotARouteAndCoordinatesMustBeFinite() {
        assertFalse(MapCoordinate(1.0, Double.POSITIVE_INFINITY).isValid)
        assertFalse(MapCoordinate(1.0, 181.0).isValid)
        assertTrue(JourneyMap("j1", segments = listOf(JourneyMapSegment(status = "mapped",
            polyline = listOf(MapCoordinate(12.0, 77.0))))).renderableSegments.isEmpty())
    }
}
