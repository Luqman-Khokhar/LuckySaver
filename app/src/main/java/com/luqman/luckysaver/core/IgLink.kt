package com.luqman.luckysaver.core

import java.math.BigInteger

sealed interface IgLink {
    data class Post(val shortcode: String) : IgLink
    data class Story(val username: String, val storyPk: String?) : IgLink
    data class Highlight(val highlightId: String) : IgLink
    data class Profile(val username: String) : IgLink
    /** instagram.com/share/... links; must be resolved via redirect first. */
    data class Share(val url: String) : IgLink
}

object IgLinkParser {
    private val urlInText = Regex("""https?://(?:www\.|m\.)?(?:instagram\.com|instagr\.am)/\S+""", RegexOption.IGNORE_CASE)
    private val post = Regex("""^/(?:[A-Za-z0-9._]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""")
    private val highlight = Regex("""^/stories/highlights/(\d+)""")
    private val story = Regex("""^/stories/([A-Za-z0-9._]+)(?:/(\d+))?""")
    private val share = Regex("""^/share/""")
    private val profile = Regex("""^/([A-Za-z0-9._]{1,30})/?$""")
    private val reservedPaths = setOf("explore", "accounts", "direct", "about", "developer", "legal", "stories", "p", "reel", "reels", "tv", "share")

    /** Accepts raw share text (may contain caption + link) or a bare URL. */
    fun parse(input: String): IgLink? {
        val raw = urlInText.find(input.trim())?.value ?: return null
        val url = raw.trimEnd('.', ',', ')', '"', '\'')
        val afterHost = url.substringAfter("://").substringAfter('/', "")
        val path = "/" + afterHost.substringBefore('?').substringBefore('#')

        if (share.containsMatchIn(path)) return IgLink.Share(url.substringBefore('?'))
        highlight.find(path)?.let { return IgLink.Highlight(it.groupValues[1]) }
        story.find(path)?.let { m ->
            return IgLink.Story(m.groupValues[1], m.groupValues[2].ifEmpty { null })
        }
        post.find(path)?.let { return IgLink.Post(it.groupValues[1]) }
        profile.find(path)?.let { m ->
            val name = m.groupValues[1]
            if (name.lowercase() !in reservedPaths) return IgLink.Profile(name)
        }
        return null
    }
}

object Shortcode {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val SIXTY_FOUR = BigInteger.valueOf(64)

    /** Private-post shortcodes carry a suffix after the first 11 chars; only the prefix encodes the id. */
    fun toMediaId(shortcode: String): String {
        var id = BigInteger.ZERO
        for (c in shortcode.take(11)) {
            val idx = ALPHABET.indexOf(c)
            require(idx >= 0) { "Invalid shortcode char '$c'" }
            id = id.multiply(SIXTY_FOUR).add(BigInteger.valueOf(idx.toLong()))
        }
        return id.toString()
    }

    fun fromMediaId(mediaId: String): String {
        var id = BigInteger(mediaId.substringBefore('_'))
        if (id.signum() == 0) return "A"
        val sb = StringBuilder()
        while (id.signum() > 0) {
            val (q, r) = id.divideAndRemainder(SIXTY_FOUR)
            sb.append(ALPHABET[r.toInt()])
            id = q
        }
        return sb.reverse().toString()
    }
}
