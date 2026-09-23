package com.luqman.luckysaver.resolve

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Endpoint paths and headers, refreshed from a small JSON file in the project repo.
 *
 * Instagram moves these regularly, and every move otherwise means building and sideloading a new
 * APK. Values are cached on the device, fall back to the versions compiled in, and a broken or
 * unreachable file changes nothing.
 */
data class IgEndpoints(
    val appId: String = IgSession.IG_WEB_APP_ID,
    val userAgent: String = IgSession.DESKTOP_UA,
    val mediaInfo: String = "/api/v1/media/{id}/info/",
    val userInfo: String = "/api/v1/users/web_profile_info/?username={username}",
    val reels: String = "/api/v1/feed/reels_media/?reel_ids={ids}",
    val embed: String = "/p/{code}/embed/captioned/",
    val minRequestIntervalMs: Long = 2_000,
) {
    fun mediaInfoPath(id: String) = mediaInfo.replace("{id}", id)
    fun userInfoPath(username: String) = userInfo.replace("{username}", username)
    fun reelsPath(ids: String) = reels.replace("{ids}", ids)
    fun embedPath(code: String) = embed.replace("{code}", code)
}

class RemoteConfig(private val context: Context, private val http: OkHttpClient) {

    @Volatile
    var endpoints: IgEndpoints = read()
        private set

    /** Called at startup; failures are silent by design, the built-in values still work. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val stale = System.currentTimeMillis() - prefs().getLong(KEY_FETCHED_AT, 0) > MAX_AGE_MS
        if (!stale) return@withContext
        runCatching {
            val body = http.newCall(Request.Builder().url(CONFIG_URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext
                resp.body?.string().orEmpty()
            }
            val parsed = parse(JSONObject(body))
            prefs().edit().putString(KEY_JSON, body).putLong(KEY_FETCHED_AT, System.currentTimeMillis()).apply()
            endpoints = parsed
        }
        Unit
    }

    private fun read(): IgEndpoints = runCatching {
        prefs().getString(KEY_JSON, null)?.let { parse(JSONObject(it)) }
    }.getOrNull() ?: IgEndpoints()

    private fun parse(json: JSONObject): IgEndpoints {
        val fallback = IgEndpoints()
        fun str(key: String, default: String) = json.optString(key).takeIf { it.isNotBlank() } ?: default
        return IgEndpoints(
            appId = str("appId", fallback.appId),
            userAgent = str("userAgent", fallback.userAgent),
            mediaInfo = str("mediaInfo", fallback.mediaInfo),
            userInfo = str("userInfo", fallback.userInfo),
            reels = str("reels", fallback.reels),
            embed = str("embed", fallback.embed),
            minRequestIntervalMs = json.optLong("minRequestIntervalMs", fallback.minRequestIntervalMs)
                .coerceAtLeast(500),
        )
    }

    private fun prefs() = context.getSharedPreferences("remote_config", Context.MODE_PRIVATE)

    private companion object {
        const val CONFIG_URL =
            "https://raw.githubusercontent.com/Luqman-Khokhar/LuckySaver/main/config/endpoints.json"
        const val KEY_JSON = "json"
        const val KEY_FETCHED_AT = "fetched_at"
        const val MAX_AGE_MS = 12 * 60 * 60 * 1000L
    }
}
