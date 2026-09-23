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
        val originalUrl = inputData.getString(K_URL) ?: return Result.failure()
        val key = inputData.getString(K_KEY) ?: return Result.failure()
        val fileName = inputData.getString(K_FILE) ?: return Result.failure()
        val isVideo = inputData.getBoolean(K_VIDEO, false)
        val saver = MediaStoreSaver(applicationContext)
        // A retry re-runs with the original input data, so a URL refreshed on the previous
        // attempt is stashed rather than kept in memory.
        val url = refreshedUrls(applicationContext).getString(key, null) ?: originalUrl

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
                    // 403 on the CDN means the signed URL expired. The same URL will never work
                    // again, but the post it came from can be resolved afresh.
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
                                        notify(
                                            notificationId,
                                            DownloadNotifications.progress(applicationContext, fileName, pct, total),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                saver.publish(uri)
                refreshedUrls(applicationContext).edit().remove(key).apply()
                DownloadNotifications.saved(applicationContext, resultNotificationId, fileName, uri, isVideo)
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
                val refreshed = refreshUrl(key)
                if (refreshed != null && runAttemptCount < 3) {
                    // Hand the fresh URL to the retry rather than telling the user to redo it.
                    refreshedUrls(applicationContext).edit().putString(key, refreshed).apply()
                    Result.retry()
                } else {
                    DownloadNotifications.failed(
                        applicationContext, resultNotificationId, fileName,
                        "Instagram's link expired and the post couldn't be read again.",
                    )
                    Result.failure(workDataOf(K_ERROR to "Link expired and could not be refreshed"))
                }
            } catch (e: IOException) {
                saver.discard(uri)
                if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    DownloadNotifications.failed(
                        applicationContext, resultNotificationId, fileName, e.message ?: "Network error",
                    )
                    Result.failure(workDataOf(K_ERROR to (e.message ?: "Network error")))
                }
            } catch (e: Throwable) {
                saver.discard(uri)
                DownloadNotifications.cancel(applicationContext, notificationId)
                throw e
            }
        }
    }

    /**
     * Re-resolves the post this file came from and returns the current URL for the same item.
     * Shortcode links only: a story URL is not addressable once it has rotated.
     */
    private suspend fun refreshUrl(key: String): String? {
        val code = inputData.getString(K_CODE)?.takeIf { it.isNotBlank() && it != "profile" } ?: return null
        return runCatching {
            app.resolver.resolve("https://www.instagram.com/p/$code/")
                .firstOrNull { it.key == key }
                ?.url
        }.getOrNull()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = DownloadNotifications.progress(
            applicationContext, inputData.getString(K_FILE).orEmpty(), -1, 0,
        )
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

    private fun notify(id: Int, notification: android.app.Notification) {
        runCatching {
            applicationContext.getSystemService(NotificationManager::class.java).notify(id, notification)
        }
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

        private fun refreshedUrls(context: Context) =
            context.getSharedPreferences("refreshed_urls", Context.MODE_PRIVATE)

        /** Queues a batch, raises one "started" popup, and returns the work ids to watch. */
        fun enqueueAll(context: Context, items: List<MediaItem>): List<java.util.UUID> {
            if (items.isEmpty()) return emptyList()
            val ids = items.map { enqueue(context, it) }
            DownloadNotifications.started(context, items.size)
            return ids
        }

        fun enqueue(context: Context, item: MediaItem): java.util.UUID {
            val settings = (context.applicationContext as App).settings.current
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(
                    workDataOf(
                        K_URL to item.url,
                        K_KEY to item.key,
                        K_FILE to item.fileName(settings.fileNameTemplate),
                        K_VIDEO to (item.kind == MediaKind.VIDEO),
                        K_OWNER to item.owner,
                        K_CODE to item.shortcode,
                        K_TAKEN to item.takenAt,
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                        )
                        .build()
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(TAG)
                .addTag("key:${item.key}")
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("dl-${item.key}", ExistingWorkPolicy.KEEP, request)
            return request.id
        }
    }
}
