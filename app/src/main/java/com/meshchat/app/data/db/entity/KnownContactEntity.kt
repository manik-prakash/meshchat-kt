package com.meshchat.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "known_contacts")
data class KnownContactEntity(
    @PrimaryKey
    @ColumnInfo(name = "public_key") val publicKey: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "last_resolved_geohash") val lastResolvedGeoHash: String?,
    @ColumnInfo(name = "last_resolved_lat") val lastResolvedLat: Double?,
    @ColumnInfo(name = "last_resolved_lon") val lastResolvedLon: Double?,
    @ColumnInfo(name = "last_location_at") val lastLocationAt: Long?
)
