package com.meshchat.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "relay_queue")
data class RelayQueueEntity(
    @PrimaryKey
    @ColumnInfo(name = "packet_id") val packetId: String,
    @ColumnInfo(name = "destination_public_key") val destinationPublicKey: String,
    @ColumnInfo(name = "serialized_payload") val serializedPayload: String,
    @ColumnInfo(name = "ttl") val ttl: Int,
    @ColumnInfo(name = "routing_mode") val routingMode: String,
    @ColumnInfo(name = "status", defaultValue = "PENDING") val status: String,
    @ColumnInfo(name = "retry_count", defaultValue = "0") val retryCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_tried_at") val lastTriedAt: Long?,
    @ColumnInfo(name = "failure_reason") val failureReason: String?
)
