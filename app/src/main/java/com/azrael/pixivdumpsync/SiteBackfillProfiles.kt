package com.azrael.pixivdumpsync

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale

/** Site-specific listing contracts; never infer history from an arbitrary home
 * page or count registration as successful backfill. These profiles supplement
 * existing specialized engines without replacing their saved source identities.
 */
internal object SiteBackfillProfiles {
    private data class Profile(val host:String,val area:String,
        val selector:String,val path:Regex,val kind:String,
        val pager:String="a[rel=next][href],.pagination a.next[href],a.next[href],a.page-numbers.next[href]")

    private val chapters="article a[href],main a[href],.novel-list a[href],"+
        ".novel-item a[href],.book-item a[href],.list-novel a[href],"+
        ".hot-novels a[href],.novel-title a[href],.book-title a[href]"
    private val comics=".page-item a[href],.gallery a[href],.gallerythumb a[href],"+
        "#gallery a[href],.gallery-item a[href],.entry a[href],"+
        "main a[href],.manga-item a[href],.bs a[href]"
    private val cosplay="article h2 a[href],article h3 a[href],"+
        ".entry-title a[href],.post-title a[href],.post-thumbnail a[href],"+
        ".post-card a[href],.article-list a[href],.gallery-item a[href],"+
        "main article a[href],.post-grid a[href],.media-item a[href]"
    private val profiles=listOf(
        Profile("freewebnovel.com","BOOKS",chapters,
            Regex("^/novel/[A-Za-z0-9][A-Za-z0-9_-]*/?$"),"books"),
        Profile("novelbuddy.com","BOOKS",chapters,
            Regex("^/(?:novel|book)/[A-Za-z0-9][A-Za-z0-9_-]*/?$"),"books"),
        Profile("novelbuddy.me","BOOKS",chapters,
            Regex("^/(?:novel|book)/[A-Za-z0-9][A-Za-z0-9_-]*/?$"),"books"),
        Profile("readnovelfull.com","BOOKS",chapters,
            Regex("^/(?:novel|book)/[A-Za-z0-9][A-Za-z0-9_-]*/?$"),"books"),
        Profile("sushiscan.net","COMICS",comics,
            Regex("^/catalogue/[A-Za-z0-9][A-Za-z0-9_-]*/?$"),"comics"),
        Profile("nhentai.net","COMICS",comics,
            Regex("^/g/[0-9]{1,12}/?$"),"comics"),
        Profile("nhentai.xxx","COMICS",comics,
            Regex("^/g/[0-9]{1,12}/?$"),"comics"),
        // MP4Hentai's current public homepage exposes its recent videos
        // through dated WordPress post URLs. The wp-json endpoint can return
        // HTTP 403 while ordinary HTML listing access succeeds. The generic
        // heuristic previously discarded many legitimate article anchors.
        Profile("mp4hentai.com","HENTAI",
            ".gridwp-grid-post-title a[href],"+
            ".gridwp-grid-post-thumbnail a[href],"+
            ".gridwp-grid-post a[href],"+
            "article h2 a[href],article h3 a[href],"+
            ".entry-title a[href],.post-title a[href],"+
            "article .post-thumbnail a[href],"+
            ".post article a[href],main article a[href]",
            Regex("^/20[0-9]{2}/(?:0[1-9]|1[0-2])/.{3,240}/?$"),"video"),
        Profile("kiutaku.com","COSPLAY",
            cosplay+",main a[href],.gallery-grid a[href]",
            Regex("^/[0-9]{1,9}/?$"),"cosplay"),
        Profile("cosplaytele.com","COSPLAY",cosplay,
            Regex("^/[a-z0-9][a-z0-9_-]{2,180}/?$"),"cosplay"),
        Profile("pornpics.com","COSPLAY",
            cosplay+",.gallery-thumb a[href],.thumb a[href],.galleries a[href]",
            Regex("^/(?:galleries|gallery)/[^/?#]+(?:/[^/?#]+)?/?$"),"cosplay"),
        Profile("celeb.st","COSPLAY",
            cosplay+",.feed a[href],.media-list a[href]",
            Regex("^/(?:(?:p|post|posts|gallery|photos|media)/[^/?#]+|creator/[^/?#]+/(?:images|videos|posts))/?$"),
            "cosplay")
    ).associateBy{it.host}

