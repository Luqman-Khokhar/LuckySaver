package com.luqman.luckysaver.whatsapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.luqman.luckysaver.App
import com.luqman.luckysaver.core.WaApp
import com.luqman.luckysaver.core.WaFiles
import com.luqman.luckysaver.data.DownloadEntity
import com.luqman.luckysaver.download.MediaStoreSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** One status file in WhatsApp's folder. [uri] is a document uri this app holds a grant for. */
data class WaStatus(
    val app: WaApp,
    val name: String,
    val uri: Uri,
    val mime: String,
    val isVideo: Boolean,
    /** When WhatsApp wrote the file, which is when it loaded the status, not when it was posted. */
    val modified: Long,
    val size: Long,
) {
    val key: String get() = WaFiles.key(name)
}

/**
 * Reads and copies WhatsApp statuses. Nothing goes over the network: WhatsApp has already put the
 * files on the phone, and the user grants this app read access to that one folder through the
 * system picker. The grant survives restarts; it is lost if WhatsApp is reinstalled.
 */
class WaStatusStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("whatsapp", Context.MODE_PRIVATE)
    private val saver = MediaStoreSaver(context)

    private val _granted = MutableStateFlow(readGrants())
    /** Apps whose status folder this install can read. */
    val granted: StateFlow<Set<WaApp>> = _granted.asStateFlow()

    fun isInstalled(app: WaApp): Boolean = runCatching {
        context.packageManager.getPackageInfo(app.packageName, 0)
        true
    }.getOrDefault(false)

    fun installed(): List<WaApp> = WaApp.entries.filter(::isInstalled)

    fun launchIntent(app: WaApp): Intent? = context.packageManager.getLaunchIntentForPackage(app.packageName)

    /** Opens the picker inside the status folder, so granting is one tap on "Use this folder". */
    fun initialUri(app: WaApp): Uri =
        DocumentsContract.buildDocumentUri(EXTERNAL_AUTHORITY, "primary:${app.folder}")

    /** Keeps the grant if the user picked the right folder. False means they picked another one. */
    fun grant(app: WaApp, tree: Uri): Boolean {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return false
        if (tree.authority != EXTERNAL_AUTHORITY || !WaFiles.isStatusFolder(id, app)) return false
        runCatching {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { return false }
        prefs.edit().putString(app.name, tree.toString()).apply()
        _granted.value = readGrants()
        return true
    }

    /** Newest first. Throws when the folder can't be read, so callers can tell that from empty. */
    suspend fun list(app: WaApp): List<WaStatus> = withContext(Dispatchers.IO) {
        val tree = tree(app) ?: return@withContext emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val out = ArrayList<WaStatus>()
        val cursor = try {
            context.contentResolver.query(children, columns, null, null, null)
        } catch (e: SecurityException) {
            // WhatsApp reinstalled or the grant revoked in system settings: ask again.
            forget(app)
            throw e
        } ?: error("Couldn't read the ${app.label} status folder")
        cursor.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val rawMime = c.getString(2)
                if (!WaFiles.isMedia(name, rawMime) || c.getLong(4) <= 0) continue
                out += WaStatus(
                    app = app,
                    name = name,
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                    mime = WaFiles.mimeOf(name, rawMime),
                    isVideo = WaFiles.isVideo(name, rawMime),
                    modified = c.getLong(3),
                    size = c.getLong(4),
                )
            }
        }
        out.sortedByDescending { it.modified }
    }

    /** Keys of the given statuses already saved and still on the phone. */
    suspend fun savedKeys(statuses: List<WaStatus>): Set<String> {
        if (statuses.isEmpty()) return emptySet()
        return (context.applicationContext as App).db.downloads().presentKeys(statuses.map { it.key }).toSet()
    }

    /** Copies one status into the album. Returns the row it was recorded as. */
    suspend fun save(status: WaStatus): DownloadEntity = withContext(Dispatchers.IO) {
        val fileName = WaFiles.saveName(status.app, status.name)
        val target = saver.create(fileName, status.mime, status.isVideo, status.modified, subfolder = SUBFOLDER)
        try {
            val input = context.contentResolver.openInputStream(status.uri)
                ?: error("${status.app.label} removed this status")
            input.use { src -> saver.open(target).use { dst -> src.copyTo(dst) } }
            saver.publish(target)
        } catch (e: Exception) {
            saver.discard(target)
            throw e
        }
        val now = System.currentTimeMillis()
        DownloadEntity(
            key = status.key,
            owner = status.app.label,
            shortcode = null,
            isVideo = status.isVideo,
            uri = target.toString(),
            fileName = fileName,
            savedAt = now,
            takenAt = status.modified,
        ).also { (context.applicationContext as App).db.downloads().upsert(it) }
    }

    /** Saves everything not already saved. Returns how many were copied; failures are skipped. */
    suspend fun saveAll(statuses: List<WaStatus>): Int {
        val saved = savedKeys(statuses)
        return statuses.filter { it.key !in saved }.count { runCatching { save(it) }.isSuccess }
    }

    private fun tree(app: WaApp): Uri? = prefs.getString(app.name, null)?.let(Uri::parse)

    private fun forget(app: WaApp) {
        prefs.edit().remove(app.name).apply()
        _granted.value = readGrants()
    }

    /** A stored grant only counts while Android still lists it as persisted. */
    private fun readGrants(): Set<WaApp> {
        val held = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        return WaApp.entries.filter { prefs.getString(it.name, null) in held }.toSet()
    }

    /** Called when the app comes back to the front: grants can be revoked from system settings. */
    fun refreshGrants() {
        _granted.value = readGrants()
    }

    companion object {
        const val EXTERNAL_AUTHORITY = "com.android.externalstorage.documents"
        /** Saved statuses sit in their own folder inside the album. */
        const val SUBFOLDER = "WhatsApp"
    }
}
