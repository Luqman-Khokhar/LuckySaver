package com.luqman.luckysaver.resolve

import com.luqman.luckysaver.core.MediaItem

/**
 * Remembers recent resolves so repeating a link costs no request at all.
 *
 * Entries are short-lived on purpose: the media URLs inside are signed and expire, so serving a
 * stale one would trade a rate-limit error for a download failure.
 */
class ResolveCache(private val ttlMs: Long = 10 * 60 * 1000, private val maxEntries: Int = 32) {

    private data class Entry(val items: List<MediaItem>, val storedAt: Long)

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    fun get(key: String): List<MediaItem>? {
        val entry = entries[key] ?: return null
        if (System.currentTimeMillis() - entry.storedAt > ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.items
    }

    @Synchronized
    fun put(key: String, items: List<MediaItem>) {
        if (items.isEmpty()) return
        entries[key] = Entry(items, System.currentTimeMillis())
        while (entries.size > maxEntries) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    @Synchronized
    fun clear() = entries.clear()
}
