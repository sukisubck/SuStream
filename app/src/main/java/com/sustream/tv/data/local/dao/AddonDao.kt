package com.sustream.tv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sustream.tv.data.local.entity.AddonConfigurationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AddonDao {

    @Query("SELECT * FROM addon_configuration ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<AddonConfigurationEntity>>

    @Query("SELECT * FROM addon_configuration WHERE authorisedByUser = 1 ORDER BY addedAt ASC")
    fun observeActive(): Flow<List<AddonConfigurationEntity>>

    @Query("SELECT * FROM addon_configuration WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AddonConfigurationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AddonConfigurationEntity)

    @Query("DELETE FROM addon_configuration WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM addon_configuration")
    suspend fun clear()
}