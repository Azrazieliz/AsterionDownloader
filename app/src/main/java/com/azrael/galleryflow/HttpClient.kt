package com.azrael.galleryflow

import android.webkit.CookieManager
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Collections
import java.util.Locale

object NetworkRequestRegistry {
    private val active = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())
    fun register(conn: HttpURLConnection) { active += conn }
    fun unregister(conn: HttpURLConnection) { active -= conn }
    fun cancelAll() {
        val copy = synchronized(active) { active.toList() }
        copy.forEach { runCatching { it.disconnect() } }
    }
}

/** The live 4KGirl publisher returned HTTP 200 containing the 138-byte
 * nginx "403 Forbidden" document. An HTTP-code-only check mistakenly treated
 * that denial as a successful empty gallery and never tried the existing
 * authorized browser fallback. Do not classify ordinary content as a denial.
 */
internal object GalleryAccessGate {
    private val forbiddenTitle=Regex(
        """(?is)<(?:title|h1)\b[^>]*>\s*403\s+Forbidden\s*</(?:title|h1)>"""
    )
    fun isMaskedForbidden(url:String,status:Int,body:String):Boolean {
        if(status!=200 || body.length !in 45..2048)return false
        val host=runCatching{URI(url).host.orEmpty()
            .lowercase(Locale.ROOT).removePrefix("www.")}
            .getOrDefault("")
        return host=="4kgirl.com" &&
            forbiddenTitle.containsMatchIn(body)
    }
}

open class HttpClient(private val context:android.content.Context?=null) {
    data class TextResponse(
        val body: String,
        val finalUrl: String,
        val contentType: String?
    )

    fun textResponse(
        url: String,
        referer: String? = null,
        accept: String = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    ): TextResponse {
        if (!SyncControl.checkpoint()) throw SyncCancelledException()
        val connection = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(30_000)
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .ignoreContentType(true)
            .header("Accept-Language", "en-US,en;q=0.8")
            .header("Accept", accept)

        // Gallery pages use the same configured per-provider route as video
        // discovery. Previously Jsoup ignored it entirely, making selected
        // Cosplay galleries fail under routes that worked in VideoFlow.
        val routedSite=referer?.takeIf { it.startsWith("http") } ?:url
        com.asterion.videoflow.NetworkRoute.javaProxy(context,routedSite)
            ?.let{connection.proxy(it)}
        if (!referer.isNullOrBlank()) connection.referrer(referer)
        webCookies(url)?.let { connection.header("Cookie", it) }

        val response = connection.execute()
        // These calls never ask for a Range. A successful 206 contains only
        // part of an original, even when its shorter Content-Length matches.
        if (response.statusCode() == 206)
            throw java.io.IOException("Unsolicited HTTP 206 partial page: " + url)
        if (response.statusCode() !in 200..299) {
            throw HttpStatusException(response.statusCode(), "HTTP " + response.statusCode() + " for " + url)
        }
        val finalUrl = response.url().toString()
        val body=response.body()
        if(GalleryAccessGate.isMaskedForbidden(finalUrl,response.statusCode(),body)){
            throw HttpStatusException(403,
                "4KGirl returned a 403 Forbidden page despite HTTP 200; "+
                "authorized browser session needed")
        }
        persistResponseCookies(finalUrl, response.headers("Set-Cookie"))
        return TextResponse(
            body = body,
            finalUrl = finalUrl,
            contentType = response.contentType()
        )
    }

    /** First successful fetch is unchanged. One bounded retry is allowed
     * ONLY for timed-out Kyora publisher HTML pages whose failures were
     * recorded on the owner's phone. Media bytes, uploads, authenticated
     * actions and every frozen video-provider implementation are untouched.
     */
    internal fun retryableGalleryHtml(address:String):Boolean {
        val host=runCatching{URI(address).host.orEmpty().lowercase(Locale.ROOT)}
            .getOrDefault("")
        return host in setOf("buondua.net","www.buondua.net",
            "buondua.com","www.buondua.com",
            "cosplaytele.com","www.cosplaytele.com",
            "kiutaku.com","www.kiutaku.com")
    }

