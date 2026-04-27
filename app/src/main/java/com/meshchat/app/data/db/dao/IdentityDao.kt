package com.meshchat.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.meshchat.app.data.db.entity.IdentityEntity

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identity LIMIT 1")
    suspend fun getIdentity(): IdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(identity: IdentityEntity)

    @Query("UPDATE identity SET display_name = :name")
    suspend fun updateDisplayName(name: String)

    @Query("UPDATE identity SET has_completed_onboarding = 1")
    suspend fun markOnboardingComplete()
}
