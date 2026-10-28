package app.olenva.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.olenva.R
import app.olenva.container
import app.olenva.net.ModelInfo
import kotlinx.coroutines.launch
import java.util.Locale

/** Full-screen model chooser. [onPick] gets the chosen id; favourites and custom ids come from settings. */
@Composable
fun ModelPicker(
    currentId: String,
    title: String,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val models by c.models.models.collectAsStateWithLifecycle()
    val loading by c.models.loading.collectAsStateWithLifecycle()
    val failed by c.models.failed.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var justAdded by remember { mutableStateOf(false) }
    var othersOpen by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    BackHandler(onBack = onClose)
    // The list keeps its anchor when the custom section appears above it; show the new entry instead.
    LaunchedEffect(settings.customModels) { if (justAdded) { justAdded = false; list.scrollToItem(0) } }

    val q = query.trim().lowercase(Locale.ROOT)
    fun matches(m: ModelInfo) = m.supportsText &&
        (q.isEmpty() || m.id.lowercase(Locale.ROOT).contains(q) || m.name.lowercase(Locale.ROOT).contains(q) || m.provider.lowercase(Locale.ROOT).contains(q))

    val byId = remember(models) { models.associateBy { it.id } }
    // Models the user picked out themselves stay visible however old they are.
    val custom = settings.customModels.map { byId[it] ?: placeholder(it) }
        .filter { q.isEmpty() || it.id.lowercase(Locale.ROOT).contains(q) }
    val favorites = settings.favorites.mapNotNull { byId[it] }.filter(::matches)
    val labs = remember(models, q) { groupByLab(models.filter(::matches), System.currentTimeMillis() / 1000) }
    val (majorLabs, otherLabs) = labs.partition { isMajorLab(it.second.first()) }

    fun toggleFav(id: String) = scope.launch {
        c.settings.update { s -> s.copy(favorites = if (id in s.favorites) s.favorites - id else s.favorites + id) }
    }
    fun removeCustom(id: String) = scope.launch {
        c.settings.update { s -> s.copy(customModels = s.customModels - id) }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (loading) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                IconButton(onClick = { c.models.refresh() }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search_models)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { adding = true },
                    label = { Text(stringResource(R.string.add_model_id)) },
                    leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
                )
            }
            if (failed && models.isEmpty()) {
                Text(stringResource(R.string.models_failed), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
            }
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                fun section(label: String, list: List<ModelInfo>, keyPrefix: String, removable: Boolean = false) {
                    if (list.isEmpty()) return
                    item(key = "h-$keyPrefix") {
                        Text(
                            label,
                            Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(list, key = { "$keyPrefix-${it.id}" }) { m ->
                        ModelRow(
                            m, m.id == currentId, m.id in settings.favorites, m.id == settings.defaultModel,
                            { onPick(m.id) }, { toggleFav(m.id) },
                            onRemove = if (removable) ({ removeCustom(m.id) }) else null,
                        )
                    }
                }
                section(context.getString(R.string.custom_models), custom, "c", removable = true)
                section(context.getString(R.string.favorites), favorites, "f")
                majorLabs.forEach { (lab, list) -> section(lab, list, "l-$lab") }
                if (otherLabs.isNotEmpty()) {
                    // A search shows every match, so the fold only applies to plain browsing.
                    val open = othersOpen || q.isNotEmpty()
                    item(key = "h-others") {
                        Row(
                            Modifier.fillMaxWidth().clickable { othersOpen = !othersOpen }.padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.other_labs, otherLabs.size),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (open) otherLabs.forEach { (lab, list) -> section(lab, list, "l-$lab") }
                }
            }
        }
    }
    if (adding) {
        AddModelDialog(
            onDismiss = { adding = false },
            onAdd = { id ->
                adding = false
                justAdded = true
                scope.launch { c.settings.update { s -> s.copy(customModels = s.customModels.filter { it != id } + id) } }
            },
        )
    }
}