    open fun document(url: String, referer: String? = null): Document {
        var attempt=0
        while(true){
            if (!SyncControl.checkpoint()) throw SyncCancelledException()
            try{
                val response=textResponse(url,referer)
                return Jsoup.parse(response.body,response.finalUrl)
            }catch(e:HttpStatusException){
                val host=runCatching{URI(url).host.orEmpty()
                    .lowercase(Locale.ROOT).removePrefix("www.")}
                    .getOrDefault("")
                // If 4KGirl denies plain HTML but serves its public page in
                // the ordinary browser, read the actual authorized DOM.
                // A blocked browser page still fails the gallery parser.
                if(e.code==403 && context!=null &&
                    host in setOf("4kgirl.com","4khd.com") &&
                    (com.azrael.pixivdumpsync.BrowserPageFetch.publicEligible(url) ||
                     SiteSession.bound(context,url))){
                    val html=com.azrael.pixivdumpsync.BrowserPageFetch.html(
                        context,url,referer)
                    if(GalleryAccessGate.isMaskedForbidden(url,200,html))
                        throw HttpStatusException(403,
                            "4KGirl browser still returned 403 Forbidden; "+
                            "gallery previews require publisher access")
                    return Jsoup.parse(html,url)
                }
                throw e
            }catch(e:java.net.SocketTimeoutException){
                if(attempt++>=1 || !retryableGalleryHtml(url))throw e
                // Delay only a failed, idempotent selected HTML GET.
                Thread.sleep(500L)
            }
        }
    }

    /** Read a selected publisher iframe through the application's existing
     * authorised browser owner, not a new WebView or media extractor.
     * Only a first-party CosplayTele player linked by its selected article
     * may be inspected; a failed/blocked browser never counts as media.
     */
    internal fun selectedCosplayTelePlayerHtml(
        selectedArticle:String,frame:String
    ):String? {
        val app=context?:return null
        if(!com.asterion.videoflow.SiteSpecificOriginalSources
                .selectedExtractorFrame(selectedArticle,frame))
            return null
        return com.azrael.pixivdumpsync.BrowserPageFetch.html(
            app,frame,selectedArticle)
    }

    fun resolveDownloadUrl(url: String, referer: String? = null, provider: String? = null): String =
        resolveDownloadUrlInternal(url, referer, provider, 0)

