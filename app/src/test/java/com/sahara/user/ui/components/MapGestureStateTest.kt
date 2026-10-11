package com.sahara.user.ui.components

import org.junit.Assert.*
import org.junit.Test

class MapGestureStateTest {
    @Test fun horizontalPanStaysCapturedUntilEndEvenWhenDirectionChanges() {
        val gesture = MapGestureState(10f)
        gesture.begin(0f, 0f)
        assertTrue(gesture.capture(30f, 0f, 1))
        assertTrue(gesture.capture(30f, 100f, 1))
        gesture.end()
        gesture.begin(0f, 0f)
        assertFalse(gesture.capture(0f, 100f, 1))
    }

    @Test fun liftingOneFingerDoesNotHandAnActiveMapGestureToTheConversation() {
        val gesture = MapGestureState(10f)
        gesture.begin(0f, 0f)
        assertTrue(gesture.capture(0f, 0f, 2))
        assertTrue(gesture.capture(0f, 40f, 1))
        gesture.end()
        assertFalse(gesture.capture(0f, 0f, 1))
    }
}
