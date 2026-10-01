package com.luqman.luckysaver.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import com.luqman.luckysaver.data.DownloadEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Full-screen viewer. In stories mode it plays like Instagram: photos advance after five seconds,
 * videos when they end, segments across the top count the current account's stories. From Saved
 * it behaves like a gallery: nothing advances on its own.
 */
@Composable
fun ViewerScreen(
    items: List<DownloadEntity>,
    startKey: String,
    storiesMode: Boolean,
    avatars: Map<String, String?>,
    onDelete: (DownloadEntity) -> Unit,
    onClose: () -> Unit,
) {
    if (items.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = items.indexOfFirst { it.key == startKey }.coerceAtLeast(0)) { items.size }
    var paused by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<DownloadEntity?>(null) }
    val progress = remember { Animatable(0f) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val closeDistance = with(LocalDensity.current) { 140.dp.toPx() }
    val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]

    DarkSystemBars()

    fun next() {
        if (pager.currentPage < items.lastIndex) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
        else onClose()
    }
    fun previous() {
        if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
        else scope.launch { progress.snapTo(0f) }
    }

    PredictiveBackHandler { events ->
        try {
            events.collect { backProgress = it.progress }
            onClose()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }

    // Photos in stories mode run a five-second clock that holding the screen pauses.
    LaunchedEffect(pager.currentPage, paused, storiesMode) {
        if (!storiesMode || current.isVideo || paused) return@LaunchedEffect
        if (progress.targetValue >= 1f) progress.snapTo(0f)
        progress.animateTo(1f, tween(((1f - progress.value) * PHOTO_MS).roundToInt(), easing = LinearEasing))
        next()
    }
    LaunchedEffect(pager.currentPage) { progress.snapTo(0f) }

    val scale by animateFloatAsState(1f - 0.1f * backProgress - (dragY / closeDistance).coerceIn(0f, 1f) * 0.15f, label = "viewer-scale")
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 1f - (dragY / closeDistance).coerceIn(0f, 1f) * 0.6f))
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationY = dragY.coerceAtLeast(0f)
                    shape = RoundedCornerShape((24 * (1f - scale) / 0.1f).coerceIn(0f, 24f).dp)
                    clip = scale < 1f
                }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { dragY = (dragY + it).coerceAtLeast(0f) },
                    onDragStarted = { paused = true },
                    onDragStopped = {
                        if (dragY > closeDistance) onClose() else { dragY = 0f; paused = false }
                    },
                ),
        ) {
            HorizontalPager(state = pager, beyondViewportPageCount = 1, key = { items[it].key }) { page ->
                val e = items[page]
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(page) {
                            detectTapGestures(
                                onPress = {
                                    val held = scope.launch { delay(200); paused = true }
                                    tryAwaitRelease()
                                    held.cancel()
                                    paused = false
                                },
                                onTap = { offset -> if (offset.x < size.width / 3f) previous() else next() },
                            )
                        },
                ) {
                    if (e.isVideo) VideoPage(
                        e,
                        active = page == pager.currentPage,
                        paused = paused,
                        loop = !storiesMode,
                        onProgress = { if (page == pager.currentPage) scope.launch { progress.snapTo(it) } },
                        onEnded = { if (storiesMode && page == pager.currentPage) next() },
                    ) else AsyncImage(
                        model = Uri.parse(e.uri),
                        contentDescription = "Photo from @${e.owner}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().sharedMedia(e.key),
                    )
                }
            }

            // Header and actions sit on soft gradients so white text reads on any frame.
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent)))
                    .statusBarsPadding()
                    .graphicsLayer { alpha = if (paused && dragY == 0f) 0f else 1f },
            ) {
                if (storiesMode) Segments(items, pager.currentPage, progress.value)
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Avatar(avatars[current.owner.lowercase()], current.owner, 32.dp, Modifier.size(32.dp))
                    Column(Modifier.weight(1f)) {
                        Text(current.owner, style = MaterialTheme.typography.titleSmall, color = Color.White)
                        Text(
                            listOfNotNull(When.savedPhrase(context, current.savedAt), When.postedBefore(current.savedAt, current.takenAt))
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
                }
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .graphicsLayer { alpha = if (paused && dragY == 0f) 0f else 1f },
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                ViewerAction(Icons.Rounded.Share, "Share") { share(context, current) }
                ViewerAction(Icons.AutoMirrored.Rounded.OpenInNew, "Open") { openExternally(context, current) }
                ViewerAction(Icons.Rounded.Delete, "Delete") { paused = true; confirmDelete = current }
            }
        }
    }

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null; paused = false },
            title = { Text(if (target.isStory) "Delete this story?" else "Delete this file?") },
            text = {
                Text(
                    "It's removed from your phone. LuckySaver remembers it was saved, so it won't be downloaded again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    paused = false
                    // The list is live: the deleted item drops out and the pager settles on its neighbour.
                    onDelete(target)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null; paused = false }) { Text("Cancel") } },
        )
    }
}