    private fun resolveDownloadUrlInternal(
        url: String,
        referer: String?,
        provider: String?,
        depth: Int
    ): String {
        if (!SyncControl.checkpoint()) throw SyncCancelledException()
        if (depth > 6) {
            throw InteractiveProviderRequiredException(
                provider ?: "Archive provider",
                url,
                "Too many provider redirects."
            )
        }

        val normalizedProvider = provider?.lowercase(Locale.ROOT).orEmpty()
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host?.lowercase(Locale.ROOT).orEmpty()
        val path = uri?.path?.lowercase(Locale.ROOT).orEmpty()

        if (isDirectFilePath(path)) return url

        if (normalizedProvider == "mediafire" || "mediafire.com" in host) {
            val page = textResponse(url, referer)
            val doc = Jsoup.parse(page.body, page.finalUrl)
            val direct = doc.selectFirst(
                "a#downloadButton[href],a.input[href],a[aria-label*=download][href],a[download][href]"
            )?.absUrl("href")?.takeIf { isUsefulDirectUrl(it) }
                ?: doc.select("a[href]").mapNotNull { a ->
                    a.absUrl("href").takeIf { isUsefulDirectUrl(it) }
                }.firstOrNull()
                ?: extractDirectFromText(page.body)

            if (!direct.isNullOrBlank()) return direct
            throw InteractiveProviderRequiredException(
                "MediaFire",
                page.finalUrl,
                "MediaFire did not expose a direct file URL."
            )
        }

        if (host == "m.4khd.com" || host.endsWith(".4khd.com")) {
            // The selected bridge can return HTTP 403 (or 200 masking 403)
            // to Jsoup while remaining accessible in the authorized browser.
            // Previously the exception escaped BEFORE the existing official
            // TeraBox/first-party link resolver was ever attempted.
            val page = try {
                textResponse(url, referer)
            } catch (blocked: HttpStatusException) {
                if (blocked.code != 403 || context == null ||
                    !com.azrael.pixivdumpsync.BrowserPageFetch.publicEligible(url))
                    throw blocked
                val rendered = com.azrael.pixivdumpsync.BrowserPageFetch.html(
                    context,url,referer)
                if (GalleryAccessGate.isMaskedForbidden(url,200,rendered))
                    throw blocked
                TextResponse(rendered,url,"text/html")
            }
            val doc = Jsoup.parse(page.body, page.finalUrl)
            // A placeholder #custom_button can have href="#",
            // javascript:void(0), or the current bridge page. Those are
            // NOT a selected TeraBox archive and must never prevent the
            // real delayed first-party link from being rendered.
            val staticBridge =
                doc.select("#custom_button[href],a[href*=terabox],"+
                    "a[href*=1024tera],a[href*=teraboxapp]")
                    .mapNotNull{it.absUrl("href").takeIf(::isReal4khdBridge)}
                    .firstOrNull()
                    ?: extractTeraBoxUrl(page.body)?.takeIf(::isReal4khdBridge)
                    ?: extractRedirect(doc)?.takeIf(::isReal4khdBridge)
            // m.4khd.com often adds the official TeraBox link AFTER its
            // first-party JavaScript initializes. Static HTML returning
            // only a page shell does not prove the selected archive absent.
            val browserContext=context
            val browserBridge=if(staticBridge.isNullOrBlank() &&
                host=="m.4khd.com" && browserContext!=null &&
                com.azrael.pixivdumpsync.BrowserPageFetch.publicEligible(url)){
                runCatching{
                    val html=com.azrael.pixivdumpsync.BrowserPageFetch.html(
                        browserContext,url,referer)
                    val rendered=Jsoup.parse(html,url)
                    rendered.selectFirst("a#custom_button[href]")
                        ?.absUrl("href")?.takeIf{isTeraBoxHost(hostOf(it))}
                        ?:extractTeraBoxUrl(html)
                }.getOrNull()
            }else null
            val bridgeTarget=staticBridge?:browserBridge

            if (!bridgeTarget.isNullOrBlank() && bridgeTarget != url) {
                return resolveDownloadUrlInternal(
                    bridgeTarget,
                    page.finalUrl,
                    if (isTeraBoxHost(hostOf(bridgeTarget))) "TeraBox" else provider,
                    depth + 1
                )
            }

            val finalHost = hostOf(page.finalUrl)
            if (isTeraBoxHost(finalHost)) {
                return TeraBoxResolver(this).resolvePublicShare(page.finalUrl, referer)
            }

            throw InteractiveProviderRequiredException(
                "4KHD/TeraBox",
                page.finalUrl,
                "4KHD bridge did not expose its TeraBox share URL."
            )
        }

        if (normalizedProvider == "terabox" || isTeraBoxHost(host)) {
            return TeraBoxResolver(this).resolvePublicShare(url, referer)
        }

        if (normalizedProvider == "sorafolder" || "sorafolder.com" in host) {
            val page = textResponse(url, referer)
            val doc = Jsoup.parse(page.body, page.finalUrl)
            val direct = doc.selectFirst(
                "a[download][href],a#download[href],a.download[href],a[href*=download]"
            )?.absUrl("href")?.takeIf { isUsefulDirectUrl(it) }
                ?: doc.select("[data-url],[data-download],[data-href]").mapNotNull { element ->
                    listOf("data-url", "data-download", "data-href")
                        .firstNotNullOfOrNull { attr ->
                            element.attr(attr).takeIf { it.startsWith("http") && isUsefulDirectUrl(it) }
                        }
                }.firstOrNull()
                ?: extractDirectFromText(page.body)

            if (!direct.isNullOrBlank() && hostOf(direct) != host) return direct
            if (!direct.isNullOrBlank() && isDirectFilePath(runCatching { URI(direct).path.lowercase(Locale.ROOT) }.getOrDefault(""))) {
                return direct
            }
            throw InteractiveProviderRequiredException(
                "SoraFolder",
                page.finalUrl,
                "SoraFolder requires its timed browser download for this file."
            )
        }

        if (normalizedProvider == "gofile" || "gofile.io" in host) {
            throw InteractiveProviderRequiredException(
                "Gofile",
                url,
                "Gofile link requires provider resolution."
            )
        }

        if (normalizedProvider == "telegram" || host == "t.me" || host.endsWith(".t.me")) {
            throw InteractiveProviderRequiredException(
                "Telegram",
                url,
                "Telegram is a mirror/navigation provider, not a direct archive URL."
            )
        }

        return url
    }

