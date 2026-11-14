package com.azrael.galleryflow

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale

/**
 * Pure HTML inspection used by the generic adapter.
 * Albums commonly contain previews linking to a separate photo page.
 * Prefer a verified original/direct image over those index thumbnails.
 */
object AlbumImageExtractor {
    private val imageExtensions =
        Regex("\\.(?:jpe?g|png|gif|webp|avif)(?:$|[?#])", RegexOption.IGNORE_CASE)
    private val photoPattern = Regex(
        "/(?:photo|photos|pic|pics|picture|pictures|image|images|media|view)/[^/?#]+",
        RegexOption.IGNORE_CASE
    )
    private val junk = listOf(
        "favicon", "avatar", "logo", "emoji", "tracking", "/pixel",
        "/ads/", "/banner", "spacer", "sprite", "placeholder"
    )

    data class Item(val url: String, val referer: String)

    private fun absolute(base: String, raw: String): String? = runCatching {
        val value = URI(base).resolve(raw.trim()).normalize()
        if (value.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http") ||
            value.host.isNullOrBlank()) null else value.toString()
    }.getOrNull()

    private fun eligible(url: String): Boolean =
        junk.none { url.lowercase(Locale.ROOT).contains(it) } &&
            (imageExtensions.containsMatchIn(url) || url.contains("/cdn/") ||
                url.contains("/image/", ignoreCase = true))

    private fun bestSrcset(value: String): String? = value.split(',')
        .mapNotNull { component ->
            val segments = component.trim().split(Regex("\\s+"))
            val src = segments.firstOrNull().orEmpty()
            if (src.isBlank()) null else {
                val score = segments.getOrNull(1)?.removeSuffix("w")
                    ?.removeSuffix("x")?.toFloatOrNull() ?: 0f
                score to src
            }
        }.maxByOrNull { it.first }?.second

    /** Album thumbs that link to same-site dedicated photo pages. */
    fun photoPages(doc: Document, albumUrl: String): List<String> {
        val host = URI(albumUrl).host?.lowercase(Locale.ROOT)
        val links = linkedSetOf<String>()
        for (a in doc.select("a[href]")) {
            val link = absolute(doc.baseUri(), a.attr("href")) ?: continue
            val uri = runCatching { URI(link) }.getOrNull() ?: continue
            if (uri.host?.lowercase(Locale.ROOT) != host) continue
            if (imageExtensions.containsMatchIn(link)) continue
            val classes = (a.className() + " " + a.parent()?.className().orEmpty())
                .lowercase(Locale.ROOT)
            // Recommendation/related/sidebar cards on an album page belong to
            // OTHER albums. Do not silently expand a single selected album into
            // arbitrary photo-detail pages from those widgets.
            if(a.closest("aside,.related,.recommended,.recommendations,"+
                    ".you-may-like,.sidebar,nav,header,footer")!=null)continue
            val hasPreview = a.selectFirst("img,picture,video[poster]") != null
            val detail = photoPattern.containsMatchIn(uri.path) ||
                classes.contains("photo") || classes.contains("thumb")
            if (hasPreview && detail && link != albumUrl) links.add(link.substringBefore('#'))
        }
        return links.toList()
    }

    /**
     * Finds original image URLs on a photo/detail page or an album where images
     * are directly embedded. Does not use og:image unless no real images exist.
     */
    fun originals(doc: Document, referer: String): List<Item> {
        val urls = linkedSetOf<String>()
        fun add(raw: String?) {
            if (raw.isNullOrBlank()) return
            val url = absolute(doc.baseUri().ifBlank { referer }, raw) ?: return
            if (eligible(url)) urls.add(url)
        }
        // Explicit download/full-size links outrank visible thumbnails.
        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (imageExtensions.containsMatchIn(href) &&
                (a.hasAttr("download") ||
                    a.selectFirst("img") != null ||
                    a.className().contains("download", true) ||
                    a.className().contains("original", true) ||
                    a.text().contains("full", true))) add(href)
        }
        for (img in doc.select("img,picture source")) {
            if(img.closest("aside,.related,.recommended,.recommendations,"+
                    ".you-may-like,.sidebar,nav,header,footer")!=null)continue
            val isSource = img.tagName() == "source"
            if (isSource) {
                bestSrcset(img.attr("srcset"))?.let(::add)
                continue
            }
            // Do not accept tiny widgets when sizes are specified.
            val w = img.attr("width").toIntOrNull()
            val h = img.attr("height").toIntOrNull()
            if ((w != null && w < 160) || (h != null && h < 160)) continue
            val candidates = listOf(
                "data-original", "data-full", "data-full-src", "data-zoom",
                "data-highres", "data-large", "data-image", "data-src"
            )
            val preferred = candidates.firstNotNullOfOrNull { attr ->
                img.attr(attr).takeIf(String::isNotBlank)
            }
            val fromPicture = img.parent()?.takeIf { it.tagName() == "picture" }
                ?.select("source[srcset]")?.mapNotNull { bestSrcset(it.attr("srcset")) }
                ?.lastOrNull()
            val srcset = bestSrcset(img.attr("srcset"))
            val parentFull = img.parents().take(2).firstOrNull {
                it.tagName() == "a" && imageExtensions.containsMatchIn(it.attr("href"))
            }?.attr("href")
            add(parentFull ?: preferred ?: fromPicture ?: srcset ?: img.attr("src"))
        }
        // A detail page can have a displayed thumbnail and an og:image original.
        if (urls.isEmpty() || urls.all { likelyThumbnail(it) }) {
            doc.select("meta[property=og:image],meta[name=twitter:image]").forEach {
                add(it.attr("content"))
            }
        }
        val full = urls.filterNot(::likelyThumbnail)
        return (full.ifEmpty { urls.toList() }).map { Item(it, referer) }
    }

    private fun likelyThumbnail(url: String): Boolean {
        val value = url.lowercase(Locale.ROOT)
        return listOf("/thumb/", "/thumbs/", "thumbnail", "_thumb", "-thumb",
            "/preview/", "_small.", "-150x", "-300x").any { value.contains(it) }
    }

    fun directImages(doc: Document): List<Item> = originals(doc, doc.baseUri())
}
