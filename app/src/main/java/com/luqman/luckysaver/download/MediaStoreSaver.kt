package com.luqman.luckysaver.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.luqman.luckysaver.data.SettingsStore
import java.io.OutputStream

/** Scoped-storage writer: no storage permission needed on API 29+. */
class MediaStoreSaver(private val context: Context) {

    fun create(fileName: String, mime: String, isVideo: Boolean, takenAtMillis: Long): Uri {
        val collection = if (isVideo)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // Photos and videos share one album. MediaStore allows video under Pictures/, so both
        // land in the same folder instead of being split across Pictures/ and Movies/.
        val folder = SettingsStore.sanitizeFolder(
            (context.applicationContext as? com.luqman.luckysaver.App)?.settings?.current?.folder ?: FOLDER
        )
        val dir = Environment.DIRECTORY_PICTURES
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$folder")
            put(MediaStore.MediaColumns.DATE_TAKEN, takenAtMillis)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(collection, values)
            ?: error("MediaStore insert failed for $fileName")
    }

    fun open(uri: Uri): OutputStream =
        context.contentResolver.openOutputStream(uri, "w") ?: error("Cannot open $uri")

    fun publish(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    fun discard(uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    fun exists(uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    companion object {
        const val FOLDER = "LuckySaver"
    }
}
