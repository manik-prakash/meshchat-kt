package com.meshchat.app.data.repository

import com.meshchat.app.domain.Peer
import com.meshchat.app.testutil.FakeConversationDao
import com.meshchat.app.testutil.FakeMessageDao
import com.meshchat.app.testutil.FakePeerDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationRepositoryPeerTest {
    @Test
    fun `remote peer upsert preserves known direct ble address`() = runBlocking {
        val peerDao = FakePeerDao()
        val repo = ConversationRepository(FakeConversationDao(), FakeMessageDao(), peerDao)

        repo.upsertPeer(
            Peer(
                deviceId = "peer-key",
                displayName = "Casey",
                lastSeen = 10L,
                rssi = -55,
                bleId = "AA:BB"
            )
        )

        repo.upsertPeer(
            Peer(
                deviceId = "peer-key",
                displayName = "Casey",
                lastSeen = 20L,
                rssi = null,
                bleId = null
            )
        )

        val stored = peerDao.peers.getValue("peer-key")
        assertEquals("AA:BB", stored.bleId)
        assertEquals(-55, stored.rssi)
        assertEquals(20L, stored.lastSeen)
    }

    @Test
    fun `stable public key peer remains distinct from scan placeholder`() = runBlocking {
        val peerDao = FakePeerDao()
        val repo = ConversationRepository(FakeConversationDao(), FakeMessageDao(), peerDao)

        repo.upsertPeer(
            Peer(
                deviceId = "AA:BB",
                displayName = "Robin",
                lastSeen = 10L,
                rssi = -60,
                bleId = "AA:BB"
            )
        )
        repo.upsertPeer(
            Peer(
                deviceId = "pubkey-robin",
                displayName = "Robin",
                lastSeen = 20L,
                rssi = null,
                bleId = null
            )
        )

        assertEquals(setOf("AA:BB", "pubkey-robin"), peerDao.peers.keys)
    }
}
