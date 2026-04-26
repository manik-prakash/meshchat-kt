package com.meshchat.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshchat.app.ble.BleState
import com.meshchat.app.ble.BleMeshManager
import com.meshchat.app.data.db.AppDatabase
import com.meshchat.app.data.repository.ConversationRepository
import com.meshchat.app.data.repository.IdentityRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BleMeshManagerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var identityRepo: IdentityRepository
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var manager: BleMeshManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        identityRepo   = IdentityRepository(db.identityDao())
        conversationRepo = ConversationRepository(db.conversationDao(), db.messageDao(), db.peerDao())
        manager = BleMeshManager(context, identityRepo, conversationRepo)
    }

    @After
    fun tearDown() {
        manager.destroy()
        db.close()
    }

    @Test
    fun initialStateIsIdle() {
        assertEquals(BleState.IDLE, manager.state.value)
    }

    @Test
    fun stopScanWhenNotScanningIsNoOp() {
        manager.stopScan()
        assertEquals(BleState.IDLE, manager.state.value)
    }

    @Test
    fun identityCreatedOnFirstAccess() = runBlocking {
        val identity = identityRepo.ensureIdentity()
        assertNotNull(identity.deviceId)
        assertTrue(identity.displayName.startsWith("anon_"))
    }

    @Test
    fun identityIsCachedOnSecondAccess() = runBlocking {
        val first  = identityRepo.ensureIdentity()
        val second = identityRepo.ensureIdentity()
        assertEquals(first.deviceId, second.deviceId)
    }

    @Test
    fun updateDisplayNamePersists() = runBlocking {
        identityRepo.ensureIdentity()
        identityRepo.updateDisplayName("TestUser")
        val updated = identityRepo.ensureIdentity()
        assertEquals("TestUser", updated.displayName)
    }

    @Test
    fun conversationCreatedForNewPeer() = runBlocking {
        val conv = conversationRepo.getOrCreateConversation("peer123", "PeerName")
        assertNotNull(conv.id)
        assertEquals("peer123", conv.peerDeviceId)
        assertEquals("PeerName", conv.peerDisplayName)
    }

    @Test
    fun getOrCreateConversationIsIdempotent() = runBlocking {
        val first  = conversationRepo.getOrCreateConversation("peer456", "AnotherPeer")
        val second = conversationRepo.getOrCreateConversation("peer456", "AnotherPeer")
        assertEquals(first.id, second.id)
    }

    @Test
    fun messageInsertedAndStatusUpdated() = runBlocking {
        val conv = conversationRepo.getOrCreateConversation("p1", "P1")
        val msg  = conversationRepo.insertMessage(conv.id, "p1", "hello")
        assertFalse(msg.id.isEmpty())

        conversationRepo.updateMessageStatus(msg.id, com.meshchat.app.domain.MessageStatus.SENT)
        val msgs = conversationRepo.getMessages(conv.id).first()
        assertEquals(com.meshchat.app.domain.MessageStatus.SENT, msgs.first().status)
    }

    @Test
    fun messageExistsReturnsTrueAfterInsert() = runBlocking {
        val conv = conversationRepo.getOrCreateConversation("p2", "P2")
        val msg  = conversationRepo.insertMessage(conv.id, "p2", "msg", messageId = "known-id-001")
        assertTrue(conversationRepo.messageExists("known-id-001"))
        assertFalse(conversationRepo.messageExists("unknown-id-xyz"))
    }

    @Test
    fun peerUpsertDeduplicatesByDisplayName() = runBlocking {
        val peer1 = com.meshchat.app.domain.Peer("mac1", "Alice", System.currentTimeMillis(), -70, "mac1")
        val peer2 = com.meshchat.app.domain.Peer("mac2", "Alice", System.currentTimeMillis() + 1000, -60, "mac2")
        conversationRepo.upsertPeer(peer1)
        conversationRepo.upsertPeer(peer2)

        // Only one Alice entry should exist (latest BLE address wins)
        val allPeers = db.peerDao().getAll().first()
        val alices = allPeers.filter { it.displayName == "Alice" }
        assertEquals(1, alices.size)
        assertEquals("mac2", alices[0].bleId)
    }
}
