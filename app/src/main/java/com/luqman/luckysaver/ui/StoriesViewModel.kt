package com.luqman.luckysaver.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.luqman.luckysaver.App
import com.luqman.luckysaver.core.ResolveException
import com.luqman.luckysaver.data.WatchedAccount
import com.luqman.luckysaver.download.StoryWatchWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the one-line status under the Stories title says, and which color its dot takes. */
sealed interface WatchStatus {
    data object Off : WatchStatus
    data object LoggedOut : WatchStatus
    data object Checking : WatchStatus
    data class CoolingDown(val remaining: String) : WatchStatus
    /** [nextAt] is 0 when Android hasn't scheduled the next run yet. */
    data class Running(val everyHours: Int, val nextAt: Long, val lastCheckedAt: Long) : WatchStatus
}

class StoriesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val work = WorkManager.getInstance(application)

    val accounts: StateFlow<List<WatchedAccount>?> = app.db.watched().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _adding = MutableStateFlow(false)
    val adding: StateFlow<Boolean> = _adding.asStateFlow()

    private val _addError = MutableStateFlow<String?>(null)
    val addError: StateFlow<String?> = _addError.asStateFlow()

    /** Fires once an account is added, so the sheet can close itself. */
    private val _added = MutableStateFlow<String?>(null)
    val added: StateFlow<String?> = _added.asStateFlow()

    /** Cooldown and login state live outside any flow; a slow tick keeps the line honest. */
    private val tick = flow { while (true) { emit(System.currentTimeMillis()); delay(30_000) } }

    val status: StateFlow<WatchStatus> = combine(
        app.settings.state,
        work.getWorkInfosForUniqueWorkFlow(StoryWatchWorker.WORK_NAME),
        work.getWorkInfosForUniqueWorkFlow(StoryWatchWorker.MANUAL_WORK_NAME),
        app.db.watched().observeAll(),
        tick,
    ) { settings, periodic, manual, accounts, _ ->
        val checking = (periodic + manual).any { it.state == WorkInfo.State.RUNNING }
        when {
            !app.session.isLoggedIn -> WatchStatus.LoggedOut
            checking -> WatchStatus.Checking
            app.rateLimiter.isCoolingDown -> WatchStatus.CoolingDown(app.rateLimiter.describeRemaining())
            !settings.storyWatchEnabled -> WatchStatus.Off
            else -> WatchStatus.Running(
                everyHours = settings.storyIntervalHours,
                nextAt = periodic.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
                    ?.nextScheduleTimeMillis?.takeIf { it != Long.MAX_VALUE } ?: 0,
                lastCheckedAt = accounts.maxOfOrNull { it.lastCheckedAt } ?: 0,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchStatus.Off)

    /** Resolves the username to an id and picture up front, so scheduled checks never look one up. */
    fun add(username: String) {
        val name = username.removePrefix("@").trim()
        if (name.isBlank() || _adding.value) return
        if ((accounts.value?.size ?: 0) >= MAX_WATCHED) {
            _addError.value = "You can watch up to $MAX_WATCHED accounts. More would look automated to Instagram."
            return
        }
        _adding.value = true
        _addError.value = null
        viewModelScope.launch {
            try {
                val user = app.resolver.lookupUser(name)
                app.db.watched().upsert(
                    WatchedAccount(
                        userId = user.id,
                        username = user.username,
                        addedAt = System.currentTimeMillis(),
                        avatarUrl = user.avatarUrl,
                    )
                )
                // The first account is the moment watching starts to mean something.
                if (!app.settings.current.storyWatchEnabled) setWatching(true)
                // Its current stories are why it was added; waiting for the next scheduled check
                // could lose some. One batched request, spaced by the same throttle as the lookup.
                checkNow()
                _added.value = user.username
            } catch (e: ResolveException) {
                _addError.value = e.message
            } catch (e: Exception) {
                _addError.value = "Couldn't reach Instagram. Check your connection and try again."
            } finally {
                _adding.value = false
            }
        }
    }

    fun consumeAdded() { _added.value = null }
    fun clearAddError() { _addError.value = null }

    fun remove(account: WatchedAccount) {
        viewModelScope.launch { app.db.watched().delete(account.userId) }
    }

    fun setEnabled(account: WatchedAccount, enabled: Boolean) {
        viewModelScope.launch { app.db.watched().setEnabled(account.userId, enabled) }
    }

    fun setWatching(on: Boolean) {
        app.settings.update { it.copy(storyWatchEnabled = on) }
        if (on) StoryWatchWorker.schedule(getApplication(), app.settings.current.storyIntervalHours)
        else StoryWatchWorker.cancel(getApplication())
    }

    fun setInterval(hours: Int) {
        app.settings.update { it.copy(storyIntervalHours = hours) }
        if (app.settings.current.storyWatchEnabled) StoryWatchWorker.schedule(getApplication(), hours)
    }

    fun checkNow() {
        if (app.rateLimiter.isCoolingDown || !app.session.isLoggedIn) return
        StoryWatchWorker.runOnce(getApplication())
    }

    private companion object {
        const val MAX_WATCHED = 10
    }
}
