package com.azrael.pixivdumpsync

import org.json.JSONArray
import org.json.JSONObject

object ArtworkPreviewCodec {
    /** A single working thumbnail must not conceal four broken artwork cards. */
    fun needsThumbnailRecovery(items:List<PixivApi.ArtworkPreview>):Boolean =
        items.isEmpty() || items.take(5).any{it.thumbnailUrl.isNullOrBlank()}
    fun encode(items: List<PixivApi.ArtworkPreview>): String {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("userId", item.userId)
                    .put("title", item.title)
                    .put("thumbnailUrl", item.thumbnailUrl ?: JSONObject.NULL)
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): List<PixivApi.ArtworkPreview> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id", "")
                    val userId = item.optString("userId", "")
                    if (id.isBlank()) continue
                    add(
                        PixivApi.ArtworkPreview(
                            id = id,
                            userId = userId,
                            title = item.optString("title", ""),
                            thumbnailUrl = item.optString("thumbnailUrl", "")
                                .takeIf { it.isNotBlank() && it != "null" }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
