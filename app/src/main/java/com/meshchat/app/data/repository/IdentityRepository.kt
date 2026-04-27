package com.meshchat.app.data.repository

import com.meshchat.app.crypto.CryptoManager
import com.meshchat.app.data.db.dao.IdentityDao
import com.meshchat.app.data.db.entity.toEntity
import com.meshchat.app.data.db.entity.toDomain
import com.meshchat.app.domain.Identity
import java.util.UUID

class IdentityRepository(
    private val dao: IdentityDao,
    private val crypto: CryptoManager
) {

    @Volatile private var cached: Identity? = null

    suspend fun ensureIdentity(): Identity {
        cached?.let { return it }
        val existing = dao.getIdentity()
        if (existing != null) {
            val domain = existing.toDomain()
            // Backfill public key for installs predating crypto identity
            if (domain.publicKey.isEmpty()) {
                dao.updatePublicKey(crypto.publicKeyBase64)
                return domain.copy(publicKey = crypto.publicKeyBase64).also { cached = it }
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
