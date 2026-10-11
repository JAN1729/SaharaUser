package com.sahara.user.ui.components

import com.sahara.user.data.api.JourneyOption
import org.junit.Assert.*
import org.junit.Test

class JourneyFareTest {
    @Test fun missingAllFaresDoesNotLookLikeAFreeJourney() {
        assertEquals("Fare incomplete", fare(JourneyOption("j", "", "")))
    }

    @Test fun partialKnownFareIsDistinguishedFromACompleteTotal() {
        assertEquals("₹450 known · total incomplete", fare(JourneyOption("j", "", "", knownFareMinor = 45000)))
    }

    @Test fun VerifiedZeroTotalCanStillBeShown() {
        assertEquals("₹0", fare(JourneyOption("j", "", "", totalFareMinor = 0)))
    }
}
