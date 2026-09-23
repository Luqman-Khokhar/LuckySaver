package com.luqman.luckysaver

import android.app.Application
import android.webkit.CookieManager
import com.luqman.luckysaver.data.AppDatabase
import com.luqman.luckysaver.data.Quality
import com.luqman.luckysaver.data.SettingsStore
import com.luqman.luckysaver.download.DownloadNotifications
import com.luqman.luckysaver.resolve.ApiResolver
import com.luqman.luckysaver.resolve.EmbedResolver
import com.luqman.luckysaver.resolve.IgSession
import com.luqman.luckysaver.resolve.RemoteConfig
import com.luqman.luckysaver.resolve.ResolverChain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual DI container. Small app; no Hilt needed. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Loads the WebView cookie store up front, so isLoggedIn is correct on the first frame.
        runCatching { CookieManager.getInstance().setAcceptCookie(true) }
        // Endpoint drift is the usual reason this app breaks; pick up any fix without a rebuild.
        scope.launch { remoteConfig.refresh() }
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
    val session: IgSession by lazy { IgSession(this) }
    val db: AppDatabase by lazy { AppDatabase.build(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }

    /** For work that has to outlive the screen that started it, such as a share with no UI. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val remoteConfig: RemoteConfig by lazy { RemoteConfig(this, http) }
    val resolver: ResolverChain by lazy {
        ResolverChain(
            http,
            listOf(
                ApiResolver(
                    http, session,
                    endpoints = { remoteConfig.endpoints },
                    preferSmaller = { settings.current.quality == Quality.SMALLER },
                ),
                EmbedResolver(http, session) { remoteConfig.endpoints },
            ),
            onSessionExpired = {
                session.markExpired()
                DownloadNotifications.sessionExpired(this)
            },
        )
    }
}
