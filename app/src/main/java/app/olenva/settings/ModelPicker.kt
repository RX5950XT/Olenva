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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.olenva.R
import app.olenva.container
import app.olenva.net.ModelInfo
import kotlinx.coroutines.launch
import java.util.Locale

/** Full-screen model chooser. [onPick] gets the chosen id; favourites and recents come from settings. */
@Composable
fun ModelPicker(
    currentId: String,
    visionOnlyInitially: Boolean,
    title: String,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val models by c.models.models.collectAsStateWithLifecycle()
    val loading by c.models.loading.collectAsStateWithLifecycle()
    val failed by c.models.failed.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var visionOnly by remember { mutableStateOf(visionOnlyInitially) }
    BackHandler(onBack = onClose)

    val filtered = remember(models, query, visionOnly) {
        val q = query.trim().lowercase(Locale.ROOT)
        models.filter { m ->
            (!visionOnly || m.supportsImages) && m.supportsText &&
                (q.isEmpty() || m.id.lowercase(Locale.ROOT).contains(q) || m.name.lowercase(Locale.ROOT).contains(q) || m.provider.lowercase(Locale.ROOT).contains(q))
        }
    }
    val byId = remember(filtered) { filtered.associateBy { it.id } }
    val favorites = settings.favorites.mapNotNull { byId[it] }
    val recents = settings.recents.filter { it !in settings.favorites }.mapNotNull { byId[it] }
    val rest = filtered.sortedWith(compareBy({ it.provider.lowercase(Locale.ROOT) }, { it.name.lowercase(Locale.ROOT) }))

    fun toggleFav(id: String) = scope.launch {
        c.settings.update { s -> s.copy(favorites = if (id in s.favorites) s.favorites - id else s.favorites + id) }
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
                FilterChip(
                    selected = visionOnly,
                    onClick = { visionOnly = !visionOnly },
                    label = { Text(stringResource(R.string.filter_vision)) },
                    leadingIcon = { Icon(Icons.Default.Image, null, Modifier.size(18.dp)) },
                )
            }
            if (failed && models.isEmpty()) {
                Text(stringResource(R.string.models_failed), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                fun section(label: Int, list: List<ModelInfo>, keyPrefix: String) {
                    if (list.isEmpty()) return
                    item(key = "h-$keyPrefix") {
                        Text(
                            stringResource(label),
                            Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(list, key = { "$keyPrefix-${it.id}" }) { m ->
                        ModelRow(m, m.id == currentId, m.id in settings.favorites, m.id == settings.defaultModel, { onPick(m.id) }, { toggleFav(m.id) })
                    }
                }
                section(R.string.favorites, favorites, "f")
                section(R.string.recent, recents, "r")
                section(R.string.all_models, rest, "a")
            }
        }
    }
}

@Composable
private fun ModelRow(m: ModelInfo, selected: Boolean, favorite: Boolean, isDefault: Boolean, onClick: () -> Unit, onFav: () -> Unit) {
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
