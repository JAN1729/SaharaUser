package com.sahara.user.data.api

import org.junit.Assert.*
import org.junit.Test

class ServerAddressTest {
    @Test fun phoneAndEmulatorCanUseExplicitDevelopmentAddresses() {
        assertEquals("http://10.0.2.2:8787", normalizeServerAddress(" http://10.0.2.2:8787/ ", true))
        assertEquals("http://192.168.1.8:8787", normalizeServerAddress("http://192.168.1.8:8787", true))
    }
    @Test fun productionRequiresHttpsAndEmbeddedCredentialsAreRejected() {
        for (url in listOf("http://192.168.1.8:8787", "https://user:secret@example.com", "https://example.com?key=secret", "file:///tmp", "https://example.com#fragment")) {
            assertTrue(runCatching { normalizeServerAddress(url, false) }.isFailure)
        }
        assertEquals("https://commute.example.com", normalizeServerAddress("https://commute.example.com/", false))
    }
}
