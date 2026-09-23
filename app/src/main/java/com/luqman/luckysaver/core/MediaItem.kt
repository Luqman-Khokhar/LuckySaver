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

    /** Tokens: {user} {code} {index} {date}. Anything else in the template is kept as typed. */
    fun fileName(template: String = DEFAULT_TEMPLATE): String {
        val id = shortcode ?: key.substringBefore('_')
        val idx = key.substringAfter('_', "0")
        val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(java.util.Date(takenAt * 1000))
        val name = template
            .replace("{user}", owner)
            .replace("{code}", id)
            .replace("{index}", idx)
            .replace("{date}", date)
            .ifBlank { "${owner}_${id}_$idx" }
        return "$name.$extension".replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    companion object {
        const val DEFAULT_TEMPLATE = "{user}_{code}_{index}"
    }
}

class ResolveException(message: String, val needsLogin: Boolean = false, cause: Throwable? = null) :
    Exception(message, cause)
