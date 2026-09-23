package com.luqman.luckysaver.resolve

import com.luqman.luckysaver.core.IgJson
import com.luqman.luckysaver.core.IgLink
import com.luqman.luckysaver.core.IgLinkParser
import com.luqman.luckysaver.core.MediaItem
import com.luqman.luckysaver.core.ResolveException
import com.luqman.luckysaver.core.Shortcode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

interface MediaResolver {
    val name: String
    fun supports(link: IgLink): Boolean
    suspend fun resolve(link: IgLink): List<MediaItem>
}

/** Logged-in private web API. Handles posts, reels, stories, highlights, profile pictures. */
class ApiResolver(
    private val http: OkHttpClient,
    private val session: IgSession,
    private val preferSmaller: () -> Boolean = { false },
) : MediaResolver {
    override val name = "api"
    override fun supports(link: IgLink) = session.isLoggedIn && link !is IgLink.Share

    override suspend fun resolve(link: IgLink): List<MediaItem> = when (link) {
        is IgLink.Post -> {
            val root = getJson("/api/v1/media/${Shortcode.toMediaId(link.shortcode)}/info/")
            val items = root.optJSONArray("items") ?: throw ResolveException("No items in response")
            (0 until items.length()).flatMap { IgJson.parseMedia(items.getJSONObject(it), preferSmaller()) }
        }
        is IgLink.Story -> {
            val userId = userInfo(link.username).getString("id")
            val nodes = IgJson.parseReels(getJson("/api/v1/feed/reels_media/?reel_ids=$userId"))
            if (nodes.isEmpty()) throw ResolveException("@${link.username} has no active stories")
            val picked = link.storyPk?.let { pk -> nodes.filter { it.optString("pk") == pk }.ifEmpty { nodes } } ?: nodes
            picked.flatMap { IgJson.parseMedia(it, preferSmaller()) }
        }
        is IgLink.Highlight -> {
            val nodes = IgJson.parseReels(getJson("/api/v1/feed/reels_media/?reel_ids=highlight:${link.highlightId}"))
            if (nodes.isEmpty()) throw ResolveException("Highlight is empty or unavailable")
            nodes.flatMap { IgJson.parseMedia(it, preferSmaller()) }
        }
        is IgLink.Profile -> {
            val user = userInfo(link.username)
            val url = user.optString("profile_pic_url_hd").ifEmpty { user.optString("profile_pic_url") }
            if (url.isEmpty()) throw ResolveException("No profile picture found")
            listOf(
                MediaItem(
                    key = "${user.optString("id")}_pfp", kind = com.luqman.luckysaver.core.MediaKind.IMAGE,
                    url = url, thumbnailUrl = url, width = 0, height = 0, owner = link.username,
                    shortcode = "profile", takenAt = System.currentTimeMillis() / 1000, caption = null,
                )
            )
        }
        is IgLink.Share -> emptyList()
    }

    private suspend fun userInfo(username: String): JSONObject =
        getJson("/api/v1/users/web_profile_info/?username=$username")
            .optJSONObject("data")?.optJSONObject("user")
            ?: throw ResolveException("User @$username not found")

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(IgSession.IG_ORIGIN + path).apply {
            session.headers().forEach { (k, v) -> header(k, v) }
        }.build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val loginWall = resp.code == 401 || resp.code == 403 ||
                body.contains("login_required") || body.contains("checkpoint_required")
            if (loginWall) throw ResolveException("Instagram wants you to log in again", needsLogin = true)
            if (resp.code == 429) throw ResolveException("Rate limited by Instagram. Wait a few minutes.")
            if (resp.code == 404) throw ResolveException("Not found. Post deleted or account private.")
            if (!resp.isSuccessful) throw ResolveException("Instagram returned HTTP ${resp.code}")
            if (!body.trimStart().startsWith("{")) throw ResolveException("Unexpected response (not JSON)", needsLogin = true)
            JSONObject(body)
        }
    }
}

