package com.meshchat.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.meshchat.app.data.db.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY COALESCE(last_message_at, created_at) DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE peer_device_id = :peerDeviceId LIMIT 1")
    suspend fun getByPeerDeviceId(peerDeviceId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET last_message = :text, last_message_at = :timestamp WHERE id = :conversationId")
    suspend fun updateLastMessage(conversationId: String, text: String, timestamp: Long)

    @Query("SELECT * FROM conversations WHERE peer_display_name = :displayName LIMIT 1")
    suspend fun getByPeerDisplayName(displayName: String): ConversationEntity?

    @Query("UPDATE conversations SET peer_device_id = :deviceId, peer_display_name = :displayName WHERE id = :id")
    suspend fun updatePeerIdentity(id: String, deviceId: String, displayName: String)
}
