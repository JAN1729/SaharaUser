package com.sahara.user.data.api

import org.junit.Assert.*
import org.junit.Test

class EventFramesTest {
    @Test fun eventIsNotEmittedBeforeItsDelimiter() {
        val frames = EventFrames()
        assertNull(frames.push("{\"type\":\"thinking\",\"data\":\"Working\"}"))
        assertEquals("{\"type\":\"thinking\",\"data\":\"Working\"}", frames.push(""))
    }
    @Test fun conventionalSseDataPrefixIsAccepted() {
        val frames = EventFrames()
        frames.push("data: {\"type\":\"final_response\"}")
        assertEquals("{\"type\":\"final_response\"}", frames.push("\r"))
        assertNull(frames.push(""))
    }
    @Test fun unterminatedFinalFrameIsRetainedAtEndOfStream() {
        val frames = EventFrames()
        frames.push("{\"type\":\"error\"}")
        assertEquals("{\"type\":\"error\"}", frames.finish())
        assertNull(frames.finish())
    }
}
