package com.luqman.luckysaver.core

/** Where a YouTube link points: decides the default save (music saves audio) and the sub-tab. */
enum class YtKind { VIDEO, SHORT, MUSIC }

data class YtLink(val videoId: String, val kind: YtKind) {
    /** One canonical form for the extractor, whatever host or tracking params the share had. */
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$videoId"
}

object YtLinkParser {
    private val urlInText = Regex(
        """https?://(?:(?:www|m|music)\.)?(?:youtube\.com|youtu\.be|youtube-nocookie\.com)/\S+""",
        RegexOption.IGNORE_CASE,
    )
    private val id = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val pathId = Regex("""^/(shorts|live|embed|v)/([A-Za-z0-9_-]{11})""")

    /** Accepts raw share text (may contain a title) or a bare URL. Playlists without a video are not links to save. */
    fun parse(input: String): YtLink? {
        val raw = urlInText.find(input.trim())?.value ?: return null
        val url = raw.trimEnd('.', ',', ')', '"', '\'')
        val host = url.substringAfter("://").substringBefore('/').lowercase()
        val afterHost = url.substringAfter("://").substringAfter('/', "")
        val path = "/" + afterHost.substringBefore('?').substringBefore('#')
        val query = afterHost.substringAfter('?', "").substringBefore('#')
        val music = host.startsWith("music.")

        if (host.endsWith("youtu.be")) {
            val v = path.removePrefix("/").substringBefore('/')
            return if (id.matches(v)) YtLink(v, YtKind.VIDEO) else null
        }
        pathId.find(path)?.let { m ->
            val kind = if (m.groupValues[1] == "shorts") YtKind.SHORT else if (music) YtKind.MUSIC else YtKind.VIDEO
            return YtLink(m.groupValues[2], kind)
        }
        if (path == "/watch" || path == "/watch/") {
            val v = query.split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=") ?: return null
            return if (id.matches(v)) YtLink(v, if (music) YtKind.MUSIC else YtKind.VIDEO) else null
        }
        return null
    }
}

/** Which app a pasted or shared link belongs to, so one entry point can route it. */
enum class LinkPlatform { INSTAGRAM, YOUTUBE }

object Links {
    fun platformOf(text: String): LinkPlatform? = when {
        YtLinkParser.parse(text) != null -> LinkPlatform.YOUTUBE
        IgLinkParser.parse(text) != null -> LinkPlatform.INSTAGRAM
        else -> null
    }
}
