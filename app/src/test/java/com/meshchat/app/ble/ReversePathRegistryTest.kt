package com.meshchat.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReversePathRegistryTest {
    @Test
    fun `first upstream wins and is consumed once`() {
        val registry = ReversePathRegistry()

        registry.remember("packet-1", "ble-a")
        registry.remember("packet-1", "ble-b")

        assertEquals("ble-a", registry.takeUpstream("packet-1"))
        assertNull(registry.takeUpstream("packet-1"))
    }
}
