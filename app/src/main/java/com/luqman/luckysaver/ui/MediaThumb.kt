package com.luqman.luckysaver.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thumbnails from Android's own thumbnail cache. Videos are never decoded just to draw a tile,
 * and the in-memory cache means scrolling back or reopening a screen costs nothing.
 */
object Thumbs {
    private val bitmaps = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val durations = LruCache<String, Long>(2_000)
    private val missing = HashSet<String>()

    fun cached(uri: String, px: Int): Bitmap? = bitmaps.get("$uri@$px")

    suspend fun load(resolver: ContentResolver, uri: String, px: Int): Bitmap? {
        cached(uri, px)?.let { return it }
        if (isMissing(uri)) return null
        return withContext(Dispatchers.IO) {
            runCatching { resolver.loadThumbnail(Uri.parse(uri), Size(px, px), null) }
                .onFailure { synchronized(missing) { missing += uri } }
                .getOrNull()
                ?.also { bitmaps.put("$uri@$px", it) }
        }
    }

    /** Video length in millis, read from MediaStore rather than the file. */
    suspend fun duration(resolver: ContentResolver, uri: String): Long? {
        durations.get(uri)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                resolver.query(Uri.parse(uri), arrayOf(MediaStore.Video.VideoColumns.DURATION), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            }.getOrNull()?.also { durations.put(uri, it) }
        }
    }

    fun isMissing(uri: String): Boolean = synchronized(missing) { uri in missing }

    fun forget(uri: String) {
        bitmaps.snapshot().keys.filter { it.startsWith("$uri@") }.forEach(bitmaps::remove)
    }
}

/**
 * A saved file's thumbnail. The tile's background shows until the bitmap lands, then the image
 * fades in over 150 ms, so a fast scroll never flashes.
 */
@Composable
fun MediaThumb(
    uri: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    sizeHint: Dp = 160.dp,
) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { sizeHint.roundToPx() }.coerceIn(96, 1080)
    val bitmap by produceState(Thumbs.cached(uri, px), uri, px) {
        if (value == null) value = Thumbs.load(context.contentResolver, uri, px)
    }
    val alpha by animateFloatAsState(if (bitmap != null) 1f else 0f, tween(150), label = "thumb")
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(alpha),
            )
        } else if (Thumbs.isMissing(uri)) {
            Icon(
                Icons.Rounded.BrokenImage,
                contentDescription = "File not found",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(24.dp),
            )
        }
    }
}
