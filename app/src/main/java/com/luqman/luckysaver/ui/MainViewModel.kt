package com.luqman.luckysaver.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.luqman.luckysaver.App
import com.luqman.luckysaver.core.IgLinkParser
import com.luqman.luckysaver.core.MediaItem
import com.luqman.luckysaver.core.ResolveException
import com.luqman.luckysaver.data.Settings
import com.luqman.luckysaver.download.DownloadNotifications
import com.luqman.luckysaver.download.DownloadWorker
import com.luqman.luckysaver.overlay.BubbleService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ResolveState {
    data object Idle : ResolveState
    data object Loading : ResolveState
    data class Error(val message: String, val needsLogin: Boolean) : ResolveState
    data class Ready(val items: List<MediaItem>, val selected: Set<String>, val alreadySaved: Set<String>) : ResolveState
}

data class QueueStatus(
    val running: Int,
    val queued: Int,
    val failed: List<String>,
    /** 0..1 across running downloads, or null while none reports a size yet. */
    val progress: Float? = null,
) {
    val active: Int get() = running + queued
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private var dismissedLink: String? = null
    private val workManager = WorkManager.getInstance(application)

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _state = MutableStateFlow<ResolveState>(ResolveState.Idle)
    val state: StateFlow<ResolveState> = _state.asStateFlow()

    private val _loggedIn = MutableStateFlow(app.session.isLoggedIn)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    private val _sessionExpired = MutableStateFlow(app.session.isExpired)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    val settings: StateFlow<Settings> get() = app.settings.state

    private val _cooldown = MutableStateFlow<String?>(null)
    val cooldown: StateFlow<String?> = _cooldown.asStateFlow()

    private val _clipboardLink = MutableStateFlow<String?>(null)
    val clipboardLink: StateFlow<String?> = _clipboardLink.asStateFlow()

    private val _undoable = MutableStateFlow<List<java.util.UUID>>(emptyList())
    val undoable: StateFlow<List<java.util.UUID>> = _undoable.asStateFlow()

    private val _bubbleOn = MutableStateFlow(BubbleService.isEnabled(application))
    val bubbleOn: StateFlow<Boolean> = _bubbleOn.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _sheetOpen = MutableStateFlow(false)
    /** The download sheet over Saved; it replaces the old home screen. */
    val sheetOpen: StateFlow<Boolean> = _sheetOpen.asStateFlow()

    val queue: StateFlow<QueueStatus> = workManager.getWorkInfosByTagFlow(DownloadWorker.TAG)
        .map { infos ->
            QueueStatus(
                running = infos.count { it.state == WorkInfo.State.RUNNING },
                queued = infos.count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED },
                failed = infos.filter { it.state == WorkInfo.State.FAILED }
                    .mapNotNull { it.outputData.getString(DownloadWorker.K_ERROR) },
                progress = infos.filter { it.state == WorkInfo.State.RUNNING }
                    .map { it.progress.getInt(DownloadWorker.K_PROGRESS, -1) }
                    .filter { it >= 0 }
                    .takeIf { it.isNotEmpty() }
                    ?.average()?.toFloat()?.div(100f),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueStatus(0, 0, emptyList()))

    /**
     * A pasted link is fetched straight away. Typing is not: a half-typed link can already look
     * valid, and fetching it would spend a request on the wrong post.
     */
    fun onInput(text: String) {
        val pasted = text.length - _input.value.length >= PASTE_MIN_CHARS
        _input.value = text
        if (pasted && IgLinkParser.parse(text.trim()) != null) resolve()
        else if (_state.value is ResolveState.Error) _state.value = ResolveState.Idle
    }

    /** Opens the sheet; a fresh Instagram link in the clipboard is used without asking. */
    fun openSheet() {
        _sheetOpen.value = true
        val link = _clipboardLink.value
        if (link != null) useClipboardLink()
    }

    /** Closing keeps a finished result out of the next opening. */
    fun closeSheet() {
        _sheetOpen.value = false
        if (_state.value !is ResolveState.Loading) {
            _state.value = ResolveState.Idle
            _input.value = ""
        }
    }

    /**
     * Called when the app comes to the front: an Instagram link sitting in the clipboard is
     * almost always what the user came to save, so offer it instead of making them paste.
     */
    fun onClipboard(text: String?) {
        val link = text?.trim().orEmpty()
        _clipboardLink.value = when {
            link.isEmpty() -> null
            IgLinkParser.parse(link) == null -> null
            link == _input.value.trim() -> null
            link == dismissedLink -> null
            else -> link
        }
    }

    fun dismissClipboard() {
        dismissedLink = _clipboardLink.value
        _clipboardLink.value = null
    }

    fun useClipboardLink() {
        val link = _clipboardLink.value ?: return
        dismissedLink = link
        _clipboardLink.value = null
        _sheetOpen.value = true
        _input.value = link
        resolve()
    }

    fun refreshLogin() {
        _loggedIn.value = app.session.isLoggedIn
        _cooldown.value = if (app.rateLimiter.isCoolingDown) app.rateLimiter.describeRemaining() else null
        _sessionExpired.value = app.session.isExpired
        _bubbleOn.value = BubbleService.isEnabled(getApplication())
    }

    /** Returns false when the overlay permission still has to be granted. */
    fun toggleBubble(on: Boolean): Boolean {
        val context = getApplication<Application>()
        if (on && !BubbleService.canDrawOverlay(context)) return false
        if (on) BubbleService.start(context) else BubbleService.stop(context)
        _bubbleOn.value = on
        return true
    }

    fun logout() {
        app.session.logout()
        DownloadNotifications.clearSessionExpired(getApplication())
        refreshLogin()
    }

    /** Called once a login completes: the session is trustworthy again. */
    fun onLoggedIn() {
        app.session.markValid()
        DownloadNotifications.clearSessionExpired(getApplication())
        refreshLogin()
    }

    fun consumeMessage() { _message.value = null }

    /** Called for ACTION_SEND shares: fill the box and resolve immediately. */
    fun onShared(text: String) {
        _sheetOpen.value = true
        _input.value = text
        resolve()
    }

    fun resolve() {
        val text = _input.value.trim()
        if (text.isEmpty() || _state.value is ResolveState.Loading) return
        _state.value = ResolveState.Loading
        viewModelScope.launch {
            _state.value = try {
                val items = app.resolver.resolve(text)
                val saved = if (app.settings.current.skipDuplicates) {
                    app.db.downloads().existingKeys(items.map { it.key }).toSet()
                } else {
                    emptySet()
                }
                val fresh = items.filter { it.key !in saved }
                // Nothing to choose between on a single-item post, so don't make the user choose.
                if (app.settings.current.autoDownload && fresh.size == 1) {
                    _undoable.value = DownloadWorker.enqueueAll(getApplication(), fresh)
                    _message.value = "Downloading 1 item"
                    _sheetOpen.value = false
                    _input.value = ""
                    ResolveState.Ready(items, selected = emptySet(), alreadySaved = saved + fresh.map { it.key })
                } else {
                    ResolveState.Ready(items, selected = fresh.map { it.key }.toSet(), alreadySaved = saved)
                }
            } catch (e: ResolveException) {
                _cooldown.value =
                    if (app.rateLimiter.isCoolingDown) app.rateLimiter.describeRemaining() else null
                ResolveState.Error(e.message ?: "Failed", e.needsLogin)
            } catch (e: Exception) {
                ResolveState.Error(e.message ?: e.javaClass.simpleName, false)
            }
        }
    }

    fun toggle(key: String) = _state.update { s ->
        if (s !is ResolveState.Ready) s
        else s.copy(selected = if (key in s.selected) s.selected - key else s.selected + key)
    }

    fun selectAll(all: Boolean) = _state.update { s ->
        if (s !is ResolveState.Ready) s else s.copy(selected = if (all) s.items.map { it.key }.toSet() else emptySet())
    }

    fun downloadSelected() {
        val s = _state.value as? ResolveState.Ready ?: return
        val picked = s.items.filter { it.key in s.selected }
        if (picked.isEmpty()) return
        _undoable.value = DownloadWorker.enqueueAll(getApplication(), picked)
        _message.value = "Downloading ${picked.size} item${if (picked.size == 1) "" else "s"}"
        _sheetOpen.value = false
        _input.value = ""
        _state.value = ResolveState.Idle
    }

    fun clearFailed() { workManager.pruneWork() }

    /** Cancels the batch queued a moment ago; anything already written stays. */
    fun undoLastBatch() {
        _undoable.value.forEach { workManager.cancelWorkById(it) }
        _undoable.value = emptyList()
        _message.value = "Download cancelled"
    }

    fun updateSettings(block: (Settings) -> Settings) = app.settings.update(block)

    private companion object {
        /** Longer than any single keystroke burst, shorter than the shortest Instagram link. */
        const val PASTE_MIN_CHARS = 12
    }

}
