package com.luqman.luckysaver.core

import org.json.JSONArray
import org.json.JSONObject

/** Parses Instagram private-API (v1) media nodes into [MediaItem]s. Pure; unit-testable. */
object IgJson {

    fun parseMedia(node: JSONObject): List<MediaItem> {
        val owner = node.optJSONObject("user")?.optString("username").orEmpty()
            .ifEmpty { node.optJSONObject("owner")?.optString("username").orEmpty() }
            .ifEmpty { "instagram" }
        val code = node.optString("code").ifEmpty { null }
        val pk = node.optString("pk").ifEmpty { node.optString("id").substringBefore('_') }
        val takenAt = node.optLong("taken_at")
        val caption = node.optJSONObject("caption")?.optString("text")

        val carousel = node.optJSONArray("carousel_media")
        if (node.optInt("media_type") == 8 && carousel != null) {
            return (0 until carousel.length()).mapNotNull { i ->
                single(carousel.getJSONObject(i), "${pk}_$i", owner, code, takenAt, caption)
            }
        }
        return listOfNotNull(single(node, "${pk}_0", owner, code, takenAt, caption))
    }

    private fun single(
        n: JSONObject, key: String, owner: String, code: String?, takenAt: Long, caption: String?,
    ): MediaItem? {
        val image = bestCandidate(n.optJSONObject("image_versions2")?.optJSONArray("candidates"))
        val video = bestCandidate(n.optJSONArray("video_versions"))
        val isVideo = n.optInt("media_type") == 2 || video != null
        val chosen = (if (isVideo) video else image) ?: return null
        return MediaItem(
            key = key,
            kind = if (isVideo) MediaKind.VIDEO else MediaKind.IMAGE,
            url = chosen.optString("url"),
            thumbnailUrl = image?.optString("url") ?: chosen.optString("url"),
            width = chosen.optInt("width"),
            height = chosen.optInt("height"),
            owner = owner,
            shortcode = code,
            takenAt = takenAt,
            caption = caption,
        )
    }

    private fun bestCandidate(arr: JSONArray?): JSONObject? {
        if (arr == null || arr.length() == 0) return null
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("url").isNotEmpty() }
            .maxByOrNull { it.optInt("width") * it.optInt("height") }
    }

    /** reels_media responses come either as {"reels": {id: {...}}} or {"reels_media": [...]}. */
    fun parseReels(root: JSONObject): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        root.optJSONObject("reels")?.let { reels ->
            reels.keys().forEach { k -> reels.optJSONObject(k)?.let(out::add) }
        }
        if (out.isEmpty()) root.optJSONArray("reels_media")?.let { arr ->
            for (i in 0 until arr.length()) out += arr.getJSONObject(i)
        }
        return out.flatMap { reel ->
            val items = reel.optJSONArray("items") ?: return@flatMap emptyList()
            val reelUser = reel.optJSONObject("user")
            (0 until items.length()).map { i ->
                items.getJSONObject(i).also { if (!it.has("user") && reelUser != null) it.put("user", reelUser) }
            }
        }
    }

    /** Embed pages expose GraphQL-shaped nodes (display_url / video_url / edge_sidecar_to_children). */
    fun parseGraphQl(node: JSONObject, fallbackCode: String): List<MediaItem> {
        val owner = node.optJSONObject("owner")?.optString("username").orEmpty().ifEmpty { "instagram" }
        val code = node.optString("shortcode").ifEmpty { fallbackCode }
        val pk = node.optString("id").ifEmpty { Shortcode.toMediaId(code) }
        val takenAt = node.optLong("taken_at_timestamp")
        val caption = node.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")
            ?.optJSONObject(0)?.optJSONObject("node")?.optString("text")
        val children = node.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
        val nodes = if (children != null && children.length() > 0)
            (0 until children.length()).map { children.getJSONObject(it).getJSONObject("node") }
        else listOf(node)
        return nodes.mapIndexedNotNull { i, n ->
            val isVideo = n.optBoolean("is_video") && n.optString("video_url").isNotEmpty()
            val url = if (isVideo) n.optString("video_url") else n.optString("display_url")
            if (url.isEmpty()) return@mapIndexedNotNull null
            val dims = n.optJSONObject("dimensions")
            MediaItem(
                key = "${pk}_$i",
                kind = if (isVideo) MediaKind.VIDEO else MediaKind.IMAGE,
                url = url,
                thumbnailUrl = n.optString("display_url").ifEmpty { url },
                width = dims?.optInt("width") ?: 0,
                height = dims?.optInt("height") ?: 0,
                owner = owner,
                shortcode = code,
                takenAt = takenAt,
                caption = caption,
            )
        }
    }
}
