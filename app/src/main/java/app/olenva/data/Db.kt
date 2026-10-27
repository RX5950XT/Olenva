package app.olenva.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query(
        """SELECT DISTINCT c.* FROM conversations c LEFT JOIN messages m ON m.conversationId = c.id
           WHERE c.title LIKE '%' || :q || '%' OR m.content LIKE '%' || :q || '%' ORDER BY c.updatedAt DESC"""
    )
    fun searchConversations(q: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversation(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeConversation(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations")
    suspend fun allConversations(): List<ConversationEntity>

    @Upsert
    suspend fun upsertConversation(c: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()

    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY createdAt")
    fun observeMessages(cid: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY createdAt")
    suspend fun messages(cid: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun message(id: String): MessageEntity?

    @Query("SELECT attachments FROM messages WHERE attachments != '[]'")
    suspend fun allAttachmentJson(): List<String>

    @Query("SELECT COALESCE(SUM(cost), 0) FROM messages WHERE conversationId = :cid")
    fun observeTotalCost(cid: String): Flow<Double>

    @Upsert
    suspend fun upsertMessage(m: MessageEntity)

    @Delete
    suspend fun deleteMessage(m: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :cid AND createdAt > :after")
    suspend fun deleteMessagesAfter(cid: String, after: Long)

    @Query("UPDATE messages SET status = 'stopped' WHERE status = 'streaming'")
    suspend fun markInterrupted()

    @Query("SELECT * FROM drafts WHERE `key` = :key")
    suspend fun draft(key: String): DraftEntity?

    @Query("SELECT * FROM drafts")
    suspend fun allDrafts(): List<DraftEntity>

    @Upsert
    suspend fun upsertDraft(d: DraftEntity)

    @Query("DELETE FROM drafts WHERE `key` = :key")
    suspend fun deleteDraft(key: String)

    @Query("DELETE FROM drafts")
    suspend fun deleteAllDrafts()
}

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, DraftEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): ChatDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "olenva.db").build()
    }
}
