package com.meshchat.app.domain

enum class MessageStatus {
    SENDING,
    /** Accepted locally; no route available yet. */
    QUEUED,
    /** Handed to a relay node; awaiting end-to-end confirmation. */
    FORWARDED,
    /** Direct-link delivery (legacy / single-hop). */
    SENT,
    /** DeliveryAck received from the final destination. */
    DELIVERED,
    /** Legacy single-hop failure retained for DB compat. */
    FAILED,
    /** No relay-capable neighbor found at send time. */
    FAILED_UNREACHABLE,
    /** Packet TTL reached zero before delivery. */
    FAILED_EXPIRED,
}

data class Message(
    val id: String,
    val conversationId: String,
    val senderDeviceId: String,
    val text: String,
    val status: MessageStatus,
    val createdAt: Long
)
