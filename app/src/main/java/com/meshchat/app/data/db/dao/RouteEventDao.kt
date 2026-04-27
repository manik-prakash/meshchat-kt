package com.meshchat.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.meshchat.app.data.db.entity.RouteEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RouteEventDao {
    @Query("SELECT * FROM route_events WHERE packet_id = :packetId ORDER BY timestamp ASC")
    fun getEventsForPacket(packetId: String): Flow<List<RouteEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: RouteEventEntity)

    @Query("DELETE FROM route_events WHERE timestamp < :before")
    suspend fun pruneOld(before: Long)
}
