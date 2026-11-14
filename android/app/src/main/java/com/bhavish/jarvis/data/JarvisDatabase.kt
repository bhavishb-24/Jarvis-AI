package com.bhavish.jarvis.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "memories", indices = [Index("category"), Index("updatedAt")])
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val title: String,
    val detail: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "messages", indices = [Index("conversationId"), Index("createdAt")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val text: String,
    val provider: String = "",
    val model: String = "",
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE title LIKE '%' || :query || '%' OR detail LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    fun search(query: String): Flow<List<MemoryEntity>>

    @Insert suspend fun insert(memory: MemoryEntity): Long
    @Update suspend fun update(memory: MemoryEntity)
    @Delete suspend fun delete(memory: MemoryEntity)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY createdAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recentMessages(conversationId: Long, limit: Int): List<MessageEntity>

    @Insert suspend fun insertConversation(conversation: ConversationEntity): Long
    @Query("UPDATE conversations SET title = :title WHERE id = :conversationId")
    suspend fun updateTitle(conversationId: Long, title: String)
    @Insert suspend fun insertMessage(message: MessageEntity): Long
    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessages(conversationId: Long)
    @Delete suspend fun deleteConversation(conversation: ConversationEntity)
}

@Database(entities = [MemoryEntity::class, ConversationEntity::class, MessageEntity::class,
    ComputerEntity::class, AgentTaskEntity::class, AgentTaskStepEntity::class,
    TaskEventEntity::class, ScheduledItemEntity::class], version = 3, exportSchema = true)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun memories(): MemoryDao
    abstract fun conversations(): ConversationDao
    abstract fun computers(): ComputerDao
    abstract fun agentTasks(): AgentTaskDao
    abstract fun schedules(): ScheduleDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `scheduled_items` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, `deviceId` TEXT NOT NULL, `stepsJson` TEXT NOT NULL, `dueAt` INTEGER NOT NULL, `repeatMinutes` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `lastRunAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `computers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `platform` TEXT NOT NULL, `wifiUrl` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `usbPort` INTEGER NOT NULL, `useUsb` INTEGER NOT NULL, `lastStatus` TEXT NOT NULL, `lastSeen` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `agent_tasks` (`id` TEXT NOT NULL, `deviceId` TEXT NOT NULL, `request` TEXT NOT NULL, `summary` TEXT NOT NULL, `status` TEXT NOT NULL, `approved` INTEGER NOT NULL, `progress` INTEGER NOT NULL, `total` INTEGER NOT NULL, `error` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_tasks_deviceId` ON `agent_tasks` (`deviceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_tasks_status` ON `agent_tasks` (`status`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `agent_task_steps` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `taskId` TEXT NOT NULL, `position` INTEGER NOT NULL, `action` TEXT NOT NULL, `argsJson` TEXT NOT NULL, `sensitive` INTEGER NOT NULL, `status` TEXT NOT NULL, `resultJson` TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_task_steps_taskId` ON `agent_task_steps` (`taskId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `task_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `taskId` TEXT NOT NULL, `message` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_events_taskId` ON `task_events` (`taskId`)")
            }
        }
    }
}
