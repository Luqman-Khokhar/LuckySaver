package com.luqman.luckysaver.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat

/**
 * Notifications for the download queue.
 *
 * Two channels on purpose: the ongoing progress notification stays quiet at IMPORTANCE_LOW, while
 * start/finish/failure use IMPORTANCE_HIGH so they surface as a heads-up popup. Otherwise the only
 * way to know a download started is to pull down the shade.
 */
object DownloadNotifications {

    private const val CHANNEL_PROGRESS = "downloads"
    private const val CHANNEL_STATUS = "download_status"
    private const val ID_STARTED = 1_000
    private const val ID_SESSION = 1_001
    private const val ID_STORIES = 1_002

    const val EXTRA_OPEN_LOGIN = "open_login"

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun ensureChannels(context: Context) {
        manager(context).createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
        )
        manager(context).createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Download status", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Pops up when a download starts, finishes or fails"
            }
        )
    }

    private fun builder(context: Context, channel: String): NotificationCompat.Builder {
        ensureChannels(context)
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download)
    }

    /** [pct] below zero renders an indeterminate bar, for when the size isn't known yet. */
    fun progress(context: Context, fileName: String, pct: Int, totalBytes: Long): Notification =
        builder(context, CHANNEL_PROGRESS)
            .setContentTitle(if (pct < 0) "Downloading" else "Downloading  $pct%")
            .setContentText(if (totalBytes > 0) "$fileName · ${totalBytes / 1024 / 1024} MB" else fileName)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct.coerceAtLeast(0), pct < 0)
            .build()

    /** One popup per batch, so a carousel doesn't fire a dozen of them. */
    fun started(context: Context, count: Int) {
        val what = if (count == 1) "1 file" else "$count files"
        notify(
            context, ID_STARTED,
            builder(context, CHANNEL_STATUS)
                .setContentTitle("Download started")
                .setContentText("Saving $what to LuckySaver")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setTimeoutAfter(10_000)
                .build(),
        )
    }

    fun saved(context: Context, id: Int, fileName: String, uri: Uri, isVideo: Boolean) {
        val open = PendingIntent.getActivity(
            context, id,
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, if (isVideo) "video/*" else "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        notify(
            context, id,
            builder(context, CHANNEL_STATUS)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Download complete")
                .setContentText("$fileName · tap to open")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    fun failed(context: Context, id: Int, fileName: String, reason: String) {
        notify(
            context, id,
            builder(context, CHANNEL_STATUS)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Download failed")
                .setContentText("$fileName · $reason")
                .setStyle(NotificationCompat.BigTextStyle().bigText("$fileName\n$reason"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Resolving failed, so there is no file to name yet. */
    fun resolveFailed(context: Context, reason: String) {
        notify(
            context, ID_STARTED,
            builder(context, CHANNEL_STATUS)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Couldn't fetch that post")
                .setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun alreadySaved(context: Context) {
        notify(
            context, ID_STARTED,
            builder(context, CHANNEL_STATUS)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Already saved")
                .setContentText("That post is already in your gallery")
                .setAutoCancel(true)
                .setTimeoutAfter(6_000)
                .build(),
        )
    }

    /** The one failure the user must act on, so it gets its own sticky notification. */
    fun sessionExpired(context: Context) {
        val open = PendingIntent.getActivity(
            context, 2,
            Intent(context, com.luqman.luckysaver.MainActivity::class.java)
                .putExtra(EXTRA_OPEN_LOGIN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        notify(
            context, ID_SESSION,
            builder(context, CHANNEL_STATUS)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Instagram session expired")
                .setContentText("Tap to log in again — downloads can't run until you do")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setOngoing(true)
                .setContentIntent(open)
                .build(),
        )
    }

    fun clearSessionExpired(context: Context) = cancel(context, ID_SESSION)

    /** One quiet summary for a watchlist run, rather than a popup per story. */
    fun storiesSaved(context: Context, count: Int, accounts: Int) {
        val files = if (count == 1) "1 story" else "$count stories"
        val from = if (accounts == 1) "1 account" else "$accounts accounts"
        notify(
            context, ID_STORIES,
            builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Saved $files")
                .setContentText("From $from you're watching")
                .setAutoCancel(true)
                .build(),
        )
    }

    fun cancel(context: Context, id: Int) {
        runCatching { manager(context).cancel(id) }
    }

    /** Posting throws nothing useful when POST_NOTIFICATIONS was denied; the app still works. */
    private fun notify(context: Context, id: Int, notification: Notification) {
        runCatching { manager(context).notify(id, notification) }
    }
}
