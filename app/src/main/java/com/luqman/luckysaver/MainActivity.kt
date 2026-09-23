package com.luqman.luckysaver

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luqman.luckysaver.ui.HistoryScreen
import com.luqman.luckysaver.ui.HomeScreen
import com.luqman.luckysaver.ui.LoginScreen
import com.luqman.luckysaver.ui.MainViewModel
import com.luqman.luckysaver.ui.SettingsScreen
import com.luqman.luckysaver.ui.WelcomeScreen

private enum class Screen { WELCOME, HOME, LOGIN, HISTORY, SETTINGS }

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val notifGranted = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!notifGranted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) handleShare(intent)

        setContent {
            val ctx = LocalContext.current
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = colors) {
                // Land on the welcome screen until there is a session, so the login requirement
                // is stated before the first fetch fails.
                var screen by rememberSaveable {
                    mutableStateOf(if (vm.loggedIn.value) Screen.HOME else Screen.WELCOME)
                }
                val input by vm.input.collectAsStateWithLifecycle()
                val state by vm.state.collectAsStateWithLifecycle()
                val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
                val queue by vm.queue.collectAsStateWithLifecycle()
                val message by vm.message.collectAsStateWithLifecycle()
                val bubbleOn by vm.bubbleOn.collectAsStateWithLifecycle()
                val undoable by vm.undoable.collectAsStateWithLifecycle()
                val settings by vm.settings.collectAsStateWithLifecycle()
                val clipboardLink by vm.clipboardLink.collectAsStateWithLifecycle()
                val history by vm.history.collectAsStateWithLifecycle()

                BackHandler(enabled = screen != Screen.HOME && screen != Screen.WELCOME) {
                    screen = if (loggedIn || screen != Screen.LOGIN) Screen.HOME else Screen.WELCOME
                }
                when (screen) {
                    Screen.WELCOME -> WelcomeScreen(
                        onLogin = { screen = Screen.LOGIN },
                        onSkip = { screen = Screen.HOME },
                    )
                    Screen.HOME -> HomeScreen(
                        input = input, state = state, loggedIn = loggedIn, queue = queue, message = message,
                        bubbleOn = bubbleOn,
                        onToggleBubble = { on ->
                            // Drawing over other apps is a special permission: send the user to
                            // the system screen when it has not been granted yet.
                            if (!vm.toggleBubble(on)) {
                                startActivity(com.luqman.luckysaver.overlay.BubbleService.overlaySettingsIntent(this))
                            }
                        },
                        onInput = vm::onInput, onResolve = vm::resolve, onToggle = vm::toggle,
                        onSelectAll = vm::selectAll, onDownload = vm::downloadSelected,
                        onLogin = { screen = Screen.LOGIN }, onLogout = vm::logout,
                        onHistory = { screen = Screen.HISTORY }, onClearFailed = vm::clearFailed,
                        onSettings = { screen = Screen.SETTINGS },
                        onUndo = if (undoable.isNotEmpty()) vm::undoLastBatch else null,
                        clipboardLink = clipboardLink,
                        onUseClipboard = vm::useClipboardLink,
                        onDismissClipboard = vm::dismissClipboard,
                        onMessageShown = vm::consumeMessage,
                    )
                    Screen.LOGIN -> LoginScreen(
                        onDone = {
                            vm.refreshLogin()
                            screen = if (vm.loggedIn.value) Screen.HOME else Screen.WELCOME
                        },
                    )
                    Screen.HISTORY -> HistoryScreen(history, onBack = { screen = Screen.HOME }, onForget = vm::forget)
                    Screen.SETTINGS -> SettingsScreen(
                        settings = settings,
                        onChange = vm::updateSettings,
                        onBack = { screen = Screen.HOME },
                    )
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

    private fun handleShare(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!text.isNullOrBlank()) vm.onShared(text)
    }
}
