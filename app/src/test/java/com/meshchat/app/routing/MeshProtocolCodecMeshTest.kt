package com.meshchat.app.routing

import com.meshchat.app.ble.MeshProtocolCodec
import com.meshchat.app.domain.BlePayload
import com.meshchat.app.domain.FailureReason
import com.meshchat.app.domain.RoutingMode
import org.junit.Assert.*
import org.junit.Test

class MeshProtocolCodecMeshTest {

    // ── Beacon ───────────────────────────────────────────────────────────────

    @Test
    fun `beacon round-trip minimal fields`() {
        val original = BlePayload.Beacon(
            nodeId = "node-abc-1",
            displayName = "Alice",
            timestamp = 1_700_000_000_000L,
            seqNum = 42
        )
        val decoded = reassemble(MeshProtocolCodec.encode(original), "beacon") as BlePayload.Beacon
        assertEquals(original.nodeId, decoded.nodeId)
        assertEquals(original.displayName, decoded.displayName)
        assertEquals(original.timestamp, decoded.timestamp)
        assertEquals(original.seqNum, decoded.seqNum)
        assertEquals(false, decoded.relayCapable)
        assertEquals("", decoded.geohash)
        assertEquals(0.0, decoded.lat, 0.0)
        assertEquals(0.0, decoded.lon, 0.0)
    }

    @Test
    fun `beacon round-trip with geo and relay flag`() {
        val original = BlePayload.Beacon(
            nodeId = "node-geo",
            displayName = "Bob",
            timestamp = 1_700_000_000_000L,
            seqNum = 7,
            relayCapable = true,
            geohash = "u4pruyd",
            lat = 48.8566,
            lon = 2.3522,
            signature = "sig-test"
        )
        val decoded = reassemble(MeshProtocolCodec.encode(original), "beacon_geo") as BlePayload.Beacon
        assertEquals(original.relayCapable, decoded.relayCapable)
        assertEquals(original.geohash, decoded.geohash)
        assertEquals(original.lat, decoded.lat, 1e-9)
        assertEquals(original.lon, decoded.lon, 1e-9)
        assertEquals(original.signature, decoded.signature)
    }

    @Test
    fun `beacon seqNum zero and max int survive round-trip`() {
        for (seq in listOf(0, Int.MAX_VALUE)) {
            val original = BlePayload.Beacon("n", "X", 0L, seq)
            val decoded = reassemble(MeshProtocolCodec.encode(original), "beacon_$seq") as BlePayload.Beacon
            assertEquals(seq, decoded.seqNum)
        }
    }

    // ── RoutedMessage ────────────────────────────────────────────────────────

    @Test
    fun `routedMessage round-trip short body`() {
        val original = routedMsg(body = "hello")
        val decoded = reassemble(MeshProtocolCodec.encode(original), "rm_short") as BlePayload.RoutedMessage
        assertRoutedMessageEquals(original, decoded)
    }

    @Test
    fun `routedMessage round-trip long body is fragmented`() {
        val original = routedMsg(body = "Z".repeat(500))
        val chunks = MeshProtocolCodec.encode(original)
        assertTrue("long body should fragment", chunks.size > 1)
        val decoded = reassemble(chunks, "rm_long") as BlePayload.RoutedMessage
        assertRoutedMessageEquals(original, decoded)
    }

    @Test
    fun `routedMessage preserves ttl and hopCount`() {
        val original = routedMsg(ttl = 5, hopCount = 3)
        val decoded = reassemble(MeshProtocolCodec.encode(original), "rm_ttl") as BlePayload.RoutedMessage
        assertEquals(5, decoded.ttl)
        assertEquals(3, decoded.hopCount)
    }

    @Test
    fun `routedMessage ttl boundary values`() {
        for (ttl in listOf(0, 1, 255)) {
            val original = routedMsg(ttl = ttl)
            val decoded = reassemble(MeshProtocolCodec.encode(original), "ttl_$ttl") as BlePayload.RoutedMessage
            assertEquals(ttl, decoded.ttl)
        }
    }

