package com.luqman.luckysaver.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import com.luqman.luckysaver.App
import com.luqman.luckysaver.BuildConfig

/**
 * Real instagram.com login inside a WebView. We never see the password; we only read the
 * session cookies Instagram sets. Closes itself once `sessionid` appears.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onDone: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    val session = (LocalContext.current.applicationContext as App).session
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log in to Instagram") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    // Remote inspection is a debug-only affordance; a release build must not
                    // expose the logged-in session to anything attached over adb.
                    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = session.userAgent
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        // Instagram's login page composites to an empty layer under GPU raster on
                        // some devices (blank/black WebView); software layer renders it correctly.
                        setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                        setBackgroundColor(android.graphics.Color.WHITE)
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                                Log.d(TAG, "console: ${msg.message()}")
                                return true
                            }
                        }
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                loading = true
                            }
                            override fun onReceivedError(
                                view: WebView, request: WebResourceRequest, error: WebResourceError,
                            ) {
                                Log.w(TAG, "error ${error.errorCode} ${error.description} on ${request.url}")
                            }
                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                CookieManager.getInstance().flush()
                                val cookies = CookieManager.getInstance().getCookie("https://www.instagram.com").orEmpty()
                                if ("sessionid=" in cookies && "/accounts/login" !in url.orEmpty()) onDone()
                            }
                        }
                        loadUrl("https://www.instagram.com/accounts/login/")
                        Log.d(TAG, "UA=${session.userAgent}")
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}

private const val TAG = "LuckySaverLogin"
