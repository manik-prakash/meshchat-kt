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
}
