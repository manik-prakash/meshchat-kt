package com.meshchat.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.meshchat.app.data.db.entity.PeerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    // Deduped view: one row per display_name, keeping the most recently seen entry
    @Query("""
        SELECT p.* FROM peers p
        INNER JOIN (
            SELECT display_name, MAX(last_seen) AS max_seen
            FROM peers GROUP BY display_name
        ) latest ON p.display_name = latest.display_name AND p.last_seen = latest.max_seen
        ORDER BY p.last_seen DESC
    """)
    fun getAll(): Flow<List<PeerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(peer: PeerEntity)

    @Query("SELECT * FROM peers WHERE device_id = :deviceId LIMIT 1")
    suspend fun findByDeviceId(deviceId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE display_name = :displayName AND device_id != :excludeDeviceId LIMIT 1")
    suspend fun findByDisplayName(displayName: String, excludeDeviceId: String): PeerEntity?

    @Query("UPDATE peers SET last_seen = :lastSeen, rssi = :rssi, ble_id = :bleId WHERE device_id = :deviceId")
    suspend fun update(deviceId: String, lastSeen: Long, rssi: Int?, bleId: String?)
}
