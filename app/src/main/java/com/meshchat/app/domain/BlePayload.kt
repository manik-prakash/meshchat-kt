package com.meshchat.app.domain

sealed class BlePayload {
    data class Handshake(
        val deviceId: String,
        val displayName: String
    ) : BlePayload()

    data class Message(
        val id: String,
        val senderDeviceId: String,
        val text: String,
        val timestamp: Long
    ) : BlePayload()

    data class Ack(val messageId: String) : BlePayload()
}
