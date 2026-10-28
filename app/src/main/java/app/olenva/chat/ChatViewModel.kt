package app.olenva.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.olenva.R
import app.olenva.container
import app.olenva.data.Attachment
import app.olenva.data.AttachmentException
import app.olenva.data.AttachmentKind
import app.olenva.data.ConversationEntity
import app.olenva.data.DraftEntity
import app.olenva.data.MessageEntity
import app.olenva.data.Role
import app.olenva.data.Status
import app.olenva.net.ApiException
import app.olenva.net.ErrorKind
import app.olenva.net.ModelInfo
import app.olenva.net.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

data class Draft(val text: String = "", val attachments: List<Attachment> = emptyList()) {
    val isEmpty get() = text.isBlank() && attachments.isEmpty()
}

sealed interface UiEvent {
    data class Toast(val message: String) : UiEvent
    data object NeedApiKey : UiEvent
    data class ModelLacksImages(val modelName: String) : UiEvent
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val dao = c.dao
    private val res = app.resources

    val settings = c.settings.settings
    val apiKey = c.secrets.apiKey
    val models = c.models.models

    private val _conversationId = MutableStateFlow<String?>(null)
    val conversationId: StateFlow<String?> = _conversationId

    /** Model chosen for the next message when no conversation exists yet. */
    private val _pendingModel = MutableStateFlow<String?>(null)

    val conversation: StateFlow<ConversationEntity?> = _conversationId
        .flatMapLatest { id -> if (id == null) flowOf(null) else dao.observeConversation(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentModelId: StateFlow<String> = combine(conversation, _pendingModel, settings) { conv, pending, s ->
        conv?.modelId ?: pending ?: s.defaultModel
    }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.value.defaultModel)

    private val streaming = MutableStateFlow<MessageEntity?>(null)

    val messages: StateFlow<List<MessageEntity>> = combine(
        _conversationId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.observeMessages(id) },
        streaming,
    ) { stored, live ->
        if (live == null || live.conversationId != _conversationId.value) stored
        else stored.map { if (it.id == live.id) live else it }.let { if (it.none { m -> m.id == live.id }) it + live else it }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totalCost: StateFlow<Double> = _conversationId
        .flatMapLatest { id -> if (id == null) flowOf(0.0) else dao.observeTotalCost(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0.0)

    private val _draft = MutableStateFlow(Draft())
    val draft: StateFlow<Draft> = _draft

    private var job: Job? = null
    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    /** Bumped to tell the transcript to jump to the bottom (sending, switching conversations). */
    private val _scrollSignal = MutableStateFlow(0)
    val scrollSignal: StateFlow<Int> = _scrollSignal

    init {
        viewModelScope.launch {
            val s = settings.value
            val last = s.lastConversationId
            if (s.startup == "last" && last != null && dao.conversation(last) != null) openConversation(last)
            else loadDraft(null)
        }
        // Persist drafts so nothing typed or scanned is lost if the app is killed.
        viewModelScope.launch {
            _draft.drop(1).debounce(400).collect { d -> saveDraft(_conversationId.value, d) }
        }
    }

    private fun draftKey(cid: String?) = cid ?: "new"

    private suspend fun saveDraft(cid: String?, d: Draft) {
        if (d.isEmpty) dao.deleteDraft(draftKey(cid))
        else dao.upsertDraft(DraftEntity(draftKey(cid), d.text, Attachment.encode(d.attachments)))
    }

    private suspend fun loadDraft(cid: String?) {
        val d = dao.draft(draftKey(cid))
        _draft.value = if (d == null) Draft() else Draft(d.text, Attachment.decode(d.attachments).filter { c.attachments.file(it.path).exists() })
    }

    // --- navigation ---------------------------------------------------------

    fun newChat() {
        if (_conversationId.value == null && _draft.value.isEmpty) return
        switchTo(null)
    }

    fun openConversation(id: String) = switchTo(id)

    private fun switchTo(id: String?) {
        viewModelScope.launch {
            saveDraft(_conversationId.value, _draft.value)
            _conversationId.value = id
            _pendingModel.value = null
            loadDraft(id)
            _scrollSignal.value++
            c.settings.update { it.copy(lastConversationId = id) }
        }
    }

    // --- draft --------------------------------------------------------------

    fun setText(t: String) { _draft.value = _draft.value.copy(text = t) }

    fun addAttachments(list: List<Attachment>) {
        if (list.isEmpty()) return
        _draft.value = _draft.value.copy(attachments = _draft.value.attachments + list)
    }

    /** Picked or shared files; failures are reported per file and never touch the rest of the draft. */
    fun importUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val ok = mutableListOf<Attachment>()
            for (u in uris) {
                try {
                    ok += c.attachments.import(u)
                } catch (e: AttachmentException) {
                    val reason = res.getString(
                        when (e.reason) {
                            AttachmentException.Reason.UNSUPPORTED -> R.string.att_unsupported
                            AttachmentException.Reason.TOO_LARGE -> R.string.att_too_large
                            AttachmentException.Reason.CORRUPT -> R.string.att_corrupt
                            AttachmentException.Reason.READ_FAILED -> R.string.att_read_failed
                        }
                    )
                    _events.tryEmit(UiEvent.Toast("${e.name}：$reason"))
                }
            }
            addAttachments(ok)
        }
    }

