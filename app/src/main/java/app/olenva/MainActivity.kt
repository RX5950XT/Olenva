package app.olenva

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.SideEffect
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.olenva.chat.ChatNavigator
import app.olenva.chat.ChatScreen
import app.olenva.chat.ChatViewModel
import app.olenva.chat.SheetItem
import app.olenva.chat.copyToClipboard
import app.olenva.data.Attachment
import app.olenva.scan.AttachmentEditor
import app.olenva.scan.ScannerScreen
import app.olenva.settings.ModelPicker
import app.olenva.settings.SettingsScreen
import app.olenva.ui.OlenvaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            val s by vm.settings.collectAsStateWithLifecycle()
            OlenvaTheme(theme = s.theme, fontScale = s.fontScale, compact = s.compact) {
                // Status/nav bar icons must follow the in-app theme, not the system one.
                val dark = app.olenva.ui.LocalReading.current.dark
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                Root(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        intent ?: return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        vm.importUris(uris)
        if (uris.isEmpty()) intent.getStringExtra(Intent.EXTRA_TEXT)?.let(vm::appendText)
    }
}

private data class EditorTarget(val attachment: Attachment, val editable: Boolean, val fromScanner: ((Attachment?) -> Unit)? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root(vm: ChatViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var scannerOpen by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf<Boolean?>(null) }   // forDefault
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    var exportId by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val modelId by vm.currentModelId.collectAsStateWithLifecycle()
    val c = context.container

    val saveMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val id = pendingExport ?: return@rememberLauncherForActivityResult
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    val conv = c.dao.conversation(id)!!
                    val md = c.backup.markdown(conv, c.dao.messages(id), context.getString(R.string.you), context.getString(R.string.assistant))
                    context.contentResolver.openOutputStream(uri)!!.use { it.write(md.toByteArray()) }
                }
            }.isSuccess
            Toast.makeText(context, if (ok) R.string.export_done else R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = pendingExport ?: return@rememberLauncherForActivityResult
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { c.backup.writeZip(it, listOf(id)) } }
            }.isSuccess
            Toast.makeText(context, if (ok) R.string.export_done else R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    val nav = remember {
        object : ChatNavigator {
            override fun openSettings() { settingsOpen = true }
            override fun openModelPicker() { picker = false }
            override fun openScanner() { scannerOpen = true }
            override fun openAttachment(a: Attachment, editable: Boolean, messageId: String?) { editor = EditorTarget(a, editable) }
            override fun exportConversation(id: String) { exportId = id }
        }
    }

    ChatScreen(vm, nav)

    if (settingsOpen) {
        SettingsScreen(
            onClose = { settingsOpen = false },
            onPickDefaultModel = { picker = true },
            onDataCleared = { vm.newChat() },
        )
    }
    if (scannerOpen) {
        ScannerScreen(
            settings = settings,
            onDone = { pages -> vm.addAttachments(pages); scannerOpen = false },
            onClose = { scannerOpen = false },
            onEditPage = { a, cb -> editor = EditorTarget(a, true, cb) },
        )
    }
    picker?.let { forDefault ->
        ModelPicker(
            currentId = if (forDefault) settings.defaultModel else modelId,
            title = stringResource(if (forDefault) R.string.default_model else R.string.choose_model),
            onPick = { id ->
                if (forDefault) scope.launch { c.settings.update { it.copy(defaultModel = id) } } else vm.selectModel(id)
                picker = null
            },
            onClose = { picker = null },
        )
    }
    editor?.let { t ->
        val inDraft = t.fromScanner == null && vm.draft.value.attachments.any { it.id == t.attachment.id }
        AttachmentEditor(
            attachment = t.attachment,
            editable = t.editable,
            onChange = { a -> t.fromScanner?.invoke(a) ?: vm.replaceAttachment(a) },
            onDelete = when {
                t.fromScanner != null -> { { t.fromScanner.invoke(null); c.attachments.delete(t.attachment); editor = null } }
                inDraft -> { { vm.removeAttachment(t.attachment); editor = null } }
                else -> null
            },
            onRetake = if (inDraft) { { vm.removeAttachment(t.attachment); editor = null; scannerOpen = true } } else null,
            onClose = { editor = null },
        )
    }
    exportId?.let { id ->
        ModalBottomSheet(onDismissRequest = { exportId = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(stringResource(R.string.export), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                SheetItem(Icons.Default.ContentCopy, stringResource(R.string.copy_all)) {
                    exportId = null
                    scope.launch {
                        val conv = c.dao.conversation(id) ?: return@launch
                        context.copyToClipboard(c.backup.markdown(conv, c.dao.messages(id), context.getString(R.string.you), context.getString(R.string.assistant)))
                    }
                }
                SheetItem(Icons.Default.Description, stringResource(R.string.export_markdown)) {
                    exportId = null; pendingExport = id
                    scope.launch { saveMarkdown.launch(fileName(c.dao.conversation(id)?.title, "md")) }
                }
                SheetItem(Icons.Default.FolderZip, stringResource(R.string.export_backup)) {
                    exportId = null; pendingExport = id
                    scope.launch { saveZip.launch(fileName(c.dao.conversation(id)?.title, "zip")) }
                }
            }
        }
    }
}

private fun fileName(title: String?, ext: String) =
    (title ?: "olenva").replace(Regex("[\\\\/:*?\"<>|\\n]"), " ").trim().take(60).ifBlank { "olenva" } + ".$ext"
