package com.meshchat.app.data.repository

import com.meshchat.app.crypto.CryptoManager
import com.meshchat.app.data.db.dao.IdentityDao
import com.meshchat.app.data.db.entity.toEntity
import com.meshchat.app.data.db.entity.toDomain
import com.meshchat.app.domain.Identity
import android.util.Log
import java.util.UUID

class IdentityRepository(
    private val dao: IdentityDao,
    private val crypto: CryptoManager
) {
    private val tag = "IdentityRepo"

    @Volatile private var cached: Identity? = null

    suspend fun ensureIdentity(): Identity {
        cached?.let { return it }
        val existing = dao.getIdentity()
        if (existing != null) {
            val domain = existing.toDomain()
            val currentPublicKey = crypto.publicKeyBase64
            // Backfill public key for installs predating crypto identity.
            // Also repair restored/stale DB state when the stored key no longer matches
            // the active Android Keystore identity on this install.
            if (domain.publicKey != currentPublicKey) {
                val reason = if (domain.publicKey.isEmpty()) "missing" else "mismatched"
                Log.w(tag, "Repairing $reason public key in identity table")
                dao.updatePublicKey(currentPublicKey)
                return domain.copy(publicKey = currentPublicKey).also { cached = it }
            }
            return domain.also { cached = it }
        }
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val suffix = (1..6).map { chars.random() }.joinToString("")
        val identity = Identity(
            deviceId = UUID.randomUUID().toString(),
            displayName = "anon_$suffix",
            createdAt = System.currentTimeMillis(),
            publicKey = crypto.publicKeyBase64
        )
        dao.insert(identity.toEntity())
        cached = identity
        return identity
    }

    suspend fun getIdentity(): Identity? =
        cached ?: dao.getIdentity()?.toDomain()?.also { cached = it }

    suspend fun updateDisplayName(name: String) {
        dao.updateDisplayName(name)
        cached = cached?.copy(displayName = name)
    }

    suspend fun completeOnboarding() {
        dao.markOnboardingComplete()
        cached = cached?.copy(hasCompletedOnboarding = true)
    }
}
