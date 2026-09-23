package com.luqman.luckysaver.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.luqman.luckysaver.App
import com.luqman.luckysaver.core.MediaItem
import com.luqman.luckysaver.core.MediaKind
import com.luqman.luckysaver.data.DownloadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app get() = applicationContext as App

    override suspend fun doWork(): Result {
        val url = inputData.getString(K_URL) ?: return Result.failure()
        val key = inputData.getString(K_KEY) ?: return Result.failure()
        val fileName = inputData.getString(K_FILE) ?: return Result.failure()
        val isVideo = inputData.getBoolean(K_VIDEO, false)
        val saver = MediaStoreSaver(applicationContext)

        runCatching { setForeground(getForegroundInfo()) }

        return withContext(Dispatchers.IO) {
            val uri = saver.create(
                fileName = fileName,
                mime = if (isVideo) "video/mp4" else "image/jpeg",
                isVideo = isVideo,
                takenAtMillis = inputData.getLong(K_TAKEN, System.currentTimeMillis() / 1000) * 1000,
            )
            try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", app.session.userAgent)
                    .header("Referer", "https://www.instagram.com/")
                    .build()
                app.http.newCall(req).execute().use { resp ->
                    // 403 on the CDN = signed URL expired; retrying the same URL won't help.
                    if (resp.code == 403 || resp.code == 410) throw ExpiredUrl()
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("Empty body")
                    val total = body.contentLength()
                    saver.open(uri).use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        setProgress(workDataOf(K_PROGRESS to pct))
                                        // Keep the ongoing notification in step with the download so
                                        // progress stays visible while the app is in the background.
                                        notify(notificationId, progressNotification(fileName, pct, total))
                                    }
                                }
                            }
                        }
                    }
                }
                saver.publish(uri)
                notify(resultNotificationId, doneNotification(fileName, uri, isVideo))
                app.db.downloads().upsert(
                    DownloadEntity(
                        key = key,
                        owner = inputData.getString(K_OWNER).orEmpty(),
                        shortcode = inputData.getString(K_CODE),
                        isVideo = isVideo,
                        uri = uri.toString(),
                        fileName = fileName,
                        savedAt = System.currentTimeMillis(),
                    )
                )
                Result.success()
            } catch (e: ExpiredUrl) {
                saver.discard(uri)
                notify(resultNotificationId, failedNotification(fileName, "Link expired. Fetch the post again."))
                Result.failure(workDataOf(K_ERROR to "Link expired. Resolve the post again."))
            } catch (e: IOException) {
                saver.discard(uri)
                if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    notify(resultNotificationId, failedNotification(fileName, e.message ?: "Network error"))
                    Result.failure(workDataOf(K_ERROR to (e.message ?: "Network error")))
                }
            } catch (e: Throwable) {
                saver.discard(uri)
                cancelNotification(notificationId)
                throw e
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = progressNotification(inputData.getString(K_FILE).orEmpty(), -1, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notificationId, notification)
    }

    private val notificationId get() = inputData.getString(K_KEY).hashCode()

    /**
     * WorkManager cancels the foreground notification when the worker finishes, so the
     * result notice needs an id of its own to survive.
     */
    private val resultNotificationId get() = notificationId + 1

    private val notificationManager
        get() = applicationContext.getSystemService(NotificationManager::class.java)

    private fun builder(): NotificationCompat.Builder {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
    }

    /** [pct] < 0 renders an indeterminate bar (size not known yet). */
    private fun progressNotification(fileName: String, pct: Int, totalBytes: Long) = builder()
        .setContentTitle(if (pct < 0) "Downloading" else "Downloading  $pct%")
        .setContentText(if (totalBytes > 0) "$fileName · ${totalBytes / 1024 / 1024} MB" else fileName)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(100, pct.coerceAtLeast(0), pct < 0)
        .build()

    private fun doneNotification(fileName: String, uri: Uri, isVideo: Boolean) = builder()
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("Saved to LuckySaver")
        .setContentText(fileName)
        .setAutoCancel(true)
        .setContentIntent(
            PendingIntent.getActivity(
                applicationContext,
                notificationId,
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, if (isVideo) "video/*" else "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

    private fun failedNotification(fileName: String, reason: String) = builder()
        .setSmallIcon(android.R.drawable.stat_notify_error)
        .setContentTitle("Download failed")
        .setContentText("$fileName · $reason")
        .setAutoCancel(true)
        .build()

    private fun notify(id: Int, notification: android.app.Notification) {
        runCatching { notificationManager.notify(id, notification) }
    }

    private fun cancelNotification(id: Int) {
        runCatching { notificationManager.cancel(id) }
    }

    private class ExpiredUrl : IOException()

    companion object {
        const val TAG = "media-download"
        const val K_URL = "url"
        const val K_KEY = "key"
        const val K_FILE = "file"
        const val K_VIDEO = "video"
        const val K_OWNER = "owner"
        const val K_CODE = "code"
        const val K_TAKEN = "taken"
        const val K_PROGRESS = "progress"
        const val K_ERROR = "error"
        private const val CHANNEL = "downloads"

        fun enqueue(context: Context, item: MediaItem) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(
                    workDataOf(
                        K_URL to item.url,
                        K_KEY to item.key,
                        K_FILE to item.fileName(),
                        K_VIDEO to (item.kind == MediaKind.VIDEO),
                        K_OWNER to item.owner,
                        K_CODE to item.shortcode,
                        K_TAKEN to item.takenAt,
                    )
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(TAG)
                .addTag("key:${item.key}")
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("dl-${item.key}", ExistingWorkPolicy.KEEP, request)
        }
    }
}
