package com.bhavish.jarvis.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "computers")
data class ComputerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val platform: String,
    val wifiUrl: String,
    val fingerprint: String,
    val usbPort: Int = 8765,
    val useUsb: Boolean = false,
    val lastStatus: String = "unknown",
    val lastSeen: Long = 0
)

@Entity(tableName = "agent_tasks", indices = [Index("deviceId"), Index("status")])
data class AgentTaskEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val request: String,
    val summary: String,
    val status: String = "queued",
    val approved: Boolean = false,
    val progress: Int = 0,
    val total: Int = 0,
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "agent_task_steps", indices = [Index("taskId")])
data class AgentTaskStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val position: Int,
    val action: String,
    val argsJson: String,
    val sensitive: Boolean,
    val status: String = "queued",
    val resultJson: String = ""
)

@Entity(tableName = "task_events", indices = [Index("taskId")])
data class TaskEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val message: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ComputerDao {
    @Query("SELECT * FROM computers ORDER BY name ASC")
    fun observeAll(): Flow<List<ComputerEntity>>
    @Query("SELECT * FROM computers WHERE id = :id LIMIT 1")
    suspend fun get(id: String): ComputerEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(device: ComputerEntity)
    @Query("DELETE FROM computers WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface AgentTaskDao {
    @Query("SELECT * FROM agent_tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AgentTaskEntity>>
    @Query("SELECT * FROM agent_tasks WHERE id = :id LIMIT 1")
    suspend fun get(id: String): AgentTaskEntity?
    @Query("SELECT * FROM agent_task_steps WHERE taskId = :taskId ORDER BY position ASC")
    suspend fun steps(taskId: String): List<AgentTaskStepEntity>
    @Query("SELECT * FROM agent_task_steps WHERE taskId = :taskId ORDER BY position ASC")
    fun observeSteps(taskId: String): Flow<List<AgentTaskStepEntity>>
    @Query("SELECT * FROM task_events WHERE taskId = :taskId ORDER BY createdAt ASC, id ASC")
    fun observeEvents(taskId: String): Flow<List<TaskEventEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(task: AgentTaskEntity)
    @Insert suspend fun addSteps(steps: List<AgentTaskStepEntity>)
    @Insert suspend fun addEvent(event: TaskEventEntity)
    @Query("UPDATE agent_tasks SET status = :status, progress = :progress, total = :total, error = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun update(id: String, status: String, progress: Int, total: Int, error: String,
                       updatedAt: Long = System.currentTimeMillis())
    @Query("UPDATE agent_task_steps SET status = :status, resultJson = :resultJson WHERE taskId = :taskId AND position = :position")
    suspend fun updateStep(taskId: String, position: Int, status: String, resultJson: String)
}
