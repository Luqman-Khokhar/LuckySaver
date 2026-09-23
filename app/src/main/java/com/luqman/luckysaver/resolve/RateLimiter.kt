package com.luqman.luckysaver.resolve

import android.content.Context

/**
 * Tracks Instagram's soft blocks and decides how long to stay quiet.
 *
 * Backoff escalates while the blocks keep coming and resets after a clean call, because a soft
 * block that is ignored turns into a longer one — and eventually into a checkpoint on the
 * account. State is persisted: a cooldown has to outlive the process, or restarting the app
 * becomes a way to hammer straight through it.
 */
class RateLimiter(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("rate_limit", Context.MODE_PRIVATE)

    val cooldownUntil: Long get() = prefs.getLong(KEY_UNTIL, 0)

    val isCoolingDown: Boolean get() = cooldownUntil > System.currentTimeMillis()

    val remainingMs: Long get() = (cooldownUntil - System.currentTimeMillis()).coerceAtLeast(0)

    /** Requests are spaced further apart for a while after a block, not just paused. */
    val minIntervalMs: Long
        get() = if (prefs.getInt(KEY_STRIKES, 0) > 0) SLOWED_INTERVAL_MS else NORMAL_INTERVAL_MS

    /** @param suggestedMs honours Retry-After when Instagram sends one. */
    fun recordBlock(suggestedMs: Long = 0): Long {
        val strikes = (prefs.getInt(KEY_STRIKES, 0) + 1).coerceAtMost(BACKOFF_STEPS.lastIndex + 1)
        val wait = maxOf(suggestedMs, BACKOFF_STEPS[strikes - 1])
        prefs.edit()
            .putInt(KEY_STRIKES, strikes)
            .putLong(KEY_UNTIL, System.currentTimeMillis() + wait)
            .apply()
        return wait
    }

    fun recordSuccess() {
        if (prefs.getInt(KEY_STRIKES, 0) == 0 && cooldownUntil == 0L) return
        prefs.edit().putInt(KEY_STRIKES, 0).putLong(KEY_UNTIL, 0).apply()
    }

    fun describeRemaining(): String {
        val seconds = remainingMs / 1000
        return when {
            seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    private companion object {
        const val KEY_UNTIL = "until"
        const val KEY_STRIKES = "strikes"
        const val NORMAL_INTERVAL_MS = 2_000L
        const val SLOWED_INTERVAL_MS = 6_000L

        /** 5 min, 15 min, 1 hour, 3 hours. */
        val BACKOFF_STEPS = longArrayOf(5 * 60_000, 15 * 60_000, 60 * 60_000, 3 * 60 * 60_000)
    }
}
