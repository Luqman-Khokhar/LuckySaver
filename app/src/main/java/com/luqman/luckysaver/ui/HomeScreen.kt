package com.luqman.luckysaver.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.luqman.luckysaver.core.MediaItem
import com.luqman.luckysaver.core.MediaKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    input: String,
    state: ResolveState,
    loggedIn: Boolean,
    bubbleOn: Boolean,
    queue: QueueStatus,
    message: String?,
    onInput: (String) -> Unit,
    onResolve: () -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onToggleBubble: (Boolean) -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onHistory: () -> Unit,
    onClearFailed: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message != null) { snackbar.showSnackbar(message); onMessageShown() }
    }
    val ready = state as? ResolveState.Ready

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LuckySaver") },
                actions = {
                    IconButton(onClick = onHistory) { Icon(Icons.Default.History, "Download history") }
                    if (loggedIn) IconButton(onClick = onLogout) { Icon(Icons.Default.Logout, "Log out of Instagram") }
                    else IconButton(onClick = onLogin) { Icon(Icons.Default.Login, "Log in to Instagram") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (ready != null && ready.selected.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onDownload,
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                    text = { Text("Download ${ready.selected.size}") },
                )
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInput,
                        label = { Text("Instagram link") },
                        placeholder = { Text("instagram.com/p/…  /reel/…  /stories/…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onResolve() }),
                        trailingIcon = {
                            IconButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let(onInput)
                            }) { Icon(Icons.Default.ContentPaste, "Paste from clipboard") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = onResolve,
                        enabled = input.isNotBlank() && state !is ResolveState.Loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Fetch media") }

                    BubbleRow(bubbleOn, onToggleBubble)

                    if (!loggedIn) Text(
                        "Not logged in: only public posts and reels work. Log in (use a secondary account) for stories, highlights and private accounts you follow.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    QueueBanner(queue, onClearFailed)
                    StateHeader(state, onResolve, onLogin, onSelectAll)
                }
            }
            if (ready != null) {
                items(ready.items, key = { it.key }) { item ->
                    MediaTile(item, selected = item.key in ready.selected, saved = item.key in ready.alreadySaved) {
                        onToggle(item.key)
                    }
                }
            }
        }
    }
}

/** Toggle for the floating download button that works over other apps. */
@Composable
private fun BubbleRow(bubbleOn: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Floating button", style = MaterialTheme.typography.titleSmall)
            Text(
                "Copy a link in Instagram, then tap the bubble to download without leaving the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = bubbleOn, onCheckedChange = onToggle)
    }
}

@Composable
private fun QueueBanner(queue: QueueStatus, onClearFailed: () -> Unit) {
    if (queue.running + queue.queued > 0) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Downloading ${queue.running}, waiting ${queue.queued}", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (queue.failed.isNotEmpty()) {
        Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${queue.failed.size} download(s) failed: ${queue.failed.first()}",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClearFailed) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun StateHeader(
    state: ResolveState, onRetry: () -> Unit, onLogin: () -> Unit, onSelectAll: (Boolean) -> Unit,
) {
    when (state) {
        ResolveState.Idle -> Text(
            "Tip: in Instagram tap Share, then pick LuckySaver. The link opens here automatically.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        ResolveState.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is ResolveState.Error -> Card(
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Couldn't fetch media", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(state.message, color = MaterialTheme.colorScheme.onErrorContainer)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRetry) { Text("Retry") }
                    if (state.needsLogin) Button(onClick = onLogin) { Text("Log in") }
                }
            }
        }
        is ResolveState.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
            val owner = state.items.firstOrNull()?.owner.orEmpty()
            Text(
                "@$owner · ${state.items.size} item${if (state.items.size == 1) "" else "s"}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            val allSelected = state.selected.size == state.items.size
            TextButton(onClick = { onSelectAll(!allSelected) }) { Text(if (allSelected) "Select none" else "Select all") }
        }
    }
}

@Composable
private fun MediaTile(item: MediaItem, selected: Boolean, saved: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .aspectRatio(if (item.width > 0 && item.height > 0) (item.width.toFloat() / item.height).coerceIn(0.56f, 1.8f) else 1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() }),
    ) {
        AsyncImage(
            model = item.thumbnailUrl,
            contentDescription = "${if (item.kind == MediaKind.VIDEO) "Video" else "Photo"} by @${item.owner}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.kind == MediaKind.VIDEO) Icon(
            Icons.Default.PlayCircle, contentDescription = null,
            tint = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.align(Alignment.Center).size(36.dp),
        )
        Icon(
            if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), RoundedCornerShape(50)),
        )
        if (saved) Text(
            "Saved",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
