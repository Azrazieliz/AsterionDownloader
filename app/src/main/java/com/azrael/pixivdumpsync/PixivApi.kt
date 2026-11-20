package com.azrael.pixivdumpsync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class PixivApi(private val context: Context) {
    companion object {
        const val USER_AGENT = "Kuroha/0.5.2 (Android; personal-use client)"
        private const val BASE = "https://www.pixiv.net"
    }

    data class UserProfile(
        val userId: String,
        val name: String,
        val imageUrl: String?
    )

    data class ArtworkPreview(
        val id: String,
        val userId: String,
        val title: String,
        val thumbnailUrl: String?
    )

    data class UserArtworkSnapshot(
        val ids: List<String>,
        val previews: List<ArtworkPreview>
    )

    data class FollowingFeedPage(
        val items: List<ArtworkPreview>,
        val isLastPage: Boolean
    )

    data class ArtworkDetail(
        val id: String,
        val userId: String,
        val title: String,
        val pageCount: Int,
        val illustType: Int,
        val isBookmarked: Boolean,
        val thumbnailUrl: String?,
        val originalUrl: String?,
        val tags: List<String>
    )

    private var csrfToken: String? = null

    init {
        if (!SessionStore.isLoggedIn(context)) {
            throw IOException("Pixiv session is not connected.")
        }
        NetworkCookies.install(context)
    }

    fun userProfile(userId: String): UserProfile {
        val root = getJson("$BASE/ajax/user/$userId?full=1&lang=en")
        val body = root.getJSONObject("body")
        return UserProfile(
            userId = body.optString("userId", userId),
            name = body.optString("name", body.optString("userName", "Pixiv user $userId")),
            imageUrl = firstNonBlank(
                body.optString("imageBig", ""),
                body.optString("image", ""),
                body.optString("profileImageUrl", "")
            )
        )
    }

    fun userArtworkSnapshot(
        userId: String,
        previewLimit: Int = 5,
        resolveMissingPreviews: Boolean = false
    ): UserArtworkSnapshot {
        val root = getJson("$BASE/ajax/user/$userId/profile/all?lang=en")
        val body = root.getJSONObject("body")
        val previewsById = linkedMapOf<String, ArtworkPreview>()

        for (key in listOf("illusts", "manga")) {
            val obj = body.optJSONObject(key) ?: continue
            val iterator = obj.keys()
            while (iterator.hasNext()) {
                val id = iterator.next()
                val item = obj.optJSONObject(id)
                previewsById[id] = artworkPreview(id, item, userId)
            }
        }

        val ids = previewsById.keys
            .sortedByDescending { it.toLongOrNull() ?: 0L }

        var previews = ids.take(previewLimit.coerceAtLeast(0))
            .mapNotNull { previewsById[it] }

        if (resolveMissingPreviews && previews.any { it.thumbnailUrl.isNullOrBlank() }) {
            val hydrated = runCatching {
                artworkPreviews(userId, previews.map { it.id }).associateBy { it.id }
            }.getOrDefault(emptyMap())

            previews = previews.map { preview ->
                hydrated[preview.id]?.takeIf{
                    !it.thumbnailUrl.isNullOrBlank()
                } ?: preview
            }
        }

        return UserArtworkSnapshot(ids = ids, previews = previews)
    }

    fun userArtworkIds(userId: String): List<String> =
        userArtworkSnapshot(userId, previewLimit = 0).ids

    fun followingFeedPage(page: Int = 1): FollowingFeedPage {
        val root = getJson("$BASE/ajax/follow_latest/illust?p=$page&mode=all&lang=en")
        val body = root.getJSONObject("body")
        val pageInfo = body.optJSONObject("page")
        val thumbnails = body.optJSONObject("thumbnails")
        val illusts = thumbnails?.optJSONArray("illust") ?: JSONArray()
        val items = mutableListOf<ArtworkPreview>()

        for (i in 0 until illusts.length()) {
            val item = illusts.optJSONObject(i) ?: continue
            val id = item.optString("id", "")
            val userId = item.optString("userId", "")
            if (id.isBlank() || userId.isBlank()) continue
            items += artworkPreview(id, item, userId)
        }

        return FollowingFeedPage(
            items = items.distinctBy { it.id },
            isLastPage = pageInfo?.optBoolean("isLastPage", items.isEmpty()) ?: items.isEmpty()
        )
    }

    fun followingUsers(): List<UserProfile> {
        val ownId = currentUserId()
        val result = linkedMapOf<String, UserProfile>()
        var publicSucceeded = false
        var publicFailure: Throwable? = null

        for (rest in listOf("show", "hide")) {
            try {
                var offset = 0
                val limit = 24

                while (true) {
                    val root = getJson(
                        "$BASE/ajax/user/$ownId/following?offset=$offset&limit=$limit&rest=$rest&lang=en"
                    )
                    val body = root.getJSONObject("body")
                    val users = body.optJSONArray("users") ?: JSONArray()
                    if (rest == "show") publicSucceeded = true

                    for (i in 0 until users.length()) {
                        val user = users.optJSONObject(i) ?: continue
                        val id = user.optString("userId", "")
                        if (id.isBlank()) continue
                        result[id] = UserProfile(
                            userId = id,
                            name = firstNonBlank(
                                user.optString("userName", ""),
                                user.optString("name", ""),
                                "Pixiv user $id"
                            ) ?: "Pixiv user $id",
                            imageUrl = firstNonBlank(
                                user.optString("profileImageUrl", ""),
                                user.optString("imageBig", ""),
                                user.optString("image", "")
                            )
                        )
                    }

                    val total = body.optInt("total", offset + users.length())
                    offset += users.length()
                    if (users.length() == 0 || offset >= total) break
                    Thread.sleep(120L)
                }
            } catch (t: Throwable) {
                if (rest == "show") publicFailure = t
            }
        }

        if (!publicSucceeded && publicFailure != null) throw publicFailure
        return result.values.toList()
    }

    fun currentUserId(): String {
        SessionStore.pixivUserIdFromCookie(context)?.let { return it }

        val root = getJson("$BASE/ajax/settings/self?lang=en")
        val body = root.optJSONObject("body") ?: throw IOException("Pixiv account data unavailable.")
        return findString(body, "userId")
            ?: findString(body, "id")
            ?: throw IOException("Could not resolve the signed-in Pixiv user ID.")
    }

    fun artworkDetail(id: String): ArtworkDetail {
        val root = getJson("$BASE/ajax/illust/$id?lang=en")
        val body = root.getJSONObject("body")
        val urls = body.optJSONObject("urls")
        return ArtworkDetail(
            id = body.getString("id"),
            userId = body.getString("userId"),
            title = body.optString("title", ""),
            pageCount = body.optInt("pageCount", 1),
            illustType = body.optInt("illustType", 0),
            isBookmarked = !body.isNull("bookmarkData"),
            thumbnailUrl = firstNonBlank(
                urls?.optString("small", "") ?: "",
                urls?.optString("regular", "") ?: "",
                body.optString("url", "")
            ),
            originalUrl = firstNonBlank(
                urls?.optString("original", "") ?: "",
                body.optString("original", "")
            ),
            tags = buildList {
                val entries = body.optJSONObject("tags")?.optJSONArray("tags") ?: JSONArray()
                for (index in 0 until entries.length()) {
                    val entry = entries.optJSONObject(index) ?: continue
                    val raw = entry.optString("tag", "").trim()
                    if (raw.isNotEmpty()) add(raw)
                    val translation = entry.optJSONObject("translation")
                    val translated = translation?.optString("en", "")?.trim().orEmpty()
                    if (translated.isNotEmpty()) add(translated)
                }
            }
        )
    }

    fun pageOriginalUrls(id: String): List<String> {
        val root = getJson("$BASE/ajax/illust/$id/pages?lang=en")
        val body: JSONArray = root.getJSONArray("body")
        return buildList {
            for (i in 0 until body.length()) {
                add(body.getJSONObject(i).getJSONObject("urls").getString("original"))
            }
        }
    }

    fun bookmark(id: String) {
        val token = csrfToken ?: fetchCsrfToken().also { csrfToken = it }
        val payload = JSONObject()
            .put("illust_id", id)
            .put("restrict", 0)
            .put("comment", "")
            .put("tags", JSONArray())
            .toString()

        try {
            postJson("$BASE/ajax/illusts/bookmarks/add", payload, token)
        } catch (e: HttpStatusException) {
            if (e.code == 403) {
                val fresh = fetchCsrfToken().also { csrfToken = it }
                postJson("$BASE/ajax/illusts/bookmarks/add", payload, fresh)
            } else {
                throw e
            }
        }
    }

    private fun artworkPreviews(
        userId: String,
        ids: List<String>
    ): List<ArtworkPreview> {
        if (ids.isEmpty()) return emptyList()

        val idQuery = ids.distinct().joinToString("&") { "ids%5B%5D=$it" }
        val root = getJson(
            "$BASE/ajax/user/$userId/profile/illusts?" +
                "$idQuery&work_category=illustManga&is_first_page=1&lang=en"
        )
        val body = root.optJSONObject("body") ?: return emptyList()
        val works = body.optJSONObject("works") ?: body
        val out = mutableListOf<ArtworkPreview>()

        for (id in ids) {
            val item = works.optJSONObject(id) ?: continue
            out += artworkPreview(id, item, userId)
        }
        return out
    }

    private fun artworkPreview(
        id: String,
        item: JSONObject?,
        fallbackUserId: String
    ): ArtworkPreview {
        val urls = item?.optJSONObject("urls")
        return ArtworkPreview(
            id = id,
            userId = item?.optString("userId", fallbackUserId)?.takeIf { it.isNotBlank() }
                ?: fallbackUserId,
            title = item?.optString("title", "") ?: "",
            thumbnailUrl = firstNonBlank(
                item?.optString("url", "") ?: "",
                urls?.optString("small", "") ?: "",
                urls?.optString("regular", "") ?: "",
                urls?.optString("thumb_mini", "") ?: ""
            )
        )
    }

    private fun firstNonBlank(vararg values: String): String? =
        values.firstOrNull { it.isNotBlank() }

    private fun findString(obj: JSONObject, key: String): String? {
        if (obj.has(key)) {
            val value = obj.optString(key, "")
            if (value.isNotBlank()) return value
        }

        val keys = obj.keys()
        while (keys.hasNext()) {
            val next = keys.next()
            when (val value = obj.opt(next)) {
                is JSONObject -> findString(value, key)?.let { return it }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val child = value.optJSONObject(i) ?: continue
                        findString(child, key)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun fetchCsrfToken(): String {
        val html = requestText("$BASE/", "GET", null, null)
        return PixivCsrf.extract(html)
            ?: throw IOException("Could not read Pixiv CSRF token. Reconnect Pixiv and try again.")
    }

    private fun getJson(url: String): JSONObject {
        val root = JSONObject(requestText(url, "GET", null, null))
        if (root.optBoolean("error", false)) {
            throw IOException(root.optString("message", "Pixiv returned an error"))
        }
        return root
    }

    private fun postJson(url: String, body: String, csrf: String): JSONObject {
        val root = JSONObject(requestText(url, "POST", body, csrf))
        if (root.optBoolean("error", false)) {
            throw IOException(root.optString("message", "Pixiv returned an error"))
        }
        return root
    }

    private fun requestText(
        urlString: String,
        method: String,
        body: String?,
        csrf: String?
    ): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        NetworkRequestRegistry.register(conn)
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 12_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.8")
            conn.setRequestProperty("Referer", "$BASE/")
            if (!csrf.isNullOrBlank()) conn.setRequestProperty("X-CSRF-TOKEN", csrf)

            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                throw HttpStatusException(code, "HTTP $code from Pixiv: " + responseText.take(240))
            }
            return responseText
        } catch (t: Throwable) {
            if (SyncControl.isStopping()) throw SyncCancelledException()
            throw t
        } finally {
            NetworkRequestRegistry.unregister(conn)
            runCatching { conn.disconnect() }
        }
    }

}

class HttpStatusException(val code: Int, message: String) : IOException(message)