    fun appendText(t: String) {
        val cur = _draft.value.text
        setText(if (cur.isBlank()) t else "$cur\n$t")
    }

    fun replaceAttachment(a: Attachment) {
        _draft.value = _draft.value.copy(attachments = _draft.value.attachments.map { if (it.id == a.id) a else it })
    }

    fun removeAttachment(a: Attachment) {
        _draft.value = _draft.value.copy(attachments = _draft.value.attachments.filter { it.id != a.id })
        c.attachments.delete(a)
    }

    fun quote(m: MessageEntity) {
        val quoted = m.content.lines().joinToString("\n") { "> $it" }
        val cur = _draft.value.text
        setText((if (cur.isBlank()) "" else "$cur\n\n") + quoted + "\n\n")
    }

    // --- model --------------------------------------------------------------

    fun selectModel(id: String) {
        viewModelScope.launch {
            val conv = conversation.value
            if (conv != null) dao.upsertConversation(conv.copy(modelId = id))
            else _pendingModel.value = id
        }
    }

    fun modelInfo(id: String): ModelInfo? = c.models.find(id)

    // --- sending ------------------------------------------------------------

    fun send() {
        if (_generating.value) return
        val d = _draft.value
        if (d.isEmpty) return
        val key = apiKey.value
        if (key.isNullOrBlank()) { _events.tryEmit(UiEvent.NeedApiKey); return }
        val modelId = currentModelId.value
        val info = modelInfo(modelId)
        if (d.attachments.any { it.kind == AttachmentKind.IMAGE } && info != null && !info.supportsImages) {
            _events.tryEmit(UiEvent.ModelLacksImages(info.name))
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val cid = _conversationId.value ?: createConversation(d, modelId, now)
            // The scan originals were only needed for re-editing before sending.
            val sent = d.attachments.map { a ->
                if (a.originalPath != null && a.originalPath != a.path) c.attachments.file(a.originalPath).delete()
                a.copy(originalPath = null, quad = null)
            }
            dao.upsertMessage(
                MessageEntity(
                    id = UUID.randomUUID().toString(), conversationId = cid, role = Role.USER,
                    content = d.text.trim(), attachments = Attachment.encode(sent), createdAt = now,
                )
            )
            dao.deleteDraft(draftKey(null))
            dao.deleteDraft(cid)
            _draft.value = Draft()
            _scrollSignal.value++
            generate(cid, modelId)
        }
    }

    private suspend fun createConversation(d: Draft, modelId: String, now: Long): String {
        val id = UUID.randomUUID().toString()
        val title = d.text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.take(40)
            ?: res.getString(R.string.untitled_scan)
        dao.upsertConversation(ConversationEntity(id, title, modelId, now, now))
        dao.deleteDraft("new")
        _conversationId.value = id
        _pendingModel.value = null
        c.settings.update { it.copy(lastConversationId = id) }
        return id
    }

    fun stop() { job?.cancel() }

    fun regenerate(messageId: String) {
        if (_generating.value) return
        viewModelScope.launch {
            val m = dao.message(messageId) ?: return@launch
            val cid = m.conversationId
            val all = dao.messages(cid)
            // Regenerating an assistant reply drops it and everything after; on a user message, keep it.
            val cut = if (m.role == Role.ASSISTANT) all.indexOfFirst { it.id == m.id } - 1 else all.indexOfFirst { it.id == m.id }
            if (cut < 0) return@launch
            dao.deleteMessagesAfter(cid, all[cut].createdAt)
            generate(cid, currentModelId.value)
        }
    }

    fun editAndResend(messageId: String, text: String) {
        if (_generating.value) return
        viewModelScope.launch {
            val m = dao.message(messageId) ?: return@launch
            dao.upsertMessage(m.copy(content = text.trim()))
            dao.deleteMessagesAfter(m.conversationId, m.createdAt)
            generate(m.conversationId, currentModelId.value)
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            val m = dao.message(messageId) ?: return@launch
            if (m.status == Status.STREAMING) job?.cancel()
            dao.deleteMessage(m)
            m.attachmentList.forEach { c.attachments.delete(it) }
        }
    }

