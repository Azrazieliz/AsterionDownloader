package com.azrael.pixivdumpsync

import java.net.URI
import java.util.Locale

/** Enforce the distinction between a real post/file and a provider's
 * navigation, language, category or marketing pages.
 *
 * Indexing is not download verification. This check only permits eligible
 * media entries into feeds; it never calls a file 'saved' or 'completed'.
 */
object IndexedMediaQuality {
    private val file=Regex(
        "(?i)\\.(?:mp4|m4v|webm|mkv|mov|epub|zip|7z|rar|cbz|blend|fbx|obj|glb|gltf|stl|apk|torrent|png|jpe?g|webp|gif|avif|pmx|pmd|vmd|vpd|vrm|moc3|unitypackage)(?:[?#]|$)")
    private val siteMenu=Regex(
        "(?i)^(?:home|index|next|previous|all|all videos|new|latest|trending|popular|search|browse|explore|discover|categories|category|tags|tag|login|log in|sign in|register|account|about|contact|privacy|terms|language|languages|choose language|select language|all sites|more videos|load more|recommended)$")
    private val localeMenu=Regex(
        "(?i)^(?:english|portugu[eê]s|espa[nñ]ol|fran[cç]ais|deutsch|italiano|bahasa(?: indonesia| melayu)?|c[eě]ština|arabic|русский|日本語|한국어|中文|العربية|हिन्दी|русский язык|traducción|translation|language:.*)$")
    private val blockedSegments=setOf(
        "language","languages","lang","locale","locales","category",
        "categories","tag","tags","search","browse","explore","index",
        "login","register","signup","account","privacy","terms","contact",
        "dmca","about","faq","advertise","sitemap","popular","latest",
        "trending","countries")
    private val mediaSegments=setOf(
        "video","videos","watch","view","post","posts","gallery","g",
        "album","albums","galleries","story","stories",
        "artworks","artwork","novel","book","books","chapter","chapters",
        "works","products","product","downloads","file","release","torrent",
        "game","games","id","item")
    fun navigationTitle(title:String):Boolean {
        val cleaned=title.trim().replace(Regex("\\s+")," ").lowercase(Locale.ROOT)
        return cleaned.isBlank() || cleaned.length<3 ||
            siteMenu.matches(cleaned) || localeMenu.matches(cleaned) ||
            cleaned in setOf("ad information","notice to users",
                "advertising information","cookie policy","user agreement") ||
            // Release calendars and account listings are not downloadable
            // individual originals, even when an image appears beside them.
            Regex("""(?i)^permanent link to upcoming .{3,100} (?:hentai )?release$""")
                .matches(cleaned) ||
            Regex("""(?i)^[a-z0-9_]{3,28}\s+videos$""").matches(cleaned) ||
            cleaned.startsWith("choose your language") ||
            cleaned.startsWith("site language")
    }
    fun isMedia(url:String,title:String,preview:String?,area:String):Boolean {
        if(navigationTitle(title))return false
        val uri=runCatching{URI(url)}.getOrNull()?:return false
        if(uri.scheme !in setOf("http","https"))return false
        val segments=uri.path.orEmpty().trim('/').lowercase(Locale.ROOT)
            .split('/').filter{it.isNotBlank()}
        if(segments.isEmpty())return false
        if(segments.any{it in blockedSegments})return false
        // Creator profile path segments are valid ancestors of individual
        // posts, but a profile itself is not a media item.
        if(segments.first() in setOf("models","performers","users",
                "artists","creators","model","performer","artist","creator") &&
            segments.size<=2)return false
        if(file.containsMatchIn(url))return true
        // RSS/catalog results for torrents can legitimately lack cover images,
        // but site navigation pages can never count as individual releases.
        if(area=="GAMES" && segments.any{it in setOf("torrent","release","game","games")})
            return segments.size>=2
        if(preview.isNullOrBlank())return false
        if(!runCatching{URI(preview).scheme in setOf("http","https")}
                .getOrDefault(false))return false
        if(segments.size>=2 && segments.any{it in mediaSegments})return true
        if(segments.size>=2 &&
            !segments.last().matches(Regex("(?i)page-?\\d+|p\\d+|\\d+")))return true
        return false
    }
}
