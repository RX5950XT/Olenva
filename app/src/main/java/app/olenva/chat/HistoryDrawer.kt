package app.olenva.chat

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.olenva.R
import app.olenva.container
import app.olenva.data.ConversationEntity

@Composable
fun HistoryDrawer(
    vm: ChatViewModel,
    currentId: String?,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onExport: (String) -> Unit,
) {
    val dao = LocalContext.current.container.dao
    var query by remember { mutableStateOf("") }
    val flow = remember(query) { if (query.isBlank()) dao.observeConversations() else dao.searchConversations(query.trim()) }
    val list by (flow).collectAsStateWithLifecycle(initialValue = emptyList())
    val totalCost by vm.totalCost.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<ConversationEntity?>(null) }
    var deleting by remember { mutableStateOf<ConversationEntity?>(null) }

    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(320.dp)) {
        Column(Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.history), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onNew) { Icon(Icons.Default.EditNote, stringResource(R.string.new_chat)) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search_chats)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (list.isEmpty()) {
                Text(
                    stringResource(if (query.isBlank()) R.string.no_chats else R.string.no_results),
                    Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.id }) { c ->
                    ConversationRow(
                        c = c,
                        selected = c.id == currentId,
                        cost = if (c.id == currentId && totalCost > 0) money(totalCost) else null,
                        onClick = { onOpen(c.id) },
                        onRename = { renaming = c },
                        onExport = { onExport(c.id) },
                        onDelete = { deleting = c },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onSettings).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Settings, null)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    renaming?.let { c ->
        var title by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(title, { title = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(c.id, title); renaming = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_chat_title)) },
            text = { Text(stringResource(R.string.delete_chat_body, c.title)) },
            confirmButton = { TextButton(onClick = { vm.deleteConversation(c.id); deleting = null }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ConversationRow(
    c: ConversationEntity,
    selected: Boolean,
    cost: String?,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 1.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                val rel = DateUtils.getRelativeTimeSpanString(c.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                Text(
                    listOfNotNull(rel, cost).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more), Modifier.size(20.dp)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.export)) }, leadingIcon = { Icon(Icons.Default.FileDownload, null) }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = colors.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = colors.error) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

