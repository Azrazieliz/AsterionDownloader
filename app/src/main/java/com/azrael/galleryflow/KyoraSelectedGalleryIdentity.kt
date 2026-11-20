package com.azrael.galleryflow

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** The original Kyora adapters own gallery IDs, not the unified UI.
 * Generic SHA IDs break BuonDua's /photos/<album-id>/ originals and create
 * duplicate receipts for 4KHD/CosplayTele on otherwise valid selected albums.
 */
internal object KyoraSelectedGalleryIdentity {
    private fun host(url:String)=URI(url).host.orEmpty()
        .lowercase(Locale.ROOT).removePrefix("www.")

    fun sameApprovedSite(origin:String,selected:String):Boolean {
        val a=host(origin)
        val b=host(selected)
        return a==b || setOf(a,b)==setOf("buondua.com","buondua.net")
    }

    fun parent(entities:List<EntityRecord>,entityId:String,
               rawUrl:String,selectedSiteHost:String?,
               selectedSiteSource:String?=null):EntityRecord? {
        val selectedHost=host(rawUrl)
        // SiteControls stores BuonDua's .com/.net aliases under .net.
        fun provider(url:String):String =
            host(url).let{if(it=="buondua.com")"buondua.net" else it}
        val matched=entities.filter{
            it.entityId==entityId && it.kind=="site" &&
                (selectedSiteSource==null ||
                    it.source.wireName==selectedSiteSource) &&
                sameApprovedSite(it.canonicalUrl,rawUrl) &&
                (selectedSiteHost==null ||
                    provider(it.canonicalUrl)==selectedSiteHost)
        }
        // An ambiguous root is an error, never a random other site.
        return matched.singleOrNull()
    }

    fun canonicalUrl(source:SourceId,raw:String):String {
        val uri=URI(raw)
        require(uri.scheme=="https" && uri.userInfo==null &&
            uri.port==-1 && uri.fragment==null){"Invalid gallery URL"}
        // The old Kyora BuonDua adapter uses buondua.net for originals.
        // .com is a known catalogue alias, not a second storage provider.
        if(source==SourceId.BUONDUA){
            require(host(raw) in setOf("buondua.net","buondua.com")){
                "Not a BuonDua gallery"
            }
            return "https://buondua.net"+uri.rawPath.orEmpty().trimEnd('/')
        }
        return raw
    }

    fun galleryId(source:SourceId,selected:String):String {
        val path=URI(selected).path.orEmpty().trimEnd('/')
        return when(source){
            SourceId.KIUTAKU -> {
                require(Regex("^/[0-9]{1,9}$").matches(path)){
                    "Kiutaku selected gallery must have a numeric ID"
                }
                path.removePrefix("/")
            }
            SourceId.FOUR_K_HD -> {
                require(Regex("""^/content/[0-9]{1,8}/[A-Za-z0-9_-]{2,180}\.html$""")
                    .matches(path)){"Expected a 4KHD published album"}
                path.removePrefix("/content/").removeSuffix(".html")
            }
            SourceId.BUONDUA -> {
                require(Regex("""^/albums/[A-Za-z0-9_-]{1,200}$""")
                    .matches(path)){"Expected a BuonDua published album"}
                path.substringAfterLast('/')
            }
            SourceId.COSPLAYTELE -> {
                require(Regex("""^/[A-Za-z0-9_-]{2,180}$""").matches(path)){
                    "Expected a CosplayTele album post"
                }
                path.trim('/')
            }
            else -> MessageDigest.getInstance("SHA-256")
                .digest(selected.toByteArray(Charsets.UTF_8))
                .joinToString(""){"%02x".format(it)}.take(32)
        }
    }
}
