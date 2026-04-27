package com.meshchat.app.domain

import java.security.MessageDigest
import java.util.Base64

data class Identity(
    val deviceId: String,
    val displayName: String,
    val createdAt: Long,
    val hasCompletedOnboarding: Boolean = false,
    val publicKey: String = ""
) {
    /** First 16 hex chars of SHA-256(public key DER) — stable short display ID. */
    val publicKeyFingerprint: String get() {
        if (publicKey.isEmpty()) return ""
        return try {
            val bytes = Base64.getDecoder().decode(publicKey)
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .take(8).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "" }
    }
}
