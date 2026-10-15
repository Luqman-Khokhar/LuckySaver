package com.luqman.luckysaver.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab as TabItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luqman.luckysaver.core.YtLinkParser
import com.luqman.luckysaver.whatsapp.ForegroundApp

/** One main tab per app, so each app's saves live together. */
private enum class Tab(val label: String, val selected: ImageVector, val idle: ImageVector) {
    INSTAGRAM("Instagram", Icons.Rounded.PhotoCamera, Icons.Outlined.PhotoCamera),
    WHATSAPP("WhatsApp", Icons.Rounded.Forum, Icons.Outlined.Forum),
    YOUTUBE("YouTube", Icons.Rounded.SmartDisplay, Icons.Outlined.SmartDisplay),
    SETTINGS("Settings", Icons.Rounded.Settings, Icons.Outlined.Settings),
}

/** Instagram's sub-tabs: what was saved by hand, and what the watchlist saved. */
private enum class IgSub(val label: String) { SAVED("Saved"), STORIES("Stories") }

/** What the viewer shows. Keys, not rows, so a deleted item drops out while it's open. */
private data class ViewerTarget(val keys: List<String>, val startKey: String, val stories: Boolean, val whatsapp: Boolean = false)

@OptIn(ExperimentalSharedTransitionApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    main: MainViewModel,
    library: LibraryViewModel,
    stories: StoriesViewModel,
    whatsapp: WhatsAppViewModel,
    youtube: YouTubeViewModel,
    batteryRestricted: Boolean,
    onLogin: () -> Unit,
    onBubble: (Boolean) -> Unit,
    onFixBattery: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.INSTAGRAM) }
    var igSub by rememberSaveable { mutableStateOf(IgSub.SAVED) }
    var ytShelf by rememberSaveable { mutableStateOf(YtShelf.VIDEOS) }
    var viewer by remember { mutableStateOf<ViewerTarget?>(null) }
    var addOpen by rememberSaveable { mutableStateOf(false) }
    // Hoisted so scroll position survives switching tabs and opening the viewer.
    val savedGrid = rememberLazyGridState()
    val storiesGrid = rememberLazyGridState()
    val whatsappGrid = rememberLazyGridState()
    val ytGrids = YtShelf.entries.associateWith { rememberLazyGridState() }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current

    val settings by main.settings.collectAsStateWithLifecycle()
    val loggedIn by main.loggedIn.collectAsStateWithLifecycle()
    val sessionExpired by main.sessionExpired.collectAsStateWithLifecycle()
    val queue by main.queue.collectAsStateWithLifecycle()
    val message by main.message.collectAsStateWithLifecycle()
    val undoable by main.undoable.collectAsStateWithLifecycle()
    val clipboardLink by main.clipboardLink.collectAsStateWithLifecycle()
    val sheetOpen by main.sheetOpen.collectAsStateWithLifecycle()
    val input by main.input.collectAsStateWithLifecycle()
    val resolveState by main.state.collectAsStateWithLifecycle()
    val cooldown by main.cooldown.collectAsStateWithLifecycle()
    val bubbleOn by main.bubbleOn.collectAsStateWithLifecycle()

    val libraryState by library.library.collectAsStateWithLifecycle()
    val filter by library.filter.collectAsStateWithLifecycle()
    val query by library.query.collectAsStateWithLifecycle()
    val storySections by library.stories.collectAsStateWithLifecycle()
    val newSince by library.newSince.collectAsStateWithLifecycle()
    val deleteRequest by library.deleteRequest.collectAsStateWithLifecycle()
    val libraryMessage by library.message.collectAsStateWithLifecycle()

    val accounts by stories.accounts.collectAsStateWithLifecycle()
    val status by stories.status.collectAsStateWithLifecycle()
    val adding by stories.adding.collectAsStateWithLifecycle()
    val addError by stories.addError.collectAsStateWithLifecycle()
    val added by stories.added.collectAsStateWithLifecycle()

    val waInstalled by whatsapp.installed.collectAsStateWithLifecycle()
    val waSelected by whatsapp.selected.collectAsStateWithLifecycle()
    val waFolders by whatsapp.folders.collectAsStateWithLifecycle()
    val waSaved by whatsapp.saved.collectAsStateWithLifecycle()
    val waSaving by whatsapp.saving.collectAsStateWithLifecycle()
    val waMessage by whatsapp.message.collectAsStateWithLifecycle()
    val waOpenRequest by whatsapp.openRequest.collectAsStateWithLifecycle()
    val usageAccess by whatsapp.usageAccess.collectAsStateWithLifecycle()

    val ytShelves by library.youtube.collectAsStateWithLifecycle()
    val ytInput by youtube.input.collectAsStateWithLifecycle()
    val ytState by youtube.state.collectAsStateWithLifecycle()
    val ytSheetOpen by youtube.sheetOpen.collectAsStateWithLifecycle()
    val ytMessage by youtube.message.collectAsStateWithLifecycle()
    val ytHandoff by main.ytHandoff.collectAsStateWithLifecycle()

    // ---- one-off events become snackbars ----
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        main.consumeMessage()
        if (text.startsWith("Downloading")) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        val undo = if (undoable.isNotEmpty()) main::undoLastBatch else null
        val result = snackbar.showSnackbar(text, actionLabel = if (undo != null) "Undo" else null, duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) undo?.invoke()
    }
    LaunchedEffect(libraryMessage) {
        val text = libraryMessage ?: return@LaunchedEffect
        library.consumeMessage()
        snackbar.showSnackbar(text, duration = SnackbarDuration.Short)
    }
    LaunchedEffect(waMessage) {
        val text = waMessage ?: return@LaunchedEffect
        whatsapp.consumeMessage()
        snackbar.showSnackbar(text, duration = SnackbarDuration.Short)
    }
    LaunchedEffect(ytMessage) {
        val text = ytMessage ?: return@LaunchedEffect
        youtube.consumeMessage()
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        snackbar.showSnackbar(text, duration = SnackbarDuration.Short)
    }
    // A YouTube link from a share, the clipboard or the Instagram sheet opens on the YouTube tab.
    LaunchedEffect(ytHandoff) {
        val link = ytHandoff ?: return@LaunchedEffect
        main.consumeYtHandoff()
        viewer = null
        tab = Tab.YOUTUBE
        youtube.open(link)
    }
    LaunchedEffect(waOpenRequest) {
        if (!waOpenRequest) return@LaunchedEffect
        whatsapp.consumeOpenRequest()
        viewer = null
        tab = Tab.WHATSAPP
    }
    LaunchedEffect(added) {
        val name = added ?: return@LaunchedEffect
        stories.consumeAdded()
        addOpen = false
        snackbar.showSnackbar("Watching @$name", duration = SnackbarDuration.Short)
    }
    LaunchedEffect(clipboardLink, sheetOpen, ytSheetOpen) {
        val link = clipboardLink
        if (link == null || sheetOpen || ytSheetOpen) return@LaunchedEffect
        val app = if (YtLinkParser.parse(link) != null) "YouTube" else "Instagram"
        val result = snackbar.showSnackbar(
            "$app link copied", actionLabel = "Download", withDismissAction = true, duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) main.useClipboardLink() else main.dismissClipboard()
    }
    LaunchedEffect(tab, igSub) {
        when (tab) {
            Tab.INSTAGRAM -> library.markSeen(if (igSub == IgSub.SAVED) SeenArea.SAVED else SeenArea.STORIES)
            Tab.YOUTUBE -> library.markSeen(SeenArea.YOUTUBE)
            Tab.WHATSAPP, Tab.SETTINGS -> Unit
        }
    }

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        library.onDeleteConfirmed(it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(deleteRequest) {
        deleteRequest?.let { deleteLauncher.launch(IntentSenderRequest.Builder(it.sender).build()) }
    }

    // Back walks home: another app's tab, then Instagram's first sub-tab.
    BackHandler(enabled = viewer == null && (tab != Tab.INSTAGRAM || igSub != IgSub.SAVED)) {
        if (tab != Tab.INSTAGRAM) tab = Tab.INSTAGRAM else igSub = IgSub.SAVED
    }

    val avatars = remember(accounts) { accounts.orEmpty().associate { it.username.lowercase() to it.avatarUrl } }
    val everything = remember(libraryState, storySections, ytShelves) {
        (libraryState?.sections.orEmpty().flatMap { it.items } + storySections.orEmpty().flatMap { it.items } +
            ytShelves.orEmpty().values.flatten().flatMap { it.items })
            .associateBy { it.key }
    }
    val igSubTabs: @Composable () -> Unit = {
        PrimaryTabRow(selectedTabIndex = igSub.ordinal, containerColor = MaterialTheme.colorScheme.surface) {
            IgSub.entries.forEach { sub ->
                TabItem(
                    selected = sub == igSub,
                    onClick = { igSub = sub },
                    text = { Text(sub.label, style = MaterialTheme.typography.titleSmall) },
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    // Kept apart from saved files: a saved status shares its key with the live one.
    val waPreviews = remember(waFolders) {
        waFolders.values.filterIsInstance<WaFolderState.Ready>().flatMap { it.previews }.associateBy { it.key }
    }

    SharedTransitionLayout {
        AnimatedContent(
            targetState = viewer,
            contentKey = { it != null },
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
            label = "viewer",
        ) { target ->
            CompositionLocalProvider(
                LocalSharedScope provides this@SharedTransitionLayout,
                LocalContentScope provides this@AnimatedContent,
            ) {
                if (target != null) {
                    ViewerScreen(
                        items = target.keys.mapNotNull(if (target.whatsapp) waPreviews::get else everything::get),
                        startKey = target.startKey,
                        storiesMode = target.stories,
                        avatars = avatars,
                        onDelete = library::delete,
                        onClose = { viewer = null },
                        onSave = if (target.whatsapp) { e -> whatsapp.statusFor(e.key)?.let(whatsapp::save) } else null,
                        savedKeys = if (target.whatsapp) waSaved else emptySet(),
                    )
                } else Scaffold(
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = t == tab,
                                    onClick = { tab = t },
                                    icon = { Icon(if (t == tab) t.selected else t.idle, contentDescription = null) },
                                    label = { Text(t.label, style = MaterialTheme.typography.labelMedium) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    // Fade through: the old tab leaves quickly, the new one settles in from 8dp below.
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            (fadeIn(tween(210, delayMillis = 90)) + slideInVertically(tween(300)) { it / 80 })
                                .togetherWith(fadeOut(tween(90)))
                        },
                        label = "tabs",
                        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                    ) { t ->
                        Box(Modifier.fillMaxSize()) {
                            when (t) {
                                Tab.INSTAGRAM -> if (igSub == IgSub.SAVED) SavedScreen(
                                    library = libraryState,
                                    filter = filter,
                                    query = query,
                                    newSince = newSince[SeenArea.SAVED] ?: Long.MAX_VALUE,
                                    queue = queue,
                                    loggedIn = loggedIn,
                                    sessionExpired = sessionExpired,
                                    gridState = savedGrid,
                                    snackbar = snackbar,
                                    onFilter = library::setFilter,
                                    onQuery = library::setQuery,
                                    onOpen = { e, list -> viewer = ViewerTarget(list.map { it.key }, e.key, stories = false) },
                                    onPaste = main::openSheet,
                                    onLogin = onLogin,
                                    onLogout = main::logout,
                                    onClearFailed = main::clearFailed,
                                    title = "Instagram",
                                    subTabs = igSubTabs,
                                ) else StoriesScreen(
                                    accounts = accounts,
                                    stories = storySections,
                                    status = status,
                                    newSince = newSince[SeenArea.STORIES] ?: Long.MAX_VALUE,
                                    batteryRestricted = batteryRestricted,
                                    gridState = storiesGrid,
                                    snackbar = snackbar,
                                    onAdd = { stories.clearAddError(); addOpen = true },
                                    onCheckNow = stories::checkNow,
                                    onTurnOn = { stories.setWatching(true) },
                                    onLogin = onLogin,
                                    onFixBattery = onFixBattery,
                                    onPause = stories::setEnabled,
                                    onRemove = stories::remove,
                                    onOpen = { e, list -> viewer = ViewerTarget(list.map { it.key }, e.key, stories = true) },
                                    title = "Instagram",
                                    subTabs = igSubTabs,
                                )
                                Tab.WHATSAPP -> WhatsAppScreen(
                                    installed = waInstalled,
                                    selected = waSelected,
                                    folder = waSelected?.let { waFolders[it] },
                                    saved = waSaved,
                                    saving = waSaving,
                                    gridState = whatsappGrid,
                                    snackbar = snackbar,
                                    initialUri = whatsapp::initialUri,
                                    onSelect = whatsapp::select,
                                    onRefresh = whatsapp::refresh,
                                    onFolderPicked = whatsapp::onFolderPicked,
                                    onOpenWhatsApp = { wa -> whatsapp.launchIntent(wa)?.let(context::startActivity) },
                                    onSave = whatsapp::save,
                                    onSaveAll = whatsapp::saveAll,
                                    onOpen = { e, list -> viewer = ViewerTarget(list.map { it.key }, e.key, stories = false, whatsapp = true) },
                                )
                                Tab.YOUTUBE -> YouTubeScreen(
                                    shelves = ytShelves,
                                    shelf = ytShelf,
                                    newSince = newSince[SeenArea.YOUTUBE] ?: Long.MAX_VALUE,
                                    queue = queue,
                                    gridState = ytGrids.getValue(ytShelf),
                                    snackbar = snackbar,
                                    onShelf = { ytShelf = it },
                                    onPaste = {
                                        // A copied YouTube link is used straight away, as on Instagram.
                                        val copied = clipboardLink
                                        if (copied != null && YtLinkParser.parse(copied) != null) main.useClipboardLink()
                                        else youtube.openSheet()
                                    },
                                    onOpen = { e, list -> viewer = ViewerTarget(list.map { it.key }, e.key, stories = false) },
                                    onDelete = library::delete,
                                    onClearFailed = main::clearFailed,
                                )
                                Tab.SETTINGS -> SettingsScreen(
                                    settings = settings,
                                    loggedIn = loggedIn,
                                    bubbleOn = bubbleOn,
                                    usageAccess = usageAccess,
                                    batteryRestricted = batteryRestricted,
                                    onChange = main::updateSettings,
                                    onWatching = stories::setWatching,
                                    onInterval = stories::setInterval,
                                    onBubble = onBubble,
                                    onUsageAccess = { ForegroundApp.openSettings(context) },
                                    onFixBattery = onFixBattery,
                                    onLogin = onLogin,
                                    onLogout = main::logout,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (sheetOpen) DownloadSheet(
        input = input,
        state = resolveState,
        cooldown = cooldown,
        onInput = main::onInput,
        onResolve = main::resolve,
        onToggle = main::toggle,
        onSelectAll = main::selectAll,
        onDownload = main::downloadSelected,
        onLogin = { main.closeSheet(); onLogin() },
        onDismiss = main::closeSheet,
    )
    if (ytSheetOpen) YouTubeSheet(
        input = ytInput,
        state = ytState,
        onInput = youtube::onInput,
        onLookup = youtube::lookup,
        onSelect = youtube::select,
        onDownload = youtube::download,
        onDismiss = youtube::closeSheet,
    )
    if (addOpen) AddAccountSheet(
        adding = adding,
        error = addError,
        everyHours = settings.storyIntervalHours,
        onAdd = stories::add,
        onEdit = stories::clearAddError,
        onDismiss = { addOpen = false },
    )
}