    private fun generate(cid: String, modelId: String) {
        val key = apiKey.value ?: run { _events.tryEmit(UiEvent.NeedApiKey); return }
        _generating.value = true
        job = viewModelScope.launch {
            val history = dao.messages(cid)
            val start = maxOf(System.currentTimeMillis(), (history.lastOrNull()?.createdAt ?: 0) + 1)
            val base = MessageEntity(
                id = UUID.randomUUID().toString(), conversationId = cid, role = Role.ASSISTANT, content = "",
                modelId = modelId, status = Status.STREAMING, createdAt = start,
            )
            dao.upsertMessage(base)
            streaming.value = base
            val content = StringBuilder()
            val reasoning = StringBuilder()
            var usage: StreamEvent.Usage? = null
            var finish: String? = null
            var result: MessageEntity
            try {
                val body = buildRequest(history, modelId)
                var lastPush = 0L
                var lastSave = System.currentTimeMillis()
                c.openRouter.stream(key, body).collect { ev ->
                    when (ev) {
                        is StreamEvent.Delta -> { content.append(ev.content); reasoning.append(ev.reasoning) }
                        is StreamEvent.Usage -> usage = ev
                        is StreamEvent.Finish -> finish = ev.reason
                    }
                    val t = System.currentTimeMillis()
                    if (t - lastPush > 50) {
                        lastPush = t
                        streaming.value = base.copy(content = content.toString(), reasoning = reasoning.toString())
                    }
                    if (t - lastSave > 2000) {   // checkpoint so a crash keeps most of the answer
                        lastSave = t
                        dao.upsertMessage(base.copy(content = content.toString(), reasoning = reasoning.toString()))
                    }
                }
                result = base.copy(content = content.toString(), reasoning = reasoning.toString(), status = Status.DONE)
                if (content.isBlank()) {
                    result = result.copy(status = Status.ERROR, error = res.getString(R.string.err_empty))
                }
            } catch (e: CancellationException) {
                val stopped = base.copy(content = content.toString(), reasoning = reasoning.toString(), status = Status.STOPPED)
                withContext(NonCancellable) {
                    // The conversation may have been deleted mid-stream; then there is nothing to keep.
                    runCatching { dao.upsertMessage(stopped.withUsage(usage, finish)) }
                    streaming.value = null
                    _generating.value = false
                }
                throw e
            } catch (e: ApiException) {
                Log.w("Chat", "request failed: ${e.kind}")
                result = base.copy(content = content.toString(), reasoning = reasoning.toString(), status = Status.ERROR, error = errorText(e))
            } catch (e: AttachmentException) {
                result = base.copy(status = Status.ERROR, error = res.getString(R.string.err_attachment_read, e.name))
            } catch (e: Exception) {
                Log.e("Chat", "unexpected failure", e)
                result = base.copy(content = content.toString(), status = Status.ERROR, error = res.getString(R.string.err_unknown))
            }
            dao.upsertMessage(result.withUsage(usage, finish))
            streaming.value = null
            _generating.value = false
            touchConversation(cid)
            if (result.status == Status.DONE) maybeTitle(cid, key, modelId)
        }
    }

    private fun MessageEntity.withUsage(u: StreamEvent.Usage?, finish: String?) = copy(
        promptTokens = u?.promptTokens, completionTokens = u?.completionTokens, cost = u?.cost, finishReason = finish,
    )

    private suspend fun touchConversation(cid: String) {
        dao.conversation(cid)?.let { dao.upsertConversation(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    private suspend fun buildRequest(history: List<MessageEntity>, modelId: String): JsonObject {
        val s = settings.value
        val info = modelInfo(modelId)
        fun ok(p: String) = info == null || info.supports(p)
        var hasPdf = false
        val messages = buildJsonArray {
            if (s.systemPrompt.isNotBlank()) add(buildJsonObject { put("role", "system"); put("content", s.systemPrompt) })
            for (m in history) {
                when (m.role) {
                    Role.USER -> {
                        val atts = m.attachmentList
                        if (atts.isEmpty()) {
                            add(buildJsonObject { put("role", "user"); put("content", m.content) })
                            continue
                        }
                        add(buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                if (m.content.isNotBlank()) add(textPart(m.content))
                                for (a in atts) when (a.kind) {
                                    AttachmentKind.IMAGE -> add(buildJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") { put("url", c.attachments.imageDataUrl(a)) }
                                    })
                                    AttachmentKind.PDF -> {
                                        hasPdf = true
                                        add(buildJsonObject {
                                            put("type", "file")
                                            putJsonObject("file") {
                                                put("filename", a.name)
                                                put("file_data", "data:application/pdf;base64," + c.attachments.base64(a))
                                            }
                                        })
                                    }
                                    AttachmentKind.TEXT -> add(textPart("[${a.name}]\n```\n${c.attachments.text(a)}\n```"))
                                }
                            }
                        })
                    }
                    Role.ASSISTANT -> if (m.content.isNotBlank()) {
                        add(buildJsonObject { put("role", "assistant"); put("content", m.content) })
                    }
                }
            }
        }
        return buildJsonObject {
            put("model", modelId)
            put("stream", true)
            put("messages", messages)
            putJsonObject("usage") { put("include", true) }
            if (s.temperature != null && ok("temperature")) put("temperature", s.temperature)
            if (s.topP != null && ok("top_p")) put("top_p", s.topP)
            // The model's full output room: thinking budgets are a share of it, so a small cap would starve the answer.
            if (info?.maxOutput != null && ok("max_tokens")) put("max_tokens", info.maxOutput)
            if (info?.supports("reasoning") == true) putJsonObject("reasoning") { put("effort", s.reasoning) }
            // Models without native PDF input get OpenRouter's free text extraction instead of a paid OCR default.
            if (hasPdf && info?.supportsFiles != true) {
                put("plugins", JsonArray(listOf(buildJsonObject {
                    put("id", "file-parser")
                    putJsonObject("pdf") { put("engine", "pdf-text") }
                })))
            }
        }
    }

