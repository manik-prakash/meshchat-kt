package com.meshchat.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.meshchat.app.domain.Identity

@Entity(tableName = "identity")
data class IdentityEntity(
    @PrimaryKey
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

fun IdentityEntity.toDomain() = Identity(deviceId, displayName, createdAt)
fun Identity.toEntity() = IdentityEntity(deviceId, displayName, createdAt)
