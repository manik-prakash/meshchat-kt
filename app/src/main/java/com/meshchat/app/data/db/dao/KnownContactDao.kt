package com.meshchat.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.meshchat.app.data.db.entity.KnownContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KnownContactDao {
    @Query("SELECT * FROM known_contacts ORDER BY display_name ASC")
    fun getAll(): Flow<List<KnownContactEntity>>

    @Query("SELECT * FROM known_contacts WHERE public_key = :publicKey LIMIT 1")
    suspend fun getByPublicKey(publicKey: String): KnownContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: KnownContactEntity)

    @Query("""
        UPDATE known_contacts
        SET last_resolved_geohash = :geohash, last_resolved_lat = :lat,
            last_resolved_lon = :lon, last_location_at = :at
        WHERE public_key = :publicKey
    """)
    suspend fun updateLocation(publicKey: String, geohash: String?, lat: Double?, lon: Double?, at: Long)

    @Query("DELETE FROM known_contacts WHERE public_key = :publicKey")
    suspend fun delete(publicKey: String)
}
