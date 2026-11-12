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
        Profile("kiutaku.com","COSPLAY",
            cosplay+",main a[href],.gallery-grid a[href]",
            Regex("^/[0-9]{1,9}/?$"),"cosplay"),
        Profile("cosplaytele.com","COSPLAY",cosplay,
            Regex("^/[a-z0-9][a-z0-9_-]{2,180}/?$"),"cosplay"),
        Profile("pornpics.com","COSPLAY",
            cosplay+",.gallery-thumb a[href],.thumb a[href],.galleries a[href]",
            Regex("^/(?:galleries|gallery)/[^/?#]+(?:/[^/?#]+)?/?$"),"cosplay"),
        // The publisher serves full recording pages at numeric /<id> paths,
        // not gallery-photo posts or a /video/<id> route.
        Profile("archivebate.com","ADULT",
            "main a[href],article a[href],.video a[href],"+
            ".videos a[href],.video-card a[href],.card a[href]",
            Regex("^/[0-9]{1,10}/?$"),"video"),
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
                !profile.path.matches(url.path.orEmpty()))continue
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
            val image=cover?.let{img->
                listOf("data-original","data-src","src").firstNotNullOfOrNull{
                    k->img.absUrl(k).takeIf{u->SourceIndexParser.safe(u)}
                }
            }
            found.putIfAbsent(destination,IndexedItem(destination,title,image,
                base,area,profile.kind,now,false))
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
            val url=runCatching{URI(doc.location()).resolve(raw).toString()}
                .getOrNull()?:return
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
            "main .post-content,main .content,article .post-content")
        val parts=if(scopes.isNotEmpty())scopes else doc.select("article")
        for(content in parts.take(30)){
            content.select(".related,.related-posts,.recommendations,.sidebar,"+
                "aside,nav,header,footer,.advertisement").remove()
            for(link in content.select("a[href]")){
                if(link.selectFirst("img")==null)continue
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
                    val direct=image.absUrl("src")
                    val photoHost=runCatching{URI(direct).host.orEmpty().lowercase(Locale.ROOT)}
                        .getOrDefault("")
                    if(width>=600 && height>=600 &&
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
        return results.values.toList()
    }
}
