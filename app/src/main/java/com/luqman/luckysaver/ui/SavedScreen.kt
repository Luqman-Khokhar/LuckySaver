package com.luqman.luckysaver.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.luqman.luckysaver.data.DownloadEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(
    library: LibraryState?,
    filter: LibraryFilter,
    query: String,
    newSince: Long,
    queue: QueueStatus,
    loggedIn: Boolean,
    sessionExpired: Boolean,
    gridState: LazyGridState,
    snackbar: SnackbarHostState,
    onFilter: (LibraryFilter) -> Unit,
    onQuery: (String) -> Unit,
    onOpen: (DownloadEntity, List<DownloadEntity>) -> Unit,
    onPaste: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onClearFailed: () -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    // The FAB shortens to its icon while you scroll down through the grid, and grows back on the way up.
    val fabExpanded by remember { derivedStateOf { !gridState.lastScrolledForward || !gridState.canScrollBackward } }

    BackHandler(enabled = searching) { searching = false; onQuery("") }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                if (searching) SearchBar(query, onQuery) { searching = false; onQuery("") }
                else TopAppBar(
                    title = { Text("Saved") },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Search by account") }
                        AccountMenu(loggedIn, onLogin, onLogout)
                    },
                    scrollBehavior = scroll,
                )
                PrimaryTabRow(
                    selectedTabIndex = filter.ordinal,
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    LibraryFilter.entries.forEach { f ->
                        Tab(
                            selected = f == filter,
                            onClick = { onFilter(f) },
                            text = { Text(f.label, style = MaterialTheme.typography.titleSmall) },
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
                icon = { Icon(Icons.Rounded.ContentPaste, contentDescription = null) },
                text = { Text("Paste link") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            fullWidth("notices") {
                Column {
                    AnimatedVisibility(sessionExpired, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Notice(
                            "Instagram signed this phone out. Stories and private posts need a new login.",
                            action = "Log in", onAction = onLogin, error = true,
                        )
                    }
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
                library == null -> skeleton(aspect = 1f)
                library.total == 0 -> fullWidth("empty") {
                    EmptyState(
                        title = "Nothing saved yet",
                        body = "In Instagram, tap Share and pick LuckySaver. Or copy a link and tap Paste link.",
                    )
                }
                library.shown == 0 -> fullWidth("no-match") {
                    EmptyState(
                        title = if (query.isNotBlank()) "No saves from “$query”" else "No ${filter.label.lowercase()} yet",
                        body = if (query.isNotBlank()) "Search matches account names." else "Saved ${filter.label.lowercase()} will show up here.",
                    )
                }
                else -> {
                    val flat = library.sections.flatMap { it.items }
                    library.sections.forEach { section ->
                        fullWidth("h-${section.day}") {
                            DayHeader(When.dayLabel(section.day), section.items.size.toString(), Modifier.animateItem())
                        }
                        items(section.items, key = { it.key }, contentType = { "tile" }) { e ->
                            MediaTile(
                                entity = e,
                                isNew = e.savedAt > newSince,
                                aspect = 1f,
                                onClick = { onOpen(e, flat) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search") } },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search accounts") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Clear search") }
        },
    )
}

@Composable
private fun AccountMenu(loggedIn: Boolean, onLogin: () -> Unit, onLogout: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Rounded.AccountCircle,
                contentDescription = if (loggedIn) "Instagram account" else "Log in to Instagram",
                tint = if (loggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                if (loggedIn) "Logged in to Instagram" else "Not logged in",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (loggedIn) DropdownMenuItem(text = { Text("Log out") }, onClick = { open = false; onLogout() })
            else DropdownMenuItem(text = { Text("Log in") }, onClick = { open = false; onLogin() })
        }
    }
}

@Composable
private fun DownloadingRow(queue: QueueStatus) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (queue.active == 1) "Downloading 1 item" else "Downloading ${queue.active} items",
            style = MaterialTheme.typography.bodyMedium,
        )
        val p = queue.progress
        if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/** One line of text with an optional action, flush on the background: no card. */
@Composable
fun Notice(text: String, action: String?, onAction: () -> Unit, error: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun EmptyState(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Rounded.PhotoLibrary, contentDescription = null,
            tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(40.dp).padding(bottom = 8.dp),
        )
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) TextButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(action) }
    }
}

/**
 * A saved file in a grid. Square corners: it's content, not a control. Video length sits
 * bottom-right on a soft shadow, the gold dot top-right means saved since you last looked.
 */
@Composable
fun MediaTile(
    entity: DownloadEntity,
    isNew: Boolean,
    aspect: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    topStart: (@Composable () -> Unit)? = null,
    bottomStartText: String? = null,
) {
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val duration by produceState<Long?>(null, entity.uri) {
        if (entity.isVideo) value = Thumbs.duration(context.contentResolver, entity.uri)
    }
    val fresh = LocalAccents.current.fresh
    Box(
        modifier
            .aspectRatio(aspect)
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(),
                role = Role.Button,
                onClickLabel = "Open",
                onClick = onClick,
            ),
    ) {
        MediaThumb(
            uri = entity.uri,
            contentDescription = "${if (entity.isVideo) "Video" else "Photo"} from @${entity.owner}",
            modifier = Modifier.fillMaxSize().sharedMedia(entity.key),
        )
        val overlay = TextStyle(color = Color.White, shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 8f))
        val d = duration
        if (entity.isVideo && d != null) Text(
            When.duration(d),
            style = MaterialTheme.typography.labelSmall.merge(overlay),
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
        )
        if (bottomStartText != null) Text(
            bottomStartText,
            style = MaterialTheme.typography.labelSmall.merge(overlay),
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
        )
        if (topStart != null) Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { topStart() }
        if (isNew) Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(8.dp)
                .background(fresh, CircleShape),
        )
    }
}

fun LazyGridScope.fullWidth(key: String, content: @Composable LazyGridItemScope.() -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = "full") { content() }
}

/** Grey tiles in the exact grid the real ones will fill, so nothing moves when they arrive. */
fun LazyGridScope.skeleton(aspect: Float, count: Int = 12) {
    fullWidth("skeleton-head") { Box(Modifier.fillMaxWidth().padding(top = 48.dp)) }
    items(count, key = { "skeleton-$it" }, contentType = { "skeleton" }) {
        val shimmer = rememberInfiniteTransition(label = "skeleton")
        val a by shimmer.animateFloat(
            0.55f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "skeleton-alpha",
        )
        Box(
            Modifier
                .aspectRatio(aspect)
                .alpha(a)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}
