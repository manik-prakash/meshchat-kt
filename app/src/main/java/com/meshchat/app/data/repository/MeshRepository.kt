package com.meshchat.app.data.repository

import com.meshchat.app.data.db.dao.KnownContactDao
import com.meshchat.app.data.db.dao.NeighborDao
import com.meshchat.app.data.db.dao.RelayQueueDao
import com.meshchat.app.data.db.dao.RouteEventDao
import com.meshchat.app.data.db.dao.SeenPacketDao
import com.meshchat.app.data.db.entity.KnownContactEntity
import com.meshchat.app.data.db.entity.NeighborEntity
import com.meshchat.app.data.db.entity.RelayQueueEntity
import com.meshchat.app.data.db.entity.RouteEventEntity
import com.meshchat.app.data.db.entity.SeenPacketEntity
import com.meshchat.app.domain.FailureReason
import com.meshchat.app.domain.RelayStatus
import com.meshchat.app.domain.RouteAction
import com.meshchat.app.domain.RoutingMode
import kotlinx.coroutines.flow.Flow

class MeshRepository(
    private val seenPacketDao: SeenPacketDao,
    private val relayQueueDao: RelayQueueDao,
    private val neighborDao: NeighborDao,
    private val knownContactDao: KnownContactDao,
    private val routeEventDao: RouteEventDao
) {

    // --- Seen-packet dedup (replaces in-memory PacketIdCache) ---

    suspend fun isSeen(packetId: String): Boolean {
        val now = System.currentTimeMillis()
        return seenPacketDao.isSeen(packetId, now) > 0
    }

    suspend fun markSeen(packetId: String, ttlMs: Long = SEEN_TTL_MS) {
        val now = System.currentTimeMillis()
        seenPacketDao.markSeen(SeenPacketEntity(packetId, now, now + ttlMs))
    }

    suspend fun pruneExpiredSeen() {
        seenPacketDao.pruneExpired(System.currentTimeMillis())
    }

    // --- Relay queue ---

    fun getPendingRelayPackets(): Flow<List<RelayQueueEntity>> = relayQueueDao.getPending()

    suspend fun getAllPendingRelayPackets(): List<RelayQueueEntity> = relayQueueDao.getAllPending()

    suspend fun resetRelayPacket(packetId: String) =
        relayQueueDao.resetToPending(packetId, System.currentTimeMillis())

    suspend fun getPendingPacketsFor(destinationPublicKey: String): List<RelayQueueEntity> =
        relayQueueDao.getPendingFor(destinationPublicKey)

    suspend fun enqueueRelayPacket(
        packetId: String,
        destinationPublicKey: String,
        serializedPayload: String,
        ttl: Int,
        routingMode: RoutingMode
    ) {
        relayQueueDao.enqueue(
            RelayQueueEntity(
                packetId = packetId,
                destinationPublicKey = destinationPublicKey,
                serializedPayload = serializedPayload,
                ttl = ttl,
                routingMode = routingMode.name,
                status = RelayStatus.PENDING.name,
                retryCount = 0,
                createdAt = System.currentTimeMillis(),
                lastTriedAt = null,
                failureReason = null
            )
        )
    }

    suspend fun markRelayInFlight(packetId: String) {
        relayQueueDao.markInFlight(packetId, System.currentTimeMillis())
    }

    suspend fun markRelayDelivered(packetId: String) {
        relayQueueDao.markDelivered(packetId)
    }

    suspend fun markRelayFailed(packetId: String, reason: FailureReason?) {
        relayQueueDao.markFailed(packetId, reason?.name)
    }

    suspend fun resetInFlightPackets() {
        relayQueueDao.resetInFlight()
    }

    suspend fun getExpiredPendingPackets(expiredBefore: Long): List<RelayQueueEntity> =
        relayQueueDao.getExpiredPending(expiredBefore)

    suspend fun resetStaleInFlight(staleBefore: Long) =
        relayQueueDao.resetStaleInFlight(staleBefore)

    suspend fun pruneSettledRelayPackets(olderThanMs: Long = SETTLED_TTL_MS) {
        relayQueueDao.pruneSettled(System.currentTimeMillis() - olderThanMs)
    }

    // --- Neighbors ---

    fun getNeighbors(): Flow<List<NeighborEntity>> = neighborDao.getAll()

    suspend fun getRecentNeighbors(windowMs: Long = NEIGHBOR_WINDOW_MS): List<NeighborEntity> =
        neighborDao.getRecent(System.currentTimeMillis() - windowMs)

    suspend fun getNeighborByPublicKey(publicKey: String): NeighborEntity? =
        neighborDao.getByPublicKey(publicKey)

    suspend fun upsertNeighbor(neighbor: NeighborEntity) {
        neighborDao.upsert(neighbor)
    }

    suspend fun updateNeighborSighting(publicKey: String, rssi: Int?, bleAddress: String?) {
        neighborDao.updateSighting(publicKey, rssi, System.currentTimeMillis(), bleAddress)
    }

    suspend fun pruneStaleNeighbors(olderThanMs: Long = NEIGHBOR_WINDOW_MS) {
        neighborDao.pruneStale(System.currentTimeMillis() - olderThanMs)
    }

    // --- Known contacts ---

    fun getKnownContacts(): Flow<List<KnownContactEntity>> = knownContactDao.getAll()

    suspend fun getContact(publicKey: String): KnownContactEntity? =
        knownContactDao.getByPublicKey(publicKey)

    suspend fun ensureContact(publicKey: String, displayName: String) {
        val existing = knownContactDao.getByPublicKey(publicKey)
        if (existing == null) {
            knownContactDao.upsert(
                KnownContactEntity(
                    publicKey = publicKey,
                    displayName = displayName,
                    lastResolvedGeoHash = null,
                    lastResolvedLat = null,
                    lastResolvedLon = null,
                    lastLocationAt = null
                )
            )
            return
        }

        if (existing.displayName != displayName) {
            knownContactDao.upsert(existing.copy(displayName = displayName))
        }
    }

    suspend fun upsertContact(contact: KnownContactEntity) {
        knownContactDao.upsert(contact)
    }

    suspend fun updateContactLocation(
        publicKey: String,
        displayName: String,
        geohash: String?,
        lat: Double?,
        lon: Double?
    ) {
        val now = System.currentTimeMillis()
        val existing = knownContactDao.getByPublicKey(publicKey)
        if (existing == null) {
            knownContactDao.upsert(
                KnownContactEntity(
                    publicKey = publicKey,
                    displayName = displayName,
                    lastResolvedGeoHash = geohash,
                    lastResolvedLat = lat,
                    lastResolvedLon = lon,
                    lastLocationAt = now
                )
            )
            return
        }

        knownContactDao.updateLocation(publicKey, geohash, lat, lon, now)
    }

    suspend fun hasRecentRelayCandidates(
        excludePublicKey: String? = null,
        windowMs: Long = NEIGHBOR_STALE_MS
    ): Boolean = getRecentNeighbors(windowMs).any {
        it.relayCapable &&
            !it.bleAddress.isNullOrBlank() &&
            it.neighborPublicKey != excludePublicKey
    }

    // --- Route events ---

    fun getRouteEvents(packetId: String): Flow<List<RouteEventEntity>> =
        routeEventDao.getEventsForPacket(packetId)

    fun getLatestRouteEvent(): Flow<RouteEventEntity?> = routeEventDao.getLatestEvent()

    suspend fun logRouteEvent(
        packetId: String,
        action: RouteAction,
        chosenNextHop: String? = null,
        reason: String? = null
    ) {
        routeEventDao.insert(
            RouteEventEntity(
                packetId = packetId,
                action = action.name,
                timestamp = System.currentTimeMillis(),
                chosenNextHop = chosenNextHop,
                reason = reason
            )
        )
    }

    suspend fun pruneOldRouteEvents(olderThanMs: Long = ROUTE_EVENT_TTL_MS) {
        routeEventDao.pruneOld(System.currentTimeMillis() - olderThanMs)
    }

    companion object {
        const val SEEN_TTL_MS = 5 * 60_000L
        const val NEIGHBOR_WINDOW_MS = 10 * 60_000L
        /** After this interval a neighbor is considered offline and pruned from the live table. */
        const val NEIGHBOR_STALE_MS = 60_000L
        /** Hard expiry for relay queue entries (5 min). */
        const val PACKET_EXPIRY_MS = 5 * 60_000L
        /** Packets queued > 2 min without a known destination location get a separate failure. */
        const val STALE_LOCATION_TIMEOUT_MS = 2 * 60_000L
        /** IN_FLIGHT entries older than this are reset to PENDING (crash / silent drop recovery). */
        const val IN_FLIGHT_TIMEOUT_MS = 20_000L
        const val SETTLED_TTL_MS = 24 * 60 * 60_000L
        const val ROUTE_EVENT_TTL_MS = 7 * 24 * 60 * 60_000L
    }
}
