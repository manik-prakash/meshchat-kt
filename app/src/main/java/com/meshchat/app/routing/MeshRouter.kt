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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class RoutingDecision { DIRECT, FORWARDED, QUEUED, UNREACHABLE }

interface MeshRouter {
    /**
     * Originate (or re-originate on retry) a mesh-routed message already stored in the
     * conversation DB. The [packetId] doubles as the message row ID.
     */
    suspend fun originate(
        packetId: String,
        destinationId: String,
        destinationDisplayName: String,
        text: String,
        timestamp: Long
    ): RoutingDecision

    /** A RoutedMessage arrived over BLE; relay or deliver as appropriate. */
    fun onMessageReceived(packet: BlePayload.RoutedMessage, fromNeighbor: String)

    /** A direct BLE link to a neighbor is available; drain queued packets. */
    fun onNeighborAvailable(neighborPublicKey: String)
}

/**
 * @param forwardPacket      called to send a packet to all currently reachable BLE neighbors
 * @param deliverLocally     called when a packet is addressed to this node
 * @param reportFailure      called to propagate a RouteFailure back toward the source
 * @param onQueuedForwarded  called when the worker successfully forwards a previously-QUEUED
 *                           packet so the conversation status can be updated to FORWARDED
 */
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
) : MeshRouter {

    private val TAG = "MeshRouter"

    @Volatile private var cachedSelfId: String? = null
    private suspend fun selfId(): String =
        cachedSelfId ?: identityRepo.ensureIdentity().publicKey.also { cachedSelfId = it }

    // ── Public API ────────────────────────────────────────────────────────────

    override suspend fun originate(
        packetId: String,
        destinationId: String,
        destinationDisplayName: String,
        text: String,
        timestamp: Long
    ): RoutingDecision {
        val identity = identityRepo.ensureIdentity()
        val self     = identity.publicKey
        val geoHint  = meshRepository.getContact(destinationId)?.lastResolvedGeoHash ?: ""
        val sig      = crypto.signRoutedMessage(
            packetId = packetId,
            sourcePublicKey = self,
            sourceDisplayName = identity.displayName,
            destinationPublicKey = destinationId,
            destinationDisplayName = destinationDisplayName,
            timestamp = timestamp,
            body = text
        )
        val packet  = BlePayload.RoutedMessage(
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
            packetId, destinationId,
            MeshProtocolCodec.encodeToBase64(packet),
            DEFAULT_TTL, RoutingMode.GREEDY
        )
        meshRepository.logRouteEvent(packetId, RouteAction.ORIGINATED)
        Log.d(
            TAG,
            "Originate packet=${packetId.takeLast(8)} dst=${destinationId.take(12)} " +
                "geo=${if (geoHint.isBlank()) "none" else geoHint} ttl=$DEFAULT_TTL"
        )

        return attemptForward(packet)
    }

    override fun onMessageReceived(packet: BlePayload.RoutedMessage, fromNeighbor: String) {
        scope.launch {
            if (packet.signature.isEmpty()) {
                Log.w(TAG, "Dropping unsigned packet ${packet.packetId}")
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
                Log.w(TAG, "Invalid sig on ${packet.packetId} from ${packet.sourcePublicKey.take(16)}")
                meshRepository.logRouteEvent(packet.packetId, RouteAction.DROPPED_INVALID_SIG)
                return@launch
            }

            if (meshRepository.isSeen(packet.packetId)) {
                meshRepository.logRouteEvent(packet.packetId, RouteAction.DROPPED_DUPLICATE)
                // The sender is retrying because it never received our DeliveryAck.
                // Re-deliver (idempotent DB insert) so the ACK is sent again without
                // re-inserting the message if it already exists.
                if (packet.destinationPublicKey == selfId()) {
                    Log.d(TAG, "Re-ACKing duplicate ${packet.packetId.take(16)} for self")
                    deliverLocally(packet)
                }
                return@launch
            }
            meshRepository.markSeen(packet.packetId)

            val ttlLeft = packet.ttl - 1
            if (ttlLeft < 0) {
                Log.d(TAG, "TTL exhausted for ${packet.packetId}")
                meshRepository.logRouteEvent(packet.packetId, RouteAction.DROPPED_TTL)
                sendRouteFailure(packet.packetId, FailureReason.TTL_EXCEEDED)
                return@launch
            }

            val self = selfId()
            if (packet.destinationPublicKey == self) {
                Log.d(TAG, "Packet ${packet.packetId.takeLast(8)} reached destination self")
                meshRepository.logRouteEvent(packet.packetId, RouteAction.DELIVERED)
                deliverLocally(packet)
            } else {
                // voidHopCount and routingMode are preserved from the incoming packet;
                // they will be updated by attemptForward when forwarding.
                val reduced = packet.copy(ttl = ttlLeft, hopCount = packet.hopCount + 1)
                meshRepository.enqueueRelayPacket(
                    reduced.packetId, reduced.destinationPublicKey,
                    MeshProtocolCodec.encodeToBase64(reduced),
                    reduced.ttl, reduced.routingMode
                )
                val decision = attemptForward(reduced)
                if (decision == RoutingDecision.UNREACHABLE) {
                    sendRouteFailure(reduced.packetId, FailureReason.NO_ROUTE)
                }
            }
        }
    }

    override fun onNeighborAvailable(neighborPublicKey: String) {
        scope.launch {
            val pending = meshRepository.getAllPendingRelayPackets()
            if (pending.isEmpty()) return@launch
            Log.d(TAG, "Draining ${pending.size} queued packet(s) after neighbor=$neighborPublicKey")
            for (entry in pending) {
                try {
                    val packet = MeshProtocolCodec.decodeFromBase64(entry.serializedPayload)
                        as? BlePayload.RoutedMessage ?: continue
                    if (packet.ttl <= 0) continue   // worker will expire this entry
                    val decision = attemptForward(packet)
                    meshRepository.logRouteEvent(
                        packet.packetId, RouteAction.DEQUEUED,
                        chosenNextHop = neighborPublicKey,
                        reason = decision.name
                    )
                    // Update the conversation status so the UI reflects the forwarding result
                    // (the original QUEUED status from originate() is now stale).
                    if (decision == RoutingDecision.DIRECT || decision == RoutingDecision.FORWARDED) {
                        onQueuedForwarded(packet.packetId)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Queue drain error for ${entry.packetId}: ${e.message}")
                }
            }
        }
    }

    // ── Failure back-propagation ──────────────────────────────────────────────

    private suspend fun sendRouteFailure(packetId: String, reason: FailureReason) {
        try {
            val self = selfId()
            val now  = System.currentTimeMillis()
            val sig  = crypto.signRouteFailure(packetId, self, reason.id, now)
            reportFailure(BlePayload.RouteFailure(packetId, self, reason, now, sig))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to emit RouteFailure for $packetId: ${e.message}")
        }
    }

    // ── Routing core ──────────────────────────────────────────────────────────

    /**
     * Result of [selectForward]. Sealed so the caller must handle every case explicitly.
     */
    private sealed class ForwardDecision {
        /** Neighbor is the destination itself — deliver directly. */
        data class GreedyHop(
            val hop: NeighborEntity,
            /** True when this hop switches the packet back from PERIMETER to GREEDY. */
            val recoveredFromPerimeter: Boolean = false
        ) : ForwardDecision()

        /** Greedy progress blocked; forward to best-available neighbor to route around the void. */
        data class PerimeterHop(val hop: NeighborEntity) : ForwardDecision()

        /** Consecutive perimeter hops reached [MAX_VOID_HOPS]; hold for a better neighbor. */
        object VoidLimitExceeded : ForwardDecision()

        /** No relay-capable neighbors with location data; check non-geo neighbors separately. */
        object NoRoutableNeighbors : ForwardDecision()
    }

    private suspend fun attemptForward(packet: BlePayload.RoutedMessage): RoutingDecision {
        // Fast path: destination is a directly-connected BLE neighbor right now.
        val directNeighbor = meshRepository.getNeighborByPublicKey(packet.destinationPublicKey)
        if (directNeighbor != null && isNeighborReachable(directNeighbor)) {
            val outPacket = packet.copy(routingMode = RoutingMode.GREEDY, voidHopCount = 0)
            meshRepository.markRelayInFlight(packet.packetId)
            try {
                Log.d(
                    TAG,
                    "Direct forward packet=${packet.packetId.takeLast(8)} ble=${directNeighbor.bleAddress} " +
                        "dst=${packet.destinationPublicKey.take(12)}"
                )
                val forwarded = forwardPacket(outPacket, directNeighbor.bleAddress ?: "")
                if (!forwarded) {
                    Log.d(TAG, "Direct forward failed packet=${packet.packetId.takeLast(8)} ble=${directNeighbor.bleAddress}")
                    meshRepository.resetRelayPacket(packet.packetId)
                    return RoutingDecision.QUEUED
                }
            } catch (e: Exception) {
                Log.w(TAG, "forwardPacket failed for ${packet.packetId.take(16)}: ${e.message}")
                meshRepository.resetRelayPacket(packet.packetId)
                return RoutingDecision.QUEUED
            }
            meshRepository.logRouteEvent(packet.packetId, RouteAction.FORWARDED,
                chosenNextHop = packet.destinationPublicKey,
                reason = "direct")
            return RoutingDecision.DIRECT
        }

        return when (val fd = selectForward(packet)) {
            is ForwardDecision.GreedyHop -> {
                val outPacket = packet.copy(routingMode = RoutingMode.GREEDY, voidHopCount = 0)
                meshRepository.markRelayInFlight(packet.packetId)
                try {
                    Log.d(
                        TAG,
                        "Greedy forward packet=${packet.packetId.takeLast(8)} next=${fd.hop.neighborPublicKey.take(12)} " +
                            "ble=${fd.hop.bleAddress}"
                    )
                    val forwarded = forwardPacket(outPacket, fd.hop.bleAddress ?: "")
                    if (!forwarded) {
                        Log.d(TAG, "Greedy forward failed packet=${packet.packetId.takeLast(8)} ble=${fd.hop.bleAddress}")
                        meshRepository.resetRelayPacket(packet.packetId)
                        return RoutingDecision.QUEUED
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "forwardPacket failed for ${packet.packetId.take(16)}: ${e.message}")
                    meshRepository.resetRelayPacket(packet.packetId)
                    return RoutingDecision.QUEUED
                }
                meshRepository.logRouteEvent(
                    packet.packetId, RouteAction.FORWARDED,
                    chosenNextHop = fd.hop.neighborPublicKey,
                    reason = if (fd.recoveredFromPerimeter) "greedy_recovered" else null
                )
                RoutingDecision.FORWARDED
            }

            is ForwardDecision.PerimeterHop -> {
                val newVoidCount = packet.voidHopCount + 1
                val outPacket = packet.copy(
                    routingMode  = RoutingMode.PERIMETER,
                    voidHopCount = newVoidCount
                )
                meshRepository.markRelayInFlight(packet.packetId)
                try {
                    Log.d(
                        TAG,
                        "Perimeter forward packet=${packet.packetId.takeLast(8)} next=${fd.hop.neighborPublicKey.take(12)} " +
                            "ble=${fd.hop.bleAddress} void=${newVoidCount}"
                    )
                    val forwarded = forwardPacket(outPacket, fd.hop.bleAddress ?: "")
                    if (!forwarded) {
                        Log.d(TAG, "Perimeter forward failed packet=${packet.packetId.takeLast(8)} ble=${fd.hop.bleAddress}")
                        meshRepository.resetRelayPacket(packet.packetId)
                        return RoutingDecision.QUEUED
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "forwardPacket failed for ${packet.packetId.take(16)}: ${e.message}")
                    meshRepository.resetRelayPacket(packet.packetId)
                    return RoutingDecision.QUEUED
                }
                meshRepository.logRouteEvent(
                    packet.packetId, RouteAction.PERIMETER_HOP,
                    chosenNextHop = fd.hop.neighborPublicKey,
                    reason = "void=$newVoidCount/$MAX_VOID_HOPS"
                )
                Log.d(TAG, "Perimeter hop ${packet.packetId.take(16)} → ${fd.hop.neighborPublicKey.take(16)} void=$newVoidCount")
                RoutingDecision.FORWARDED
            }

            ForwardDecision.VoidLimitExceeded -> {
                meshRepository.logRouteEvent(
                    packet.packetId, RouteAction.VOID_LIMIT_EXCEEDED,
                    reason = "voidHopCount=${packet.voidHopCount} limit=$MAX_VOID_HOPS"
                )
                Log.d(TAG, "Void budget exhausted for ${packet.packetId.take(16)} — holding in queue")
                // Keep packet PENDING; the worker or next beacon will re-evaluate.
                RoutingDecision.QUEUED
            }

            ForwardDecision.NoRoutableNeighbors -> {
                // No relay-capable neighbors with known locations. Check for any neighbors at all
                // to distinguish QUEUED (there are neighbors but none are geo-capable yet) from
                // UNREACHABLE (nobody around).
                val anyNeighbors = meshRepository
                    .getRecentNeighbors(MeshRepository.NEIGHBOR_STALE_MS)
                    .isNotEmpty()
                if (anyNeighbors) RoutingDecision.QUEUED else RoutingDecision.UNREACHABLE
            }
        }
    }

    /**
     * Select the best neighbor to forward [packet] toward its destination.
     *
     * Priority order:
     * 1. **Greedy** — a neighbor that is ≥[IMPROVEMENT_THRESHOLD] × closer to the destination
     *    than the current node. Switches back from PERIMETER to GREEDY if applicable.
     * 2. **Perimeter** — when greedy is blocked, pick the least-bad neighbor (closest to
     *    destination) to route around the void, up to [MAX_VOID_HOPS] consecutive hops.
     * 3. **VoidLimitExceeded** — if already in PERIMETER mode and budget exhausted, hold.
     * 4. **NoRoutableNeighbors** — no relay-capable neighbors with location data.
     */
    private suspend fun selectForward(packet: BlePayload.RoutedMessage): ForwardDecision {
        val candidates = meshRepository
            .getRecentNeighbors(MeshRepository.NEIGHBOR_STALE_MS)
            .filter { it.relayCapable && it.lat != null && it.lon != null && isNeighborReachable(it) }

        if (candidates.isEmpty()) return ForwardDecision.NoRoutableNeighbors

        val dstContact = meshRepository.getContact(packet.destinationPublicKey)
        val dstLat     = dstContact?.lastResolvedLat
        val dstLon     = dstContact?.lastResolvedLon

        // No destination coordinates known — flood to the first relay-capable neighbor in both modes.
        if (dstLat == null || dstLon == null) {
            return ForwardDecision.GreedyHop(candidates.first())
        }

        val selfLoc  = locationProvider.getLastLocation()
        val selfDist = if (selfLoc != null)
            haversineKm(selfLoc.latitude, selfLoc.longitude, dstLat, dstLon)
        else
            Double.MAX_VALUE

        // Closest neighbor to the destination (candidate for both greedy and perimeter).
        val best     = candidates.minByOrNull { haversineKm(it.lat!!, it.lon!!, dstLat, dstLon) }!!
        val bestDist = haversineKm(best.lat!!, best.lon!!, dstLat, dstLon)

        if (bestDist < selfDist * IMPROVEMENT_THRESHOLD) {
            // Greedy progress: this neighbor is meaningfully closer than we are.
            val recovered = packet.routingMode == RoutingMode.PERIMETER
            return ForwardDecision.GreedyHop(best, recovered)
        }

        // ── Greedy failed — routing void detected ────────────────────────────
        // Check if the perimeter budget is exhausted before taking another void hop.
        if (packet.routingMode == RoutingMode.PERIMETER && packet.voidHopCount >= MAX_VOID_HOPS) {
            return ForwardDecision.VoidLimitExceeded
        }

        // Perimeter hop: forward to the best-available neighbor even though it doesn't
        // improve proximity to the destination. The voidHopCount budget prevents looping.
        return ForwardDecision.PerimeterHop(best)
    }

    // ── Geometry ──────────────────────────────────────────────────────────────

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a    = sin(dLat / 2).pow(2) +
                   cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return EARTH_RADIUS_KM * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun isNeighborReachable(neighbor: NeighborEntity): Boolean =
        canReachBleAddress(neighbor.bleAddress)

    companion object {
        const val DEFAULT_TTL = 7
        /** Neighbor must be at least 5 % closer for a greedy advance to qualify. */
        private const val IMPROVEMENT_THRESHOLD = 0.95
        /** Maximum consecutive perimeter hops before the packet is held for re-evaluation. */
        const val MAX_VOID_HOPS = 3
        private const val EARTH_RADIUS_KM = 6371.0
    }
}
