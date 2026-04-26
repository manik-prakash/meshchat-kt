package com.meshchat.app.domain

data class Conversation(
    val id: String,
    val peerDeviceId: String,
    val peerDisplayName: String,
    val lastMessage: String?,
    val lastMessageAt: Long?,
    val createdAt: Long
)
