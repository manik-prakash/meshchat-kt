package com.meshchat.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.meshchat.app.data.db.dao.ConversationDao
import com.meshchat.app.data.db.dao.IdentityDao
import com.meshchat.app.data.db.dao.MessageDao
import com.meshchat.app.data.db.dao.PeerDao
import com.meshchat.app.data.db.entity.ConversationEntity
import com.meshchat.app.data.db.entity.IdentityEntity
import com.meshchat.app.data.db.entity.MessageEntity
import com.meshchat.app.data.db.entity.PeerEntity

@Database(
    entities = [IdentityEntity::class, PeerEntity::class, ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun identityDao(): IdentityDao
    abstract fun peerDao(): PeerDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "meshchat.db")
                .build()
                .also { instance = it }
        }
    }
}