    fun supports(host:String):Boolean=host.lowercase(Locale.ROOT)
        .removePrefix("www.") in profiles

    /** A first-party public cover is a post only if its path matches the
     * publisher-specific gallery URL; category and creator profiles excluded.
     */
    fun recognizesGalleryPage(source:String,target:String):Boolean {
        val origin=runCatching{URI(source)}.getOrNull()?:return false
        val url=runCatching{URI(target)}.getOrNull()?:return false
        val host=origin.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        val profile=profiles[host]?.takeIf{it.area=="COSPLAY"}?:return false
        return url.scheme=="https" && url.userInfo==null &&
            url.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")==host &&
            profile.path.matches(url.path.orEmpty().lowercase(Locale.ROOT))
    }

    fun entries(doc:Document,base:String,area:String,now:Long):
            List<IndexedItem>? {
        val uri=runCatching{URI(base)}.getOrNull()?:return null
        val host=uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
            ?:return null
        val profile=profiles[host]?.takeIf{it.area==area}?:return null
        val found=LinkedHashMap<String,IndexedItem>()
        for(link in doc.select(profile.selector).take(6000)){
            val url=runCatching{URI(doc.location()).resolve(link.attr("href"))}
                .getOrNull()?:continue
            if(url.scheme!="https" || url.userInfo!=null ||
                url.host?.lowercase(Locale.ROOT)?.removePrefix("www.")!=host ||
                !profile.path.matches(url.path.orEmpty()) ||
                (host=="mp4hentai.com" &&
                    url.path.orEmpty().contains("upcoming-",true) &&
                    url.path.orEmpty().contains("-hentai-release",true)))continue
            val destination=url.toString().substringBefore('#').trimEnd('/')+
                if(profile.kind=="books")"" else "/"
            val title=link.attr("title").ifBlank{
                link.text().takeIf{it.length>=3}.orEmpty()
            }.ifBlank{
                link.selectFirst("img")?.attr("alt").orEmpty()
            }.ifBlank{
                link.closest("article,.post,.gallery-item,.video,.video-card,.card")
                    ?.selectFirst("h2,h3,.entry-title,.title,.video-title")
                    ?.text().orEmpty()
            }.ifBlank{
                if(host=="archivebate.com")"Archivebate recording "+
                    url.path.trim('/').take(12) else ""
            }.trim().take(180)
            if(title.length<3)continue
            val cover=link.selectFirst("img")?:link.parent()?.selectFirst("img")
                ?:link.closest(".gridwp-grid-post,.post,.gridwp-grid-post-item")
                    ?.selectFirst("img")
            val image=cover?.let{img->
                listOf("data-original","data-src","src").firstNotNullOfOrNull{
                    k->img.absUrl(k).takeIf{u->SourceIndexParser.safe(u)}
                }
            }
            val prior=found[destination]
            if(prior==null || (prior.preview.isNullOrBlank() &&
                !image.isNullOrBlank())){
                found[destination]=IndexedItem(destination,
                    if(prior!=null && prior.title.length>title.length)
                        prior.title else title,
                    image?:prior?.preview,base,area,profile.kind,now,false)
            }
            require(found.size<2500){
                "Site-specific listing has too many records for one page; cursor not advanced"
            }
        }
        require(doc.select(profile.selector).size<=6000){
            "Site-specific listing exceeds safe anchor capacity; backfill paused"
        }
        return found.values.toList()
    }

