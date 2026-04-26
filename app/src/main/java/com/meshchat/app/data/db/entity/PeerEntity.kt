package com.meshchat.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.meshchat.app.domain.Peer

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "last_seen") val lastSeen: Long,
    @ColumnInfo(name = "rssi") val rssi: Int?,
    @ColumnInfo(name = "ble_id") val bleId: String?
)

fun PeerEntity.toDomain() = Peer(deviceId, displayName, lastSeen, rssi, bleId)
fun Peer.toEntity() = PeerEntity(deviceId, displayName, lastSeen, rssi, bleId)
