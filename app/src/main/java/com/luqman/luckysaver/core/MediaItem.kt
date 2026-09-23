package com.luqman.luckysaver.core

enum class MediaKind { IMAGE, VIDEO }

data class MediaItem(
    /** Stable unique key: media pk + carousel index. Used for dedupe. */
    val key: String,
    val kind: MediaKind,
    val url: String,
    val thumbnailUrl: String,
    val width: Int,
    val height: Int,
    val owner: String,
    val shortcode: String?,
    /** Epoch seconds. */
    val takenAt: Long,
    val caption: String?,
) {
    val extension: String get() = if (kind == MediaKind.VIDEO) "mp4" else "jpg"
    val mimeType: String get() = if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg"

    fun fileName(): String {
        val id = shortcode ?: key.substringBefore('_')
        val idx = key.substringAfter('_', "0")
        return "${owner}_${id}_$idx.$extension".replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}

class ResolveException(message: String, val needsLogin: Boolean = false, cause: Throwable? = null) :
    Exception(message, cause)
