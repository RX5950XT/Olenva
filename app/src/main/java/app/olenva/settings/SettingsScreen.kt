package app.olenva.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.olenva.R
import app.olenva.chat.Transcript
import app.olenva.chat.TranscriptCallbacks
import app.olenva.chat.sizeText
import app.olenva.container
import app.olenva.data.AppSettings
import app.olenva.data.Attachment
import app.olenva.data.MessageEntity
import app.olenva.data.Role
import app.olenva.net.ApiException
import app.olenva.net.ErrorKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import app.olenva.ui.haptic
import app.olenva.ui.hapticToggle
import androidx.compose.ui.platform.LocalView
import app.olenva.ui.tick
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox

@Composable
fun SettingsScreen(onClose: () -> Unit, onPickDefaultModel: () -> Unit, onDataCleared: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val key by c.secrets.apiKey.collectAsStateWithLifecycle()
    val models by c.models.models.collectAsStateWithLifecycle()
    fun update(f: (AppSettings) -> AppSettings) { scope.launch { c.settings.update(f) } }
    BackHandler(onBack = onClose)

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = haptic(onClose)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
                // --- AI ---
                Section(R.string.sec_ai)
                ApiKeyCard(key)
                val defaultName = models.firstOrNull { it.id == s.defaultModel }?.name ?: s.defaultModel
                Row(Modifier.fillMaxWidth().clickable(onClick = haptic(onPickDefaultModel)).padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.default_model), style = MaterialTheme.typography.bodyLarge)
                        Text(defaultName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ToggleRow(R.string.show_usage, R.string.show_usage_desc, s.showUsage) { v -> update { it.copy(showUsage = v) } }
                AdvancedParams(s, models.firstOrNull { it.id == s.defaultModel }, ::update)
                Note(R.string.privacy_note)

                // --- Appearance ---
                Section(R.string.sec_appearance)
                ChoiceRow(R.string.theme, listOf("system" to R.string.theme_system, "light" to R.string.theme_light, "dark" to R.string.theme_dark), s.theme) { v -> update { it.copy(theme = v) } }
                FontSizeRow(s.fontScale) { v -> update { it.copy(fontScale = v) } }
                ChoiceRow(R.string.density, listOf("normal" to R.string.density_normal, "compact" to R.string.density_compact), if (s.compact) "compact" else "normal") { v -> update { it.copy(compact = v == "compact") } }
                val currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags().let {
                    when { it.isEmpty() -> "system"; it.startsWith("en") -> "en"; else -> "zh-TW" }
                }
                ChoiceRow(R.string.language, listOf("system" to R.string.lang_system, "zh-TW" to R.string.lang_zh, "en" to R.string.lang_en), currentLang) { v ->
                    AppCompatDelegate.setApplicationLocales(if (v == "system") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(v))
                }
                ChoiceRow(R.string.startup, listOf("new" to R.string.startup_new, "last" to R.string.startup_last), s.startup) { v -> update { it.copy(startup = v) } }

                // --- Camera ---
                Section(R.string.sec_camera)
                ToggleRow(R.string.cam_auto, R.string.cam_auto_desc, s.autoScan) { v -> update { it.copy(autoScan = v) } }
                ToggleRow(R.string.cam_wide, R.string.cam_wide_desc, s.preferWide) { v -> update { it.copy(preferWide = v) } }
                ToggleRow(R.string.cam_crop, R.string.cam_crop_desc, s.autoCrop) { v -> update { it.copy(autoCrop = v) } }
                ToggleRow(R.string.cam_enhance, R.string.cam_enhance_desc, s.autoEnhance) { v -> update { it.copy(autoEnhance = v) } }
                ToggleRow(R.string.cam_multi, R.string.cam_multi_desc, s.multiPage) { v -> update { it.copy(multiPage = v) } }
                ToggleRow(R.string.cam_haptics, null, s.haptics) { v -> update { it.copy(haptics = v) } }

                // --- Data ---
                Section(R.string.sec_data)
                DataSection(onDataCleared)

                Text(
                    "Olenva " + remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() },
                    Modifier.fillMaxWidth().padding(24.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Section(title: Int) {
    Column {
        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            stringResource(title),
            Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun Note(text: Int) {
    Text(
        stringResource(text),
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ToggleRow(title: Int, desc: Int?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = haptic { onChange(!value) }).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            if (desc != null) Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = value, onCheckedChange = hapticToggle(onChange))
    }
}

@Composable
private fun ChoiceRow(title: Int, options: List<Pair<String, Int>>, value: String, onChange: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (k, label) ->
                SegmentedButton(
                    selected = k == value,
                    onClick = haptic { onChange(k) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    label = { Text(stringResource(label), maxLines = 1) },
                )
            }
        }
    }
}

private val previewMessages = listOf(
    MessageEntity("p1", "preview", Role.USER, "∫ 怎麼算？", createdAt = 0),
    MessageEntity(
        "p2", "preview", Role.ASSISTANT,
        "利用分部積分：\n\n$$\\int_0^1 x e^{x}\\,dx = \\left[x e^{x}\\right]_0^1 - \\int_0^1 e^{x}\\,dx = 1$$\n\n其中 \$u = x\$、\$dv = e^{x}dx\$。",
        createdAt = 1,
    ),
)

@Composable
private fun FontSizeRow(value: Float, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableStateOf(value) }
    val view = LocalView.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.font_size), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("${(local * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = haptic { local = 1f; onChange(1f) }, enabled = local != 1f) { Text(stringResource(R.string.reset)) }
        }
        Slider(
            value = local,
            onValueChange = {
                val v = (it * 20).roundToInt() / 20f
                if (v != local) view.tick()
                local = v
            },
            onValueChangeFinished = { onChange(local) },
            valueRange = 0.8f..1.6f,
            steps = 15,
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(16.dp)),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            app.olenva.ui.OlenvaTheme(
                theme = if (app.olenva.ui.LocalReading.current.dark) "dark" else "light",
                fontScale = local,
                compact = app.olenva.ui.LocalReading.current.compact,
            ) {
                Transcript(
                    messages = previewMessages,
                    scrollSignal = 0,
                    showUsage = false,
                    modelLookup = { null },
                    callbacks = object : TranscriptCallbacks {
                        override fun onAction(action: String, messageId: String) {}
                        override fun onImage(messageId: String, index: Int) {}
                        override fun onMath(tex: String) {}
                        override fun onCopy(text: String) {}
                        override fun onOpenDrawer() {}
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ApiKeyCard(stored: String?) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val masked = stored?.let { if (it.length > 12) it.take(6) + "…" + it.takeLast(4) else "••••" }

    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.api_key), style = MaterialTheme.typography.bodyLarge)
        Text(
            if (stored == null) stringResource(R.string.api_key_missing) else stringResource(R.string.api_key_saved, masked ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.trim(); status = null },
            placeholder = { Text(if (stored == null) "sk-or-v1-…" else stringResource(R.string.api_key_replace)) },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = haptic { visible = !visible }) {
                    Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(if (visible) R.string.hide else R.string.show))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = haptic { c.secrets.save(input); input = ""; status = true to context.getString(R.string.api_key_stored) }, enabled = input.length >= 10) {
                Text(stringResource(R.string.save))
            }
            FilledTonalButton(
                enabled = !testing && (input.isNotBlank() || stored != null),
                onClick = haptic {
                    testing = true
                    scope.launch {
                        status = try {
                            c.openRouter.checkKey(input.ifBlank { stored!! })
                            true to context.getString(R.string.key_ok)
                        } catch (e: ApiException) {
                            false to context.getString(if (e.kind == ErrorKind.INVALID_KEY) R.string.err_invalid_key else if (e.kind == ErrorKind.NETWORK) R.string.err_network else R.string.key_test_failed)
                        }
                        testing = false
                    }
                },
            ) { Text(stringResource(if (testing) R.string.testing else R.string.test_connection)) }
            if (stored != null) OutlinedButton(onClick = haptic { confirmRemove = true }) { Text(stringResource(R.string.remove)) }
        }
        status?.let { (ok, msg) ->
            Text(msg, Modifier.padding(top = 6.dp), color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            stringResource(R.string.get_key_hint),
            Modifier.padding(top = 6.dp).clickable(onClick = haptic { app.olenva.chat.openExternal(context, "https://openrouter.ai/keys") }),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            text = { Text(stringResource(R.string.remove_key_confirm)) },
            confirmButton = { TextButton(onClick = haptic { c.secrets.clear(); confirmRemove = false; status = null }) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = haptic { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun AdvancedParams(s: AppSettings, model: app.olenva.net.ModelInfo?, update: ((AppSettings) -> AppSettings) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun supported(p: String) = model == null || model.supports(p)
    Row(Modifier.fillMaxWidth().clickable(onClick = haptic { open = !open }).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.advanced), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.advanced_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (open) "−" else "+", style = MaterialTheme.typography.titleLarge)
    }
    if (!open) return
    Column(Modifier.padding(horizontal = 20.dp)) {
        OutlinedTextField(
            value = s.systemPrompt,
            onValueChange = { v -> update { it.copy(systemPrompt = v) } },
            label = { Text(stringResource(R.string.system_prompt)) },
            modifier = Modifier.fillMaxWidth().height(120.dp),
        )
        Spacer(Modifier.height(12.dp))
        NullableSlider(R.string.temperature, s.temperature, 0f..2f, 1f, supported("temperature")) { v -> update { it.copy(temperature = v) } }
        NullableSlider(R.string.top_p, s.topP, 0f..1f, 1f, supported("top_p")) { v -> update { it.copy(topP = v) } }
        Text(stringResource(R.string.params_note), Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = haptic {
            update { it.copy(temperature = null, topP = null, systemPrompt = "") }
        }) { Text(stringResource(R.string.reset_params)) }
    }
}

@Composable
private fun NullableSlider(title: Int, value: Float?, range: ClosedFloatingPointRange<Float>, fallback: Float, enabled: Boolean, onChange: (Float?) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value?.let { String.format(Locale.US, "%.2f", it) } ?: stringResource(R.string.model_default_short), style = MaterialTheme.typography.labelLarge)
            Switch(checked = value != null, onCheckedChange = hapticToggle { onChange(if (it) fallback else null) }, enabled = enabled, modifier = Modifier.padding(start = 8.dp))
        }
        if (value != null && enabled) {
            val view = LocalView.current
            // The stored value lags a frame behind the drag, so remember the last step locally to tick once per step.
            var last by remember { mutableStateOf(value) }
            Slider(value = value, onValueChange = {
                val v = (it * 20).roundToInt() / 20f
                if (v != last) { last = v; view.tick(); onChange(v) }
            }, valueRange = range)
        }
        if (!enabled) Text(stringResource(R.string.param_unsupported), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DataSection(onDataCleared: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf(false) }
    var withKey by remember { mutableStateOf(false) }
    val hasKey = c.secrets.apiKey.collectAsStateWithLifecycle().value != null
    fun refreshUsage() = scope.launch { usage = withContext(Dispatchers.IO) { c.attachments.usageBytes() + context.getDatabasePath("olenva.db").length() } }
    remember { refreshUsage() }

    val exportAll = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { c.backup.writeZip(it, null, withKey) } } }.isSuccess
            Toast.makeText(context, if (ok) R.string.export_done else R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val importZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { c.backup.readZip(it) } } }.getOrNull()
            val msg = when {
                r == null -> context.getString(R.string.import_failed)
                r.keyRestored -> context.getString(R.string.import_done_key, r.conversations)
                else -> context.getString(R.string.import_done, r.conversations)
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            refreshUsage()
        }
    }

    Column {
        Text(
            stringResource(R.string.storage_used, usage?.let { sizeText(it) } ?: "…"),
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        ActionRow(R.string.clear_cache, R.string.clear_cache_desc) {
            scope.launch {
                val referenced = mutableSetOf<String>()
                c.dao.allAttachmentJson().forEach { j -> Attachment.decode(j).forEach { a -> referenced += a.path; a.originalPath?.let(referenced::add) } }
                c.dao.allDrafts().forEach { d -> Attachment.decode(d.attachments).forEach { a -> referenced += a.path; a.originalPath?.let(referenced::add) } }
                c.attachments.cleanup(referenced)
                withContext(Dispatchers.IO) { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
                refreshUsage()
                Toast.makeText(context, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
            }
        }
        ActionRow(R.string.export_all, R.string.export_all_desc) {
            withKey = false
            if (hasKey) exportDialog = true else exportAll.launch(backupName())
        }
        ActionRow(R.string.import_backup, null) { importZip.launch(arrayOf("application/zip", "application/octet-stream")) }
        ActionRow(R.string.permissions, R.string.permissions_desc) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
        ActionRow(R.string.clear_all, R.string.clear_all_desc, danger = true) { confirmClear = true }
    }
    if (exportDialog) {
        AlertDialog(
            onDismissRequest = { exportDialog = false },
            title = { Text(stringResource(R.string.export_all)) },
            text = {
                Column {
                    Row(
                        Modifier.fillMaxWidth().toggleable(withKey, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = hapticToggle { withKey = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = withKey, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.include_key), style = MaterialTheme.typography.bodyLarge)
                    }
                    if (withKey) Text(
                        stringResource(R.string.include_key_warning),
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = { TextButton(onClick = haptic { exportDialog = false; exportAll.launch(backupName()) }) { Text(stringResource(R.string.export)) } },
            dismissButton = { TextButton(onClick = haptic { exportDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_all)) },
            text = { Text(stringResource(R.string.clear_all_confirm)) },
            confirmButton = {
                TextButton(onClick = haptic {
                    confirmClear = false
                    scope.launch {
                        c.dao.deleteAllConversations()
                        c.dao.deleteAllDrafts()
                        c.attachments.cleanup(emptySet())
                        c.settings.update { it.copy(lastConversationId = null) }
                        onDataCleared()
                        refreshUsage()
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = haptic { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ActionRow(title: Int, desc: Int?, danger: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = haptic(onClick)).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (desc != null) Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun backupName() = "olenva-backup-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.zip"
