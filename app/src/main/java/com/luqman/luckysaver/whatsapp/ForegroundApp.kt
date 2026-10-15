package com.luqman.luckysaver.whatsapp

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings

/**
 * Which app is on screen, from Android's usage events. Needs the special "Usage access" grant,
 * which only the user can give from system settings. Without it [current] returns null and the
 * bubble falls back to the mode picked by hand.
 *
 * Reads events incrementally: each call only asks for what happened since the previous one.
 */
class ForegroundApp(private val context: Context) {
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private var lastQuery = 0L
    private var last: String? = null

    fun current(): String? {
        if (!hasAccess(context)) return null
        val now = System.currentTimeMillis()
        val from = if (lastQuery == 0L) now - FIRST_WINDOW_MS else lastQuery - OVERLAP_MS
        val events = runCatching { usage.queryEvents(from, now) }.getOrNull() ?: return last
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = event.packageName
        }
        lastQuery = now
        return last
    }

    companion object {
        private const val FIRST_WINDOW_MS = 60 * 60_000L
        /** Events can land slightly after their timestamp; re-reading a second loses none. */
        private const val OVERLAP_MS = 1_000L

        // The non-deprecated replacement needs API 30; this one covers minSdk 29 up.
        @Suppress("DEPRECATION")
        fun hasAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            return ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }

        /** This app's own page where the phone supports it, the full list where it doesn't. */
        fun openSettings(context: Context) {
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(flags)
                )
            }.recoverCatching {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(flags))
            }
        }
    }
}
