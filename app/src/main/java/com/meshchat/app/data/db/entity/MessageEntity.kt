package com.meshchat.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.meshchat.app.domain.Message
import com.meshchat.app.domain.MessageStatus

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversation_id", "created_at"])],
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversation_id"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class MessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "sender_device_id") val senderDeviceId: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "status") val status: String = "sending",
    @ColumnInfo(name = "created_at") val createdAt: Long
)

fun MessageEntity.toDomain() = Message(
    id = id,
    conversationId = conversationId,
    senderDeviceId = senderDeviceId,
    text = text,
    status = when (status) {
        "sent"               -> MessageStatus.SENT
        "queued"             -> MessageStatus.QUEUED
        "forwarded"          -> MessageStatus.FORWARDED
        "delivered"          -> MessageStatus.DELIVERED
        "failed"             -> MessageStatus.FAILED
        "failed_unreachable" -> MessageStatus.FAILED_UNREACHABLE
        "failed_expired"     -> MessageStatus.FAILED_EXPIRED
        else                 -> MessageStatus.SENDING
    },
    createdAt = createdAt
)

fun Message.toEntity() = MessageEntity(
    id = id,
    conversationId = conversationId,
    senderDeviceId = senderDeviceId,
    text = text,
    status = status.name.lowercase(),
    createdAt = createdAt
)
