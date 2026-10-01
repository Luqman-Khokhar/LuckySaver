package com.luqman.luckysaver

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luqman.luckysaver.download.DownloadNotifications
import com.luqman.luckysaver.ui.AppShell
import com.luqman.luckysaver.ui.LibraryViewModel
import com.luqman.luckysaver.ui.LoginScreen
import com.luqman.luckysaver.ui.LuckyTheme
import com.luqman.luckysaver.ui.MainViewModel
import com.luqman.luckysaver.ui.StoriesViewModel
import com.luqman.luckysaver.ui.WelcomeScreen
import com.luqman.luckysaver.ui.isAppInDarkTheme

private enum class Screen { WELCOME, LOGIN, MAIN }

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val library: LibraryViewModel by viewModels()
    private val stories: StoriesViewModel by viewModels()
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val notifGranted = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!notifGranted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) handleShare(intent)

        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val dark = isAppInDarkTheme(settings.darkMode)
            // System bar icons follow the app's theme, which can differ from the phone's.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            LuckyTheme(settings.palette, dark) {
                // Land on the welcome screen until there is a session, so the login requirement
                // is stated before the first fetch fails.
                var screen by rememberSaveable {
                    mutableStateOf(
                        when {
                            intent?.getBooleanExtra(DownloadNotifications.EXTRA_OPEN_LOGIN, false) == true ->
                                Screen.LOGIN
                            vm.loggedIn.value -> Screen.MAIN
                            else -> Screen.WELCOME
                        }
                    )
                }
                val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()

                BackHandler(enabled = screen == Screen.LOGIN) {
                    screen = if (loggedIn) Screen.MAIN else Screen.WELCOME
                }
                Surface(color = MaterialTheme.colorScheme.background) {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                        label = "root",
                    ) { s ->
                        when (s) {
                            Screen.WELCOME -> WelcomeScreen(
                                onLogin = { screen = Screen.LOGIN },
                                onSkip = { screen = Screen.MAIN },
                            )
                            Screen.LOGIN -> LoginScreen(
                                onDone = {
                                    vm.onLoggedIn()
                                    screen = if (vm.loggedIn.value) Screen.MAIN else Screen.WELCOME
                                },
                            )
                            Screen.MAIN -> AppShell(
                                main = vm,
                                library = library,
                                stories = stories,
                                batteryRestricted = !isIgnoringBatteryOptimizations(),
                                onLogin = { screen = Screen.LOGIN },
                                onBubble = { on ->
                                    // Drawing over other apps is a special permission: send the user to
                                    // the system screen when it has not been granted yet.
                                    if (!vm.toggleBubble(on)) {
                                        startActivity(com.luqman.luckysaver.overlay.BubbleService.overlaySettingsIntent(this@MainActivity))
                                    }
                                },
                                onFixBattery = ::requestBatteryExemption,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshLogin()
        // Reading the clipboard is only allowed while focused, which is exactly now.
        val clip = getSystemService(ClipboardManager::class.java)
        vm.onClipboard(
            clip?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        )
    }

    override fun onPause() {
        super.onPause()
        // Instagram rotates cookies as you browse; without an explicit flush they stay in memory
        // and a cold start looks logged out.
        android.webkit.CookieManager.getInstance().flush()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /** Scheduled checks are frozen by aggressive battery management on many phones. */
    private fun requestBatteryExemption() {
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        }
    }

    private fun handleShare(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!text.isNullOrBlank()) vm.onShared(text)
    }
}
