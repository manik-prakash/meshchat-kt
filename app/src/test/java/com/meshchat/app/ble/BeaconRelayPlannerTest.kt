package com.meshchat.app.ble

import com.meshchat.app.data.db.entity.NeighborEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class BeaconRelayPlannerTest {
    @Test
    fun `relay targets exclude inbound and non-relay neighbors`() {
        val targets = BeaconRelayPlanner.selectRelayTargets(
            neighbors = listOf(
                neighbor("a", "ble-a", relayCapable = true),
                neighbor("b", "ble-b", relayCapable = true),
                neighbor("c", "ble-c", relayCapable = false),
                neighbor("d", null, relayCapable = true)
            ),
            inboundAddress = "ble-a"
        )

        assertEquals(listOf("ble-b"), targets)
    }

    private fun neighbor(
        publicKey: String,
        bleAddress: String?,
        relayCapable: Boolean
    ) = NeighborEntity(
        neighborPublicKey = publicKey,
        displayName = publicKey,
        bleAddress = bleAddress,
        rssi = null,
        lastSeen = 1L,
        relayCapable = relayCapable,
        geohash = null,
        lat = null,
        lon = null,
        trustScore = 0.0
    )
}
