package com.luqman.luckysaver.core

/**
 * WhatsApp keeps the statuses you have loaded in a hidden folder on shared storage. These are the
 * pure rules for that folder: which app it belongs to, which files are media, what to call a copy.
 */
enum class WaApp(val packageName: String, val label: String, val shortLabel: String, val folder: String) {
    WHATSAPP(
        "com.whatsapp", "WhatsApp", "WA",
        "Android/media/com.whatsapp/WhatsApp/Media/.Statuses",
    ),
    BUSINESS(
        "com.whatsapp.w4b", "WhatsApp Business", "WAB",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses",
    ),
    ;

    companion object {
        fun ofPackage(pkg: String?): WaApp? = entries.firstOrNull { it.packageName == pkg }
    }
}

object WaFiles {
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp")
    private val VIDEO = setOf("mp4", "3gp", "mkv", "webm")

    /** Media only: WhatsApp also leaves `.nomedia` and half-written temp files in the folder. */
    fun isMedia(name: String, mime: String?): Boolean {
        if (name.startsWith(".")) return false
        return isVideo(name, mime) || mime?.startsWith("image/") == true || extension(name) in IMAGE
    }

    fun isVideo(name: String, mime: String?): Boolean =
        mime?.startsWith("video/") == true || extension(name) in VIDEO

    fun mimeOf(name: String, mime: String?): String = when {
        mime != null && (mime.startsWith("image/") || mime.startsWith("video/")) -> mime
        isVideo(name, mime) -> "video/mp4"
        extension(name) == "png" -> "image/png"
        extension(name) == "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    /**
     * The folder picker returns whatever the user chose, so only a `.Statuses` folder is accepted.
     * Tree document ids look like `primary:Android/media/com.whatsapp/WhatsApp/Media/.Statuses`.
     */
    fun isStatusFolder(treeDocumentId: String, app: WaApp): Boolean =
        treeDocumentId.substringAfter(':').trimEnd('/').equals(app.folder, ignoreCase = true)

    /** WhatsApp's names are unique hashes, so the name alone identifies a status. */
    fun key(name: String): String = "wa:$name"

    fun saveName(app: WaApp, name: String): String = "${app.shortLabel}_$name"

    private fun extension(name: String) = name.substringAfterLast('.', "").lowercase()
}
