package com.azrael.galleryflow

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class TeraBoxResolver(private val http: HttpClient) {
    fun resolvePublicShare(url: String, referer: String? = null,
                           requireArchive:Boolean=false): String {
        val budget=GalleryAcquisitionBudget()
        val landing = http.textResponse(url, referer, timeoutMs=budget.nextTimeoutMs())
        val html = unescape(landing.body)

        // A selected 4KHD album must resolve an actual archive, not a
        // share landing page's generic direct URL or promotional video.
        if(!requireArchive)extractDirect(html)?.let { return it }
        else uniqueArchiveLandingLink(html)?.let{return it}

        val jsToken = extractJsToken(html)
            ?: throw InteractiveProviderRequiredException(
                "TeraBox",
                landing.finalUrl,
                "TeraBox share token could not be resolved automatically."
            )
        val shortUrl = extractShortUrl(landing.finalUrl, html)
            ?: throw InteractiveProviderRequiredException(
                "TeraBox",
                landing.finalUrl,
                "TeraBox share id could not be resolved automatically."
            )

        val files = listFiles(
            sharePage = landing.finalUrl,
            jsToken = jsToken,
            shortUrl = shortUrl,
            logId = publishedLogId(html),
            directory = null,
            depth = 0,
            budget = budget
        )
        if (files.isEmpty()) {
            throw InteractiveProviderRequiredException(
                "TeraBox",
                landing.finalUrl,
                "TeraBox share exposed no downloadable files."
            )
        }

        val preferred = if(requireArchive){
            val candidates=files.filter{
                it.size>0 && isArchive(it.name) && secureFileUrl(it.url)
            }.distinctBy{it.name to it.url}
            if(candidates.size!=1)
                throw InteractiveProviderRequiredException(
                    "TeraBox",landing.finalUrl,
                    if(candidates.isEmpty())"Selected share exposed no complete archive"
                    else "Selected share contains multiple archives; choose the matching file")
            candidates.single()
        }else files.firstOrNull { isArchive(it.name) }
            ?: files.firstOrNull { isMedia(it.name) }
            ?: files.maxByOrNull { it.size }

        val direct = preferred?.url?.takeIf {
            if(requireArchive)secureFileUrl(it)
            else it.startsWith("http://") || it.startsWith("https://")
        }
        if (!direct.isNullOrBlank()) return direct

        throw InteractiveProviderRequiredException(
            "TeraBox",
            landing.finalUrl,
            "TeraBox returned file metadata but no direct download URL."
        )
    }

    private fun listFiles(
        sharePage: String,
        jsToken: String,
        shortUrl: String,
        logId: String?,
        directory: String?,
        depth: Int,
        budget:GalleryAcquisitionBudget
    ): List<RemoteFile> {
        budget.nextTimeoutMs()
        if (depth > 4) return emptyList()
        // Public shares currently have multiple first-party request shapes.
        // The prior implementation always forced channel=dubox and
        // clienttype=0; some shares reply errno=-4 with this shape even
        // when their own /share/list contract accepts the minimal fields.
        // We never invent a token, guess a password or bypass verification.
        val queries = shareListQueries(jsToken,shortUrl,directory,logId)
        val hosts = linkedSetOf(
            apiBase(sharePage),
            "https://www.1024tera.com",
            "https://www.terabox.com",
            "https://www.terabox.app"
        )

        var lastError: String? = null
        for (base in hosts) for ((variant,query) in queries.withIndex()) {
            val timeout=budget.nextTimeoutMs()
            val response = try {
                http.textResponse("$base/share/list?$query", sharePage,
                    "application/json,text/plain,*/*",timeoutMs=timeout)
            } catch (t: Exception) {
                if(t is SyncCancelledException || t is InterruptedException ||
                    Thread.currentThread().isInterrupted)throw t
                if(t is HttpStatusException && t.code in setOf(401,403,429))throw t
                lastError = t.message?.take(150)
                continue
            }
            val json = try {
                JSONObject(response.body)
            } catch (_: org.json.JSONException) {
                lastError = "TeraBox share-list response was not JSON"
                continue
            }
            val errno = json.optInt("errno", -1)
            if (errno != 0) {
                lastError = "public share/list error $errno on "+
                    URI(base).host+" request shape "+(variant+1)
                // A password/captcha requirement is not a retryable route.
                if(errno in setOf(400141,4000020,400210))throw
                    InteractiveProviderRequiredException(
                        "TeraBox",sharePage,
                        "Share requires its normal website verification.")
                continue
            }
            val list = json.optJSONArray("list") ?: JSONArray()
            val files = mutableListOf<RemoteFile>()
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val isDir = item.optInt("isdir", 0) == 1 || item.optString("isdir") == "1"
                if (isDir) {
                    val path = item.optString("path").takeIf { it.isNotBlank() } ?: continue
                    // Keep the ORIGINAL public share URL as Referer through
                    // nested directory traversal. An API /share/list URL is
                    // not the authorized share page and can cause errno=-4.
                    files += listFiles(sharePage, jsToken, shortUrl,
                        logId, path, depth + 1,budget)
                    continue
                }
                val name = item.optString("server_filename").ifBlank { "file-$i" }
                val dlink = item.optString("dlink")
                val size = when (val value = item.opt("size")) {
                    is Number -> value.toLong()
                    is String -> value.toLongOrNull() ?: 0L
                    else -> 0L
                }
                if (dlink.isNotBlank()) files += RemoteFile(name, dlink, size)
            }
            if (files.isNotEmpty()) return files
        }

        if (!lastError.isNullOrBlank()) {
            throw InteractiveProviderRequiredException(
                "TeraBox",
                sharePage,
                "TeraBox public-share API could not be resolved: $lastError"
            )
        }
        return emptyList()
    }

    /** Equivalent first-party public-share forms, in conservative order.
     * Do not derive a guessed access token or send a private account cookie to
     * third-party domains. Only the public share's actual jsToken is reused. */
    internal fun shareListQueries(
        token:String,shortUrl:String,dir:String?,publishedLogId:String?=null
    ):List<String> {
        require(token.length in 4..512 && shortUrl.length in 3..250)
        val standard=linkedMapOf(
            "app_id" to "250528",
            "jsToken" to token,
            "shorturl" to shortUrl,
            "page" to "1",
            "num" to "100"
        )
        standard[if(dir.isNullOrBlank())"root" else "dir"]=
            dir?.takeIf{it.isNotBlank()}?:"1"
        publishedLogId?.takeIf{
            it.matches(Regex("[A-Za-z0-9_-]{6,160}"))
        }?.let{standard["dp-logid"]=it}
        fun build(extra:Map<String,String>):String =
            (standard+extra).entries.joinToString("&"){
                enc(it.key)+"="+enc(it.value)
            }
        return listOf(
            build(emptyMap()),
            build(mapOf("web" to "1","channel" to "0",
                "clienttype" to "0","by" to "name","order" to "asc")),
            build(mapOf("web" to "1","channel" to "dubox",
                "clienttype" to "0","by" to "name","order" to "asc"))
        ).distinct()
    }

    /** Optional, publisher-rendered public-share request identifier.
     * Never synthesize a log id or mix it with a different share.
     * When absent, the exact old three request forms are preserved.
     */
    internal fun publishedLogId(html:String):String? {
        val pattern=Regex(
            """(?i)["']?dp[-_]?logid["']?\s*[:=]\s*["']?([A-Za-z0-9_-]{6,160})""")
        return pattern.find(html)?.groupValues?.getOrNull(1)
    }

    private fun extractJsToken(html: String): String? {
        val patterns = listOf(
            Regex("""fn%28%22([^%"]+)%22%29""", RegexOption.IGNORE_CASE),
            Regex("""window\.jsToken\s*=\s*["']([^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""["']jsToken["']\s*[:=]\s*["']([^"']+)""", RegexOption.IGNORE_CASE)
        )
        return patterns.asSequence()
            .mapNotNull { it.find(html)?.groupValues?.getOrNull(1) }
            .map { decodePercent(it) }
            .firstOrNull { it.isNotBlank() }
    }

    private fun extractShortUrl(finalUrl: String, html: String): String? {
        val uri = runCatching { URI(finalUrl) }.getOrNull()
        queryValue(uri?.rawQuery, "surl")?.let { return it }

        uri?.path?.let { path ->
            Regex("""/s/([^/?#]+)""").find(path)?.groupValues?.getOrNull(1)?.let { return it }
        }

        val patterns = listOf(
            Regex("""[?&]surl=([^&"'<>]+)""", RegexOption.IGNORE_CASE),
            Regex("""["']shorturl["']\s*[:=]\s*["']([^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""/s/([A-Za-z0-9_-]+)""")
        )
        return patterns.asSequence()
            .mapNotNull { it.find(html)?.groupValues?.getOrNull(1) }
            .map { decodePercent(it) }
            .firstOrNull { it.isNotBlank() }
    }

    internal fun secureFileUrl(url:String):Boolean=runCatching{
        val uri=URI(url)
        uri.scheme=="https" && !uri.host.isNullOrBlank() &&
            uri.userInfo==null && uri.port==-1 && uri.rawFragment==null
    }.getOrDefault(false)

    internal fun uniqueArchiveLandingLink(html:String):String? {
        // Only explicit file-valued metadata, not arbitrary .zip text or
        // unrelated page resources. Ambiguous metadata falls through to
        // the normal published share file list.
        val field=Regex("""["'](?:dlink|downloadUrl|download_url|directUrl|direct_url)["']\s*[:=]\s*["'](https?://[^"']+)""",
            RegexOption.IGNORE_CASE)
        return field.findAll(html).map{unescape(it.groupValues[1])}
            .filter{secureFileUrl(it) && isArchive(URI(it).path.orEmpty())}
            .distinct().toList().singleOrNull()
    }

    private fun extractDirect(html: String): String? {
        val patterns = listOf(
            Regex("""["'](?:dlink|downloadUrl|download_url|directUrl|direct_url)["']\s*[:=]\s*["'](https?://[^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'<>\s]+(?:\.zip|\.rar|\.7z|\.mp4|\.webm)(?:\?[^"'<>\s]*)?""", RegexOption.IGNORE_CASE)
        )
        return patterns.asSequence()
            .mapNotNull { regex ->
                val match = regex.find(html) ?: return@mapNotNull null
                if (match.groupValues.size > 1) match.groupValues[1] else match.value
            }
            .map(::unescape)
            .firstOrNull { it.startsWith("http") }
    }

    private fun apiBase(url: String): String {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
        return when {
            host.endsWith("terabox.app") -> "https://www.terabox.app"
            host.endsWith("1024tera.com") -> "https://www.1024tera.com"
            else -> "https://www.terabox.com"
        }
    }

    private fun queryValue(query: String?, name: String): String? {
        if (query.isNullOrBlank()) return null
        return query.split('&').firstNotNullOfOrNull { pair ->
            val p = pair.split('=', limit = 2)
            if (p.firstOrNull() == name) decodePercent(p.getOrElse(1) { "" }) else null
        }
    }

    private fun isArchive(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z")
    }

    private fun isMedia(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".mp4", ".webm", ".mov")
            .any(lower::endsWith)
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decodePercent(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private fun unescape(value: String): String = value
        .replace("&amp;", "&")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\/", "/")
        .replace("&quot;", "\"")

    private data class RemoteFile(
        val name: String,
        val url: String,
        val size: Long
    )
}
