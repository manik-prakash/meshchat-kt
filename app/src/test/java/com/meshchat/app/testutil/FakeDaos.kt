package com.meshchat.app.testutil

import com.meshchat.app.data.db.dao.ConversationDao
import com.meshchat.app.data.db.dao.KnownContactDao
import com.meshchat.app.data.db.dao.MessageDao
import com.meshchat.app.data.db.dao.NeighborDao
import com.meshchat.app.data.db.dao.PeerDao
import com.meshchat.app.data.db.dao.RelayQueueDao
import com.meshchat.app.data.db.dao.RouteEventDao
import com.meshchat.app.data.db.dao.SeenPacketDao
import com.meshchat.app.data.db.entity.ConversationEntity
import com.meshchat.app.data.db.entity.KnownContactEntity
import com.meshchat.app.data.db.entity.MessageEntity
import com.meshchat.app.data.db.entity.NeighborEntity
import com.meshchat.app.data.db.entity.PeerEntity
import com.meshchat.app.data.db.entity.RelayQueueEntity
import com.meshchat.app.data.db.entity.RouteEventEntity
import com.meshchat.app.data.db.entity.SeenPacketEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeConversationDao : ConversationDao {
    val conversations = linkedMapOf<String, ConversationEntity>()

    override fun getAll(): Flow<List<ConversationEntity>> = flowOf(conversations.values.toList())

    override suspend fun getByPeerDeviceId(peerDeviceId: String): ConversationEntity? =
        conversations.values.firstOrNull { it.peerDeviceId == peerDeviceId }

    override suspend fun insert(conversation: ConversationEntity) {
        conversations.putIfAbsent(conversation.id, conversation)
    }

    override suspend fun updateLastMessage(conversationId: String, text: String, timestamp: Long) {
        val current = conversations[conversationId] ?: return
        conversations[conversationId] = current.copy(lastMessage = text, lastMessageAt = timestamp)
    }

    override suspend fun getByPeerDisplayName(displayName: String): ConversationEntity? =
        conversations.values.firstOrNull { it.peerDisplayName == displayName }

    override suspend fun updatePeerIdentity(id: String, deviceId: String, displayName: String) {
        val current = conversations[id] ?: return
        conversations[id] = current.copy(peerDeviceId = deviceId, peerDisplayName = displayName)
    }
}

class FakeMessageDao : MessageDao {
    val messages = linkedMapOf<String, MessageEntity>()

    override fun getMessages(conversationId: String): Flow<List<MessageEntity>> =
        flowOf(messages.values.filter { it.conversationId == conversationId }.sortedBy { it.createdAt })

    override suspend fun insert(message: MessageEntity) {
        messages.putIfAbsent(message.id, message)
    }

    override suspend fun updateStatus(messageId: String, status: String) {
        val current = messages[messageId] ?: return
        messages[messageId] = current.copy(status = status)
    }

    override suspend fun updateStatusAndHopCount(messageId: String, status: String, hopCount: Int) {
        val current = messages[messageId] ?: return
        messages[messageId] = current.copy(status = status, deliveredHopCount = hopCount)
    }

    override suspend fun exists(messageId: String): Int =
        if (messages.containsKey(messageId)) 1 else 0
}

class FakePeerDao : PeerDao {
    val peers = linkedMapOf<String, PeerEntity>()

    override fun getAll(): Flow<List<PeerEntity>> =
        flowOf(peers.values.sortedByDescending { it.lastSeen })

    override suspend fun insert(peer: PeerEntity) {
        peers[peer.deviceId] = peer
    }

    override suspend fun findByDeviceId(deviceId: String): PeerEntity? = peers[deviceId]

    override suspend fun findByDisplayName(displayName: String, excludeDeviceId: String): PeerEntity? =
        peers.values.firstOrNull { it.displayName == displayName && it.deviceId != excludeDeviceId }

    override suspend fun update(deviceId: String, lastSeen: Long, rssi: Int?, bleId: String?) {
        val current = peers[deviceId] ?: return
        peers[deviceId] = current.copy(lastSeen = lastSeen, rssi = rssi, bleId = bleId)
    }
}

class FakeSeenPacketDao : SeenPacketDao {
    val packets = linkedMapOf<String, SeenPacketEntity>()

    override suspend fun isSeen(packetId: String, now: Long): Int =
        if (packets[packetId]?.expiresAt?.let { it > now } == true) 1 else 0

    override suspend fun markSeen(packet: SeenPacketEntity) {
        packets.putIfAbsent(packet.packetId, packet)
    }

    override suspend fun pruneExpired(now: Long) {
        packets.entries.removeIf { it.value.expiresAt <= now }
    }
}

class FakeNeighborDao : NeighborDao {
    val neighbors = linkedMapOf<String, NeighborEntity>()

    override fun getAll(): Flow<List<NeighborEntity>> =
        flowOf(neighbors.values.sortedByDescending { it.lastSeen })

    override suspend fun getRecent(since: Long): List<NeighborEntity> =
        neighbors.values.filter { it.lastSeen >= since }.sortedByDescending { it.rssi ?: Int.MIN_VALUE }