    /** Only a real selected file/share route, never a placeholder
     * anchor or a random same-page redirect posing as an archive. */
    internal fun isReal4khdBridge(value:String):Boolean {
        val parsed=runCatching{URI(value)}.getOrNull()?:return false
        if(parsed.scheme!="https" || parsed.userInfo!=null ||
            parsed.host.isNullOrBlank() || parsed.port!=-1 ||
            parsed.rawFragment!=null)return false
        val host=parsed.host!!.lowercase(Locale.ROOT)
        val path=parsed.path.orEmpty().lowercase(Locale.ROOT)
        return isTeraBoxHost(host) || isDirectFilePath(path)
    }

    private fun extractRedirect(doc: Document): String? {
        doc.selectFirst("meta[http-equiv=refresh],meta[http-equiv=Refresh]")?.attr("content")?.let { content ->
            Regex("(?i)url\\s*=\\s*['\"]?([^'\";]+)").find(content)?.groupValues?.get(1)?.trim()?.let { raw ->
                return runCatching { URI(doc.baseUri()).resolve(raw).toString() }.getOrDefault(raw)
            }
        }

        doc.select("a[href]").mapNotNull { it.absUrl("href").takeIf(String::isNotBlank) }
            .firstOrNull { candidate ->
                val h = hostOf(candidate)
                isTeraBoxHost(h) ||
                    isDirectFilePath(runCatching { URI(candidate).path.lowercase(Locale.ROOT) }.getOrDefault(""))
            }?.let { return it }

        val html = unescapeHtmlJs(doc.html())
        val scripted = listOf(
            Regex("(?i)(?:window\\.)?location(?:\\.href)?\\s*=\\s*['\"](https?://[^'\"]+)"),
            Regex("(?i)location\\.replace\\(\\s*['\"](https?://[^'\"]+)"),
            Regex("(?i)[\"'](?:url|target|redirect|location)[\"']\\s*:\\s*[\"'](https?://[^\"']+)")
        )
        for (regex in scripted) {
            regex.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun extractTeraBoxUrl(text: String): String? {
        val html = unescapeHtmlJs(text)
        return URL_REGEX.findAll(html)
            .map { it.value.trimEnd(')', ']', '}', ',', ';') }
            .firstOrNull { isTeraBoxHost(hostOf(it)) }
    }

    private fun extractDirectFromText(text: String): String? {
        val html = unescapeHtmlJs(text)
        val keyed = listOf(
            Regex("(?i)[\"'](?:dlink|downloadUrl|download_url|directUrl|direct_url|fileUrl|file_url)[\"']\\s*[:=]\\s*[\"'](https?://[^\"']+)"),
            Regex("(?i)(https?://[^\"'<>\\s]+(?:\\.zip|\\.rar|\\.7z|\\.mp4|\\.webm|\\.mov)(?:\\?[^\"'<>\\s]*)?)")
        )
        for (regex in keyed) {
            val match = regex.find(html) ?: continue
            val candidate = if (match.groupValues.size > 1) match.groupValues[1] else match.value
            if (isUsefulDirectUrl(candidate)) return candidate
        }
        return null
    }

    private fun unescapeHtmlJs(value: String): String = value
        .replace("&amp;", "&")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\/", "/")
        .replace("&quot;", "\"")

    private fun isUsefulDirectUrl(value: String): Boolean {
        if (!value.startsWith("http://") && !value.startsWith("https://")) return false
        val h = hostOf(value)
        if (h.endsWith("mediafire.com") && "/folder/" in value) return false
        val p = runCatching { URI(value).path.lowercase(Locale.ROOT) }.getOrDefault("")
        return isDirectFilePath(p) ||
            "download" in value.lowercase(Locale.ROOT) ||
            h.startsWith("download.") ||
            h.startsWith("d.")
    }

    private fun isDirectFilePath(path: String): Boolean =
        listOf(
            ".zip", ".rar", ".7z",
            ".mp4", ".webm", ".mov",
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif"
        ).any { path.endsWith(it) }

    private fun isTeraBoxHost(host: String): Boolean {
        val normalized=host.lowercase(Locale.ROOT)
        return setOf("terabox.com","terabox.app","teraboxapp.com",
            "1024tera.com","1024terabox.com","4funbox.com",
            "nephobox.com","mirrobox.com","momerybox.com").any{
                normalized==it || normalized.endsWith("."+it)
            }
    }

    private fun hostOf(value: String): String =
        runCatching { URI(value).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")

    open fun open(url: String, referer: String? = null, cookie: String? = null): OpenResponse {
        if (!SyncControl.checkpoint()) throw SyncCancelledException()
        val routedSite=referer?.takeIf { it.startsWith("http") } ?:url
        val proxy=com.asterion.videoflow.NetworkRoute.javaProxy(context,routedSite)
        // Kyora article URLs may contain Japanese/Chinese photo filenames.
        // The original media identity stays unchanged; HTTP requires an
        // ASCII-encoded request path. This also preserves signed query data.
        val destination=GalleryOriginalUrl.requestTarget(url)
        val conn = (if(proxy==null)URL(destination).openConnection()
            else URL(destination).openConnection(proxy)) as HttpURLConnection
        NetworkRequestRegistry.register(conn)
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            if (!referer.isNullOrBlank()) conn.setRequestProperty("Referer", referer)
            val sessionCookie = cookie?.takeIf { it.isNotBlank() } ?: webCookies(url)
            if (!sessionCookie.isNullOrBlank()) conn.setRequestProperty("Cookie", sessionCookie)

            val code = conn.responseCode
            if (code == 206) {
                conn.disconnect()
                NetworkRequestRegistry.unregister(conn)
                throw java.io.IOException(
                    "Unsolicited HTTP 206 partial original; no Range was requested: " + url)
            }
            if (code !in 200..299) {
                conn.disconnect()
                NetworkRequestRegistry.unregister(conn)
                throw HttpStatusException(code, "HTTP " + code + " for " + url)
            }

            conn.headerFields["Set-Cookie"]?.let { persistResponseCookies(conn.url.toString(), it) }
            return OpenResponse(
                connection = conn,
                input = BufferedInputStream(conn.inputStream),
                contentType = conn.contentType?.substringBefore(';'),
                contentLength = conn.contentLengthLong,
                contentDisposition = conn.getHeaderField("Content-Disposition"),
                finalUrl = conn.url.toString()
            )
        } catch (t: Throwable) {
            NetworkRequestRegistry.unregister(conn)
            runCatching { conn.disconnect() }
            throw t
        }
    }

    private fun webCookies(url: String): String? =
        (SiteSession.cookie(url) ?: runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull())
            ?.takeIf { it.isNotBlank() }

    private fun persistResponseCookies(url: String, headers: List<String>) {
        if (headers.isEmpty()) return
        runCatching {
            val manager = CookieManager.getInstance()
            headers.forEach { header -> manager.setCookie(url, header) }
            manager.flush()
        }
    }

    data class OpenResponse(
        val connection: HttpURLConnection,
        val input: BufferedInputStream,
        val contentType: String?,
        val contentLength: Long,
        val contentDisposition: String?,
        val finalUrl: String
    ) : AutoCloseable {
        override fun close() {
            runCatching { input.close() }
            NetworkRequestRegistry.unregister(connection)
            runCatching { connection.disconnect() }
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36 Kyora/0.6.0"
        private val URL_REGEX = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    }
}
