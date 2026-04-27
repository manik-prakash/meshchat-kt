package com.meshchat.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "seen_packets")
data class SeenPacketEntity(
    @PrimaryKey
    @ColumnInfo(name = "packet_id") val packetId: String,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long
)
