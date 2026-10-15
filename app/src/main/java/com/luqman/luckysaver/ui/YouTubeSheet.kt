package com.luqman.luckysaver.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.luqman.luckysaver.core.YtLinkParser
import com.luqman.luckysaver.youtube.YtDownloadWorker
import com.luqman.luckysaver.youtube.YtFormat

/** Paste a YouTube link, see the video, pick a quality or audio only, download. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeSheet(
    input: String,
    state: YtLookupState,
    onInput: (String) -> Unit,
    onLookup: () -> Unit,
    onSelect: (YtFormat) -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Download from YouTube", style = MaterialTheme.typography.headlineSmall)
            val valid = YtLinkParser.parse(input.trim()) != null
            TextField(
                value = input,
                onValueChange = onInput,
                label = { Text("YouTube link") },
                placeholder = { Text("Video, Short or YouTube Music link") },
                singleLine = true,
                isError = state is YtLookupState.Error,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (valid) onLookup() }),
                trailingIcon = {
                    when {
                        input.isEmpty() -> IconButton(onClick = { clipboard.getText()?.text?.let(onInput) }) {
                            Icon(Icons.Rounded.ContentPaste, "Paste")
                        }
                        valid && state is YtLookupState.Idle -> IconButton(onClick = onLookup) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Fetch")
                        }
                        else -> IconButton(onClick = { onInput("") }) { Icon(Icons.Rounded.Cancel, "Clear") }
                    }
                },
                supportingText = if (input.isNotBlank() && !valid) {
                    { Text("That doesn't look like a YouTube video link") }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )

            AnimatedContent(
                targetState = state,
                contentKey = { it::class },
                transitionSpec = { fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(60)) },
                label = "yt-sheet-state",
            ) { s ->
                when (s) {
                    YtLookupState.Idle -> Text(
                        "Tip: in YouTube or YouTube Music, tap Share and pick LuckySaver. It saves without opening the app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    YtLookupState.Loading -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Reading the video", style = MaterialTheme.typography.bodyMedium)
                        }
                        // Same shape as the result, so nothing jumps when it arrives.
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                        Box(Modifier.fillMaxWidth(0.8f).height(16.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                    }
                    is YtLookupState.Error -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onLookup, enabled = valid) { Text("Try again") }
                    }
                    is YtLookupState.Ready -> ReadyVideo(s, onSelect, onDownload)
                }
            }
        }
    }
}

@Composable
private fun ReadyVideo(s: YtLookupState.Ready, onSelect: (YtFormat) -> Unit, onDownload: () -> Unit) {
    val accents = LocalAccents.current
    val chosenKey = YtDownloadWorker.keyFor(s.video.link.videoId, s.selected.audioOnly)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = s.video.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (s.video.durationSec > 0) Text(
                When.duration(s.video.durationSec * 1000),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.video.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                s.video.channel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(Modifier.selectableGroup()) {
            s.video.formats.forEach { f ->
                val savedAlready = YtDownloadWorker.keyFor(s.video.link.videoId, f.audioOnly) in s.saved
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = f == s.selected, role = Role.RadioButton, onClick = { onSelect(f) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = f == s.selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                    Text(
                        if (f.audioOnly) "Audio only (M4A)" else f.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    val note = listOfNotNull(
                        if (savedAlready) "Saved before" else null,
                        if (f.size > 0) megabytes(f.size) else null,
                    ).joinToString(" · ")
                    if (note.isNotEmpty()) Text(
                        note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
        }
        Button(
            onClick = onDownload,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accents.youtube, contentColor = accents.onYoutube),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(
                when {
                    chosenKey in s.saved -> "Download again"
                    s.selected.audioOnly -> "Save audio"
                    else -> "Download ${s.selected.label}"
                }
            )
        }
        Text(
            "Above 360p, video and sound arrive separately and are joined on the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun megabytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb < 10) "%.1f MB".format(mb) else "${mb.toInt()} MB"
}
