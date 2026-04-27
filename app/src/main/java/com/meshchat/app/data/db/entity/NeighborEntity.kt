package com.meshchat.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "neighbors")
data class NeighborEntity(
    @PrimaryKey
    @ColumnInfo(name = "neighbor_public_key") val neighborPublicKey: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "ble_address") val bleAddress: String?,
    @ColumnInfo(name = "rssi") val rssi: Int?,
    @ColumnInfo(name = "last_seen") val lastSeen: Long,
    @ColumnInfo(name = "relay_capable", defaultValue = "0") val relayCapable: Boolean,
    @ColumnInfo(name = "geohash") val geohash: String?,
    @ColumnInfo(name = "lat") val lat: Double?,
    @ColumnInfo(name = "lon") val lon: Double?,
    @ColumnInfo(name = "trust_score", defaultValue = "0.0") val trustScore: Double
)
