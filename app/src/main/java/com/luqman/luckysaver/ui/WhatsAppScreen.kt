package com.luqman.luckysaver.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.luqman.luckysaver.core.WaApp
import com.luqman.luckysaver.data.DownloadEntity
import com.luqman.luckysaver.whatsapp.WaStatus

/**
 * Statuses WhatsApp has loaded on this phone, one sub-tab per installed WhatsApp. Re-reads the
 * folder every time the screen comes back, since watching statuses in WhatsApp adds files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppScreen(
    installed: List<WaApp>?,
    selected: WaApp?,
    folder: WaFolderState?,
    saved: Set<String>,
    saving: Set<String>,
    gridState: LazyGridState,
    snackbar: SnackbarHostState,
    initialUri: (WaApp) -> android.net.Uri,
    onSelect: (WaApp) -> Unit,
    onRefresh: () -> Unit,
    onFolderPicked: (WaApp, android.net.Uri?) -> Unit,
    onOpenWhatsApp: (WaApp) -> Unit,
    onSave: (WaStatus) -> Unit,
    onSaveAll: (WaApp) -> Unit,
    onOpen: (DownloadEntity, List<DownloadEntity>) -> Unit,
) {
    val context = LocalContext.current
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    // Which app asked for the picker; survives the trip to the system UI.
    var picking by rememberSaveable { mutableStateOf<WaApp?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        picking?.let { onFolderPicked(it, tree) }
        picking = null
    }
    val grant = { wa: WaApp -> picking = wa; picker.launch(initialUri(wa)) }

    LifecycleResumeEffect(Unit) {
        onRefresh()
        onPauseOrDispose { }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("WhatsApp") },
                    actions = {
                        IconButton(onClick = onRefresh, enabled = !installed.isNullOrEmpty()) {
                            Icon(Icons.Rounded.Refresh, "Look for new statuses")
                        }
                    },
                    scrollBehavior = scroll,
                )
                if (installed != null && installed.size > 1 && selected != null) PrimaryTabRow(
                    selectedTabIndex = installed.indexOf(selected).coerceAtLeast(0),
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    installed.forEach { wa ->
                        Tab(
                            selected = wa == selected,
                            onClick = { onSelect(wa) },
                            text = {
                                Text(
                                    if (wa == WaApp.BUSINESS) "Business" else wa.label,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            when {
                installed == null -> skeleton(aspect = 9f / 16f, count = 9)
                installed.isEmpty() || selected == null -> fullWidth("not-installed") {
                    EmptyState(
                        title = "WhatsApp isn't installed",
                        body = "Install WhatsApp or WhatsApp Business. Statuses you watch there can then be saved here.",
                    )
                }
                folder == null || folder == WaFolderState.Loading -> skeleton(aspect = 9f / 16f, count = 9)
                folder == WaFolderState.NoAccess -> fullWidth("no-access") {
                    EmptyState(
                        title = "Allow access to ${selected.label} statuses",
                        body = "${selected.label} keeps the statuses you've watched in a hidden folder. Android opens it next: " +
                            "tap \"Use this folder\", then \"Allow\". LuckySaver only reads that one folder.",
                        action = "Choose folder",
                        onAction = { grant(selected) },
                    )
                }
                folder is WaFolderState.Failed -> fullWidth("failed") {
                    EmptyState(
                        title = "Couldn't read statuses",
                        body = folder.message,
                        action = "Try again",
                        onAction = onRefresh,
                    )
                }
                folder is WaFolderState.Ready && folder.statuses.isEmpty() -> fullWidth("empty") {
                    EmptyState(
                        title = "No statuses loaded",
                        body = "Open ${selected.label}, go to Updates and watch a status. Each one you load shows up here " +
                            "until ${selected.label} clears it, usually after 24 hours.",
                        action = "Open ${selected.label}",
                        onAction = { onOpenWhatsApp(selected) },
                    )
                }
                folder is WaFolderState.Ready -> {
                    val unsaved = folder.statuses.count { it.key !in saved }
                    fullWidth("summary") {
                        Summary(
                            total = folder.statuses.size,
                            unsaved = unsaved,
                            busy = saving.isNotEmpty(),
                            label = selected.label,
                            onSaveAll = { onSaveAll(selected) },
                        )
                    }
                    val previews = folder.previews
                    items(folder.statuses.indices.toList(), key = { folder.statuses[it].key }, contentType = { "status" }) { i ->
                        val status = folder.statuses[i]
                        val preview = previews[i]
                        MediaTile(
                            entity = preview,
                            isNew = false,
                            aspect = 9f / 16f,
                            onClick = { onOpen(preview, previews) },
                            modifier = Modifier.animateItem(),
                            topStart = {
                                SaveBadge(
                                    saved = status.key in saved,
                                    saving = status.key in saving,
                                    onSave = { onSave(status) },
                                )
                            },
                            bottomStartText = When.stamp(context, status.modified),
                        )
                    }
                }
            }
        }
    }
}

/** Count on the left, one action on the right. The count says what "Save all" will do. */
@Composable
private fun Summary(total: Int, unsaved: Int, busy: Boolean, label: String, onSaveAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "$total ${if (total == 1) "status" else "statuses"} · ${total - unsaved} saved",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Loaded in $label, newest first",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(onClick = onSaveAll, enabled = unsaved > 0 && !busy) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text(if (unsaved == 0) "All saved" else "Save all ($unsaved)")
        }
    }
}

/**
 * Top-left of each tile: a save button, a spinner while copying, or a "Saved" mark. The mark has
 * a word as well as a tick, so it doesn't depend on colour.
 */
@Composable
private fun SaveBadge(saved: Boolean, saving: Boolean, onSave: () -> Unit) {
    when {
        saved -> Row(
            Modifier
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(12.dp))
            Text("Saved", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        saving -> Box(
            Modifier.size(32.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
        else -> Box(
            Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onSave)
                .semantics { contentDescription = "Save this status" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}