    override suspend fun getByPublicKey(publicKey: String): NeighborEntity? = neighbors[publicKey]

    override suspend fun upsert(neighbor: NeighborEntity) {
        neighbors[neighbor.neighborPublicKey] = neighbor
    }

    override suspend fun updateSighting(publicKey: String, rssi: Int?, lastSeen: Long, bleAddress: String?) {
        val current = neighbors[publicKey] ?: return
        neighbors[publicKey] = current.copy(rssi = rssi, lastSeen = lastSeen, bleAddress = bleAddress)
    }

    override suspend fun pruneStale(before: Long) {
        neighbors.entries.removeIf { it.value.lastSeen < before }
    }
}

class FakeKnownContactDao : KnownContactDao {
    val contacts = linkedMapOf<String, KnownContactEntity>()

    override fun getAll(): Flow<List<KnownContactEntity>> =
        flowOf(contacts.values.sortedBy { it.displayName })

    override suspend fun getByPublicKey(publicKey: String): KnownContactEntity? = contacts[publicKey]

    override suspend fun upsert(contact: KnownContactEntity) {
        contacts[contact.publicKey] = contact
    }

    override suspend fun updateLocation(publicKey: String, geohash: String?, lat: Double?, lon: Double?, at: Long) {
        val current = contacts[publicKey] ?: return
        contacts[publicKey] = current.copy(
            lastResolvedGeoHash = geohash,
            lastResolvedLat = lat,
            lastResolvedLon = lon,
            lastLocationAt = at
        )
    }

    override suspend fun delete(publicKey: String) {
        contacts.remove(publicKey)
    }
}

class FakeRelayQueueDao : RelayQueueDao {
    val entries = linkedMapOf<String, RelayQueueEntity>()

    override fun getPending(): Flow<List<RelayQueueEntity>> =
        flowOf(entries.values.filter { it.status == "PENDING" }.sortedBy { it.createdAt })

    override suspend fun getAllPending(): List<RelayQueueEntity> =
        entries.values.filter { it.status == "PENDING" }.sortedBy { it.createdAt }

    override suspend fun getPendingFor(destKey: String): List<RelayQueueEntity> =
        entries.values.filter { it.status == "PENDING" && it.destinationPublicKey == destKey }.sortedBy { it.createdAt }

    override suspend fun resetToPending(packetId: String, now: Long) {
        val current = entries[packetId] ?: return
        if (current.status == "FAILED" || current.status == "IN_FLIGHT") {
            entries[packetId] = current.copy(status = "PENDING", failureReason = null, createdAt = now)
        }
    }

    override suspend fun enqueue(packet: RelayQueueEntity) {
        entries.putIfAbsent(packet.packetId, packet)
    }

    override suspend fun markInFlight(packetId: String, now: Long) {
        val current = entries[packetId] ?: return
        entries[packetId] = current.copy(
            status = "IN_FLIGHT",
            lastTriedAt = now,
            retryCount = current.retryCount + 1
        )
    }

    override suspend fun markDelivered(packetId: String) {
        val current = entries[packetId] ?: return
        entries[packetId] = current.copy(status = "DELIVERED")
    }

    override suspend fun markFailed(packetId: String, reason: String?) {
        val current = entries[packetId] ?: return
        entries[packetId] = current.copy(status = "FAILED", failureReason = reason)
    }

    override suspend fun resetInFlight() {
        entries.replaceAll { _, value ->
            if (value.status == "IN_FLIGHT") value.copy(status = "PENDING") else value
        }
    }

    override suspend fun getExpiredPending(expiredBefore: Long): List<RelayQueueEntity> =
        entries.values.filter { it.status == "PENDING" && it.createdAt < expiredBefore }.sortedBy { it.createdAt }

    override suspend fun resetStaleInFlight(staleBefore: Long) {
        entries.replaceAll { _, value ->
            if (value.status == "IN_FLIGHT" && (value.lastTriedAt ?: Long.MAX_VALUE) < staleBefore) {
                value.copy(status = "PENDING")
            } else {
                value
            }
        }
    }

    override suspend fun pruneSettled(before: Long) {
        entries.entries.removeIf {
            (it.value.status == "DELIVERED" || it.value.status == "FAILED") &&
                (it.value.lastTriedAt ?: Long.MAX_VALUE) < before
        }
    }
}

class FakeRouteEventDao : RouteEventDao {
    private var nextId = 1L
    val events = mutableListOf<RouteEventEntity>()

    override fun getEventsForPacket(packetId: String): Flow<List<RouteEventEntity>> =
        flowOf(events.filter { it.packetId == packetId }.sortedBy { it.timestamp })

    override fun getLatestEvent(): Flow<RouteEventEntity?> =
        flowOf(events.maxByOrNull { it.timestamp })

    override suspend fun insert(event: RouteEventEntity) {
        events += event.copy(id = nextId++)
    }

    override suspend fun pruneOld(before: Long) {
        events.removeIf { it.timestamp < before }
    }
}
