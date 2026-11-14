package com.bhavish.jarvis.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "scheduled_items")
data class ScheduledItemEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val deviceId: String = "",
    val stepsJson: String = "[]",
    val dueAt: Long,
    val repeatMinutes: Int = 0,
    val enabled: Boolean = true,
    val lastRunAt: Long = 0
)

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM scheduled_items ORDER BY dueAt ASC")
    fun observeAll(): Flow<List<ScheduledItemEntity>>
    @Query("SELECT * FROM scheduled_items WHERE id = :id LIMIT 1")
    suspend fun get(id: String): ScheduledItemEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(item: ScheduledItemEntity)
    @Query("DELETE FROM scheduled_items WHERE id = :id")
    suspend fun delete(id: String)
}
