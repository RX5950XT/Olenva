package app.olenva.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.olenva.R
import app.olenva.container
import app.olenva.data.Attachment
import app.olenva.data.AttachmentKind
import app.olenva.data.MessageEntity
import app.olenva.data.Role
import app.olenva.ui.LocalReading
import app.olenva.ui.MonoStyle
import app.olenva.ui.Serif
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.compose.material3.Slider
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.olenva.data.EFFORTS
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.ui.graphics.Color
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView
import app.olenva.ui.haptic
import app.olenva.ui.tick
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider

fun Context.copyToClipboard(text: String, label: String = "Olenva") {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    // Android 13+ shows its own clipboard confirmation.
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
}

fun Context.shareText(text: String) {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
}

/** Things the chat screen asks the app shell to open. */
interface ChatNavigator {
    fun openSettings()
    fun openModelPicker()
    fun openScanner()
    fun openAttachment(a: Attachment, editable: Boolean, messageId: String?)
    fun exportConversation(id: String)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, nav: ChatNavigator) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val modelId by vm.currentModelId.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val apiKey by vm.apiKey.collectAsStateWithLifecycle()
    val scrollSignal by vm.scrollSignal.collectAsStateWithLifecycle()
    val conversationId by vm.conversationId.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var sheetFor by remember { mutableStateOf<MessageEntity?>(null) }
    var editing by remember { mutableStateOf<MessageEntity?>(null) }
    var mathTex by remember { mutableStateOf<String?>(null) }
    var rawFor by remember { mutableStateOf<MessageEntity?>(null) }
    var lacksImages by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is UiEvent.Toast -> Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                UiEvent.NeedApiKey -> {
                    Toast.makeText(context, R.string.need_key_toast, Toast.LENGTH_LONG).show()
                    nav.openSettings()
                }
                is UiEvent.ModelLacksImages -> lacksImages = e.modelName
            }
        }
    }

    val modelName = remember(modelId, models) { vm.modelInfo(modelId)?.name ?: modelId.substringAfter('/') }
    val thinks = remember(modelId, models) { vm.modelInfo(modelId)?.supports("reasoning") != false }

    val callbacks = remember {
        object : TranscriptCallbacks {
            override fun onAction(action: String, messageId: String) {
                val m = vm.messages.value.firstOrNull { it.id == messageId } ?: return
                when (action) {
                    "copy" -> context.copyToClipboard(m.content)
                    "regen", "retry" -> vm.regenerate(m.id)
                    "edit" -> editing = m
                    "more" -> sheetFor = m
                }
            }
            override fun onImage(messageId: String, index: Int) {
                val m = vm.messages.value.firstOrNull { it.id == messageId } ?: return
                m.attachmentList.getOrNull(index)?.let { nav.openAttachment(it, editable = false, messageId = m.id) }
            }
            override fun onMath(tex: String) { mathTex = tex }
            override fun onCopy(text: String) { context.copyToClipboard(text) }
            override fun onOpenDrawer() { scope.launch { drawer.open() } }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        // The drawer's own drag fights the transcript's vertical scroll; when closed it opens from the
        // menu button or a clearly sideways swipe detected in the transcript instead.
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            HistoryDrawer(
                vm = vm,
                currentId = conversationId,
                onOpen = { id -> vm.openConversation(id); scope.launch { drawer.close() } },
                onNew = { vm.newChat(); scope.launch { drawer.close() } },
                onSettings = { scope.launch { drawer.close() }; nav.openSettings() },
                onExport = { id -> nav.exportConversation(id) },
                onSwipeClose = { drawer.close() },
            )
        },
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                TopBar(
                    modelName = modelName,
                    onMenu = { scope.launch { drawer.open() } },
                    onModel = { nav.openModelPicker() },
                    onNew = vm::newChat.takeIf { messages.isNotEmpty() },
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Transcript(
                        messages = messages,
                        scrollSignal = scrollSignal,
                        showUsage = settings.showUsage,
                        modelLookup = vm::modelInfo,
                        callbacks = callbacks,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (messages.isEmpty()) EmptyState(hasKey = !apiKey.isNullOrBlank(), onSetKey = nav::openSettings)
                }
                Composer(
                    draft = draft,
                    generating = generating,
                    effort = settings.reasoning.takeIf { thinks },
                    onEffort = vm::setEffort,
                    onText = vm::setText,
                    onSend = vm::send,
                    onStop = vm::stop,
                    onScan = nav::openScanner,
                    onPicked = vm::importUris,
                    onOpenAttachment = { nav.openAttachment(it, editable = true, messageId = null) },
                    onRemove = vm::removeAttachment,
                )
            }
        }
    }

    sheetFor?.let { m ->
        MessageSheet(
            m = m,
            modelName = vm.modelInfo(m.modelId)?.name ?: m.modelId,
            onDismiss = { sheetFor = null },
            onCopy = { context.copyToClipboard(m.content) },
            onEdit = { editing = m },
            onRegenerate = { vm.regenerate(m.id) },
            onQuote = { vm.quote(m) },
            onShare = { context.shareText(m.content) },
            onRaw = { rawFor = m },
            onDelete = { vm.deleteMessage(m.id) },
            onAttachment = { i -> nav.openAttachment(m.attachmentList[i], editable = false, messageId = m.id) },
        )
    }
    editing?.let { m ->
        EditDialog(m.content, onDismiss = { editing = null }) { text -> editing = null; vm.editAndResend(m.id, text) }
    }
    mathTex?.let { tex -> MathSheet(tex, onDismiss = { mathTex = null }) }
    rawFor?.let { m -> RawSheet(m.content, onDismiss = { rawFor = null }) }
    lacksImages?.let { name ->
        AlertDialog(
            onDismissRequest = { lacksImages = null },
            title = { Text(stringResource(R.string.no_image_title)) },
            text = { Text(stringResource(R.string.no_image_body, name)) },
            confirmButton = {
                Button(onClick = haptic { lacksImages = null; nav.openModelPicker() }) { Text(stringResource(R.string.pick_vision_model)) }
            },
            dismissButton = { TextButton(onClick = haptic { lacksImages = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun TopBar(modelName: String, onMenu: () -> Unit, onModel: () -> Unit, onNew: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = haptic(onMenu)) { Icon(Icons.Default.Menu, stringResource(R.string.history)) }
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = haptic(onModel))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                modelName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Default.ExpandMore, stringResource(R.string.switch_model), Modifier.size(20.dp))
        }
        // A fresh chat has nothing to start over from; the empty slot keeps the model name centred.
        if (onNew != null) IconButton(onClick = haptic(onNew)) { Icon(painterResource(R.drawable.ic_new_chat), stringResource(R.string.new_chat)) }
        else Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun EmptyState(hasKey: Boolean, onSetKey: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Olenva", fontFamily = Serif, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.empty_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasKey) {
            Spacer(Modifier.height(28.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.widthIn(max = 420.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.setup_key_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.setup_key_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = haptic(onSetKey)) { Text(stringResource(R.string.setup_key_action)) }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    draft: Draft,
    generating: Boolean,
    effort: String?,
    onEffort: (String) -> Unit,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onScan: () -> Unit,
    onPicked: (List<android.net.Uri>) -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    onRemove: (Attachment) -> Unit,
) {
    val reading = LocalReading.current
    val colors = MaterialTheme.colorScheme
    var attachMenu by remember { mutableStateOf(false) }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { onPicked(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onPicked(it) }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp, top = 4.dp)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(24.dp)),
    ) {
        Column(Modifier.padding(top = 6.dp)) {
            if (draft.attachments.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    draft.attachments.forEach { a -> AttachmentChip(a, { onOpenAttachment(a) }, { onRemove(a) }) }
                }
            }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val style = MaterialTheme.typography.bodyLarge.copy(
                    color = colors.onSurface,
                    fontSize = 16.sp * reading.fontScale,
                    lineHeight = 24.sp * reading.fontScale,
                )
                if (draft.text.isEmpty()) {
                    Text(stringResource(R.string.input_hint), style = style.copy(color = colors.onSurfaceVariant.copy(alpha = .75f)))
                }
                BasicTextField(
                    value = draft.text,
                    onValueChange = onText,
                    textStyle = style,
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp, max = 180.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                // Scanning is the headline feature: it gets the filled, labeled button.
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.primary)
                        .clickable(onClick = haptic(onScan))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.DocumentScanner, null, tint = colors.onPrimary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.scan), color = colors.onPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                Box {
                    IconButton(onClick = haptic { attachMenu = true }) { Icon(Icons.Default.Add, stringResource(R.string.attach)) }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_photos)) },
                            leadingIcon = { Icon(Icons.Default.Image, null) },
                            onClick = haptic {
                                attachMenu = false
                                pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_files)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                            onClick = haptic {
                                attachMenu = false
                                pickFiles.launch(arrayOf("application/pdf", "text/plain", "text/markdown", "image/*"))
                            },
                        )
                    }
                }
                if (effort != null) EffortButton(effort, onEffort)
                Spacer(Modifier.weight(1f))
                if (generating) {
                    RoundButton(Icons.Default.Stop, stringResource(R.string.stop), enabled = true, onClick = onStop)
                } else {
                    RoundButton(Icons.Default.ArrowUpward, stringResource(R.string.send), enabled = !draft.isEmpty, onClick = onSend)
                }
            }
        }
    }
}

