package com.luqman.luckysaver

import android.Manifest
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

private enum class Screen { HOME, LOGIN, HISTORY }

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
                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                val input by vm.input.collectAsStateWithLifecycle()
                val state by vm.state.collectAsStateWithLifecycle()
                val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
                val queue by vm.queue.collectAsStateWithLifecycle()
                val message by vm.message.collectAsStateWithLifecycle()
                val history by vm.history.collectAsStateWithLifecycle()

                BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
                when (screen) {
                    Screen.HOME -> HomeScreen(
                        input = input, state = state, loggedIn = loggedIn, queue = queue, message = message,
                        onInput = vm::onInput, onResolve = vm::resolve, onToggle = vm::toggle,
                        onSelectAll = vm::selectAll, onDownload = vm::downloadSelected,
                        onLogin = { screen = Screen.LOGIN }, onLogout = vm::logout,
                        onHistory = { screen = Screen.HISTORY }, onClearFailed = vm::clearFailed,
                        onMessageShown = vm::consumeMessage,
                    )
                    Screen.LOGIN -> LoginScreen(onDone = { vm.refreshLogin(); screen = Screen.HOME })
                    Screen.HISTORY -> HistoryScreen(history, onBack = { screen = Screen.HOME }, onForget = vm::forget)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshLogin()
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
