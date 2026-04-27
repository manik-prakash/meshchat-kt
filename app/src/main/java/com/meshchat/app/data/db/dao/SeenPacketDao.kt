package com.meshchat.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.meshchat.app.data.db.entity.SeenPacketEntity

@Dao
interface SeenPacketDao {
    @Query("SELECT COUNT(*) FROM seen_packets WHERE packet_id = :packetId AND expires_at > :now")
    suspend fun isSeen(packetId: String, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markSeen(packet: SeenPacketEntity)

    @Query("DELETE FROM seen_packets WHERE expires_at <= :now")
    suspend fun pruneExpired(now: Long)
}
