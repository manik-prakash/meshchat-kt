package com.meshchat.app.routing

import android.util.Log
import com.meshchat.app.ble.MeshProtocolCodec
import com.meshchat.app.crypto.CryptoManager
import com.meshchat.app.data.db.entity.NeighborEntity
import com.meshchat.app.data.repository.IdentityRepository
import com.meshchat.app.data.repository.MeshRepository
import com.meshchat.app.domain.BlePayload
import com.meshchat.app.domain.FailureReason
import com.meshchat.app.domain.RouteAction
import com.meshchat.app.domain.RoutingMode
import com.meshchat.app.location.LocationProvider
import com.meshchat.app.runtime.RelayActivityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class RoutingDecision { DIRECT, FORWARDED, QUEUED, UNREACHABLE }

interface MeshRouter {
    suspend fun originate(
        packetId: String,
        destinationId: String,
        destinationDisplayName: String,
        text: String,
        timestamp: Long
    ): RoutingDecision

    fun onMessageReceived(packet: BlePayload.RoutedMessage, fromNeighbor: String)

    fun onNeighborAvailable(neighborPublicKey: String)
}

class MeshRouterImpl(
    private val identityRepo: IdentityRepository,
    private val scope: CoroutineScope,
    private val meshRepository: MeshRepository,
    private val crypto: CryptoManager,
    private val locationProvider: LocationProvider,
    private val forwardPacket: suspend (packet: BlePayload.RoutedMessage, bleAddress: String) -> Boolean,
    private val deliverLocally: suspend (packet: BlePayload.RoutedMessage) -> Unit,
    private val reportFailure: suspend (failure: BlePayload.RouteFailure) -> Unit = {},
    private val onQueuedForwarded: suspend (packetId: String) -> Unit = {},
    private val canReachBleAddress: (bleAddress: String?) -> Boolean = { false },
    private val relayActivityTracker: RelayActivityTracker? = null,
) : MeshRouter {

    private val tag = "MeshRouter"

    @Volatile private var cachedSelfId: String? = null

    private suspend fun selfId(): String =
        cachedSelfId ?: identityRepo.ensureIdentity().publicKey.also { cachedSelfId = it }

    override suspend fun originate(
        packetId: String,
        destinationId: String,
        destinationDisplayName: String,
        text: String,
        timestamp: Long
    ): RoutingDecision {
        val identity = identityRepo.ensureIdentity()
        val self = identity.publicKey
        val geoHint = meshRepository.getContact(destinationId)?.lastResolvedGeoHash ?: ""
        val sig = crypto.signRoutedMessage(
            packetId = packetId,
            sourcePublicKey = self,
            sourceDisplayName = identity.displayName,
            destinationPublicKey = destinationId,
            destinationDisplayName = destinationDisplayName,
            timestamp = timestamp,
            body = text
        )
        val packet = BlePayload.RoutedMessage(
            packetId = packetId,
            sourcePublicKey = self,
            sourceDisplayNameSnapshot = identity.displayName,
            destinationPublicKey = destinationId,
            destinationDisplayNameSnapshot = destinationDisplayName,
            destinationGeoHint = geoHint,
            ttl = DEFAULT_TTL,
            hopCount = 0,
            voidHopCount = 0,
            routingMode = RoutingMode.GREEDY,
            timestamp = timestamp,
            signature = sig,
            body = text
        )

        meshRepository.resetRelayPacket(packetId)
        meshRepository.enqueueRelayPacket(
            packetId = packetId,
            destinationPublicKey = destinationId,
            serializedPayload = MeshProtocolCodec.encodeToBase64(packet),
            ttl = DEFAULT_TTL,
            routingMode = RoutingMode.GREEDY
        )
        meshRepository.logRouteEvent(packetId, RouteAction.ORIGINATED)
        Log.d(
            tag,
            "Originate packet=${packetId.takeLast(8)} dst=${destinationId.take(12)} " +
                "geo=${if (geoHint.isBlank()) "none" else geoHint} ttl=$DEFAULT_TTL"
        )

        return attemptForward(packet)
    }

    override fun onMessageReceived(packet: BlePayload.RoutedMessage, fromNeighbor: String) {
        scope.launch {
            if (packet.signature.isEmpty()) {
                Log.w(tag, "Dropping unsigned packet ${packet.packetId}")
                meshRepository.logRouteEvent(packet.packetId, RouteAction.DROPPED_INVALID_SIG)
                return@launch
            }

            val sigValid = crypto.verifyRoutedMessage(
                packet.sourcePublicKey,
                packet.packetId,
                packet.sourcePublicKey,
                packet.sourceDisplayNameSnapshot,
                packet.destinationPublicKey,
                packet.destinationDisplayNameSnapshot,
                packet.timestamp,
                packet.body,
                packet.signature
            )
            if (!sigValid) {
                Log.w(tag, "Invalid sig on ${packet.packetId} from ${packet.sourcePublicKey.take(16)}")
                meshRepository.logRouteEvent(packet.packetId, RouteAction.DROPPED_INVALID_SIG)
                return@launch
            }

            if (meshRepository.isSeen(packet.packetId)) {
                meshRepository.logRouteEvent(packet.packetId, RouteAction.DROPPED_DUPLICATE)
                if (packet.destinationPublicKey == selfId()) {
                    Log.d(tag, "Re-ACKing duplicate ${packet.packetId.take(16)} for self")
                    deliverLocally(packet)
                }
                return@launch
            }
            meshRepository.markSeen(packet.packetId)

            val ttlLeft = packet.ttl - 1
            if (ttlLeft < 0) {
                Log.d(tag, "TTL exhausted for ${packet.packetId}")
                meshRepository.logRouteEvent(packet.packetId, RouteAction.DROPPED_TTL)
                sendRouteFailure(packet.packetId, FailureReason.TTL_EXCEEDED)
                return@launch
            }

            if (packet.destinationPublicKey == selfId()) {
                Log.d(tag, "Packet ${packet.packetId.takeLast(8)} reached destination self")
                meshRepository.logRouteEvent(packet.packetId, RouteAction.DELIVERED)
                deliverLocally(packet)
                return@launch
            }

            val reduced = packet.copy(ttl = ttlLeft, hopCount = packet.hopCount + 1)
            meshRepository.enqueueRelayPacket(
                packetId = reduced.packetId,
                destinationPublicKey = reduced.destinationPublicKey,
                serializedPayload = MeshProtocolCodec.encodeToBase64(reduced),
                ttl = reduced.ttl,
                routingMode = reduced.routingMode
            )
            val decision = attemptForward(reduced, fromNeighbor)
            if (decision == RoutingDecision.DIRECT || decision == RoutingDecision.FORWARDED) {
                relayActivityTracker?.recordRelay(
                    packetId = reduced.packetId,
                    sourceDisplayName = reduced.sourceDisplayNameSnapshot,
                    destinationDisplayName = reduced.destinationDisplayNameSnapshot
                )
            }
            if (decision == RoutingDecision.UNREACHABLE) {
                sendRouteFailure(reduced.packetId, FailureReason.NO_ROUTE)
            }
        }
    }

    override fun onNeighborAvailable(neighborPublicKey: String) {
        scope.launch {
            val pending = meshRepository.getAllPendingRelayPackets()
            if (pending.isEmpty()) return@launch
            Log.d(tag, "Draining ${pending.size} queued packet(s) after neighbor=$neighborPublicKey")
            for (entry in pending) {
                try {
                    val packet = MeshProtocolCodec.decodeFromBase64(entry.serializedPayload)
                        as? BlePayload.RoutedMessage ?: continue
                    if (packet.ttl <= 0) continue
                    val decision = attemptForward(packet)
                    meshRepository.logRouteEvent(
                        packet.packetId,
                        RouteAction.DEQUEUED,
                        chosenNextHop = neighborPublicKey,
                        reason = decision.name
                    )
                    if (decision == RoutingDecision.DIRECT || decision == RoutingDecision.FORWARDED) {
                        onQueuedForwarded(packet.packetId)
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Queue drain error for ${entry.packetId}: ${e.message}")
                }
            }
        }
    }

    private suspend fun sendRouteFailure(packetId: String, reason: FailureReason) {
        try {
            val self = selfId()
            val now = System.currentTimeMillis()
            val sig = crypto.signRouteFailure(packetId, self, reason.id, now)
            reportFailure(BlePayload.RouteFailure(packetId, self, reason, now, sig))
        } catch (e: Exception) {
            Log.w(tag, "Failed to emit RouteFailure for $packetId: ${e.message}")
        }
    }

    private suspend fun attemptForward(
        packet: BlePayload.RoutedMessage,
        incomingBleAddress: String? = null
    ): RoutingDecision {
        val neighbors = meshRepository
            .getRecentNeighbors(MeshRepository.NEIGHBOR_STALE_MS)
            .filter(::isNeighborReachable)
        val dstContact = meshRepository.getContact(packet.destinationPublicKey)
        val selfLoc = locationProvider.getLastLocation()

        return when (val plan = RoutingPlanner.plan(
            packet = packet,
            neighbors = neighbors,
            destinationLat = dstContact?.lastResolvedLat,
            destinationLon = dstContact?.lastResolvedLon,
            selfLat = selfLoc?.latitude,
            selfLon = selfLoc?.longitude,
            incomingBleAddress = incomingBleAddress,
            improvementThreshold = IMPROVEMENT_THRESHOLD,
            maxVoidHops = MAX_VOID_HOPS
        )) {
            is RoutingPlanner.Plan.DirectHop -> {
                val outPacket = packet.copy(routingMode = RoutingMode.GREEDY, voidHopCount = 0)
                sendSingleHop(
                    packet = packet,
                    outPacket = outPacket,
                    hop = plan.hop,
                    logLabel = "Direct",
                    logReason = "direct"
                ) ?: return RoutingDecision.QUEUED
                RoutingDecision.DIRECT
            }

            is RoutingPlanner.Plan.GreedyHop -> {
                val outPacket = packet.copy(routingMode = RoutingMode.GREEDY, voidHopCount = 0)
                sendSingleHop(
                    packet = packet,
                    outPacket = outPacket,
                    hop = plan.hop,
                    logLabel = "Greedy",
                    logReason = if (plan.recoveredFromPerimeter) "greedy_recovered" else null
                ) ?: return RoutingDecision.QUEUED
                RoutingDecision.FORWARDED
            }

            is RoutingPlanner.Plan.PerimeterHop -> {
                val newVoidCount = packet.voidHopCount + 1
                val outPacket = packet.copy(
                    routingMode = RoutingMode.PERIMETER,
                    voidHopCount = newVoidCount
                )
                sendSingleHop(
                    packet = packet,
                    outPacket = outPacket,
                    hop = plan.hop,
                    logLabel = "Perimeter",
                    routeAction = RouteAction.PERIMETER_HOP,
                    logReason = "void=$newVoidCount/$MAX_VOID_HOPS"
                ) ?: return RoutingDecision.QUEUED
                RoutingDecision.FORWARDED
            }

            is RoutingPlanner.Plan.BroadcastHops -> {
                val outPacket = packet.copy(routingMode = RoutingMode.BROADCAST, voidHopCount = 0)
                meshRepository.markRelayInFlight(packet.packetId)
                var successCount = 0
                for (hop in plan.hops) {
                    try {
                        Log.d(
                            tag,
                            "Broadcast forward packet=${packet.packetId.takeLast(8)} next=${hop.neighborPublicKey.take(12)} " +
                                "ble=${hop.bleAddress}"
                        )
                        if (forwardPacket(outPacket, hop.bleAddress ?: "")) {
                            successCount++
                        } else {
                            Log.d(tag, "Broadcast forward failed packet=${packet.packetId.takeLast(8)} ble=${hop.bleAddress}")
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "forwardPacket failed for ${packet.packetId.take(16)}: ${e.message}")
                    }
                }

                if (successCount == 0) {
                    meshRepository.resetRelayPacket(packet.packetId)
                    meshRepository.logRouteEvent(packet.packetId, RouteAction.QUEUED, reason = "broadcast_no_path")
                    return RoutingDecision.QUEUED
                }

                meshRepository.logRouteEvent(
                    packetId = packet.packetId,
                    action = RouteAction.FORWARDED,
                    chosenNextHop = plan.hops.joinToString(",") { it.neighborPublicKey.take(12) },
                    reason = "broadcast:$successCount/${plan.hops.size}"
                )
                RoutingDecision.FORWARDED
            }

            RoutingPlanner.Plan.VoidLimitExceeded -> {
                meshRepository.logRouteEvent(
                    packet.packetId,
                    RouteAction.VOID_LIMIT_EXCEEDED,
                    reason = "voidHopCount=${packet.voidHopCount} limit=$MAX_VOID_HOPS"
                )
                Log.d(tag, "Void budget exhausted for ${packet.packetId.take(16)} - holding in queue")
                RoutingDecision.QUEUED
            }

            RoutingPlanner.Plan.NoReachableNeighbors -> {
                val anyNeighbors = meshRepository
                    .getRecentNeighbors(MeshRepository.NEIGHBOR_STALE_MS)
                    .isNotEmpty()
                if (anyNeighbors) {
                    meshRepository.logRouteEvent(packet.packetId, RouteAction.QUEUED, reason = "no_reachable_neighbors")
                    RoutingDecision.QUEUED
                } else {
                    RoutingDecision.UNREACHABLE
                }
            }
        }
    }

    private suspend fun sendSingleHop(
        packet: BlePayload.RoutedMessage,
        outPacket: BlePayload.RoutedMessage,
        hop: NeighborEntity,
        logLabel: String,
        routeAction: RouteAction = RouteAction.FORWARDED,
        logReason: String?
    ): Boolean? {
        meshRepository.markRelayInFlight(packet.packetId)
        try {
            Log.d(
                tag,
                "$logLabel forward packet=${packet.packetId.takeLast(8)} next=${hop.neighborPublicKey.take(12)} " +
                    "ble=${hop.bleAddress}"
            )
            val forwarded = forwardPacket(outPacket, hop.bleAddress ?: "")
            if (!forwarded) {
                Log.d(tag, "$logLabel forward failed packet=${packet.packetId.takeLast(8)} ble=${hop.bleAddress}")
                meshRepository.resetRelayPacket(packet.packetId)
                return null
            }
        } catch (e: Exception) {
            Log.w(tag, "forwardPacket failed for ${packet.packetId.take(16)}: ${e.message}")
            meshRepository.resetRelayPacket(packet.packetId)
            return null
        }

        meshRepository.logRouteEvent(
            packetId = packet.packetId,
            action = routeAction,
            chosenNextHop = if (routeAction == RouteAction.FORWARDED && hop.neighborPublicKey == packet.destinationPublicKey) {
                packet.destinationPublicKey
            } else {
                hop.neighborPublicKey
            },
            reason = logReason
        )
        return true
    }

    private fun isNeighborReachable(neighbor: NeighborEntity): Boolean =
        canReachBleAddress(neighbor.bleAddress)

    companion object {
        const val DEFAULT_TTL = 7
        private const val IMPROVEMENT_THRESHOLD = 0.95
        const val MAX_VOID_HOPS = 3
    }
}
