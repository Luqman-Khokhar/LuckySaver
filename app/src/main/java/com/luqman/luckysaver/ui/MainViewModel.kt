package com.luqman.luckysaver.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.luqman.luckysaver.App
import com.luqman.luckysaver.core.MediaItem
import com.luqman.luckysaver.core.ResolveException
import com.luqman.luckysaver.data.DownloadEntity
import com.luqman.luckysaver.download.DownloadWorker
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

data class QueueStatus(val running: Int, val queued: Int, val failed: List<String>)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val workManager = WorkManager.getInstance(application)

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _state = MutableStateFlow<ResolveState>(ResolveState.Idle)
    val state: StateFlow<ResolveState> = _state.asStateFlow()

    private val _loggedIn = MutableStateFlow(app.session.isLoggedIn)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val history: StateFlow<List<DownloadEntity>?> = app.db.downloads().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val queue: StateFlow<QueueStatus> = workManager.getWorkInfosByTagFlow(DownloadWorker.TAG)
        .map { infos ->
            QueueStatus(
                running = infos.count { it.state == WorkInfo.State.RUNNING },
                queued = infos.count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED },
                failed = infos.filter { it.state == WorkInfo.State.FAILED }
                    .mapNotNull { it.outputData.getString(DownloadWorker.K_ERROR) },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueStatus(0, 0, emptyList()))

    fun onInput(text: String) { _input.value = text }

    fun refreshLogin() { _loggedIn.value = app.session.isLoggedIn }

    fun logout() {
        app.session.logout()
        refreshLogin()
    }

    fun consumeMessage() { _message.value = null }

    /** Called for ACTION_SEND shares: fill the box and resolve immediately. */
    fun onShared(text: String) {
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
                val saved = app.db.downloads().existingKeys(items.map { it.key }).toSet()
                ResolveState.Ready(items, selected = items.map { it.key }.toSet() - saved, alreadySaved = saved)
            } catch (e: ResolveException) {
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
        picked.forEach { DownloadWorker.enqueue(getApplication(), it) }
        _message.value = "Queued ${picked.size} file${if (picked.size == 1) "" else "s"}"
        _state.value = s.copy(selected = emptySet(), alreadySaved = s.alreadySaved + picked.map { it.key })
    }

    fun clearFailed() { workManager.pruneWork() }

    /** Drop history rows whose file the user deleted from the gallery. */
    fun forget(entity: DownloadEntity) {
        viewModelScope.launch { app.db.downloads().delete(entity.key) }
    }

}