    fun setEffort(effort: String) = viewModelScope.launch { c.settings.update { it.copy(reasoning = effort) } }

    private fun textPart(t: String) = buildJsonObject { put("type", "text"); put("text", t) }

    private fun errorText(e: ApiException): String {
        val base = res.getString(
            when (e.kind) {
                ErrorKind.NETWORK -> R.string.err_network
                ErrorKind.TIMEOUT -> R.string.err_timeout
                ErrorKind.INVALID_KEY -> R.string.err_invalid_key
                ErrorKind.NO_CREDITS -> R.string.err_no_credits
                ErrorKind.MODEL_UNAVAILABLE -> R.string.err_model_unavailable
                ErrorKind.RATE_LIMITED -> R.string.err_rate_limited
                ErrorKind.BUSY -> R.string.err_busy
                ErrorKind.MODERATION -> R.string.err_moderation
                ErrorKind.BAD_REQUEST -> R.string.err_bad_request
                ErrorKind.UNKNOWN -> R.string.err_unknown
            }
        )
        val detail = e.detail
        return if (detail.isNullOrBlank() || e.kind == ErrorKind.NETWORK) base else "$base\n($detail)"
    }

    /** One cheap follow-up call names the conversation; failure just keeps the first-line title. */
    private fun maybeTitle(cid: String, key: String, modelId: String) = viewModelScope.launch {
        val conv = dao.conversation(cid) ?: return@launch
        if (conv.titleGenerated) return@launch
        val msgs = dao.messages(cid)
        val user = msgs.firstOrNull { it.role == Role.USER } ?: return@launch
        val answer = msgs.firstOrNull { it.role == Role.ASSISTANT && it.status == Status.DONE } ?: return@launch
        val prompt = "Write a concise title (at most 12 words, same language as the conversation, no quotes, no trailing punctuation) for this conversation.\n\nUser: ${user.content.take(800)}${if (user.attachmentList.isNotEmpty()) " [attached images/files]" else ""}\n\nAssistant: ${answer.content.take(800)}"
        val body = buildJsonObject {
            put("model", modelId)
            put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", prompt) }) })
            put("max_tokens", 1000)
        }
        val title = runCatching { c.openRouter.complete(key, body) }
            .onFailure { Log.i("Chat", "title generation skipped: ${it.javaClass.simpleName}") }
            .getOrNull()?.lineSequence()?.firstOrNull { it.isNotBlank() }
            ?.trim()?.trim('"', '「', '」', '*', '#', ' ')?.take(60)
        val latest = dao.conversation(cid) ?: return@launch
        dao.upsertConversation(latest.copy(title = if (title.isNullOrBlank()) latest.title else title, titleGenerated = true))
    }

    // --- conversation management -------------------------------------------

    fun rename(id: String, title: String) = viewModelScope.launch {
        dao.conversation(id)?.let { dao.upsertConversation(it.copy(title = title.trim().ifBlank { it.title }, titleGenerated = true)) }
    }

    fun deleteConversation(id: String) = viewModelScope.launch {
        if (_conversationId.value == id) { job?.cancel(); _conversationId.value = null; loadDraft(null) }
        val atts = dao.messages(id).flatMap { it.attachmentList } + (dao.draft(id)?.let { Attachment.decode(it.attachments) } ?: emptyList())
        dao.deleteConversation(id)
        dao.deleteDraft(id)
        atts.forEach { c.attachments.delete(it) }
    }

    fun toast(msg: String) { _events.tryEmit(UiEvent.Toast(msg)) }
}