/** Anonymous fallback: scrapes the public embed page. Public posts/reels only. */
class EmbedResolver(private val http: OkHttpClient, private val session: IgSession) : MediaResolver {
    override val name = "embed"
    override fun supports(link: IgLink) = link is IgLink.Post

    override suspend fun resolve(link: IgLink): List<MediaItem> = withContext(Dispatchers.IO) {
        val code = (link as IgLink.Post).shortcode
        val req = Request.Builder()
            .url("${IgSession.IG_ORIGIN}/p/$code/embed/captioned/")
            .header("User-Agent", session.userAgent)
            .build()
        val html = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ResolveException("Embed page HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        fromContextJson(html, code)?.takeIf { it.isNotEmpty() }
            ?: fromImgTag(html, code)
            ?: throw ResolveException("Post is private or embed disabled. Log in to download.", needsLogin = !session.isLoggedIn)
    }

    private fun fromContextJson(html: String, code: String): List<MediaItem>? {
        val marker = "\"contextJSON\":\""
        val start = html.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: return null
        var i = start
        while (i < html.length) {
            if (html[i] == '\\') i += 2 else if (html[i] == '"') break else i++
        }
        return runCatching {
            val decoded = JSONArray("[\"" + html.substring(start, i) + "\"]").getString(0)
            val media = JSONObject(decoded).optJSONObject("gql_data")?.optJSONObject("shortcode_media")
                ?: return null
            IgJson.parseGraphQl(media, code)
        }.getOrNull()
    }

    private fun fromImgTag(html: String, code: String): List<MediaItem>? {
        val src = Regex("""class="EmbeddedMediaImage"[^>]*src="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""src="([^"]+)"[^>]*class="EmbeddedMediaImage"""").find(html)?.groupValues?.get(1)
            ?: return null
        val url = src.replace("&amp;", "&")
        val owner = Regex("""class="UsernameText"[^>]*>([^<]+)<""").find(html)?.groupValues?.get(1) ?: "instagram"
        return listOf(
            MediaItem(
                key = "${Shortcode.toMediaId(code)}_0", kind = com.luqman.luckysaver.core.MediaKind.IMAGE,
                url = url, thumbnailUrl = url, width = 0, height = 0, owner = owner, shortcode = code,
                takenAt = System.currentTimeMillis() / 1000, caption = null,
            )
        )
    }
}

/**
 * Tries resolvers in order; first non-empty result wins. Serialized + throttled so we never
 * hammer Instagram (protects the logged-in account from checkpoints).
 */
class ResolverChain(
    private val http: OkHttpClient,
    private val resolvers: List<MediaResolver>,
    private val minIntervalMs: Long = 2_000,
) {
    private val mutex = Mutex()
    private var lastCall = 0L

    suspend fun resolve(input: String): List<MediaItem> = mutex.withLock {
        var link = IgLinkParser.parse(input) ?: throw ResolveException("Not an Instagram post, reel, story or profile link")
        if (link is IgLink.Share) link = followShare(link.url)

        val candidates = resolvers.filter { it.supports(link) }
        if (candidates.isEmpty()) {
            throw ResolveException("Log in to download stories, highlights and profiles", needsLogin = true)
        }
        var lastError: ResolveException? = null
        for (r in candidates) {
            val wait = lastCall + minIntervalMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastCall = System.currentTimeMillis()
            try {
                val items = r.resolve(link)
                if (items.isNotEmpty()) return@withLock items
            } catch (e: ResolveException) {
                lastError = e
            } catch (e: IOException) {
                lastError = ResolveException("Network error: ${e.message}", cause = e)
            } catch (e: Exception) {
                lastError = ResolveException("Couldn't parse Instagram response (${r.name}): ${e.message}", cause = e)
            }
        }
        throw lastError ?: ResolveException("Nothing downloadable found")
    }

    private suspend fun followShare(url: String): IgLink = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).head().build()
        val finalUrl = http.newCall(req).execute().use { it.request.url.toString() }
        IgLinkParser.parse(finalUrl)?.takeIf { it !is IgLink.Share }
            ?: throw ResolveException("Couldn't expand share link")
    }
}
