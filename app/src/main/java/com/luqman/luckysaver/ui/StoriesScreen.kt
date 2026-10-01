package com.luqman.luckysaver.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luqman.luckysaver.data.DownloadEntity
import com.luqman.luckysaver.data.WatchedAccount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoriesScreen(
    accounts: List<WatchedAccount>?,
    stories: List<AccountSection>?,
    status: WatchStatus,
    newSince: Long,
    batteryRestricted: Boolean,
    gridState: LazyGridState,
    snackbar: SnackbarHostState,
    onAdd: () -> Unit,
    onCheckNow: () -> Unit,
    onTurnOn: () -> Unit,
    onLogin: () -> Unit,
    onFixBattery: () -> Unit,
    onPause: (WatchedAccount, Boolean) -> Unit,
    onRemove: (WatchedAccount) -> Unit,
    onOpen: (DownloadEntity, List<DownloadEntity>) -> Unit,
) {
    val context = LocalContext.current
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var onlyOwner by rememberSaveable { mutableStateOf<String?>(null) }
    val byName = remember(accounts) { accounts.orEmpty().associateBy { it.username.lowercase() } }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Stories") },
                actions = {
                    val canCheck = status is WatchStatus.Running || status is WatchStatus.Off
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = {
                            PlainTooltip {
                                Text(
                                    when (status) {
                                        is WatchStatus.CoolingDown -> "Instagram asked for a pause. Try in ${status.remaining}."
                                        WatchStatus.LoggedOut -> "Log in to check stories"
                                        WatchStatus.Checking -> "Checking now"
                                        else -> "Check for new stories now"
                                    }
                                )
                            }
                        },
                        state = rememberTooltipState(),
                    ) {
                        if (status == WatchStatus.Checking) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        } else IconButton(onClick = onCheckNow, enabled = canCheck && !accounts.isNullOrEmpty()) {
                            Icon(Icons.Rounded.Refresh, "Check for new stories now")
                        }
                    }
                    IconButton(onClick = onAdd) { Icon(Icons.Rounded.PersonAdd, "Watch an account") }
                },
                scrollBehavior = scroll,
            )
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
            fullWidth("status") { StatusLine(status, batteryRestricted, onTurnOn, onLogin, onFixBattery) }

            when {
                accounts == null -> skeleton(aspect = 9f / 16f, count = 6)
                accounts.isEmpty() -> fullWidth("no-accounts") {
                    EmptyState(
                        title = "Watch an account",
                        body = "Pick up to 10 accounts. Their new stories are saved to your phone before they disappear, " +
                            "and each one shows here with the time it was saved.",
                        action = "Add account",
                        onAction = onAdd,
                    )
                }
                else -> {
                    fullWidth("tray") {
                        AccountTray(
                            accounts = accounts,
                            stories = stories.orEmpty(),
                            newSince = newSince,
                            selected = onlyOwner,
                            onSelect = { name -> onlyOwner = if (onlyOwner == name) null else name },
                            onAdd = onAdd,
                            onPause = onPause,
                            onRemove = onRemove,
                        )
                    }
                    val sections = stories?.filter { onlyOwner == null || it.owner.equals(onlyOwner, ignoreCase = true) }
                    when {
                        sections == null -> skeleton(aspect = 9f / 16f, count = 6)
                        sections.isEmpty() -> fullWidth("no-stories") {
                            EmptyState(
                                title = if (onlyOwner != null) "Nothing from @$onlyOwner yet" else "No stories saved yet",
                                body = "New stories are saved on the next check. Tap refresh to check now.",
                            )
                        }
                        else -> {
                            // The viewer plays in the order shown: account by account, newest first.
                            val playlist = sections.flatMap { it.items }
                            sections.forEach { section ->
                                val account = byName[section.owner.lowercase()]
                                fullWidth("h-${section.owner.lowercase()}") {
                                    AccountHeader(
                                        section = section,
                                        avatarUrl = account?.avatarUrl,
                                        hasNew = section.latest > newSince,
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                                items(section.items, key = { it.key }, contentType = { "story" }) { e ->
                                    MediaTile(
                                        entity = e,
                                        isNew = e.savedAt > newSince,
                                        aspect = 9f / 16f,
                                        onClick = { onOpen(e, playlist) },
                                        modifier = Modifier.animateItem(),
                                        bottomStartText = When.stamp(context, e.savedAt),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Starts one account's stories: who, how many, and when the newest arrived. */
@Composable
private fun AccountHeader(section: AccountSection, avatarUrl: String?, hasNew: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .border(2.dp, if (hasNew) LocalAccents.current.fresh else Color.Transparent, CircleShape)
                .padding(3.dp),
        ) { Avatar(avatarUrl, section.owner, 34.dp, Modifier.fillMaxSize()) }
        Column(Modifier.weight(1f)) {
            Text(section.owner, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${section.items.size} ${if (section.items.size == 1) "story" else "stories"} · latest " +
                    When.savedPhrase(context, section.latest).removePrefix("Saved ").replaceFirstChar { it.lowercase() },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One line under the title. The dot's color and the words always agree, so neither is needed alone. */
@Composable
private fun StatusLine(
    status: WatchStatus,
    batteryRestricted: Boolean,
    onTurnOn: () -> Unit,
    onLogin: () -> Unit,
    onFixBattery: () -> Unit,
) {
    val context = LocalContext.current
    val ok = MaterialTheme.colorScheme.primary
    val waiting = LocalAccents.current.fresh
    val bad = MaterialTheme.colorScheme.error
    val idle = MaterialTheme.colorScheme.outline
    val (text, color, action) = when {
        status == WatchStatus.LoggedOut -> Triple("Logged out of Instagram, so nothing is being checked", bad, "Log in" to onLogin)
        status == WatchStatus.Off -> Triple("Automatic checks are off", idle, "Turn on" to onTurnOn)
        status is WatchStatus.CoolingDown -> Triple("Paused for ${status.remaining}: Instagram asked to slow down", waiting, null)
        status == WatchStatus.Checking -> Triple("Checking for new stories", ok, null)
        batteryRestricted -> Triple("Battery saver may stop the checks", bad, "Allow" to onFixBattery)
        status is WatchStatus.Running -> Triple(
            buildString {
                append("Every ${status.everyHours} hours")
                if (status.nextAt > 0) append(" · next check ${When.time(context, status.nextAt)}")
                else if (status.lastCheckedAt > 0) append(" · last checked ${When.time(context, status.lastCheckedAt)}")
            },
            ok, null,
        )
        else -> Triple("", idle, null)
    }
    val dot by animateColorAsState(color, label = "status-dot")
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(6.dp).background(dot, CircleShape))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        if (action != null) TextButton(onClick = action.second) { Text(action.first) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountTray(
    accounts: List<WatchedAccount>,
    stories: List<AccountSection>,
    newSince: Long,
    selected: String?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onPause: (WatchedAccount, Boolean) -> Unit,
    onRemove: (WatchedAccount) -> Unit,
) {
    val fresh = LocalAccents.current.fresh
    val withNew = remember(stories, newSince) {
        stories.flatMap { it.items }.filter { it.savedAt > newSince }.map { it.owner.lowercase() }.toSet()
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(accounts, key = { it.userId }) { a ->
            var menu by remember { mutableStateOf(false) }
            val isNew = a.username.lowercase() in withNew
            val isSelected = selected.equals(a.username, ignoreCase = true)
            val ring = when {
                isSelected -> MaterialTheme.colorScheme.primary
                isNew -> fresh
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Box {
                Column(
                    Modifier
                        .width(64.dp)
                        .combinedClickable(
                            role = Role.Button,
                            onClickLabel = if (isSelected) "Show all accounts" else "Show only @${a.username}",
                            onLongClickLabel = "Options",
                            onClick = { onSelect(a.username) },
                            onLongClick = { menu = true },
                        )
                        .semantics {
                            contentDescription = "@${a.username}" + (if (isNew) ", new stories" else "") + (if (!a.enabled) ", paused" else "")
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(60.dp).border(2.5.dp, ring, CircleShape).padding(5.dp)) {
                        Avatar(a.avatarUrl, a.username, 50.dp, Modifier.fillMaxSize())
                        if (!a.enabled) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f), CircleShape))
                    }
                    Text(
                        a.username,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (a.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (a.enabled) "Pause watching" else "Resume watching") },
                        onClick = { menu = false; onPause(a, !a.enabled) },
                    )
                    DropdownMenuItem(
                        text = { Text("Stop watching") },
                        onClick = { menu = false; onRemove(a) },
                    )
                }
            }
        }
        item(key = "add") {
            Column(
                Modifier.width(64.dp).combinedClickable(role = Role.Button, onClick = onAdd),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.size(60.dp).border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                Text("Add", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
