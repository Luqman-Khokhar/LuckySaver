package com.luqman.luckysaver.resolve

import com.luqman.luckysaver.core.IgJson
import com.luqman.luckysaver.core.IgLink
import com.luqman.luckysaver.core.IgLinkParser
import com.luqman.luckysaver.core.MediaItem
import com.luqman.luckysaver.core.FailureKind
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

/** Instagram rarely sends Retry-After; five minutes is the usual soft-block window. */
private const val DEFAULT_COOLDOWN_MS = 5 * 60 * 1000L

interface MediaResolver {
    val name: String
    fun supports(link: IgLink): Boolean
    suspend fun resolve(link: IgLink): List<MediaItem>
}

/** Logged-in private web API. Handles posts, reels, stories, highlights, profile pictures. */
class ApiResolver(
    private val http: OkHttpClient,
    private val session: IgSession,
    private val endpoints: () -> IgEndpoints = { IgEndpoints() },
    private val preferSmaller: () -> Boolean = { false },
) : MediaResolver {
    override val name = "api"
    override fun supports(link: IgLink) = session.isLoggedIn && link !is IgLink.Share

    override suspend fun resolve(link: IgLink): List<MediaItem> = when (link) {
        is IgLink.Post -> {
            val root = getJson(endpoints().mediaInfoPath(Shortcode.toMediaId(link.shortcode)))
            val items = root.optJSONArray("items")
                ?: throw ResolveException("Instagram returned nothing for that post", FailureKind.UNREADABLE)
            (0 until items.length()).flatMap { IgJson.parseMedia(items.getJSONObject(it), preferSmaller()) }
        }
        is IgLink.Story -> {
            val userId = userInfo(link.username).getString("id")
            val nodes = IgJson.parseReels(getJson(endpoints().reelsPath(userId)))
            if (nodes.isEmpty()) throw ResolveException(
                "@${link.username} has no stories right now, or you can't see them",
                FailureKind.PRIVATE_ACCOUNT,
            )
            val picked = link.storyPk?.let { pk -> nodes.filter { it.optString("pk") == pk }.ifEmpty { nodes } } ?: nodes
            picked.flatMap { IgJson.parseMedia(it, preferSmaller()) }
        }
        is IgLink.Highlight -> {
            val nodes = IgJson.parseReels(getJson(endpoints().reelsPath("highlight:${link.highlightId}")))
            if (nodes.isEmpty()) throw ResolveException(
                "That highlight is empty or not visible to you", FailureKind.PRIVATE_ACCOUNT,
            )
            nodes.flatMap { IgJson.parseMedia(it, preferSmaller()) }
        }
        is IgLink.Profile -> {
            val user = userInfo(link.username)
            val url = user.optString("profile_pic_url_hd").ifEmpty { user.optString("profile_pic_url") }
            if (url.isEmpty()) throw ResolveException("That account has no profile picture", FailureKind.NOT_FOUND)
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

    /**
     * Stories for several accounts in one call. The endpoint accepts a comma-separated reel_ids,
     * so watching ten accounts costs one request rather than ten — which matters a great deal
     * when this runs on a timer.
     */
    suspend fun storiesFor(userIds: List<String>): List<MediaItem> {
        if (userIds.isEmpty()) return emptyList()
        val root = getJson(endpoints().reelsPath(userIds.joinToString(",")))
        return IgJson.parseReels(root).flatMap { IgJson.parseMedia(it, preferSmaller()) }
    }

    /** Resolves a username to the numeric id, which is what the watchlist stores. */
    suspend fun lookupUser(username: String): Pair<String, String> {
        val user = userInfo(username.removePrefix("@").trim())
        return user.getString("id") to user.optString("username").ifEmpty { username }
    }

    private suspend fun userInfo(username: String): JSONObject =
        getJson(endpoints().userInfoPath(username))
            .optJSONObject("data")?.optJSONObject("user")
            ?: throw ResolveException("There is no account called @$username", FailureKind.NOT_FOUND)

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(IgSession.IG_ORIGIN + path).apply {
            session.headers(endpoints().appId, endpoints().userAgent).forEach { (k, v) -> header(k, v) }
        }.build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val loginWall = resp.code == 401 || resp.code == 403 ||
                body.contains("login_required") || body.contains("checkpoint_required")
            when {
                loginWall -> throw ResolveException(
                    "Instagram signed this device out", FailureKind.SESSION_EXPIRED,
                )
                resp.code == 429 -> {
                    val wait = resp.header("Retry-After")?.toLongOrNull()?.times(1000) ?: DEFAULT_COOLDOWN_MS
                    throw ResolveException(
                        "Instagram is rate limiting us. Waiting ${wait / 60_000} min before trying again.",
                        FailureKind.RATE_LIMITED, retryAfterMs = wait,
                    )
                }
                resp.code == 404 -> throw ResolveException(
                    "That post no longer exists, or the account is private to you",
                    FailureKind.NOT_FOUND,
                )
                !resp.isSuccessful -> throw ResolveException(
                    "Instagram returned HTTP ${resp.code}", FailureKind.UNKNOWN,
                )
                !body.trimStart().startsWith("{") -> throw ResolveException(
                    "Instagram sent a page instead of data, which usually means the session is stale",
                    FailureKind.SESSION_EXPIRED,
                )
            }
            JSONObject(body)
        }
    }
}

