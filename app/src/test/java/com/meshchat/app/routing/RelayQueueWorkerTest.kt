package com.meshchat.app.routing

import com.meshchat.app.data.db.entity.ConversationEntity
import com.meshchat.app.data.db.entity.MessageEntity
import com.meshchat.app.data.db.entity.NeighborEntity
import com.meshchat.app.data.db.entity.RelayQueueEntity
import com.meshchat.app.data.repository.ConversationRepository
import com.meshchat.app.data.repository.MeshRepository
import com.meshchat.app.domain.MessageStatus
import com.meshchat.app.testutil.FakeConversationDao
import com.meshchat.app.testutil.FakeKnownContactDao
import com.meshchat.app.testutil.FakeMessageDao
import com.meshchat.app.testutil.FakeNeighborDao
import com.meshchat.app.testutil.FakePeerDao
import com.meshchat.app.testutil.FakeRelayQueueDao
import com.meshchat.app.testutil.FakeRouteEventDao
import com.meshchat.app.testutil.FakeSeenPacketDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayQueueWorkerTest {
    @Test
    fun `stale packet is retained when relay candidates still exist`() = runBlocking {
        val fixture = WorkerFixture()
        fixture.seedPendingPacket(createdAt = System.currentTimeMillis() - RelayQueueWorker.STALE_LOCATION_TIMEOUT_MS - 1_000L)
        fixture.neighborDao.upsert(
            NeighborEntity(
                neighborPublicKey = "relay-1",
                displayName = "Relay",
                bleAddress = "ble-relay",
                rssi = -55,
                lastSeen = System.currentTimeMillis(),
                relayCapable = true,
                geohash = null,
                lat = null,
                lon = null,
                trustScore = 0.0
            )
        )

        fixture.worker.tick()

        assertEquals("PENDING", fixture.relayQueueDao.entries.getValue("packet-1").status)
        assertEquals("queued", fixture.messageDao.messages.getValue("packet-1").status)
    }

    @Test
    fun `stale packet fails when no direct or relay path remains`() = runBlocking {
        val fixture = WorkerFixture()
        fixture.seedPendingPacket(createdAt = System.currentTimeMillis() - RelayQueueWorker.STALE_LOCATION_TIMEOUT_MS - 1_000L)

        fixture.worker.tick()

        assertEquals("FAILED", fixture.relayQueueDao.entries.getValue("packet-1").status)
        assertEquals(
            MessageStatus.FAILED_UNREACHABLE.name.lowercase(),
            fixture.messageDao.messages.getValue("packet-1").status
        )
    }

    private class WorkerFixture {
        val conversationDao = FakeConversationDao()
        val messageDao = FakeMessageDao()
        val peerDao = FakePeerDao()
        val relayQueueDao = FakeRelayQueueDao()
        val neighborDao = FakeNeighborDao()
        val meshRepository = MeshRepository(
            seenPacketDao = FakeSeenPacketDao(),
            relayQueueDao = relayQueueDao,
            neighborDao = neighborDao,
            knownContactDao = FakeKnownContactDao(),
            routeEventDao = FakeRouteEventDao()
        )
        val conversationRepository = ConversationRepository(conversationDao, messageDao, peerDao)
        val worker = RelayQueueWorker(
            meshRepository = meshRepository,
            conversationRepository = conversationRepository,
            meshRouter = object : MeshRouter {
                override suspend fun originate(
                    packetId: String,
                    destinationId: String,
                    destinationDisplayName: String,
                    text: String,
                    timestamp: Long
                ) = RoutingDecision.QUEUED

                override fun onMessageReceived(packet: com.meshchat.app.domain.BlePayload.RoutedMessage, fromNeighbor: String) = Unit

                override fun onNeighborAvailable(neighborPublicKey: String) = Unit
            },
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        suspend fun seedPendingPacket(createdAt: Long) {
            conversationDao.insert(
                ConversationEntity(
                    id = "conv-1",
                    peerDeviceId = "dest-key",
                    peerDisplayName = "Dest",
                    lastMessage = null,
                    lastMessageAt = null,
                    createdAt = createdAt
                )
            )
            messageDao.insert(
                MessageEntity(
                    id = "packet-1",
                    conversationId = "conv-1",
                    senderDeviceId = "self",
                    text = "hello",
                    status = MessageStatus.QUEUED.name.lowercase(),
                    createdAt = createdAt
                )
            )
            relayQueueDao.enqueue(
                RelayQueueEntity(
                    packetId = "packet-1",
                    destinationPublicKey = "dest-key",
                    serializedPayload = "payload",
                    ttl = 7,
                    routingMode = "GREEDY",
                    status = "PENDING",
                    retryCount = 0,
                    createdAt = createdAt,
                    lastTriedAt = null,
                    failureReason = null
                )
            )
        }
    }
}
