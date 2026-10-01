package com.luqman.luckysaver.data

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Quality(val label: String) {
    BEST("Best available"),
    SMALLER("Smaller files"),
}

data class Settings(
    val wifiOnly: Boolean = false,
    val quality: Quality = Quality.BEST,
    val folder: String = "LuckySaver",
    /** Tokens: {user} {code} {index} {date}. */
    val fileNameTemplate: String = "{user}_{code}_{index}",
    val autoDownload: Boolean = true,
    val skipDuplicates: Boolean = true,
    val storyWatchEnabled: Boolean = false,
    val storyIntervalHours: Int = 4,
    val palette: Palette = Palette.JADE,
    val darkMode: DarkMode = DarkMode.SYSTEM,
)

enum class Palette(val label: String) {
    JADE("LuckySaver jade"),
    WALLPAPER("Wallpaper colors"),
}

enum class DarkMode(val label: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
}

/** Checks per day at each interval, used to explain the risk in the picker. */
enum class WatchInterval(val hours: Int, val label: String, val risk: String) {
    TWO(2, "Every 2 hours", "12 checks a day. Highest chance Instagram flags the account as automated."),
    FOUR(4, "Every 4 hours", "6 checks a day. Balanced: catches most stories, ordinary-looking traffic."),
    EIGHT(8, "Every 8 hours", "3 checks a day. Safer, but a story posted and deleted quickly can be missed."),
    TWELVE(12, "Every 12 hours", "2 checks a day. Lowest risk, misses the most."),
    ;

    companion object {
        fun of(hours: Int) = entries.firstOrNull { it.hours == hours } ?: FOUR
    }
}

/** Small enough that SharedPreferences beats pulling in DataStore. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    val current: Settings get() = _state.value

    private fun read() = Settings(
        wifiOnly = prefs.getBoolean(KEY_WIFI, false),
        quality = runCatching { Quality.valueOf(prefs.getString(KEY_QUALITY, null) ?: "BEST") }
            .getOrDefault(Quality.BEST),
        folder = prefs.getString(KEY_FOLDER, null)?.takeIf { it.isNotBlank() } ?: "LuckySaver",
        fileNameTemplate = prefs.getString(KEY_TEMPLATE, null)?.takeIf { it.isNotBlank() }
            ?: "{user}_{code}_{index}",
        autoDownload = prefs.getBoolean(KEY_AUTO, true),
        skipDuplicates = prefs.getBoolean(KEY_SKIP_DUPES, true),
        storyWatchEnabled = prefs.getBoolean(KEY_WATCH, false),
        storyIntervalHours = prefs.getInt(KEY_WATCH_HOURS, 4),
        palette = runCatching { Palette.valueOf(prefs.getString(KEY_PALETTE, null) ?: "JADE") }
            .getOrDefault(Palette.JADE),
        darkMode = runCatching { DarkMode.valueOf(prefs.getString(KEY_DARK, null) ?: "SYSTEM") }
            .getOrDefault(DarkMode.SYSTEM),
    )

    fun update(block: (Settings) -> Settings) {
        val next = block(_state.value)
        prefs.edit()
            .putBoolean(KEY_WIFI, next.wifiOnly)
            .putString(KEY_QUALITY, next.quality.name)
            .putString(KEY_FOLDER, sanitizeFolder(next.folder))
            .putString(KEY_TEMPLATE, next.fileNameTemplate)
            .putBoolean(KEY_AUTO, next.autoDownload)
            .putBoolean(KEY_SKIP_DUPES, next.skipDuplicates)
            .putBoolean(KEY_WATCH, next.storyWatchEnabled)
            .putInt(KEY_WATCH_HOURS, next.storyIntervalHours)
            .putString(KEY_PALETTE, next.palette.name)
            .putString(KEY_DARK, next.darkMode.name)
            .apply()
        _state.value = read()
    }

    companion object {
        private const val KEY_WIFI = "wifi_only"
        private const val KEY_QUALITY = "quality"
        private const val KEY_FOLDER = "folder"
        private const val KEY_TEMPLATE = "template"
        private const val KEY_AUTO = "auto_download"
        private const val KEY_SKIP_DUPES = "skip_duplicates"
        private const val KEY_WATCH = "story_watch"
        private const val KEY_WATCH_HOURS = "story_watch_hours"
        private const val KEY_PALETTE = "palette"
        private const val KEY_DARK = "dark_mode"

        /** MediaStore rejects path separators and leading dots in a relative path segment. */
        fun sanitizeFolder(raw: String): String =
            raw.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "LuckySaver" }

        fun relativePath(folder: String): String = "${Environment.DIRECTORY_PICTURES}/${sanitizeFolder(folder)}"
    }
}