/** Anonymous fallback: scrapes the public embed page. Public posts/reels only. */
class EmbedResolver(
    private val http: OkHttpClient,
    private val session: IgSession,
    private val endpointsFor: () -> IgEndpoints = { IgEndpoints() },
) : MediaResolver {
    override val name = "embed"
    override fun supports(link: IgLink) = link is IgLink.Post

    override suspend fun resolve(link: IgLink): List<MediaItem> = withContext(Dispatchers.IO) {
        val code = (link as IgLink.Post).shortcode
        val req = Request.Builder()
            .url(IgSession.IG_ORIGIN + endpointsFor().embedPath(code))
            .header("User-Agent", session.userAgent)
            .build()
        val html = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ResolveException(
                "Embed page returned HTTP ${resp.code}", FailureKind.UNREADABLE,
            )
            resp.body?.string().orEmpty()
        }
        fromContextJson(html, code)?.takeIf { it.isNotEmpty() }
            ?: fromImgTag(html, code)
            ?: throw ResolveException(
                if (session.isLoggedIn) "That post can't be read without a session that can see it"
                else "Log in to download — Instagram no longer serves posts to signed-out apps",
                if (session.isLoggedIn) FailureKind.PRIVATE_ACCOUNT else FailureKind.SESSION_EXPIRED,
            )
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
    /** Fired once the session is known to be dead, so the app can ask for a fresh login. */
    private val onSessionExpired: () -> Unit = {},
) {
    private val mutex = Mutex()
    private var lastCall = 0L
    private var cooldownUntil = 0L

    private val api: ApiResolver? get() = resolvers.filterIsInstance<ApiResolver>().firstOrNull()

    val isRateLimited: Boolean get() = cooldownUntil > System.currentTimeMillis()

    /** Watchlist check: same throttle and cooldown as everything else on this chain. */
    suspend fun storiesFor(userIds: List<String>): List<MediaItem> = mutex.withLock {
        val resolver = api?.takeIf { it.supports(IgLink.Story("", null)) }
            ?: throw ResolveException("Log in to check stories", FailureKind.SESSION_EXPIRED)
        guardCooldown()
        throttle()
        try {
            resolver.storiesFor(userIds)
        } catch (e: ResolveException) {
            if (e.kind == FailureKind.RATE_LIMITED) cooldownUntil = System.currentTimeMillis() + e.retryAfterMs
            if (e.kind == FailureKind.SESSION_EXPIRED) onSessionExpired()
            throw e
        }
    }

    suspend fun lookupUser(username: String): Pair<String, String> = mutex.withLock {
        val resolver = api ?: throw ResolveException("Log in first", FailureKind.SESSION_EXPIRED)
        guardCooldown()
        throttle()
        resolver.lookupUser(username)
    }

    private fun guardCooldown() {
        val until = cooldownUntil
        if (until > System.currentTimeMillis()) {
            val left = (until - System.currentTimeMillis()) / 1000
            throw ResolveException(
                "Instagram asked us to slow down. Try again in ${left / 60}m ${left % 60}s.",
                FailureKind.RATE_LIMITED, retryAfterMs = until - System.currentTimeMillis(),
            )
        }
    }

    private suspend fun throttle() {
        val wait = lastCall + minIntervalMs - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastCall = System.currentTimeMillis()
    }

    suspend fun resolve(input: String): List<MediaItem> = mutex.withLock {
        guardCooldown()
        var link = IgLinkParser.parse(input)
            ?: throw ResolveException(
                "That isn't an Instagram post, reel, story or profile link", FailureKind.UNSUPPORTED_LINK,
            )
        if (link is IgLink.Share) link = followShare(link.url)

        val candidates = resolvers.filter { it.supports(link) }
        if (candidates.isEmpty()) {
            throw ResolveException(
                "Log in to download stories, highlights and profiles", FailureKind.SESSION_EXPIRED,
            )
        }
        var lastError: ResolveException? = null
        for (r in candidates) {
            throttle()
            try {
                val items = r.resolve(link)
                if (items.isNotEmpty()) return@withLock items
            } catch (e: ResolveException) {
                lastError = e
                // Backing off is the whole point of a 429; trying the next resolver makes it worse.
                if (e.kind == FailureKind.RATE_LIMITED) {
                    cooldownUntil = System.currentTimeMillis() + e.retryAfterMs
                    break
                }
                if (e.kind == FailureKind.SESSION_EXPIRED) break
            } catch (e: IOException) {
                lastError = ResolveException(
                    "No connection to Instagram. Check your network.", FailureKind.NETWORK, cause = e,
                )
            } catch (e: Exception) {
                lastError = ResolveException(
                    "Instagram's response didn't look the way this app expects (${r.name})",
                    FailureKind.UNREADABLE, cause = e,
                )
            }
        }
        lastError?.takeIf { it.needsLogin }?.let { onSessionExpired() }
        throw lastError ?: ResolveException("Nothing downloadable found")
    }

    private suspend fun followShare(url: String): IgLink = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).head().build()
        val finalUrl = http.newCall(req).execute().use { it.request.url.toString() }
        IgLinkParser.parse(finalUrl)?.takeIf { it !is IgLink.Share }
            ?: throw ResolveException("Couldn't expand that share link", FailureKind.UNSUPPORTED_LINK)
    }
}
