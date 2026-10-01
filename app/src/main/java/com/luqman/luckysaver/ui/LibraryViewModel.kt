package com.luqman.luckysaver.ui

import android.app.Application
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.luqman.luckysaver.App
import com.luqman.luckysaver.data.DownloadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

enum class LibraryFilter(val label: String) { ALL("All"), PHOTOS("Photos"), VIDEOS("Videos"), STORIES("Stories") }

data class DaySection(val day: LocalDate, val items: List<DownloadEntity>)

/** One watched account's saved stories, newest first. [latest] orders the sections. */
data class AccountSection(val owner: String, val items: List<DownloadEntity>) {
    val latest: Long get() = items.first().savedAt
}

data class LibraryState(
    val sections: List<DaySection>,
    val shown: Int,
    /** Everything saved, before filter and search: tells "nothing yet" from "nothing matches". */
    val total: Int,
)

/** Which "new" marker a screen clears when it is opened. */
enum class SeenArea { SAVED, STORIES }

/** A delete Android must confirm with the user, for files this install doesn't own. */
data class DeleteRequest(val sender: IntentSender, val keys: List<String>)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val prefs = application.getSharedPreferences("seen", Context.MODE_PRIVATE)

    private val _filter = MutableStateFlow(LibraryFilter.ALL)
    val filter: StateFlow<LibraryFilter> = _filter.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Null until the database answers, so the screen can show a skeleton rather than "empty". */
    val library: StateFlow<LibraryState?> =
        combine(app.db.downloads().observeAll(), _filter, _query) { all, filter, query ->
            val q = query.trim().removePrefix("@").lowercase()
            val shown = all.filter { e ->
                when (filter) {
                    LibraryFilter.ALL -> true
                    LibraryFilter.PHOTOS -> !e.isVideo
                    LibraryFilter.VIDEOS -> e.isVideo
                    LibraryFilter.STORIES -> e.isStory
                } && (q.isEmpty() || e.owner.lowercase().contains(q))
            }
            LibraryState(group(shown), shown.size, all.size)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** By account, the account with the newest story first; the query already sorts newest first. */
    val stories: StateFlow<List<AccountSection>?> =
        app.db.downloads().observeWatchlist()
            .map { list -> list.groupBy { it.owner.lowercase() }.values.map { AccountSection(it.first().owner, it) } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _newSince = MutableStateFlow<Map<SeenArea, Long>>(emptyMap())
    /** Items saved after these times get the gold "new" marker. */
    val newSince: StateFlow<Map<SeenArea, Long>> = _newSince.asStateFlow()

    private val _deleteRequest = MutableStateFlow<DeleteRequest?>(null)
    val deleteRequest: StateFlow<DeleteRequest?> = _deleteRequest.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // First run of this version: nothing is "new", rather than everything.
        SeenArea.entries.forEach { area ->
            if (!prefs.contains(area.name)) prefs.edit().putLong(area.name, System.currentTimeMillis()).apply()
        }
        _newSince.value = SeenArea.entries.associateWith { prefs.getLong(it.name, 0) }
        viewModelScope.launch { reconcile() }
    }

    fun setFilter(f: LibraryFilter) { _filter.value = f }
    fun setQuery(q: String) { _query.value = q }

    /**
     * Opening a screen: remember when it was last seen for this visit's markers, then store now so
     * the next visit only marks what arrived after this one.
     */
    fun markSeen(area: SeenArea) {
        val previous = prefs.getLong(area.name, 0)
        _newSince.value = _newSince.value + (area to previous)
        prefs.edit().putLong(area.name, System.currentTimeMillis()).apply()
    }

    fun consumeMessage() { _message.value = null }

    /** Removes the file. The row stays, flagged, so the same item is never downloaded again. */
    fun delete(entity: DownloadEntity) {
        viewModelScope.launch {
            val uri = Uri.parse(entity.uri)
            val outcome = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.delete(uri, null, null)
                    null
                } catch (e: RecoverableSecurityException) {
                    e.userAction.actionIntent.intentSender
                } catch (e: SecurityException) {
                    // Files from before a reinstall belong to the old install; Android asks first.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        MediaStore.createDeleteRequest(getApplication<Application>().contentResolver, listOf(uri)).intentSender
                    } else null
                }
            }
            if (outcome != null) {
                _deleteRequest.value = DeleteRequest(outcome, listOf(entity.key))
            } else {
                markRemoved(listOf(entity))
            }
        }
    }

    /** Result of Android's own delete confirmation. */
    fun onDeleteConfirmed(confirmed: Boolean) {
        val request = _deleteRequest.value ?: return
        _deleteRequest.value = null
        if (!confirmed) return
        viewModelScope.launch {
            val rows = app.db.downloads().present().filter { it.key in request.keys }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                // On Android 10 the grant only allows the delete; it still has to be done.
                withContext(Dispatchers.IO) {
                    rows.forEach { runCatching { getApplication<Application>().contentResolver.delete(Uri.parse(it.uri), null, null) } }
                }
            }
            markRemoved(rows)
        }
    }

    private suspend fun markRemoved(rows: List<DownloadEntity>) {
        rows.forEach { Thumbs.forget(it.uri) }
        app.db.downloads().markRemoved(rows.map { it.key })
        _message.value = if (rows.size == 1) "Deleted" else "Deleted ${rows.size}"
    }

    /**
     * Files deleted in the gallery leave rows that would draw as broken tiles. One query per media
     * collection finds them, instead of one per file.
     */
    private suspend fun reconcile() = withContext(Dispatchers.IO) {
        val rows = app.db.downloads().present()
        val resolver = getApplication<Application>().contentResolver
        val gone = rows.groupBy { Uri.parse(it.uri).buildUpon().path(Uri.parse(it.uri).path?.substringBeforeLast('/')).build() }
            .flatMap { (collection, inCollection) ->
                val ids = inCollection.associateBy { runCatching { ContentUris.parseId(Uri.parse(it.uri)) }.getOrDefault(-1L) }
                val found = HashSet<Long>()
                ids.keys.filter { it >= 0 }.chunked(500).forEach { chunk ->
                    runCatching {
                        resolver.query(
                            collection, arrayOf(MediaStore.MediaColumns._ID),
                            "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",")})", null, null,
                        )?.use { c -> while (c.moveToNext()) found += c.getLong(0) }
                    }.onFailure { return@flatMap emptyList() }
                }
                ids.filterKeys { it !in found }.values
            }
        if (gone.isNotEmpty()) app.db.downloads().markRemoved(gone.map { it.key })
    }

    private fun group(list: List<DownloadEntity>): List<DaySection> =
        list.groupBy { When.day(it.savedAt) }.map { (day, items) -> DaySection(day, items) }
}