@Composable
private fun AddModelDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val id = text.trim()
    val valid = isValidModelId(id)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_model_id)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("openai/gpt-5.5") },
                supportingText = { Text(stringResource(R.string.model_id_help)) },
                isError = id.isNotEmpty() && !valid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
            )
        },
        confirmButton = { TextButton(onClick = { onAdd(id) }, enabled = valid) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** OpenRouter ids look like `lab/model`, optionally with a `~` alias prefix or a `:variant` suffix. */
fun isValidModelId(id: String) = Regex("~?[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._:~-]*").matches(id)

private const val RECENT_SECONDS = 92L * 24 * 3600
private const val MIN_CONTEXT = 128_000

/** Major labs first, in this order (OpenRouter id prefixes); everyone else follows A→Z. */
private val LAB_ORDER = listOf("google", "anthropic", "openai", "x-ai", "meta", "moonshotai", "deepseek", "z-ai", "qwen", "minimax", "xiaomi")

fun isMajorLab(m: ModelInfo) = lab(m.id) in LAB_ORDER

private fun lab(id: String) = id.removePrefix("~").substringBefore('/').let { if (it == "meta-llama") "meta" else it }

/** Chat-ready models: text out (not image generators), takes images, can reason and call tools, ≥128K context, not a `:batch` variant. */
fun isUsable(m: ModelInfo) = !m.id.endsWith(":batch") && m.supportsImages && m.supports("reasoning") && m.supports("tools") && (m.contextLength ?: 0) >= MIN_CONTEXT &&
    "text" in m.outputModalities && "image" !in m.outputModalities

/** Usable models listed in the last three months, grouped by lab: major labs first, newest model first. */
fun groupByLab(models: List<ModelInfo>, nowSeconds: Long): List<Pair<String, List<ModelInfo>>> =
    models.filter { (it.created ?: nowSeconds) >= nowSeconds - RECENT_SECONDS && isUsable(it) }
        .groupBy { lab(it.id) }
        .entries
        .sortedWith(compareBy({ LAB_ORDER.indexOf(it.key).let { i -> if (i < 0) LAB_ORDER.size else i } }, { it.value.first().provider.lowercase(Locale.ROOT) }))
        .map { (_, list) -> list.first().provider to list.sortedByDescending { it.created ?: 0 } }

private fun placeholder(id: String) = ModelInfo(
    id = id,
    name = id.substringAfter('/'),
    provider = id.removePrefix("~").substringBefore('/'),
    contextLength = null,
    inputModalities = emptyList(),
    promptPrice = null,
    completionPrice = null,
    supportedParameters = emptyList(),
)

@Composable
private fun ModelRow(
    m: ModelInfo,
    selected: Boolean,
    favorite: Boolean,
    isDefault: Boolean,
    onClick: () -> Unit,
    onFav: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selected) { Spacer(Modifier.width(6.dp)); Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = colors.primary) }
            }
            Text(
                listOfNotNull(
                    m.provider,
                    m.contextLength?.let { ctx(it) },
                    price(m),
                    if (isDefault) stringResource(R.string.default_label) else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Capability(Icons.Default.Image, stringResource(R.string.cap_image), m.supportsImages)
                Capability(Icons.Default.PictureAsPdf, "PDF", m.supportsFiles)
                Capability(Icons.Default.Psychology, stringResource(R.string.cap_reasoning), m.supports("reasoning"))
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, stringResource(R.string.remove), tint = colors.onSurfaceVariant) }
        }
        IconButton(onClick = onFav) {
            Icon(
                if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                stringResource(if (favorite) R.string.unfavorite else R.string.favorite),
                tint = if (favorite) colors.primary else colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Capability(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean) {
    if (!on) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
    }
}

private fun ctx(n: Int) = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM ctx", n / 1_000_000.0).replace(".0M", "M")
    n >= 1000 -> "${n / 1000}K ctx"
    else -> "$n ctx"
}

/** Price per million tokens, input / output, exactly as OpenRouter lists it. */
private fun price(m: ModelInfo): String? {
    val p = m.promptPrice ?: return null
    val c = m.completionPrice ?: return null
    if (p == 0.0 && c == 0.0) return "Free"
    fun f(v: Double) = (v * 1_000_000).let { if (it >= 10) String.format(Locale.US, "%.0f", it) else String.format(Locale.US, "%.2f", it).trimEnd('0').trimEnd('.') }
    return "$${f(p)}/$${f(c)} /M"
}
