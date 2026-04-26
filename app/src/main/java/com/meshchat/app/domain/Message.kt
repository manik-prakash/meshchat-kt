package com.meshchat.app.domain

enum class MessageStatus { SENDING, SENT, FAILED }

data class Message(
    val id: String,
    val conversationId: String,
    val senderDeviceId: String,
    val text: String,
    val status: MessageStatus,
    val createdAt: Long
)
