package com.meshchat.app.routing

import com.meshchat.app.data.db.entity.NeighborEntity
import com.meshchat.app.data.repository.MeshRepository
import com.meshchat.app.domain.BlePayload
import com.meshchat.app.domain.RouteAction
import com.meshchat.app.domain.RoutingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Mesh routing entrypoint.
 *
 * The transport layer (BleMeshManager) calls these methods when BLE events
 * arrive. [sendMessage] is called by the UI layer to originate a new message.
 *
 * TTL is checked and decremented on every hop. Duplicate suppression uses
 * [MeshRepository] (persistent across restarts) instead of an in-memory cache.
 */
interface MeshRouter {
    /** A neighbor node has broadcast its presence. */
    fun onBeaconReceived(packet: BlePayload.Beacon, fromNeighbor: String)

    /** A routed packet arrived from a neighbor; relay or deliver as appropriate. */
    fun onMessageReceived(packet: BlePayload.RoutedMessage, fromNeighbor: String)

    /** A direct BLE link to a neighbor is now available for sending. */
    fun onNeighborAvailable(neighborId: String)

    /** Originate a new mesh message toward [destinationId]. */
    suspend fun sendMessage(destinationId: String, destinationDisplayName: String, text: String)
}

/**
 * @param selfNodeId    this node's stable public key (from IdentityRepository)
 * @param scope         coroutine scope owned by the calling component
 * @param meshRepository persistent routing state (seen packets, relay queue, neighbors)
 * @param forwardPacket called when a packet should be relayed to neighbors
 * @param deliverLocally called when a packet is addressed to this node
 */
class MeshRouterImpl(
    private val selfNodeId: String,
    private val scope: CoroutineScope,
    private val meshRepository: MeshRepository,
    private val forwardPacket: suspend (packet: BlePayload.RoutedMessage, excludeNeighbor: String?) -> Unit,
    private val deliverLocally: suspend (packet: BlePayload.RoutedMessage) -> Unit,
) : MeshRouter {

    override fun onBeaconReceived(packet: BlePayload.Beacon, fromNeighbor: String) {
        scope.launch {
            meshRepository.upsertNeighbor(
                NeighborEntity(
                    neighborPublicKey = packet.nodeId,
                    displayName = packet.displayName,
                    bleAddress = fromNeighbor,
                    rssi = null,
                    lastSeen = packet.timestamp,
                    relayCapable = true,
                    geohash = null,
                    lat = null,
                    lon = null,
                    trustScore = 0.0
                )
            )
        }
    }

    override fun onMessageReceived(packet: BlePayload.RoutedMessage, fromNeighbor: String) {
        scope.launch {
            if (meshRepository.isSeen(packet.packetId)) {
                meshRepository.logRouteEvent(packet.packetId, RouteAction.DROPPED_DUPLICATE)
                return@launch
            }
            meshRepository.markSeen(packet.packetId)

            val remaining = packet.ttl - 1
            if (remaining < 0) {
                meshRepository.logRouteEvent(packet.packetId, RouteAction.DROPPED_TTL)
                return@launch
            }

            if (packet.destinationPublicKey == selfNodeId) {
                meshRepository.logRouteEvent(packet.packetId, RouteAction.DELIVERED)
                deliverLocally(packet)
            } else {
                val relayed = packet.copy(ttl = remaining, hopCount = packet.hopCount + 1)
                // TODO: select next hop from neighbor table instead of broadcasting
                meshRepository.logRouteEvent(packet.packetId, RouteAction.FORWARDED)
                forwardPacket(relayed, fromNeighbor)
            }
        }
    }

    override fun onNeighborAvailable(neighborId: String) {
        scope.launch {
            meshRepository.updateNeighborSighting(neighborId, rssi = null, bleAddress = neighborId)
            val queued = meshRepository.getPendingPacketsFor(neighborId)
            for (entry in queued) {
                meshRepository.markRelayInFlight(entry.packetId)
                // TODO: deserialize entry.serializedPayload and forward
                meshRepository.logRouteEvent(entry.packetId, RouteAction.DEQUEUED, chosenNextHop = neighborId)
            }
        }
    }

    override suspend fun sendMessage(
        destinationId: String,
        destinationDisplayName: String,
        text: String
    ) {
        val packet = BlePayload.RoutedMessage(
            packetId = UUID.randomUUID().toString(),
            sourcePublicKey = selfNodeId,
            destinationPublicKey = destinationId,
            destinationDisplayNameSnapshot = destinationDisplayName,
            destinationGeoHint = "",
            ttl = DEFAULT_TTL,
            hopCount = 0,
            routingMode = RoutingMode.BROADCAST,
            timestamp = System.currentTimeMillis(),
            signature = "",  // TODO: sign with sender's key
            body = text
        )
        meshRepository.markSeen(packet.packetId)
        meshRepository.logRouteEvent(packet.packetId, RouteAction.ORIGINATED)
        forwardPacket(packet, null)
    }

    companion object {
        const val DEFAULT_TTL = 7
    }
}
