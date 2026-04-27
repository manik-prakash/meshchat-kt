package com.meshchat.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.meshchat.app.data.db.dao.ConversationDao
import com.meshchat.app.data.db.dao.IdentityDao
import com.meshchat.app.data.db.dao.KnownContactDao
import com.meshchat.app.data.db.dao.MessageDao
import com.meshchat.app.data.db.dao.NeighborDao
import com.meshchat.app.data.db.dao.PeerDao
import com.meshchat.app.data.db.dao.RelayQueueDao
import com.meshchat.app.data.db.dao.RouteEventDao
import com.meshchat.app.data.db.dao.SeenPacketDao
import com.meshchat.app.data.db.entity.ConversationEntity
import com.meshchat.app.data.db.entity.IdentityEntity
import com.meshchat.app.data.db.entity.KnownContactEntity
import com.meshchat.app.data.db.entity.MessageEntity
import com.meshchat.app.data.db.entity.NeighborEntity
import com.meshchat.app.data.db.entity.PeerEntity
import com.meshchat.app.data.db.entity.RelayQueueEntity
import com.meshchat.app.data.db.entity.RouteEventEntity
import com.meshchat.app.data.db.entity.SeenPacketEntity

@Database(
    entities = [
        IdentityEntity::class,
        PeerEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        KnownContactEntity::class,
        NeighborEntity::class,
        SeenPacketEntity::class,
        RelayQueueEntity::class,
        RouteEventEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun identityDao(): IdentityDao
    abstract fun peerDao(): PeerDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun knownContactDao(): KnownContactDao
    abstract fun neighborDao(): NeighborDao
    abstract fun seenPacketDao(): SeenPacketDao
    abstract fun relayQueueDao(): RelayQueueDao
    abstract fun routeEventDao(): RouteEventDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE identity ADD COLUMN has_completed_onboarding INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "UPDATE identity SET has_completed_onboarding = 1 WHERE display_name NOT LIKE 'anon_%'"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE identity ADD COLUMN public_key TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS known_contacts (
                        public_key TEXT NOT NULL PRIMARY KEY,
                        display_name TEXT NOT NULL,
                        last_resolved_geohash TEXT,
                        last_resolved_lat REAL,
                        last_resolved_lon REAL,
                        last_location_at INTEGER
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS neighbors (
                        neighbor_public_key TEXT NOT NULL PRIMARY KEY,
                        display_name TEXT NOT NULL,
                        ble_address TEXT,
                        rssi INTEGER,
                        last_seen INTEGER NOT NULL,
                        relay_capable INTEGER NOT NULL DEFAULT 0,
                        geohash TEXT,
                        lat REAL,
                        lon REAL,
                        trust_score REAL NOT NULL DEFAULT 0.0
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS seen_packets (
                        packet_id TEXT NOT NULL PRIMARY KEY,
                        first_seen_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS relay_queue (
                        packet_id TEXT NOT NULL PRIMARY KEY,
                        destination_public_key TEXT NOT NULL,
                        serialized_payload TEXT NOT NULL,
                        ttl INTEGER NOT NULL,
                        routing_mode TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        retry_count INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        last_tried_at INTEGER,
                        failure_reason TEXT
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS route_events (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        packet_id TEXT NOT NULL,
                        action TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        chosen_next_hop TEXT,
                        reason TEXT
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_route_events_packet_id ON route_events (packet_id)"
                )
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "meshchat.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
    }
}
