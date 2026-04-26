package com.meshchat.app.domain

data class Peer(
    val deviceId: String,
    val displayName: String,
    val lastSeen: Long,
    val rssi: Int?,
    val bleId: String?
)