    @Test
    fun `routedMessage all RoutingMode values round-trip`() {
        for (mode in RoutingMode.entries) {
            val original = routedMsg(routingMode = mode)
            val decoded = reassemble(MeshProtocolCodec.encode(original), "mode_${mode.name}") as BlePayload.RoutedMessage
            assertEquals(mode, decoded.routingMode)
        }
    }

    @Test
    fun `routedMessage unicode body survives round-trip`() {
        val original = routedMsg(body = "Hello 🌍 こんにちは")
        val decoded = reassemble(MeshProtocolCodec.encode(original), "rm_unicode") as BlePayload.RoutedMessage
        assertEquals(original.body, decoded.body)
    }

    @Test
    fun `routedMessage empty geoHint is valid`() {
        val original = routedMsg(geoHint = "")
        val decoded = reassemble(MeshProtocolCodec.encode(original), "rm_nogeo") as BlePayload.RoutedMessage
        assertEquals("", decoded.destinationGeoHint)
    }

    @Test
    fun `routedMessage voidHopCount zero default round-trip`() {
        val original = routedMsg()
        val decoded = reassemble(MeshProtocolCodec.encode(original), "rm_void0") as BlePayload.RoutedMessage
        assertEquals(0, decoded.voidHopCount)
    }

    @Test
    fun `routedMessage voidHopCount boundary values round-trip`() {
        for (v in listOf(0, 1, 3, 255)) {
            val original = routedMsg(voidHopCount = v)
            val decoded = reassemble(MeshProtocolCodec.encode(original), "rm_void_$v") as BlePayload.RoutedMessage
            assertEquals(v, decoded.voidHopCount)
        }
    }

    @Test
    fun `routedMessage PERIMETER mode with voidHopCount round-trip`() {
        val original = routedMsg(routingMode = RoutingMode.PERIMETER, voidHopCount = 2)
        val decoded = reassemble(MeshProtocolCodec.encode(original), "rm_perimeter") as BlePayload.RoutedMessage
        assertEquals(RoutingMode.PERIMETER, decoded.routingMode)
        assertEquals(2, decoded.voidHopCount)
    }

    @Test
    fun `routedMessage all chunks are at most CHUNK_SIZE bytes`() {
        val original = routedMsg(body = "A".repeat(500))
        val chunks = MeshProtocolCodec.encode(original)
        for ((i, chunk) in chunks.withIndex()) {
            assertTrue("chunk $i size ${chunk.size} exceeds CHUNK_SIZE", chunk.size <= 18)
        }
    }

    // ── RouteAck ─────────────────────────────────────────────────────────────

    @Test
    fun `routeAck round-trip`() {
        val original = BlePayload.RouteAck(
            packetId = "pkt-001",
            hopNodeId = "node-hop",
            timestamp = 1_700_000_001_000L
        )
        val decoded = reassemble(MeshProtocolCodec.encode(original), "rack") as BlePayload.RouteAck
        assertEquals(original.packetId, decoded.packetId)
        assertEquals(original.hopNodeId, decoded.hopNodeId)
        assertEquals(original.timestamp, decoded.timestamp)
    }

    // ── DeliveryAck ──────────────────────────────────────────────────────────

    @Test
    fun `deliveryAck round-trip`() {
        val original = BlePayload.DeliveryAck(
            packetId = "pkt-002",
            destinationNodeId = "dest-node",
            timestamp = 1_700_000_002_000L
        )
        val decoded = reassemble(MeshProtocolCodec.encode(original), "dack") as BlePayload.DeliveryAck
        assertEquals(original.packetId, decoded.packetId)
        assertEquals(original.destinationNodeId, decoded.destinationNodeId)
        assertEquals(original.timestamp, decoded.timestamp)
    }

    // ── RouteFailure ─────────────────────────────────────────────────────────

