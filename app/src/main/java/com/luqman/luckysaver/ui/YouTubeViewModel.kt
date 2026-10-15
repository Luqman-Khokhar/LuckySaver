package com.luqman.luckysaver.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.luqman.luckysaver.App
import com.luqman.luckysaver.core.ResolveException
import com.luqman.luckysaver.core.YtLinkParser
import com.luqman.luckysaver.youtube.YtDownloadWorker
import com.luqman.luckysaver.youtube.YtFormat
import com.luqman.luckysaver.youtube.YtVideo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface YtLookupState {
    data object Idle : YtLookupState
    data object Loading : YtLookupState
    data class Error(val message: String) : YtLookupState
    data class Ready(
        val video: YtVideo,
        val selected: YtFormat,
        /** Keys from [YtDownloadWorker.keyFor] already in the library: video, audio, or both. */
        val saved: Set<String>,
    ) : YtLookupState
}

/** The YouTube paste sheet: look a link up, pick a quality or audio, queue it. */
class YouTubeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private var lookupJob: Job? = null

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _state = MutableStateFlow<YtLookupState>(YtLookupState.Idle)
    val state: StateFlow<YtLookupState> = _state.asStateFlow()

    private val _sheetOpen = MutableStateFlow(false)
    val sheetOpen: StateFlow<Boolean> = _sheetOpen.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun openSheet() { _sheetOpen.value = true }

    /** A link handed over from a share, the clipboard or the Instagram sheet. */
    fun open(link: String) {
        _sheetOpen.value = true
        _input.value = link
        lookup()
    }

    fun closeSheet() {
        _sheetOpen.value = false
        lookupJob?.cancel()
        _state.value = YtLookupState.Idle
        _input.value = ""
    }

    /** Same rule as the Instagram sheet: a paste is looked up at once, typing is not. */
    fun onInput(text: String) {
        val pasted = text.length - _input.value.length >= PASTE_MIN_CHARS
        _input.value = text
        if (pasted && YtLinkParser.parse(text) != null) lookup()
        else if (_state.value !is YtLookupState.Loading) _state.value = YtLookupState.Idle
    }

    fun lookup() {
        val link = YtLinkParser.parse(_input.value) ?: run {
            _state.value = YtLookupState.Error("That isn't a YouTube video, Short or YouTube Music link")
            return
        }
        lookupJob?.cancel()
        _state.value = YtLookupState.Loading
        lookupJob = viewModelScope.launch {
            _state.value = try {
                val video = app.youtube.lookup(link)
                val saved = app.db.downloads().presentKeys(
                    listOf(YtDownloadWorker.keyFor(link.videoId, false), YtDownloadWorker.keyFor(link.videoId, true)),
                ).toSet()
                YtLookupState.Ready(video, defaultFormat(video), saved)
            } catch (e: ResolveException) {
                YtLookupState.Error(e.message ?: "Couldn't read that video")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                YtLookupState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun select(format: YtFormat) = _state.update { s ->
        if (s is YtLookupState.Ready) s.copy(selected = format) else s
    }

    fun download() {
        val s = _state.value as? YtLookupState.Ready ?: return
        val f = s.selected
        YtDownloadWorker.enqueue(
            getApplication(), s.video.link, audioOnly = f.audioOnly, maxHeight = f.height.takeIf { it > 0 } ?: 1080,
            title = s.video.title,
        )
        _message.value = if (f.audioOnly) "Saving audio: ${s.video.title}" else "Downloading ${f.label}: ${s.video.title}"
        closeSheet()
    }

    fun consumeMessage() { _message.value = null }

    /** Music links start on audio when the setting says so; videos on the highest height within the cap. */
    private fun defaultFormat(video: YtVideo): YtFormat {
        val audio = video.formats.firstOrNull { it.audioOnly }
        if (audio != null && YtDownloadWorker.defaultIsAudio(getApplication(), video.link)) return audio
        val cap = app.settings.current.ytMaxHeight
        val videos = video.formats.filter { !it.audioOnly }
        return videos.firstOrNull { it.height <= cap } ?: videos.lastOrNull() ?: audio ?: video.formats.first()
    }

    private companion object {
        const val PASTE_MIN_CHARS = 12
    }
}
