package com.luqman.luckysaver.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.luqman.luckysaver.core.IgLinkParser
import com.luqman.luckysaver.core.MediaItem
import com.luqman.luckysaver.core.MediaKind

/** Paste a link, see what's in it, pick, download. Opens over Saved so the library stays put. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(
    input: String,
    state: ResolveState,
    cooldown: String?,
    onInput: (String) -> Unit,
    onResolve: () -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onLogin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Download from link", style = MaterialTheme.typography.headlineSmall)
            val valid = IgLinkParser.parse(input.trim()) != null
            TextField(
                value = input,
                onValueChange = onInput,
                label = { Text("Instagram link") },
                placeholder = { Text("Post, reel, story or profile link") },
                singleLine = true,
                isError = state is ResolveState.Error,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (valid) onResolve() }),
                trailingIcon = {
                    when {
                        input.isEmpty() -> IconButton(onClick = {
                            clipboard.getText()?.text?.let(onInput)
                        }) { Icon(Icons.Rounded.ContentPaste, "Paste") }
                        valid && state is ResolveState.Idle -> IconButton(onClick = onResolve) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Fetch")
                        }
                        else -> IconButton(onClick = { onInput("") }) { Icon(Icons.Rounded.Cancel, "Clear") }
                    }
                },
                supportingText = when {
                    input.isNotBlank() && !valid -> { { Text("That doesn't look like an Instagram link") } }
                    else -> null
                },
                modifier = Modifier.fillMaxWidth(),
            )

            AnimatedContent(
                targetState = state,
                contentKey = { it::class },
                transitionSpec = { fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(60)) },
                label = "sheet-state",
            ) { s ->
                when (s) {
                    ResolveState.Idle -> Text(
                        "Tip: in Instagram, tap Share and pick LuckySaver. The link opens here by itself.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ResolveState.Loading -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Reading the post", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(4) {
                                Box(
                                    Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                            }
                        }
                    }
                    is ResolveState.Error -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            s.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (cooldown != null) Text(
                            "Instagram limits how often one account can ask. Fetching resumes in $cooldown; downloads already queued carry on.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (s.needsLogin) Button(onClick = onLogin) { Text("Log in") }
                            if (cooldown == null) OutlinedButton(onClick = onResolve) { Text("Try again") }
                        }
                    }
                    is ResolveState.Ready -> ReadyContent(s, onToggle, onSelectAll, onDownload)
                }
            }
        }
    }
}

@Composable
private fun ReadyContent(
    s: ResolveState.Ready,
    onToggle: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onDownload: () -> Unit,
) {
    val first = s.items.firstOrNull()
    val kind = when {
        first?.isStory == true -> "Story"
        s.items.size > 1 -> "Post"
        first?.kind == MediaKind.VIDEO -> "Reel"
        else -> "Photo"
    }
    val pickable = s.items.filter { it.key !in s.alreadySaved }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(url = null, name = first?.owner.orEmpty(), size = 40.dp, modifier = Modifier.size(40.dp))
            Column(Modifier.weight(1f)) {
                Text(first?.owner.orEmpty(), style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append(kind)
                        if (s.items.size > 1) append(" · ${s.items.size} items")
                        if (s.alreadySaved.isNotEmpty()) append(" · ${s.alreadySaved.size} saved before")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (pickable.size > 1) {
                val all = s.selected.size == pickable.size
                TextButton(onClick = { onSelectAll(!all) }) { Text(if (all) "Select none" else "Select all") }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.heightIn(max = 320.dp),
            userScrollEnabled = s.items.size > 8,
        ) {
            items(s.items, key = { it.key }) { item ->
                PickTile(item, selected = item.key in s.selected, saved = item.key in s.alreadySaved) { onToggle(item.key) }
            }
        }
        Button(
            onClick = onDownload,
            enabled = s.selected.isNotEmpty(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(
                when {
                    s.selected.isNotEmpty() -> "Download ${s.selected.size}"
                    pickable.isEmpty() -> "Already saved"
                    else -> "Select something to download"
                }
            )
        }
    }
}

@Composable
private fun PickTile(item: MediaItem, selected: Boolean, saved: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .toggleable(value = selected, enabled = !saved, role = Role.Checkbox, onValueChange = { onToggle() }),
    ) {
        AsyncImage(
            model = item.thumbnailUrl,
            contentDescription = "${if (item.kind == MediaKind.VIDEO) "Video" else "Photo"} from @${item.owner}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (!selected) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (saved) 0.5f else 0.25f)))
        when {
            saved -> Text(
                "Saved",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
            selected -> Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp)) }
            else -> Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
                    .border(2.dp, Color.White, CircleShape),
            )
        }
    }
}
