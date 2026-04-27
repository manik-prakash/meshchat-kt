package com.meshchat.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.meshchat.app.data.db.entity.NeighborEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NeighborDao {
    @Query("SELECT * FROM neighbors ORDER BY last_seen DESC")
    fun getAll(): Flow<List<NeighborEntity>>

    @Query("SELECT * FROM neighbors WHERE last_seen >= :since ORDER BY rssi DESC")
    suspend fun getRecent(since: Long): List<NeighborEntity>

    @Query("SELECT * FROM neighbors WHERE neighbor_public_key = :publicKey LIMIT 1")
    suspend fun getByPublicKey(publicKey: String): NeighborEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(neighbor: NeighborEntity)

    @Query("""
        UPDATE neighbors
        SET rssi = :rssi, last_seen = :lastSeen, ble_address = :bleAddress
        WHERE neighbor_public_key = :publicKey
    """)
    suspend fun updateSighting(publicKey: String, rssi: Int?, lastSeen: Long, bleAddress: String?)

    @Query("DELETE FROM neighbors WHERE last_seen < :before")
    suspend fun pruneStale(before: Long)
}
