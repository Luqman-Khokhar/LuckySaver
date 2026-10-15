package com.luqman.luckysaver.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.luqman.luckysaver.data.DownloadEntity
import com.luqman.luckysaver.youtube.YtExtractor

/**
 * Everything saved from YouTube, on three shelves: videos as 16:9 cards, Shorts as a tall grid like
 * Instagram's, and audio as a list, since a song is found by its title rather than its picture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeScreen(
    shelves: Map<YtShelf, List<DaySection>>?,
    shelf: YtShelf,
    newSince: Long,
    queue: QueueStatus,
    gridState: LazyGridState,
    snackbar: SnackbarHostState,
    onShelf: (YtShelf) -> Unit,
    onPaste: () -> Unit,
    onOpen: (DownloadEntity, List<DownloadEntity>) -> Unit,
    onDelete: (DownloadEntity) -> Unit,
    onClearFailed: () -> Unit,
) {
    val context = LocalContext.current
    val accents = LocalAccents.current
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val fabExpanded by remember { derivedStateOf { !gridState.lastScrolledForward || !gridState.canScrollBackward } }
    var confirmDelete by remember { mutableStateOf<DownloadEntity?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(title = { Text("YouTube") }, scrollBehavior = scroll)
                PrimaryTabRow(selectedTabIndex = shelf.ordinal, containerColor = MaterialTheme.colorScheme.surface) {
                    YtShelf.entries.forEach { s ->
                        val count = shelves?.get(s)?.sumOf { it.items.size } ?: 0
                        Tab(
                            selected = s == shelf,
                            onClick = { onShelf(s) },
                            text = {
                                Text(
                                    if (count > 0) "${s.label} $count" else s.label,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onPaste,
                expanded = fabExpanded,
                containerColor = accents.youtube,
                contentColor = accents.onYoutube,
                icon = { Icon(Icons.Rounded.ContentPaste, contentDescription = null) },
                text = { Text("Paste link") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val sections = shelves?.get(shelf)
        LazyVerticalGrid(
            columns = when (shelf) {
                YtShelf.VIDEOS -> GridCells.Adaptive(160.dp)
                YtShelf.SHORTS -> GridCells.Fixed(3)
                YtShelf.MUSIC -> GridCells.Fixed(1)
            },
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = if (shelf == YtShelf.VIDEOS) 8.dp else 0.dp,
                end = if (shelf == YtShelf.VIDEOS) 8.dp else 0.dp,
                top = padding.calculateTopPadding(),
                bottom = 96.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(if (shelf == YtShelf.VIDEOS) 8.dp else 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            fullWidth("notices") {
                Column {
                    AnimatedVisibility(queue.active > 0, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        DownloadingRow(queue)
                    }
                    AnimatedVisibility(queue.failed.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Notice(
                            "${queue.failed.size} download${if (queue.failed.size == 1) "" else "s"} failed: ${queue.failed.firstOrNull().orEmpty()}",
                            action = "Dismiss", onAction = onClearFailed, error = true,
                        )
                    }
                }
            }
            when {
                sections == null -> skeleton(
                    aspect = when (shelf) {
                        YtShelf.VIDEOS -> 16f / 9f
                        YtShelf.SHORTS -> 9f / 16f
                        YtShelf.MUSIC -> 5f
                    },
                    count = if (shelf == YtShelf.MUSIC) 6 else 12,
                )
                sections.isEmpty() -> fullWidth("empty-$shelf") {
                    EmptyState(
                        title = when (shelf) {
                            YtShelf.VIDEOS -> "No videos saved yet"
                            YtShelf.SHORTS -> "No Shorts saved yet"
                            YtShelf.MUSIC -> "No audio saved yet"
                        },
                        body = when (shelf) {
                            YtShelf.MUSIC -> "Share a YouTube Music song to LuckySaver, or paste any YouTube link and pick Audio only."
                            else -> "In YouTube, tap Share and pick LuckySaver. Or copy a link and tap Paste link."
                        },
                        action = "Paste link",
                        onAction = onPaste,
                    )
                }
                else -> {
                    val flat = sections.flatMap { it.items }
                    sections.forEach { section ->
                        fullWidth("h-$shelf-${section.day}") {
                            DayHeader(When.dayLabel(section.day), section.items.size.toString(), Modifier.animateItem())
                        }
                        items(section.items, key = { it.key }, contentType = { shelf }) { e ->
                            val isNew = e.savedAt > newSince
                            when (shelf) {
                                YtShelf.VIDEOS -> VideoCard(e, isNew, Modifier.animateItem()) { onOpen(e, flat) }
                                YtShelf.SHORTS -> MediaTile(
                                    entity = e,
                                    isNew = isNew,
                                    aspect = 9f / 16f,
                                    onClick = { onOpen(e, flat) },
                                    modifier = Modifier.animateItem(),
                                )
                                YtShelf.MUSIC -> AudioRow(
                                    e, isNew,
                                    onPlay = { play(context, e) },
                                    onShare = { shareAudio(context, e) },
                                    onDelete = { confirmDelete = e },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this file?") },
            text = { Text("“${target.title ?: target.fileName}” is removed from your phone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = null; onDelete(target) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/** A 16:9 tile with the title under it, as YouTube lists videos. */
@Composable
private fun VideoCard(e: DownloadEntity, isNew: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.padding(bottom = 10.dp)) {
        MediaTile(
            entity = e,
            isNew = isNew,
            aspect = 16f / 9f,
            onClick = onClick,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)),
        )
        Text(
            e.title ?: e.fileName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            e.owner,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AudioRow(
    e: DownloadEntity,
    isNew: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val title = e.title ?: e.fileName
    var menu by remember { mutableStateOf(false) }
    val duration by produceState<Long?>(null, e.uri) { value = Thumbs.duration(context.contentResolver, e.uri) }
    val fresh = LocalAccents.current.fresh
    ListItem(
        leadingContent = {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                // YouTube's thumbnail, by video id: covers the note when online, the note shows when not.
                e.shortcode?.let {
                    AsyncImage(
                        model = YtExtractor.thumbnailUrl(it),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (isNew) Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(8.dp).background(fresh, CircleShape),
                )
            }
        },
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(e.owner.takeIf { it.isNotBlank() }, duration?.let(When::duration)).joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More for $title") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.clickable(role = Role.Button, onClickLabel = "Play", onClick = onPlay),
    )
}

/** Audio plays in the phone's music app, which keeps going in the background. */
private fun play(context: Context, e: DownloadEntity) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(e.uri), "audio/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this phone can play it", Toast.LENGTH_SHORT).show()
    }
}

private fun shareAudio(context: Context, e: DownloadEntity) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("audio/*")
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(e.uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}
