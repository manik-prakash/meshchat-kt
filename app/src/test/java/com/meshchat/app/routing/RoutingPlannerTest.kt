package com.meshchat.app.routing

import com.meshchat.app.data.db.entity.NeighborEntity
import com.meshchat.app.domain.BlePayload
import com.meshchat.app.domain.RoutingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingPlannerTest {
    @Test
    fun `chain without destination coordinates falls back to flood then direct`() {
        val packet = routedPacket()
        val relayA = neighbor("relay-a", "ble-a")
        val destination = neighbor("dest", "ble-dest", relayCapable = false)

        val firstHop = RoutingPlanner.plan(
            packet = packet,
            neighbors = listOf(relayA),
            destinationLat = null,
            destinationLon = null,
            selfLat = null,
            selfLon = null,
            incomingBleAddress = null,
            improvementThreshold = 0.95,
            maxVoidHops = 3
        )
        assertTrue(firstHop is RoutingPlanner.Plan.BroadcastHops)
        assertEquals(listOf(relayA), (firstHop as RoutingPlanner.Plan.BroadcastHops).hops)

        val secondHop = RoutingPlanner.plan(
            packet = packet,
            neighbors = listOf(relayA, destination),
            destinationLat = null,
            destinationLon = null,
            selfLat = null,
            selfLon = null,
            incomingBleAddress = "ble-a",
            improvementThreshold = 0.95,
            maxVoidHops = 3
        )
        assertTrue(secondHop is RoutingPlanner.Plan.DirectHop)
        assertEquals("dest", (secondHop as RoutingPlanner.Plan.DirectHop).hop.neighborPublicKey)
    }

    @Test
    fun `broadcast relay excludes the incoming neighbor`() {
        val packet = routedPacket(destination = "unknown")
        val incoming = neighbor("relay-a", "ble-a")
        val forward = neighbor("relay-b", "ble-b")

        val plan = RoutingPlanner.plan(
            packet = packet,
            neighbors = listOf(incoming, forward),
            destinationLat = null,
            destinationLon = null,
            selfLat = null,
            selfLon = null,
            incomingBleAddress = "ble-a",
            improvementThreshold = 0.95,
            maxVoidHops = 3
        )

        assertTrue(plan is RoutingPlanner.Plan.BroadcastHops)
        assertEquals(listOf(forward), (plan as RoutingPlanner.Plan.BroadcastHops).hops)
    }

    @Test
    fun `missing location permission still allows flood routing`() {
        val packet = routedPacket(destination = "unknown")
        val relay = neighbor("relay-a", "ble-a", geohash = null, lat = null, lon = null)

        val plan = RoutingPlanner.plan(
            packet = packet,
            neighbors = listOf(relay),
            destinationLat = null,
            destinationLon = null,
            selfLat = null,
            selfLon = null,
            incomingBleAddress = null,
            improvementThreshold = 0.95,
            maxVoidHops = 3
        )

        assertTrue(plan is RoutingPlanner.Plan.BroadcastHops)
    }

    private fun routedPacket(destination: String = "dest") = BlePayload.RoutedMessage(
        packetId = "packet-1",
        sourcePublicKey = "source",
        sourceDisplayNameSnapshot = "Source",
        destinationPublicKey = destination,
        destinationDisplayNameSnapshot = "Dest",
        destinationGeoHint = "",
        ttl = 7,
        hopCount = 0,
        voidHopCount = 0,
        routingMode = RoutingMode.GREEDY,
        timestamp = 1L,
        signature = "sig",
        body = "hello"
    )

    private fun neighbor(
        publicKey: String,
        bleAddress: String,
        relayCapable: Boolean = true,
        geohash: String? = null,
        lat: Double? = null,
        lon: Double? = null
    ) = NeighborEntity(
        neighborPublicKey = publicKey,
        displayName = publicKey,
        bleAddress = bleAddress,
        rssi = -42,
        lastSeen = System.currentTimeMillis(),
        relayCapable = relayCapable,
        geohash = geohash,
        lat = lat,
        lon = lon,
        trustScore = 0.0
    )
}
