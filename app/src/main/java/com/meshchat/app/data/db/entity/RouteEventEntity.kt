package com.meshchat.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "route_events",
    indices = [Index(value = ["packet_id"])]
)
data class RouteEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "packet_id") val packetId: String,
    @ColumnInfo(name = "action") val action: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "chosen_next_hop") val chosenNextHop: String?,
    @ColumnInfo(name = "reason") val reason: String?
)
