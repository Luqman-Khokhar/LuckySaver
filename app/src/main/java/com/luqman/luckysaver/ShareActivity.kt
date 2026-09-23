package com.luqman.luckysaver

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.luqman.luckysaver.core.IgLinkParser
import com.luqman.luckysaver.core.ResolveException
import com.luqman.luckysaver.download.DownloadNotifications
import com.luqman.luckysaver.download.DownloadWorker
import kotlinx.coroutines.launch

/**
 * Handles "Share to LuckySaver" without showing any UI: resolve, queue, done. Progress and
 * failures are reported through notifications, so sharing a reel never pulls you out of Instagram.
 *
 * A post with several items still opens the app, because which ones to keep is a real choice.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        val app = applicationContext as App

        if (shared.isNullOrBlank() || IgLinkParser.parse(shared) == null) {
            Toast.makeText(this, "No Instagram link in that share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (!app.settings.current.autoDownload) {
            openAppWith(shared)
            return
        }

        Toast.makeText(this, "Fetching…", Toast.LENGTH_SHORT).show()
        // Runs on the application scope: this activity is gone a moment from now.
        app.scope.launch {
            try {
                val items = app.resolver.resolve(shared)
                val saved = if (app.settings.current.skipDuplicates) {
                    app.db.downloads().existingKeys(items.map { it.key }).toSet()
                } else {
                    emptySet()
                }
                val fresh = items.filter { it.key !in saved }
                when {
                    fresh.isEmpty() -> DownloadNotifications.alreadySaved(app)
                    // One file, or a post the user already chose to take whole: just take it.
                    fresh.size == 1 -> DownloadWorker.enqueueAll(app, fresh)
                    else -> openAppWith(shared)
                }
            } catch (e: ResolveException) {
                DownloadNotifications.resolveFailed(app, e.message ?: "Couldn't fetch that post")
            } catch (e: Exception) {
                DownloadNotifications.resolveFailed(app, e.message ?: "Something went wrong")
            }
        }
        finish()
    }

    private fun openAppWith(link: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, link)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}
