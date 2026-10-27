package app.olenva.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
private data class BackupFile(val version: Int, val conversations: List<BackupConversation>)

@Serializable
private data class BackupConversation(
    val id: String, val title: String, val modelId: String, val createdAt: Long, val updatedAt: Long,
    val messages: List<BackupMessage>,
)

@Serializable
private data class BackupMessage(
    val id: String, val role: String, val content: String, val reasoning: String = "",
    val attachments: List<Attachment> = emptyList(), val modelId: String = "", val status: String = Status.DONE,
    val error: String? = null, val promptTokens: Int? = null, val completionTokens: Int? = null,
    val cost: Double? = null, val finishReason: String? = null, val createdAt: Long,
)

/** Markdown export and a zip backup (JSON + attachment files). The API key is never part of either. */
class Backup(private val dao: ChatDao, private val store: AttachmentStore) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun markdown(c: ConversationEntity, messages: List<MessageEntity>, userLabel: String, aiLabel: String): String = buildString {
        append("# ").append(c.title).append("\n\n")
        append("_").append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(c.createdAt))).append(" · ").append(c.modelId).append("_\n\n")
        for (m in messages) {
            append("## ").append(if (m.role == Role.USER) userLabel else "$aiLabel (${m.modelId.substringAfter('/')})").append("\n\n")
            m.attachmentList.forEach { a -> append("- 📎 ").append(a.name).append("\n") }
            if (m.attachmentList.isNotEmpty()) append("\n")
            append(m.content.trim()).append("\n\n")
        }
    }

    suspend fun writeZip(out: OutputStream, ids: List<String>?) {
        val convs = dao.allConversations().filter { ids == null || it.id in ids }
        val files = mutableSetOf<String>()
        val data = BackupFile(1, convs.map { c ->
            BackupConversation(c.id, c.title, c.modelId, c.createdAt, c.updatedAt, dao.messages(c.id).map { m ->
                m.attachmentList.forEach { files += it.path }
                BackupMessage(m.id, m.role, m.content, m.reasoning, m.attachmentList, m.modelId, m.status, m.error,
                    m.promptTokens, m.completionTokens, m.cost, m.finishReason, m.createdAt)
            })
        })
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("olenva-backup.json"))
            zip.write(json.encodeToString(BackupFile.serializer(), data).toByteArray())
            zip.closeEntry()
            files.forEach { path ->
                val f = store.file(path)
                if (f.exists()) {
                    zip.putNextEntry(ZipEntry("attachments/$path"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Returns the number of conversations imported; existing ones with the same id are replaced. */
    suspend fun readZip(input: InputStream): Int {
        var data: BackupFile? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when {
                    e.name == "olenva-backup.json" -> data = json.decodeFromString(BackupFile.serializer(), zip.readBytes().decodeToString())
                    e.name.startsWith("attachments/") && !e.isDirectory -> {
                        val name = e.name.removePrefix("attachments/")
                        // Zip-slip guard: only plain file names are accepted.
                        if (name.isNotEmpty() && '/' !in name && '\\' !in name && name != "..") {
                            store.file(name).outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
        }
        val d = data ?: throw IllegalArgumentException("not an Olenva backup")
        for (c in d.conversations) {
            dao.upsertConversation(ConversationEntity(c.id, c.title, c.modelId, c.createdAt, c.updatedAt, titleGenerated = true))
            c.messages.forEach { m ->
                dao.upsertMessage(MessageEntity(m.id, c.id, m.role, m.content, m.reasoning, Attachment.encode(m.attachments),
                    m.modelId, if (m.status == Status.STREAMING) Status.STOPPED else m.status, m.error,
                    m.promptTokens, m.completionTokens, m.cost, m.finishReason, m.createdAt))
            }
        }
        return d.conversations.size
    }
}
