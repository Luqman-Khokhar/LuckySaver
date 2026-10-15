package com.luqman.luckysaver.youtube

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
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
import com.luqman.luckysaver.core.FailureKind
import com.luqman.luckysaver.core.ResolveException
import com.luqman.luckysaver.core.YtKind
import com.luqman.luckysaver.core.YtLink
import com.luqman.luckysaver.data.DownloadEntity
import com.luqman.luckysaver.data.Source
import com.luqman.luckysaver.download.DownloadNotifications
import com.luqman.luckysaver.download.DownloadWorker
import com.luqman.luckysaver.download.MediaStoreSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Saves one YouTube video or its audio. Unlike an Instagram file this is up to hundreds of MB in
 * two parts, so it:
 *  - resolves the video again here, because stream URLs expire and are bound to the client;
 *  - fetches in 10 MB ranges, which YouTube serves at full speed where one long request is throttled;
 *  - keeps finished bytes in the cache, so a retry or a dropped connection resumes, not restarts;
 *  - muxes video and audio straight into the MediaStore file, with no second copy.
 *
 * Shares [DownloadWorker.TAG] and its progress keys, so the Saved queue row and the bubble ring
 * follow YouTube downloads too.
 */
class YtDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app get() = applicationContext as App

    override suspend fun doWork(): Result {
        val videoId = inputData.getString(K_ID) ?: return Result.failure()
        val kind = runCatching { YtKind.valueOf(inputData.getString(K_KIND).orEmpty()) }.getOrDefault(YtKind.VIDEO)
        val audioOnly = inputData.getBoolean(K_AUDIO, false)
        val maxHeight = inputData.getInt(K_HEIGHT, 1080)
        var label = inputData.getString(K_TITLE)?.takeIf { it.isNotBlank() } ?: "YouTube video"
        val key = keyFor(videoId, audioOnly)
        val parts = File(applicationContext.cacheDir, "yt").apply { mkdirs() }

        runCatching { setForeground(getForegroundInfo()) }

        return withContext(Dispatchers.IO) {
            try {
                val info = app.youtube.info(YtLink(videoId, kind))
                val pick = app.youtube.pick(info, maxHeight, audioOnly)
                val title = info.name.orEmpty().ifBlank { videoId }
                val channel = info.uploaderName.orEmpty().removeSuffix(" - Topic")
                label = title
                val fileName = fileName(title, channel, audioOnly, pick.height)
                val wanted = listOfNotNull(pick.muxed, pick.video, pick.audio).map(::withSize)
                val total = wanted.sumOf { it.size.coerceAtLeast(0) }
                var before = 0L
                val files = wanted.map { part ->
                    val file = File(parts, "$videoId-${part.itag}.part")
                    fetch(part, file) { done -> report(before + done, total, label) }
                    before += file.length()
                    file
                }

                val saver = MediaStoreSaver(applicationContext)
                val uploaded = runCatching { info.uploadDate?.instant?.toEpochMilli() }.getOrNull() ?: 0L
                val uri = saver.createYouTube(
                    fileName, audio = audioOnly,
                    takenAtMillis = uploaded.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    title = title, artist = channel,
                )
                try {
                    setProgress(workDataOf(DownloadWorker.K_PROGRESS to 100))
                    saver.openFd(uri).use { fd -> YtMuxer.mux(files, fd.fileDescriptor) { isStopped } }
                    saver.publish(uri)
                } catch (e: Throwable) {
                    saver.discard(uri)
                    throw e
                }
                files.forEach { it.delete() }
                app.db.downloads().upsert(
                    DownloadEntity(
                        key = key,
                        owner = channel,
                        shortcode = videoId,
                        isVideo = !audioOnly,
                        uri = uri.toString(),
                        fileName = fileName,
                        savedAt = System.currentTimeMillis(),
                        takenAt = uploaded,
                        source = when {
                            audioOnly -> Source.YT_AUDIO
                            kind == YtKind.SHORT -> Source.YT_SHORT
                            else -> Source.YT_VIDEO
                        },
                        title = title,
                    )
                )
                DownloadNotifications.saved(
                    applicationContext, resultNotificationId, fileName, uri, isVideo = !audioOnly,
                    mime = if (audioOnly) "audio/*" else null,
                )
                Result.success()
            } catch (e: ExpiredUrl) {
                // The cached bytes stay: the next attempt resolves fresh URLs for the same formats.
                retryOrFail(label, "YouTube's link expired and couldn't be renewed.", parts, videoId)
            } catch (e: ResolveException) {
                when (e.kind) {
                    FailureKind.NETWORK, FailureKind.BOT_CHECK -> retryOrFail(label, e.message.orEmpty(), parts, videoId)
                    else -> fail(label, e.message ?: "Couldn't read that video", parts, videoId)
                }
            } catch (e: IOException) {
                retryOrFail(label, e.message ?: "Network error", parts, videoId)
            } catch (e: InterruptedException) {
                DownloadNotifications.cancel(applicationContext, notificationId)
                Result.failure()
            } catch (e: IllegalStateException) {
                // MediaMuxer/MediaExtractor report broken input this way; a fresh download is the fix.
                clear(parts, videoId)
                fail(label, "The downloaded file was damaged. Try again.", parts, videoId)
            }
        }
    }

    /** The single-file formats often come without a length; asking for it keeps the progress ring honest. */
    private fun withSize(part: YtPart): YtPart {
        if (part.size > 0) return part
        val request = Request.Builder().url(part.url).head().apply {
            YtExtractor.streamHeaders(part.url).forEach { (k, v) -> header(k, v) }
        }.build()
        val size = runCatching {
            app.http.newCall(request).execute().use { it.header("Content-Length")?.toLongOrNull() }
        }.getOrNull() ?: -1
        return part.copy(size = size)
    }

    /** Appends [file] from where it stopped, one range at a time. */
    private fun fetch(part: YtPart, file: File, onBytes: (Long) -> Unit) {
        var done = file.length()
        if (part.size in 1..done) {
            onBytes(done)
            return
        }
        FileOutputStream(file, true).use { out ->
            val buf = ByteArray(64 * 1024)
            while (part.size <= 0 || done < part.size) {
                if (isStopped) throw InterruptedException("Stopped")
                val end = if (part.size > 0) minOf(done + CHUNK, part.size) - 1 else done + CHUNK - 1
                val sep = if ('?' in part.url) '&' else '?'
                val request = Request.Builder().url("${part.url}${sep}range=$done-$end").apply {
                    YtExtractor.streamHeaders(part.url).forEach { (k, v) -> header(k, v) }
                }.build()
                var got = 0L
                app.http.newCall(request).execute().use { resp ->
                    if (resp.code == 403 || resp.code == 410) throw ExpiredUrl()
                    if (!resp.isSuccessful) throw IOException("YouTube returned HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("Empty response from YouTube")
                    body.byteStream().use { input ->
                        while (true) {
                            if (isStopped) throw InterruptedException("Stopped")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            onBytes(done + got)
                        }
                    }
                }
                done += got
                // Unknown size: a short range is the last one.
                if (got == 0L || (part.size <= 0 && got < CHUNK)) break
            }
        }
        if (part.size > 0 && file.length() < part.size) throw IOException("Download ended early")
    }

    private var lastPct = -1

    private fun report(done: Long, total: Long, label: String) {
        if (total <= 0) return
        // The last percent is the mux, so the ring never sits "done" while the file is being written.
        val pct = (done * 99 / total).toInt().coerceIn(0, 99)
        if (pct == lastPct) return
        lastPct = pct
        setProgressAsync(workDataOf(DownloadWorker.K_PROGRESS to pct))
        runCatching {
            applicationContext.getSystemService(NotificationManager::class.java)
                .notify(notificationId, DownloadNotifications.progress(applicationContext, label, pct, total))
        }
    }

    private fun retryOrFail(label: String, reason: String, parts: File, videoId: String): Result =
        if (runAttemptCount < 4) Result.retry() else fail(label, reason, parts, videoId)

    private fun fail(label: String, reason: String, parts: File, videoId: String): Result {
        clear(parts, videoId)
        DownloadNotifications.failed(applicationContext, resultNotificationId, label, reason)
        return Result.failure(workDataOf(DownloadWorker.K_ERROR to reason))
    }

    private fun clear(parts: File, videoId: String) {
        parts.listFiles { f -> f.name.startsWith("$videoId-") }?.forEach { it.delete() }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = DownloadNotifications.progress(
            applicationContext, inputData.getString(K_TITLE)?.takeIf { it.isNotBlank() } ?: "YouTube video", -1, 0,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notificationId, notification)
    }

    private val notificationId get() = keyFor(inputData.getString(K_ID).orEmpty(), inputData.getBoolean(K_AUDIO, false)).hashCode()
    private val resultNotificationId get() = notificationId + 1

    private class ExpiredUrl : IOException()

    companion object {
        private const val K_ID = "yt_id"
        private const val K_KIND = "yt_kind"
        private const val K_AUDIO = "yt_audio"
        private const val K_HEIGHT = "yt_height"
        private const val K_TITLE = "yt_title"
        private const val CHUNK = 10L * 1024 * 1024

        /** Video and audio of the same video are separate saves, so both can be kept. */
        fun keyFor(videoId: String, audioOnly: Boolean) = if (audioOnly) "yt:$videoId:audio" else "yt:$videoId"

        /**
         * "Title.mp4" for video, "Channel - Title.m4a" for audio so players list it by artist.
         * Unlike Instagram names, any script is kept; only characters file systems reject go.
         */
        fun fileName(title: String, channel: String, audioOnly: Boolean, height: Int): String {
            val base = if (audioOnly && channel.isNotBlank()) "$channel - $title" else title
            val clean = base.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().trim('.').take(100).ifBlank { "youtube" }
            return if (audioOnly) "$clean.m4a" else "$clean ${height}p.mp4"
        }

        /** Queues a save; returns the work id to watch. The same video queued twice runs once. */
        fun enqueue(
            context: Context,
            link: YtLink,
            audioOnly: Boolean,
            maxHeight: Int,
            title: String? = null,
        ): UUID {
            val settings = (context.applicationContext as App).settings.current
            val key = keyFor(link.videoId, audioOnly)
            val request = OneTimeWorkRequestBuilder<YtDownloadWorker>()
                .setInputData(
                    workDataOf(
                        K_ID to link.videoId,
                        K_KIND to link.kind.name,
                        K_AUDIO to audioOnly,
                        K_HEIGHT to maxHeight,
                        K_TITLE to title,
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build()
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(DownloadWorker.TAG)
                .addTag("key:$key")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("dl-$key", ExistingWorkPolicy.KEEP, request)
            return request.id
        }

        /** What a link saves when nobody is asked: audio for YouTube Music (if set), else video at the set cap. */
        fun enqueueDefault(context: Context, link: YtLink, forceAudio: Boolean = false): UUID {
            val settings = (context.applicationContext as App).settings.current
            val audio = forceAudio || (link.kind == YtKind.MUSIC && settings.ytMusicAsAudio)
            return enqueue(context, link, audio, settings.ytMaxHeight)
        }

        fun defaultIsAudio(context: Context, link: YtLink): Boolean =
            link.kind == YtKind.MUSIC && (context.applicationContext as App).settings.current.ytMusicAsAudio
    }
}
