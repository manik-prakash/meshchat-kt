package com.meshchat.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.meshchat.app.data.db.entity.RelayQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RelayQueueDao {
    @Query("SELECT * FROM relay_queue WHERE status = 'PENDING' ORDER BY created_at ASC")
    fun getPending(): Flow<List<RelayQueueEntity>>

    @Query("SELECT * FROM relay_queue WHERE status = 'PENDING' AND destination_public_key = :destKey ORDER BY created_at ASC")
    suspend fun getPendingFor(destKey: String): List<RelayQueueEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(packet: RelayQueueEntity)

    @Query("UPDATE relay_queue SET status = 'IN_FLIGHT', last_tried_at = :now, retry_count = retry_count + 1 WHERE packet_id = :packetId")
    suspend fun markInFlight(packetId: String, now: Long)

    @Query("UPDATE relay_queue SET status = 'DELIVERED' WHERE packet_id = :packetId")
    suspend fun markDelivered(packetId: String)

    @Query("UPDATE relay_queue SET status = 'FAILED', failure_reason = :reason WHERE packet_id = :packetId")
    suspend fun markFailed(packetId: String, reason: String?)

    @Query("UPDATE relay_queue SET status = 'PENDING' WHERE status = 'IN_FLIGHT'")
    suspend fun resetInFlight()

    @Query("DELETE FROM relay_queue WHERE status IN ('DELIVERED', 'FAILED') AND last_tried_at < :before")
    suspend fun pruneSettled(before: Long)
}