    @Test
    fun `routeFailure round-trip all reasons`() {
        for (reason in FailureReason.entries) {
            val original = BlePayload.RouteFailure(
                packetId = "pkt-fail",
                failingNodeId = "bad-node",
                reason = reason,
                timestamp = 1_700_000_003_000L
            )
            val decoded = reassemble(MeshProtocolCodec.encode(original), "rfail_${reason.name}") as BlePayload.RouteFailure
            assertEquals(reason, decoded.reason)
            assertEquals(original.packetId, decoded.packetId)
            assertEquals(original.failingNodeId, decoded.failingNodeId)
            assertEquals(original.timestamp, decoded.timestamp)
        }
    }

    // ── Timestamp precision ───────────────────────────────────────────────────

    @Test
    fun `timestamp large value survives round-trip in all mesh types`() {
        val ts = Long.MAX_VALUE / 2  // large but not overflow-triggering

        val beacon = BlePayload.Beacon(nodeId = "n", displayName = "X", timestamp = ts, seqNum = 0)
        assertEquals(ts, (reassemble(MeshProtocolCodec.encode(beacon), "ts_b") as BlePayload.Beacon).timestamp)

        val rack = BlePayload.RouteAck("p", "h", ts)
        assertEquals(ts, (reassemble(MeshProtocolCodec.encode(rack), "ts_ra") as BlePayload.RouteAck).timestamp)

        val dack = BlePayload.DeliveryAck("p", "d", ts)
        assertEquals(ts, (reassemble(MeshProtocolCodec.encode(dack), "ts_da") as BlePayload.DeliveryAck).timestamp)

        val fail = BlePayload.RouteFailure("p", "f", FailureReason.NO_ROUTE, ts)
        assertEquals(ts, (reassemble(MeshProtocolCodec.encode(fail), "ts_rf") as BlePayload.RouteFailure).timestamp)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun routedMsg(
        packetId: String = "pkt-xyz-001",
        srcKey: String = "src-node-key",
        dstKey: String = "dst-node-key",
        dstName: String = "Bob",
        geoHint: String = "SF:US",
        ttl: Int = 7,
        hopCount: Int = 0,
        voidHopCount: Int = 0,
        routingMode: RoutingMode = RoutingMode.BROADCAST,
        timestamp: Long = 1_700_000_000_000L,
        sig: String = "sig-placeholder",
        body: String = "hello"
    ) = BlePayload.RoutedMessage(
        packetId = packetId,
        sourcePublicKey = srcKey,
        destinationPublicKey = dstKey,
        destinationDisplayNameSnapshot = dstName,
        destinationGeoHint = geoHint,
        ttl = ttl,
        hopCount = hopCount,
        voidHopCount = voidHopCount,
        routingMode = routingMode,
        timestamp = timestamp,
        signature = sig,
        body = body
    )

    private fun assertRoutedMessageEquals(expected: BlePayload.RoutedMessage, actual: BlePayload.RoutedMessage) {
        assertEquals(expected.packetId, actual.packetId)
        assertEquals(expected.sourcePublicKey, actual.sourcePublicKey)
        assertEquals(expected.destinationPublicKey, actual.destinationPublicKey)
        assertEquals(expected.destinationDisplayNameSnapshot, actual.destinationDisplayNameSnapshot)
        assertEquals(expected.destinationGeoHint, actual.destinationGeoHint)
        assertEquals(expected.ttl, actual.ttl)
        assertEquals(expected.hopCount, actual.hopCount)
        assertEquals(expected.voidHopCount, actual.voidHopCount)
        assertEquals(expected.routingMode, actual.routingMode)
        assertEquals(expected.timestamp, actual.timestamp)
        assertEquals(expected.signature, actual.signature)
        assertEquals(expected.body, actual.body)
    }

    private fun reassemble(chunks: List<ByteArray>, key: String): BlePayload {
        var result: BlePayload? = null
        for (chunk in chunks) result = MeshProtocolCodec.decode(chunk, key)
        return checkNotNull(result) { "Assembly returned null for key=$key" }
    }
}
