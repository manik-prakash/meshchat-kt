package com.meshchat.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RelayActivityTrackerTest {
    @Test
    fun `record relay stores latest relay summary`() {
        val tracker = RelayActivityTracker()

        tracker.recordRelay(
            packetId = "packet-1",
            sourceDisplayName = "Alice",
            destinationDisplayName = "Carol",
            timestamp = 1234L
        )

        val latest = tracker.latestRelay.value
        assertNotNull(latest)
        assertEquals("packet-1", latest?.packetId)
        assertEquals("Alice", latest?.sourceDisplayName)
        assertEquals("Carol", latest?.destinationDisplayName)
        assertEquals(1234L, latest?.timestamp)
    }
}