    fun next(doc:Document,source:String,current:String):String?{
        val host=runCatching{URI(source).host?.lowercase(Locale.ROOT)
            ?.removePrefix("www.")}.getOrNull()?:return null
        val profile=profiles[host]?:return null
        val sourceBase=runCatching{URI(source)}.getOrNull()?:return null
        for(link in doc.select(profile.pager)){
            val raw=link.attr("href")
            if(raw.isBlank())continue
            val uri=runCatching{URI(doc.location()).resolve(raw)}
                .getOrNull()?:continue
            if(uri.scheme!="https" || uri.host?.lowercase(Locale.ROOT)
                    ?.removePrefix("www.")!=host ||
                uri.toString()==current || uri.userInfo!=null)continue
            // Navigation within the same listing/page family only.
            val currentPath=URI(current).path.orEmpty()
            val nextPath=uri.path.orEmpty()
            val category=sourceBase.path.trimEnd('/')
            val valid=nextPath==currentPath ||
                (category.isNotBlank() && nextPath.startsWith(category+"/")) ||
                Regex("/(?:page|paged)/[0-9]+/?$").containsMatchIn(nextPath)
            if(valid && SourceIndexParser.safe(uri.toString()))return uri.toString()
        }
        return null
    }

    /** The Kiutaku detail URL is numeric (/4495), not a /post/<id> URL.
     * Readers display full pictures under the body, so prioritize links to
     * high-resolution images and explicit original-image attributes.
     * Do not ingest recommendations/sidebar preview images as originals.
     */
    fun galleryOriginals(doc:Document,item:IndexedItem,now:Long):
            List<IndexedItem>{
        val host=runCatching{URI(item.url).host?.lowercase(Locale.ROOT)
            ?.removePrefix("www.")}.getOrNull()
        if(host !in setOf("kiutaku.com","cosplaytele.com","pornpics.com",
                "archivebate.com","celeb.st"))return emptyList()
        val results=LinkedHashMap<String,IndexedItem>()
        val banned=Regex("(?i)(?:thumb|thumbnail|preview|sample|avatar|logo|icon|sprite|placeholder|spinner)")
        fun record(raw:String,label:String){
            // Gallery filenames can contain Japanese/Chinese characters.
            // Canonical ASCII percent-encoding is required by Android HTTP
            // request targets; keep the exact publisher path, not a guessed URL.
            val url=runCatching{
                URI(doc.location()).resolve(URI(raw.trim())).toASCIIString()
            }.getOrNull()?:return
            if(!SourceIndexParser.safe(url) || banned.containsMatchIn(url))return
            val path=runCatching{URI(url).path.orEmpty().lowercase(Locale.ROOT)}
                .getOrDefault("")
            if(!listOf(".jpg",".jpeg",".png",".webp",".avif",".gif")
                .any{path.endsWith(it)})return
            val filename=path.substringAfterLast('/')
            results.putIfAbsent(url,IndexedItem(url,filename,item.preview,
                item.source,item.area,"original",now,false))
        }
        val scopes=doc.select("main article,article .entry-content,main .gallery,"+
            "main .post-content,main .content,article .post-content,"+
            "#content .entry-content,#post-content,.post-body,.gallery-images")
        // Kiutaku's real photo reader can be a simple main/#content
        // section rather than an <article>. Do not fall back to unrelated
        // global <img> tags: only genuine primary content qualifies.
        val parts=if(scopes.isNotEmpty())scopes else
            doc.select("main,#content,#main,article,.post-content")
        for(content in parts.take(30)){
            content.select(".related,.related-posts,.recommendations,.sidebar,"+
                "aside,nav,header,footer,.advertisement").remove()
            for(link in content.select("a[href]")){
                val url=link.absUrl("href")
                val originalImageLink=host=="kiutaku.com" && runCatching{
                    val uri=URI(url)
                    val host=uri.host.orEmpty().lowercase(Locale.ROOT)
                    host in setOf("mitaku.net","www.mitaku.net",
                        "kiutaku.com","www.kiutaku.com") &&
                    uri.path.orEmpty().contains("/wp-content/uploads/") &&
                    Regex("""(?i)\.(?:jpe?g|png|webp|avif)$""")
                        .containsMatchIn(uri.path.orEmpty())
                }.getOrDefault(false)
                if(link.selectFirst("img")==null && !originalImageLink)continue
                record(link.attr("href"),link.attr("title"))
                if(results.size>=3000)break
            }
            for(image in content.select("img")){
                // Kiutaku embeds the publisher's high-resolution originals
                // directly as article img[src] (often hosted by mitaku.net).
                // Only sizeable photo-body images qualify; never sidebar
                // thumbnails, tracking pixels, icons or arbitrary listing art.
                if(host=="kiutaku.com"){
                    val width=image.attr("width").toIntOrNull()?:0
                    val height=image.attr("height").toIntOrNull()?:0
                    val direct=listOf("data-full","data-original",
                        "data-full-src","data-highres","data-src","src")
                        .map{image.absUrl(it)}
                        .firstOrNull{raw->
                            runCatching {
                                val uri=URI(raw)
                                uri.host.orEmpty().lowercase(Locale.ROOT) in
                                    setOf("mitaku.net","www.mitaku.net",
                                        "kiutaku.com","www.kiutaku.com") &&
                                uri.path.orEmpty().contains("/wp-content/uploads/")
                            }.getOrDefault(false)
                        }?:image.absUrl("src")
                    val photoHost=runCatching{URI(direct).host.orEmpty().lowercase(Locale.ROOT)}
                        .getOrDefault("")
                    val publisherImage=runCatching{
                        URI(direct).path.orEmpty().lowercase(Locale.ROOT)
                            .contains("/wp-content/uploads/")
                    }.getOrDefault(false)
                    if((width>=600 && height>=600 || publisherImage) &&
                        photoHost in setOf("mitaku.net","www.mitaku.net",
                            "kiutaku.com","www.kiutaku.com"))
                        record(direct,image.attr("alt"))
                }
                // A listing's lazy data-src and ordinary img[src] are usually
                // preview images. Download originals only when the publisher
                // explicitly supplies a full-resolution attribute or an
                // image link (handled in the anchor loop above).
                for(attr in listOf("data-full","data-original","data-full-src",
                    "data-highres")){
                    val raw=image.attr(attr)
                    if(raw.isBlank())continue
                    record(raw,image.attr("alt"))
                    break
                }
                if(results.size>=3000)break
            }
        }
        if(host=="kiutaku.com" && results.isEmpty() && runCatching{
            Regex("""^/[0-9]{1,9}/?$""").matches(URI(item.url).path.orEmpty())
        }.getOrDefault(false)){
            // Live /7440 uses full-resolution photos under the page's body,
            // without the old <article>/<main> wrappers. Scan ONLY this
            // validated numeric detail page and ONLY publisher originals
            // on mitaku.net with numbered photo filenames. Avoid cards,
            // advertising images, montage covers and related-site widgets.
            for(element in doc.select("img[src],img[data-src],a[href]").take(600)){
                val raw=when(element.tagName()){
                    "a"->element.absUrl("href")
                    else->element.absUrl("data-src").ifBlank{element.absUrl("src")}
                }
                val accepted=runCatching{
                    val uri=URI(raw)
                    val imageHost=uri.host.orEmpty().lowercase(Locale.ROOT)
                    val path=uri.path.orEmpty()
                    imageHost in setOf("mitaku.net","www.mitaku.net") &&
                        path.contains("/wp-content/uploads/") &&
                        Regex("""(?i)-[0-9]{1,4}\.(?:jpe?g|png|webp|avif)$""")
                            .containsMatchIn(path)
                }.getOrDefault(false)
                if(accepted)record(raw,element.attr("alt"))
                if(results.size>=3000)break
            }
        }
        return results.values.toList()
    }
}