/** Segments for the current account's run of stories, as Instagram draws them. */
@Composable
private fun Segments(items: List<DownloadEntity>, page: Int, progress: Float) {
    val owner = items[page].owner
    var start = page
    while (start > 0 && items[start - 1].owner == owner) start--
    var end = page
    while (end < items.lastIndex && items[end + 1].owner == owner) end++
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in start..end) {
            val fill = when {
                i < page -> 1f
                i == page -> progress
                else -> 0f
            }
            Box(Modifier.weight(1f).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.35f))) {
                Box(Modifier.fillMaxWidth(fill).height(2.dp).background(Color.White))
            }
        }
    }
}

@Composable
private fun ViewerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = Color.White) }
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

/**
 * Android's own player. The thumbnail stays on top until the first frame is ready, which is
 * also what the shared-element transition animates.
 */
@Composable
private fun VideoPage(
    e: DownloadEntity,
    active: Boolean,
    paused: Boolean,
    loop: Boolean,
    onProgress: (Float) -> Unit,
    onEnded: () -> Unit,
) {
    var ready by remember(e.key) { mutableStateOf(false) }
    var view by remember { mutableStateOf<VideoView?>(null) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (active) AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(Uri.parse(e.uri))
                    setOnPreparedListener { mp ->
                        mp.isLooping = loop
                        mp.setOnInfoListener { _, what, _ ->
                            if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) ready = true
                            false
                        }
                        start()
                    }
                    setOnCompletionListener { onEnded() }
                    setOnErrorListener { _, _, _ -> ready = false; true }
                    view = this
                }
            },
            onRelease = { it.stopPlayback(); view = null },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!ready) MediaThumb(
            e.uri, contentDescription = "Video from @${e.owner}",
            modifier = Modifier.fillMaxSize().sharedMedia(e.key).background(Color.Black),
            sizeHint = 540.dp,
        )
    }
    LaunchedEffect(view, paused) {
        val v = view ?: return@LaunchedEffect
        if (paused) v.pause() else if (ready) v.start()
    }
    LaunchedEffect(view, active) {
        while (active) {
            val v = view
            if (v != null && v.duration > 0) onProgress(v.currentPosition / v.duration.toFloat())
            delay(50)
        }
    }
}

/** The viewer is dark whatever the theme, so the status bar icons go light while it's open. */
@Composable
private fun DarkSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window ?: return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        val before = controller.isAppearanceLightStatusBars to controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = before.first
            controller.isAppearanceLightNavigationBars = before.second
        }
    }
}

private fun share(context: android.content.Context, e: DownloadEntity) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(if (e.isVideo) "video/*" else "image/*")
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(e.uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}

private fun openExternally(context: android.content.Context, e: DownloadEntity) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(e.uri), if (e.isVideo) "video/*" else "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this phone can open it", Toast.LENGTH_SHORT).show()
    }
}

private const val PHOTO_MS = 5_000
