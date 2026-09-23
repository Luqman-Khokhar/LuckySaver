package com.luqman.luckysaver.resolve

import android.content.Context
import android.webkit.CookieManager

/**
 * Login state lives in the WebView cookie jar: the user logs in on instagram.com inside
 * [com.luqman.luckysaver.ui.LoginScreen] and we reuse those cookies for API calls.
 */
class IgSession(@Suppress("unused") context: Context) {
    /**
     * Desktop UA on purpose. Instagram's mobile-web login is a Bloks page that lays out but
     * never paints inside a WebView (blank screen); the desktop page renders fine. The same UA
     * must be used for the API calls, otherwise the cookies we captured get rejected.
     */
    val userAgent: String = DESKTOP_UA

    private fun cookieString(): String? = CookieManager.getInstance().getCookie(IG_ORIGIN)

    private fun cookie(name: String): String? = cookieString()
        ?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotEmpty() }

    val isLoggedIn: Boolean get() = cookie("sessionid") != null && cookie("ds_user_id") != null

    fun headers(): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        put("X-IG-App-ID", IG_WEB_APP_ID)
        put("X-Requested-With", "XMLHttpRequest")
        put("X-ASBD-ID", "129477")
        put("Referer", "$IG_ORIGIN/")
        put("Origin", IG_ORIGIN)
        put("Accept", "*/*")
        cookie("csrftoken")?.let { put("X-CSRFToken", it) }
        cookieString()?.let { put("Cookie", it) }
    }

    fun logout() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
    }

    companion object {
        const val IG_ORIGIN = "https://www.instagram.com"
        /** Public app id the instagram.com web client sends. Change here if IG rotates it. */
        const val IG_WEB_APP_ID = "936619743392459"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    }
}
