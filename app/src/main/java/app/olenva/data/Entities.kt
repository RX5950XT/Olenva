package app.olenva.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val modelId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val titleGenerated: Boolean = false,
)

@Entity(
    tableName = "messages",
    indices = [Index("conversationId")],
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val reasoning: String = "",
    val attachments: String = "[]",
    val modelId: String = "",
    val status: String = Status.DONE,
    val error: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val cost: Double? = null,
    val finishReason: String? = null,
    val createdAt: Long,
) {
    val attachmentList: List<Attachment> get() = Attachment.decode(attachments)
}

@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val key: String,
    val text: String,
    val attachments: String,
)

object Role {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

object Status {
    const val DONE = "done"
    const val STREAMING = "streaming"
    const val STOPPED = "stopped"
    const val ERROR = "error"
}

@Serializable
enum class AttachmentKind { IMAGE, PDF, TEXT }

/** Paths are relative to [AttachmentStore.dir]. Scan fields let an image be re-cropped / re-processed before sending. */
@Serializable
data class Attachment(
    val id: String,
    val kind: AttachmentKind,
    val name: String,
    val mime: String,
    val path: String,
    val size: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val originalPath: String? = null,
    val quad: List<Float>? = null,
    val mode: String? = null,
    val rotation: Int = 0,
    /** True once the text direction was checked (or the user rotated by hand). */
    val oriented: Boolean = false,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun encode(list: List<Attachment>): String = json.encodeToString(list)
        fun decode(s: String): List<Attachment> =
            runCatching { json.decodeFromString<List<Attachment>>(s) }.getOrDefault(emptyList())
    }
}
