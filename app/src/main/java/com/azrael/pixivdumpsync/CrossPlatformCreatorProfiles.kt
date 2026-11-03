package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.jsoup.nodes.Document
import java.net.URI
import java.util.Locale

data class CrossPlatformProfile(val source:String,val url:String,
    val platform:String,val evidence:String)

/** Provider-specific creator profile links, not a media identifier or file taxonomy.
 * A Pixiv teaser and a Patreon original retain separate post/media identities.
 * Never merge two creators based only on their similar display names.
 */
internal object CrossPlatformProfileParser {
    private val hosts=linkedMapOf(
        "pixiv.net" to "PIXIV", "x.com" to "X",
        "twitter.com" to "X", "patreon.com" to "PATREON",
        "fanbox.cc" to "FANBOX", "fantia.jp" to "FANTIA",
        "gumroad.com" to "GUMROAD", "onlyfans.com" to "ONLYFANS",
        "fansly.com" to "FANSLY", "koikatsucards.com" to "ASSETS",
        "pawchive.pw" to "ARCHIVE", "kemono.cr" to "ARCHIVE",
        "coomer.st" to "ARCHIVE", "deviantart.com" to "ARTWORK",
        "artstation.com" to "ARTWORK", "instagram.com" to "COSPLAY",
        "bsky.app" to "BLUESKY", "misskey.io" to "FEDIVERSE",
        "mastodon.social" to "FEDIVERSE", "youtube.com" to "VIDEO",
        "ko-fi.com" to "CREATORS", "boosty.to" to "CREATORS",
        "subscribestar.com" to "CREATORS",
        "subscribestar.adult" to "CREATORS",
        "skeb.jp" to "CREATORS", "booth.pm" to "ASSETS",
        "tumblr.com" to "ARTWORK", "cara.app" to "ARTWORK",
        "linktr.ee" to "LINK_HUB", "beacons.ai" to "LINK_HUB"
    )
    internal fun normalized(raw:String):Pair<String,String>?=runCatching{
        val uri=URI(raw.trim())
        if(uri.scheme!="https" || uri.userInfo!=null ||
            !SearchRanking.validUrl(raw))return@runCatching null
        val host=uri.host.orEmpty().lowercase(Locale.ROOT)
            .removePrefix("www.").removePrefix("mobile.").removePrefix("m.")
        val canonical=when(host){
            "twitter.com"->"x.com"
            "www.fanbox.cc"->"fanbox.cc"
            else->host
        }
        val fanboxSubdomain=canonical.endsWith(".fanbox.cc") &&
            canonical!="www.fanbox.cc" &&
            canonical.substringBefore(".fanbox.cc")
                .matches(Regex("[a-z0-9-]{2,60}"))
        val provider=hosts[canonical] ?:if(fanboxSubdomain)"FANBOX" else null
            ?:return@runCatching null
        val path=uri.path.orEmpty().trimEnd('/')
        val sections=path.trim('/').split('/').filter{it.isNotBlank()}
        // FANBOX creators commonly use username.fanbox.cc/ without a profile
        // path. This subdomain itself is the provider-assigned creator ID.
        if(sections.isEmpty()){
            if(fanboxSubdomain)return@runCatching ("https://"+canonical+"/") to provider
            return@runCatching null
        }
        val first=sections[0].lowercase(Locale.ROOT)
        if(first in setOf("explore","search","feed","login","signup","register",
                "terms","privacy","watch","post","posts","category","categories",
                "tag","tags","about","home","settings","intent","share","hashtag",
                "notifications","compose","messages","discover"))return@runCatching null
        val profile=when(provider){
            "PIXIV"->sections.size==2 && first=="users" &&
                sections[1].toLongOrNull()!=null
            "X"->sections.size==1 && Regex("[A-Za-z0-9_]{1,15}")
                .matches(sections[0])
            "FANTIA"->sections.size==2 && first=="fanclubs" &&
                sections[1].toLongOrNull()!=null
            "ARCHIVE"->sections.size>=3 && sections[1]=="user"
            "BLUESKY"->sections.size==2 && first=="profile"
            "FEDIVERSE"->sections.size==1 && sections[0].startsWith("@")
            "FANBOX"->if(fanboxSubdomain)sections.isEmpty() else sections.size in 1..2
            else->sections.size in 1..2
        }
        if(!profile)return@runCatching null
        ("https://"+canonical+path) to provider
    }.getOrNull()

    /** Discover only from a profile's explicit social/creator links.
     * Listing, comments, likes, sidebar recommendations and random page links
     * cannot prove identity. This creates suggestions, not auto-confirmations.
     */
    internal fun linkedProfiles(doc:Document,source:String):List<CrossPlatformProfile>{
        val original=normalized(source)?:return emptyList()
        val selected=doc.select(
            "link[rel=me][href],a[rel=me][href],"+
            "header a[href],.profile-links a[href],"+
            ".creator-links a[href],.social-links a[href],"+
            ".profile-bio a[href],.user-bio a[href],"+
            ".creator-bio a[href],.profile__links a[href]")
        val out=LinkedHashMap<String,CrossPlatformProfile>()
        for(anchor in selected.take(120)){
            val value=anchor.absUrl("href")
            val candidate=normalized(value)?:continue
            if(candidate.first==original.first ||
                candidate.second==original.second)continue
            out.putIfAbsent(candidate.first,CrossPlatformProfile(
                original.first,candidate.first,candidate.second,
                "Linked from "+original.first))
        }
        return out.values.take(24)
    }
}

class CrossPlatformCreatorDb(context:Context):
    SQLiteOpenHelper(context,"asterion_cross_platform_creators.sqlite",null,1){
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE profile_links(
            source TEXT NOT NULL, profile TEXT NOT NULL,
            platform TEXT NOT NULL, evidence TEXT NOT NULL,
            seen INTEGER NOT NULL, followed INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(source,profile))""")
        db.execSQL("CREATE INDEX cross_platform_lookup ON profile_links(platform,seen)")
    }
    override fun onUpgrade(db:SQLiteDatabase,old:Int,next:Int){}
    fun observe(records:List<CrossPlatformProfile>){
        for(record in records){
            val values=ContentValues().apply{
                put("source",record.source);put("profile",record.url)
                put("platform",record.platform);put("evidence",record.evidence)
                put("seen",System.currentTimeMillis())
            }
            // Refresh evidence recency without resetting the user's saved
            // follow decision or fabricating a new cross-platform identity.
            val database=writableDatabase
            if(database.insertWithOnConflict("profile_links",null,values,
                    SQLiteDatabase.CONFLICT_IGNORE)==-1L){
                database.update("profile_links",ContentValues().apply{
                    put("seen",System.currentTimeMillis())
                    put("platform",record.platform)
                    put("evidence",record.evidence)
                },"source=? AND profile=?",arrayOf(record.source,record.url))
            }
        }
    }
    fun candidates(limit:Int=80):List<CrossPlatformProfile>{
        val output=ArrayList<CrossPlatformProfile>()
        readableDatabase.rawQuery(
            """SELECT source,profile,platform,evidence FROM profile_links
                WHERE followed=0 ORDER BY seen DESC LIMIT ?""",
            arrayOf(limit.coerceIn(1,200).toString())).use{c->
            while(c.moveToNext())output+=CrossPlatformProfile(
                c.getString(0),c.getString(1),c.getString(2),c.getString(3))
        }
        return output
    }
    fun followed(source:String,url:String){
        writableDatabase.update("profile_links",
            ContentValues().apply{put("followed",1)},
            "source=? AND profile=?",arrayOf(source,url))
    }
}
