package com.meshchat.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.meshchat.app.domain.Conversation

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "peer_device_id") val peerDeviceId: String,
    @ColumnInfo(name = "peer_display_name") val peerDisplayName: String,
    @ColumnInfo(name = "last_message") val lastMessage: String?,
    @ColumnInfo(name = "last_message_at") val lastMessageAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

fun ConversationEntity.toDomain() = Conversation(id, peerDeviceId, peerDisplayName, lastMessage, lastMessageAt, createdAt)
fun Conversation.toEntity() = ConversationEntity(id, peerDeviceId, peerDisplayName, lastMessage, lastMessageAt, createdAt)
