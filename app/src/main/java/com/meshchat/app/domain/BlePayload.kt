package com.meshchat.app.domain

sealed class BlePayload {
    /** nodeId is the sender's stable public key (base64 DER X.509), used as routing identity. */
    data class Handshake(
        val nodeId: String,
        val displayName: String
    ) : BlePayload()

    data class Message(
        val id: String,
        val senderDeviceId: String,
        val text: String,
        val timestamp: Long
    ) : BlePayload()

    data class Ack(val messageId: String) : BlePayload()

    /** Periodic presence advertisement flooded to all reachable neighbors. */
    data class Beacon(
        val nodeId: String,
        val displayName: String,
        val timestamp: Long,
        val seqNum: Int,
        val relayCapable: Boolean = false,
        val geohash: String = "",
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val signature: String = ""
    ) : BlePayload()

    /** Mesh-routed message carrying full relay metadata. */
    data class RoutedMessage(
        val packetId: String,
        val sourcePublicKey: String,
        val sourceDisplayNameSnapshot: String,
        val destinationPublicKey: String,
        val destinationDisplayNameSnapshot: String,
        val destinationGeoHint: String,
        val ttl: Int,
        val hopCount: Int,
        /** Number of consecutive perimeter (void-bypass) hops since the last greedy advance. */
        val voidHopCount: Int = 0,
        val routingMode: RoutingMode,
        val timestamp: Long,
        val signature: String,
        val body: String
    ) : BlePayload()

    /** Per-hop acknowledgement: a relay node confirms receipt of a RoutedMessage. */
    data class RouteAck(
        val packetId: String,
        val hopNodeId: String,
        val timestamp: Long
    ) : BlePayload()

    /** End-to-end delivery confirmation: destination node confirms receipt. */
    data class DeliveryAck(
        val packetId: String,
        val destinationNodeId: String,
        val timestamp: Long,
        val hopCount: Int = 0,
        val signature: String = ""
    ) : BlePayload()

    /** Routing failure report propagated back toward the source. */
    data class RouteFailure(
        val packetId: String,
        val failingNodeId: String,
        val reason: FailureReason,
        val timestamp: Long,
        val signature: String = ""
    ) : BlePayload()
}
