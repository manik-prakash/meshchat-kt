package com.meshchat.app.data.repository

import com.meshchat.app.data.db.dao.IdentityDao
import com.meshchat.app.data.db.entity.toEntity
import com.meshchat.app.data.db.entity.toDomain
import com.meshchat.app.domain.Identity
import java.util.UUID

class IdentityRepository(private val dao: IdentityDao) {

    @Volatile private var cached: Identity? = null

    suspend fun ensureIdentity(): Identity {
        cached?.let { return it }
        val existing = dao.getIdentity()
        if (existing != null) {
            return existing.toDomain().also { cached = it }
        }
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val suffix = (1..6).map { chars.random() }.joinToString("")
        val identity = Identity(
            deviceId = UUID.randomUUID().toString(),
            displayName = "anon_$suffix",
            createdAt = System.currentTimeMillis()
        )
        dao.insert(identity.toEntity())
        cached = identity
        return identity
    }

    suspend fun updateDisplayName(name: String) {
        dao.updateDisplayName(name)
        cached = cached?.copy(displayName = name)
    }
}
