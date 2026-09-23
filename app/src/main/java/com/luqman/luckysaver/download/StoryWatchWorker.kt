package com.luqman.luckysaver.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.luqman.luckysaver.App
import com.luqman.luckysaver.core.FailureKind
import com.luqman.luckysaver.core.ResolveException
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Saves new stories from watched accounts before they expire.
 *
 * This is the only part of the app that talks to Instagram on its own, so it is deliberately
 * frugal: user ids are stored when an account is added, every account is checked in a single
 * batched request, and the run is jittered so the traffic does not arrive on a metronome.
 */
class StoryWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app get() = applicationContext as App

    override suspend fun doWork(): Result {
        val accounts = app.db.watched().enabled()
        if (accounts.isEmpty()) return Result.success()
        if (app.rateLimiter.isCoolingDown) {
            // Another request now would extend the block; the next scheduled run is soon enough.
            return Result.success()
        }
        if (!app.session.isLoggedIn) {
            // Nothing can succeed without a session, and trying repeatedly only draws attention.
            if (app.session.isExpired) DownloadNotifications.sessionExpired(applicationContext)
            return Result.success()
        }

        // Spread runs out a little: identical timing across days is what a bot looks like.
        if (!inputData.getBoolean(KEY_MANUAL, false)) delay(Random.nextLong(0, MAX_JITTER_MS))

        return try {
            val items = app.resolver.storiesFor(accounts.map { it.userId })
            val alreadySaved = app.db.downloads().existingKeys(items.map { it.key }).toSet()
            val fresh = items.filter { it.key !in alreadySaved }

            DownloadWorker.enqueueAll(applicationContext, fresh, silent = true)

            val now = System.currentTimeMillis()
            val perAccount = fresh.groupingBy { it.owner.lowercase() }.eachCount()
            accounts.forEach { account ->
                app.db.watched().recordCheck(
                    userId = account.userId,
                    at = now,
                    saved = perAccount[account.username.lowercase()] ?: 0,
                )
            }
            if (fresh.isNotEmpty()) {
                DownloadNotifications.storiesSaved(
                    applicationContext,
                    count = fresh.size,
                    accounts = perAccount.size,
                )
            }
            Result.success()
        } catch (e: ResolveException) {
            when (e.kind) {
                // Both mean "stop asking"; the next scheduled run is soon enough.
                FailureKind.RATE_LIMITED, FailureKind.SESSION_EXPIRED -> Result.success()
                else -> if (runAttemptCount < 2) Result.retry() else Result.success()
            }
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "story-watch"
        private const val KEY_MANUAL = "manual"
        private const val MAX_JITTER_MS = 4 * 60 * 1000L

        fun schedule(context: Context, everyHours: Int) {
            val request = PeriodicWorkRequestBuilder<StoryWatchWorker>(
                everyHours.toLong(), TimeUnit.HOURS,
                // Flex window: let Android batch this with other wakeups instead of demanding
                // an exact moment, which is kinder to the battery.
                everyHours.toLong().coerceAtMost(1), TimeUnit.HOURS,
            )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Manual "check now", which skips the jitter a scheduled run applies. */
        fun runOnce(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<StoryWatchWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(androidx.work.workDataOf(KEY_MANUAL to true))
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
