package com.sahara.user.ui.components

import com.sahara.user.data.api.*
import org.junit.Assert.*
import org.junit.Test

class TripConfirmationTest {
    private val observation = SourceObservation("sample", "fixture", "2026-10-11T10:00:00Z")
    private fun leg(id: String) = JourneyLeg("Service", "bus", "kty", "ers", "2026-10-11T10:00:00Z", "2026-10-11T11:00:00Z", observation=observation, id=id)
    private val route = JourneyOption("j", "2026-10-11T10:00:00Z", "2026-10-11T12:00:00Z", legs=listOf(leg("a"),leg("b")))

    @Test fun completionRequiresExplicitOnboardState() {
        val waiting = JourneyGuidance("j")
        assertNull(confirmedProgress(route, waiting, true))
        assertEquals(TripProgress(emptyList(), "a"), confirmedProgress(route, waiting, false))
        val aboard = waiting.copy(progress=TripProgress(emptyList(), "a"))
        assertEquals(TripProgress(listOf("a"), null), confirmedProgress(route, aboard, true))
        assertNull(confirmedProgress(route, aboard, false))
    }

    @Test fun invalidPrefixCannotSkipAConnection() {
        assertNull(confirmedProgress(route, JourneyGuidance("j", progress=TripProgress(listOf("b"))), false))
        assertNull(confirmedProgress(route, JourneyGuidance("j", progress=TripProgress(emptyList(), "b")), true))
        assertEquals(TripProgress(listOf("a"), "b"), confirmedProgress(route, JourneyGuidance("j", progress=TripProgress(listOf("a"))), false))
    }

    @Test fun interruptedConfirmationDoesNotUndoLaterBoardingOrRepeatAppliedRepair() {
        assertTrue(confirmationApplied(route, TripProgress(listOf("a"), "b"), TripProgress(listOf("a"))))
        assertTrue(confirmationApplied(route, TripProgress(listOf("a")), TripProgress(emptyList(), "a")))
        assertFalse(confirmationApplied(route, TripProgress(), TripProgress(emptyList(), "a")))
        assertTrue(confirmationApplied(route, TripProgress(), TripProgress(repairProgress=true)))
        assertFalse(confirmationApplied(route, TripProgress(listOf("bad")), TripProgress(repairProgress=true)))
    }

    @Test fun unknownTransportOutcomePreservesIntentUntilReconciliation() {
        val state = JourneyFollowState("j")
        val intent = TripProgress(emptyList(), "a")
        state.confirm(intent)
        state.handleFailure(java.io.IOException("Disconnected"))
        assertEquals(intent, state.command)
        state.handleFailure(TripConfirmationRejected("Not departed"))
        assertNull(state.command)
    }

    @Test fun unknownConnectionMarginDoesNotDisplayNullOrZero() {
        assertEquals("Connection margin unavailable", connectionSummary(ConnectionGuidance(canMake=true)))
        assertEquals("Connection timing unavailable", connectionSummary(ConnectionGuidance()))
    }
}
