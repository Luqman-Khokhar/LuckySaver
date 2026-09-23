package com.luqman.luckysaver.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.luqman.luckysaver.App
import com.luqman.luckysaver.MainActivity
import com.luqman.luckysaver.R
import com.luqman.luckysaver.core.IgLinkParser
import com.luqman.luckysaver.core.ResolveException
import com.luqman.luckysaver.download.DownloadNotifications
import com.luqman.luckysaver.download.DownloadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Floating download button drawn over other apps, so a link copied in Instagram can be saved
 * without leaving Instagram.
 *
 * Clipboard access is the constraint that shapes this: since Android 10 only the focused app can
 * read the clipboard, so the bubble cannot watch for copies in the background. Tapping it briefly
 * makes the overlay window focusable, which earns one legitimate read.
 */
class BubbleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var bubble: BubbleView? = null
    private var watchJob: Job? = null
    private var params: WindowManager.LayoutParams? = null
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        startBubbleForeground(serviceNotification())
        addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watchJob?.cancel()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        scope.cancel()
        super.onDestroy()
    }

    private fun addBubble() {
        if (!canDrawOverlay(this)) {
            stopSelf()
            return
        }
        val prefs = prefs(this)
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            UNFOCUSED_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, 0)
            y = prefs.getInt(KEY_Y, 400)
        }
        val view = BubbleView(this, R.drawable.ic_launcher).apply {
            contentDescription = getString(R.string.bubble_description)
        }
        view.setOnTouchListener(dragListener(layout))
        runCatching { windowManager.addView(view, layout) }
            .onFailure { stopSelf(); return }
        bubble = view
        params = layout
    }

    /** Drag to move, tap (little movement, short press) to download. */
    private fun dragListener(layout: WindowManager.LayoutParams) = object : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var downAt = 0L

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layout.x
                    startY = layout.y
                    touchX = event.rawX
                    touchY = event.rawY
                    downAt = System.currentTimeMillis()
                    (v as BubbleView).pressed(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = startX + (event.rawX - touchX).toInt()
                    layout.y = startY + (event.rawY - touchY).toInt()
                    runCatching { windowManager.updateViewLayout(v, layout) }
                }
                MotionEvent.ACTION_UP -> {
                    (v as BubbleView).pressed(false)
                    val moved = abs(event.rawX - touchX) > TAP_SLOP || abs(event.rawY - touchY) > TAP_SLOP
                    if (moved) {
                        prefs(this@BubbleService).edit()
                            .putInt(KEY_X, layout.x).putInt(KEY_Y, layout.y).apply()
                    } else if (System.currentTimeMillis() - downAt < LONG_PRESS_MS) {
                        onBubbleTapped()
                    } else {
                        v.performClick()
                    }
                }
            }
            return true
        }
    }

    private fun onBubbleTapped() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val link = readClipboard()
                if (link.isNullOrBlank()) {
                    fail("Copy an Instagram link first", notify = false)
                    return@launch
                }
                if (IgLinkParser.parse(link) == null) {
                    fail("Clipboard has no Instagram link", notify = false)
                    return@launch
                }
                showWorking()
                val app = applicationContext as App
                val items = app.resolver.resolve(link)
                val saved = app.db.downloads().existingKeys(items.map { it.key }).toSet()
                val fresh = items.filter { it.key !in saved }
                if (fresh.isEmpty()) {
                    succeed("Already saved")
                    return@launch
                }
                val ids = DownloadWorker.enqueueAll(applicationContext, fresh)
                toast("Downloading ${fresh.size} file${if (fresh.size == 1) "" else "s"}")
                bubble?.setState(BubbleView.State.Progress(0f))
                watchDownloads(ids)
            } catch (e: ResolveException) {
                fail(e.message ?: "Couldn't fetch that post")
            } catch (e: Exception) {
                fail(e.message ?: "Something went wrong")
            } finally {
                busy = false
            }
        }
    }

    /**
     * Only the focused window may read the clipboard, so the overlay takes focus for a moment.
     * The short delay lets the window manager actually hand focus over before we read.
     */
    private suspend fun readClipboard(): String? {
        val view = bubble ?: return null
        val layout = params ?: return null
        layout.flags = FOCUSED_FLAGS
        runCatching { windowManager.updateViewLayout(view, layout) }
        delay(FOCUS_SETTLE_MS)
        val text = withContext(Dispatchers.Default) {
            runCatching {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                    ?.coerceToText(this@BubbleService)?.toString()
            }.getOrNull()
        }
        layout.flags = UNFOCUSED_FLAGS
        runCatching { windowManager.updateViewLayout(view, layout) }
        return text
    }

    private fun showWorking() {
        bubble?.setState(BubbleView.State.Working)
    }

    /**
     * Follows the queued work and turns it into one ring fraction: finished files count as whole,
     * the running one contributes its own percentage.
     */
    private fun watchDownloads(ids: List<java.util.UUID>) {
        watchJob?.cancel()
        if (ids.isEmpty()) return
        watchJob = scope.launch {
            WorkManager.getInstance(applicationContext)
                .getWorkInfosFlow(WorkQuery.fromIds(ids))
                .collect { infos ->
                    val done = infos.count { it.state.isFinished }
                    val running = infos.filter { it.state == WorkInfo.State.RUNNING }
                        .sumOf { it.progress.getInt(DownloadWorker.K_PROGRESS, 0) } / 100f
                    val fraction = ((done + running) / infos.size).coerceIn(0f, 1f)
                    if (infos.all { it.state.isFinished }) {
                        val failed = infos.count { it.state == WorkInfo.State.FAILED }
                        if (failed == infos.size) {
                            fail("Download failed", notify = false)
                        } else {
                            bubble?.setState(BubbleView.State.Success)
                            resetLater()
                        }
                        watchJob?.cancel()
                    } else {
                        bubble?.setState(BubbleView.State.Progress(fraction))
                    }
                }
        }
    }

    private fun succeed(message: String) {
        busy = false
        bubble?.setState(BubbleView.State.Success)
        toast(message)
        resetLater()
    }

    private fun fail(message: String, notify: Boolean = true) {
        busy = false
        bubble?.setState(BubbleView.State.Error)
        toast(message)
        if (notify) DownloadNotifications.resolveFailed(this, message)
        resetLater()
    }

    private fun resetLater() {
        scope.launch {
            delay(RESET_DELAY_MS)
            if (!busy && watchJob?.isActive != true) bubble?.setState(BubbleView.State.Idle)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun serviceNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Floating button", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Floating button is on")
            .setContentText("Copy an Instagram link, then tap the bubble")
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun startBubbleForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "bubble"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.luqman.luckysaver.STOP_BUBBLE"
        private const val PREFS = "bubble"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val TAP_SLOP = 16f
        private const val LONG_PRESS_MS = 600
        private const val FOCUS_SETTLE_MS = 120L
        private const val RESET_DELAY_MS = 2_500L

        private const val UNFOCUSED_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        /** Focusable, but still not stealing touches from the app underneath. */
        private const val FOCUSED_FLAGS = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun isEnabled(context: Context): Boolean =
            prefs(context).getBoolean(KEY_ENABLED, false) && canDrawOverlay(context)

        private fun setEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun start(context: Context) {
            setEnabled(context, true)
            context.startForegroundService(Intent(context, BubbleService::class.java))
        }

        fun stop(context: Context) {
            setEnabled(context, false)
            context.stopService(Intent(context, BubbleService::class.java))
        }

        fun overlaySettingsIntent(context: Context) = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}"),
        )
    }
}
