package com.meshchat.app.data.repository

import com.meshchat.app.data.db.dao.ConversationDao
import com.meshchat.app.data.db.dao.MessageDao
import com.meshchat.app.data.db.dao.PeerDao
import com.meshchat.app.data.db.entity.ConversationEntity
import com.meshchat.app.data.db.entity.MessageEntity
import com.meshchat.app.data.db.entity.toDomain
import com.meshchat.app.data.db.entity.toEntity
import com.meshchat.app.domain.Conversation
import com.meshchat.app.domain.Message
import com.meshchat.app.domain.MessageStatus
import com.meshchat.app.domain.Peer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ConversationRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val peerDao: PeerDao
) {
    fun getConversations(): Flow<List<Conversation>> =
        conversationDao.getAll().map { list -> list.map { it.toDomain() } }

    fun getMessages(conversationId: String): Flow<List<Message>> =
        messageDao.getMessages(conversationId).map { list -> list.map { it.toDomain() } }

    suspend fun getOrCreateConversation(peerDeviceId: String, peerDisplayName: String): Conversation {
        val existing = conversationDao.getByPeerDeviceId(peerDeviceId)
        if (existing != null) return existing.toDomain()

        val now = System.currentTimeMillis()
        val id = generateId()
        val entity = ConversationEntity(
            id = id,
            peerDeviceId = peerDeviceId,
            peerDisplayName = peerDisplayName,
            lastMessage = null,
            lastMessageAt = null,
            createdAt = now
        )
        conversationDao.insert(entity)
        return entity.toDomain()
    }

    suspend fun insertMessage(
        conversationId: String,
        senderDeviceId: String,
        text: String,
        status: MessageStatus = MessageStatus.SENDING,
        messageId: String? = null
    ): Message {
        val id = messageId ?: generateId()
        val now = System.currentTimeMillis()
        val entity = MessageEntity(
            id = id,
            conversationId = conversationId,
            senderDeviceId = senderDeviceId,
            text = text,
            status = status.name.lowercase(),
            createdAt = now
        )
        messageDao.insert(entity)
        conversationDao.updateLastMessage(conversationId, text, now)
        return entity.toDomain()
    }

    suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        messageDao.updateStatus(messageId, status.name.lowercase())
    }

    suspend fun updateMessageDelivered(messageId: String, hopCount: Int) {
        messageDao.updateStatusAndHopCount(messageId, MessageStatus.DELIVERED.name.lowercase(), hopCount)
    }

    suspend fun messageExists(messageId: String): Boolean =
        messageDao.exists(messageId) > 0

    suspend fun getConversationByPeerDeviceId(peerDeviceId: String): Conversation? =
        conversationDao.getByPeerDeviceId(peerDeviceId)?.toDomain()

    suspend fun getConversationByPeerDisplayName(displayName: String): Conversation? =
        conversationDao.getByPeerDisplayName(displayName)?.toDomain()

    suspend fun repairPeerIdentity(conversationId: String, newDeviceId: String, newDisplayName: String) =
        conversationDao.updatePeerIdentity(conversationId, newDeviceId, newDisplayName)

    suspend fun upsertPeer(peer: Peer) {
        val existing = peerDao.findByDisplayName(peer.displayName, peer.deviceId)
        if (existing != null) {
            peerDao.update(existing.deviceId, peer.lastSeen, peer.rssi, peer.bleId)
        } else {
            peerDao.insert(peer.toEntity())
        }
    }
}

private fun generateId(): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    val random = (1..16).map { chars.random() }.joinToString("")
    return "${System.currentTimeMillis().toString(36)}_$random"
}
