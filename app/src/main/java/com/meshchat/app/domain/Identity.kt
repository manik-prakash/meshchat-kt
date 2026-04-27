package com.meshchat.app.domain

data class Identity(
    val deviceId: String,
    val displayName: String,
    val createdAt: Long,
    val hasCompletedOnboarding: Boolean = false,
    val publicKey: String = ""
)
