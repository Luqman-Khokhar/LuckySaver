package com.luqman.luckysaver.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.luqman.luckysaver.App
import com.luqman.luckysaver.core.WaApp
import com.luqman.luckysaver.data.DownloadEntity
import com.luqman.luckysaver.whatsapp.ForegroundApp
import com.luqman.luckysaver.whatsapp.WaStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One WhatsApp's status folder, as the screen sees it. */
sealed interface WaFolderState {
    data object Loading : WaFolderState
    /** No grant yet, or Android dropped it. */
    data object NoAccess : WaFolderState
    data class Failed(val message: String) : WaFolderState
    data class Ready(val statuses: List<WaStatus>) : WaFolderState {
        /** The same statuses shaped for the shared tile and viewer. */
        val previews: List<DownloadEntity> = statuses.map { it.preview() }
    }
}

/** A status that isn't saved yet, drawn by the components made for saved files. */
fun WaStatus.preview() = DownloadEntity(
    key = key,
    owner = app.label,
    shortcode = null,
    isVideo = isVideo,
    uri = uri.toString(),
    fileName = name,
    savedAt = modified,
    takenAt = modified,
)

class WhatsAppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val store = app.whatsapp

    private val _installed = MutableStateFlow<List<WaApp>?>(null)
    /** Null until checked; empty when neither WhatsApp is on the phone. */
    val installed: StateFlow<List<WaApp>?> = _installed.asStateFlow()

    private val _selected = MutableStateFlow<WaApp?>(null)
    val selected: StateFlow<WaApp?> = _selected.asStateFlow()

    private val _folders = MutableStateFlow<Map<WaApp, WaFolderState>>(emptyMap())
    val folders: StateFlow<Map<WaApp, WaFolderState>> = _folders.asStateFlow()

    private val _saving = MutableStateFlow<Set<String>>(emptySet())
    val saving: StateFlow<Set<String>> = _saving.asStateFlow()

    private val _usageAccess = MutableStateFlow(ForegroundApp.hasAccess(application))
    /** Whether the bubble can follow the app on screen. */
    val usageAccess: StateFlow<Boolean> = _usageAccess.asStateFlow()

    /** Keys saved and still on the phone: what the tiles mark as saved. */
    val saved: StateFlow<Set<String>> = app.db.downloads().observeWhatsAppKeys()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _openRequest = MutableStateFlow(false)
    /** The bubble sent the user here to grant access: the shell switches to this tab. */
    val openRequest: StateFlow<Boolean> = _openRequest.asStateFlow()

    fun select(wa: WaApp) {
        _selected.value = wa
        if (_folders.value[wa] !is WaFolderState.Ready) refresh()
    }

    fun requestOpen(wa: WaApp?) {
        if (wa != null) _selected.value = wa
        _openRequest.value = true
    }

    fun consumeOpenRequest() { _openRequest.value = false }

    fun consumeMessage() { _message.value = null }

    /** Usage access is granted in system settings, so it's re-read whenever the app comes back. */
    fun refreshAccess() { _usageAccess.value = ForegroundApp.hasAccess(getApplication()) }

    /** Re-reads every installed WhatsApp. Cheap: one folder listing each, no network. */
    fun refresh() {
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) { store.installed() }
            _installed.value = installed
            _usageAccess.value = ForegroundApp.hasAccess(getApplication())
            if (_selected.value !in installed) _selected.value = installed.firstOrNull()
            store.refreshGrants()
            installed.forEach { wa -> load(wa) }
        }
    }

    private suspend fun load(wa: WaApp) {
        if (wa !in store.granted.value) {
            _folders.value = _folders.value + (wa to WaFolderState.NoAccess)
            return
        }
        // Keep what's on screen while re-reading, so returning to the app doesn't flash a skeleton.
        if (_folders.value[wa] !is WaFolderState.Ready) _folders.value = _folders.value + (wa to WaFolderState.Loading)
        val next = runCatching { store.list(wa) }.fold(
            onSuccess = { WaFolderState.Ready(it) },
            onFailure = {
                if (wa !in store.granted.value) WaFolderState.NoAccess
                else WaFolderState.Failed("The ${wa.label} status folder couldn't be read. ${it.message.orEmpty()}".trim())
            },
        )
        _folders.value = _folders.value + (wa to next)
    }

    /** Result of the system folder picker. Null means the user backed out. */
    fun onFolderPicked(wa: WaApp, tree: Uri?) {
        if (tree == null) return
        if (!store.grant(wa, tree)) {
            _message.value = "That isn't the ${wa.label} status folder. Choose the .Statuses folder it opens on."
            return
        }
        _message.value = "${wa.label} statuses connected"
        viewModelScope.launch { load(wa) }
    }

    fun save(status: WaStatus) {
        if (status.key in _saving.value) return
        _saving.value = _saving.value + status.key
        viewModelScope.launch {
            runCatching { store.save(status) }
                .onSuccess { _message.value = "Saved to Pictures/${app.settings.current.folder}/WhatsApp" }
                .onFailure { _message.value = "Couldn't save: ${status.app.label} may have removed it. Tap refresh and try again." }
            _saving.value = _saving.value - status.key
        }
    }

    fun saveAll(wa: WaApp) {
        val ready = _folders.value[wa] as? WaFolderState.Ready ?: return
        val todo = ready.statuses.filter { it.key !in saved.value && it.key !in _saving.value }
        if (todo.isEmpty()) return
        _saving.value = _saving.value + todo.map { it.key }
        viewModelScope.launch {
            val count = store.saveAll(todo)
            _saving.value = _saving.value - todo.map { it.key }.toSet()
            _message.value = when {
                count == todo.size -> "Saved $count status${if (count == 1) "" else "es"}"
                count == 0 -> "Nothing saved: ${wa.label} may have removed them. Refresh and try again."
                else -> "Saved $count of ${todo.size}. The rest are no longer in ${wa.label}."
            }
        }
    }

    /** Looks up the status behind a preview, for saving from the viewer. */
    fun statusFor(key: String): WaStatus? =
        _folders.value.values.filterIsInstance<WaFolderState.Ready>().flatMap { it.statuses }.firstOrNull { it.key == key }

    fun initialUri(wa: WaApp): Uri = store.initialUri(wa)

    fun launchIntent(wa: WaApp) = store.launchIntent(wa)
}