/**
 * Thinking effort: hold the button, slide sideways through low / medium / high, let go to keep it. The scale shows
 * above the button so the finger doesn't cover it.
 */
@Composable
private fun EffortButton(effort: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val colors = MaterialTheme.colorScheme
    var pos by remember(effort) { mutableIntStateOf(EFFORTS.indexOf(effort).coerceAtLeast(0)) }
    var holding by remember { mutableStateOf(false) }
    val names = listOf(R.string.low, R.string.medium, R.string.high).map { stringResource(it) }
    val title = stringResource(R.string.reasoning)
    val step = with(LocalDensity.current) { 44.dp.toPx() }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (holding) colors.surfaceContainerHigh else Color.Transparent)
                .pointerInput(effort) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val held = awaitLongPressOrCancellation(down.id)
                        if (held == null) {
                            if (currentEvent.changes.all { it.changedToUp() }) {
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                Toast.makeText(context, R.string.effort_hint, Toast.LENGTH_SHORT).show()
                            }
                            return@awaitEachGesture
                        }
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        holding = true
                        val start = pos
                        try {
                            while (true) {
                                val c = awaitPointerEvent().changes.firstOrNull { it.id == held.id } ?: break
                                if (!c.pressed) break
                                c.consume()
                                val i = (start + ((c.position.x - held.position.x) / step).roundToInt()).coerceIn(0, 2)
                                if (i != pos) { pos = i; view.tick() }
                            }
                        } finally {
                            holding = false
                            if (EFFORTS[pos] != effort) onChange(EFFORTS[pos])
                        }
                    }
                }
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) {
                    // TalkBack can't hold-and-slide; expose it as an adjustable control instead.
                    contentDescription = title
                    stateDescription = names[pos]
                    progressBarRangeInfo = ProgressBarRangeInfo(pos.toFloat(), 0f..2f, steps = 1)
                    setProgress { v -> v.roundToInt().coerceIn(0, 2).let { pos = it; onChange(EFFORTS[it]) }; true }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Psychology, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(names[pos], style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
        }
        if (holding) {
            val gap = with(LocalDensity.current) { 64.dp.roundToPx() }
            Popup(popupPositionProvider = remember(gap) { AboveAnchor(gap) }) {
                Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerHigh, shadowElevation = 6.dp) {
                    Column(Modifier.width(232.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(title, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                        // Display only: the finger stays on the button underneath.
                        Slider(value = pos.toFloat(), onValueChange = {}, valueRange = 0f..2f, steps = 1)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            names.forEachIndexed { i, n ->
                                Text(
                                    n,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (i == pos) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (i == pos) colors.primary else colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Centres the popup over its anchor, [gap] px above it, kept inside the window. */
private class AboveAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset(
            (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width)),
            anchorBounds.top - popupContentSize.height - gap,
        )
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (enabled) c.onSurface else c.surfaceContainerHighest)
            .clickable(enabled = enabled, onClick = haptic(onClick)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (enabled) c.surface else c.onSurfaceVariant.copy(alpha = .6f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun AttachmentChip(a: Attachment, onOpen: () -> Unit, onRemove: () -> Unit) {
    val store = LocalContext.current.container.attachments
    val c = MaterialTheme.colorScheme
    Box {
        if (a.kind == AttachmentKind.IMAGE) {
            AsyncImage(
                model = store.file(a.path),
                contentDescription = a.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, c.outlineVariant, RoundedCornerShape(12.dp)).clickable(onClick = haptic(onOpen)),
            )
        } else {
            Row(
                Modifier
                    .height(64.dp)
                    .widthIn(max = 200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.surfaceContainer)
                    .clickable(onClick = haptic(onOpen))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (a.kind == AttachmentKind.PDF) Icons.AutoMirrored.Filled.InsertDriveFile else Icons.Default.TextSnippet, null, tint = c.primary)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                    Text(sizeText(a.size), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(c.inverseSurface.copy(alpha = .8f))
                .clickable(onClick = haptic(onRemove)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Close, stringResource(R.string.remove_attachment), tint = c.inverseOnSurface, modifier = Modifier.size(14.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageSheet(
    m: MessageEntity,
    modelName: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onQuote: () -> Unit,
    onShare: () -> Unit,
    onRaw: () -> Unit,
    onDelete: () -> Unit,
    onAttachment: (Int) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    fun act(f: () -> Unit) { scope.launch { state.hide() }.invokeOnCompletion { onDismiss(); f() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(bottom = 16.dp)) {
            if (m.role == Role.ASSISTANT) {
                Text(
                    stringResource(R.string.answered_by, modelName),
                    Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SheetItem(Icons.Default.ContentCopy, stringResource(R.string.copy_all)) { act(onCopy) }
            if (m.role == Role.ASSISTANT) {
                SheetItem(Icons.Default.Refresh, stringResource(R.string.regenerate)) { act(onRegenerate) }
                SheetItem(Icons.Default.FormatQuote, stringResource(R.string.quote_reply)) { act(onQuote) }
                SheetItem(Icons.Default.TextSnippet, stringResource(R.string.view_source)) { act(onRaw) }
                SheetItem(Icons.Default.Share, stringResource(R.string.share)) { act(onShare) }
            } else {
                SheetItem(Icons.Default.Edit, stringResource(R.string.edit_resend)) { act(onEdit) }
                m.attachmentList.forEachIndexed { i, a ->
                    SheetItem(Icons.Default.Image, stringResource(R.string.view_attachment, a.name)) { act { onAttachment(i) } }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SheetItem(Icons.Default.Delete, stringResource(R.string.delete_message), danger = true) { confirmDelete = true }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.delete_message_confirm)) },
            confirmButton = { TextButton(onClick = haptic { confirmDelete = false; act(onDelete) }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = haptic { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun SheetItem(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(label, color = color) },
        leadingContent = { Icon(icon, null, tint = color) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(onClick = haptic(onClick)).padding(horizontal = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MathSheet(tex: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.latex_source), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                SelectionContainer {
                    Text(
                        tex,
                        Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(14.dp),
                        style = MonoStyle.copy(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row {
                Button(onClick = haptic { context.copyToClipboard(tex); onDismiss() }) { Text(stringResource(R.string.copy_latex)) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = haptic { context.copyToClipboard("$$\n$tex\n$$"); onDismiss() }) { Text(stringResource(R.string.copy_with_delims)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RawSheet(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.view_source), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = haptic { context.copyToClipboard(text) }) { Text(stringResource(R.string.copy_all)) }
            }
            Text(stringResource(R.string.select_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text,
                    Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    style = MonoStyle.copy(fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface),
                )
            }
        }
    }
}

@Composable
private fun EditDialog(initial: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_resend)) },
        text = {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp))
        },
        confirmButton = { Button(onClick = haptic { onSubmit(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.send)) } },
        dismissButton = { TextButton(onClick = haptic(onDismiss)) { Text(stringResource(R.string.cancel)) } },
    )
}
