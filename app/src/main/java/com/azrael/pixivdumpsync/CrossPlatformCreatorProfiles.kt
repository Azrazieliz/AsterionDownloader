package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.jsoup.nodes.Document
import org.json.JSONObject
import org.json.JSONArray
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
        "linktr.ee" to "LINK_HUB", "beacons.ai" to "LINK_HUB",
        "lit.link" to "LINK_HUB", "potofu.me" to "LINK_HUB",
        "allmylinks.com" to "LINK_HUB", "bio.site" to "LINK_HUB",
        "carrd.co" to "LINK_HUB",
        "weibo.com" to "WEIBO", "weibo.cn" to "WEIBO",
        "bilibili.com" to "BILIBILI", "space.bilibili.com" to "BILIBILI",
        "xiaohongshu.com" to "XIAOHONGSHU",
        "lofter.com" to "LOFTER", "blog.naver.com" to "NAVER",
        "postype.com" to "POSTYPE", "ci-en.dlsite.com" to "CI_EN",
        "fantrie.com" to "CREATORS", "vgen.co" to "ARTWORK",
        "behance.net" to "ARTWORK", "poipiku.com" to "ARTWORK",
        "nijie.info" to "ARTWORK", "sketch.pixiv.net" to "ARTWORK"
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
            canonical!="fanbox.cc" &&
            canonical.substringBefore(".fanbox.cc")
                .matches(Regex("[a-z0-9-]{2,60}"))
        val lofterSubdomain=canonical.endsWith(".lofter.com") &&
            canonical.substringBefore(".lofter.com")
                .matches(Regex("[a-z0-9-]{2,60}"))
        val carrdSubdomain=canonical.endsWith(".carrd.co") &&
            canonical.substringBefore(".carrd.co")
                .matches(Regex("[a-z0-9-]{2,60}"))
        val provider=hosts[canonical] ?:
            when{
                fanboxSubdomain->"FANBOX"
                lofterSubdomain->"LOFTER"
                carrdSubdomain->"LINK_HUB"
                else->null
            } ?: return@runCatching null
        val path=uri.path.orEmpty().trimEnd('/')
        val sections=path.trim('/').split('/').filter{it.isNotBlank()}
        // FANBOX creators commonly use username.fanbox.cc/ without a profile
        // path. This subdomain itself is the provider-assigned creator ID.
        if(sections.isEmpty()){
            if(fanboxSubdomain||lofterSubdomain||carrdSubdomain)
                return@runCatching ("https://"+canonical+"/") to provider
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
            "FANBOX"->if(fanboxSubdomain)false else sections.size in 1..2
            "LOFTER"->!lofterSubdomain && sections.size==1
            "LINK_HUB"->!carrdSubdomain && sections.size==1
            "BILIBILI"->when(canonical){
                "space.bilibili.com"->sections.size==1 && first.toLongOrNull()!=null
                else->sections.size==2 && first=="space" && sections[1].toLongOrNull()!=null
            }
            "XIAOHONGSHU"->sections.size==3 && first=="user" &&
                sections[1]=="profile"
            "CI_EN"->sections.size==2 && first=="creator" &&
                sections[1].toLongOrNull()!=null
            "WEIBO"->(sections.size==1 && first.length>=2) ||
                (sections.size==2 && first=="u" && sections[1].toLongOrNull()!=null)
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
        fun add(value:String,evidence:String){
            val candidate=normalized(value)?:return
            if(candidate.first==original.first ||
                candidate.second==original.second)return
            out.putIfAbsent(candidate.first,CrossPlatformProfile(
                original.first,candidate.first,candidate.second,evidence))
        }
        for(anchor in selected.take(120)){
            add(anchor.absUrl("href"),"Linked from "+original.first)
        }
        // Official profile metadata often declares accounts in JSON-LD even
        // when the page has no visible anchor. This is creator-published
        // evidence, not an identity guessed from a similar display name.
        for(script in doc.select("script[type=application/ld+json]").take(8)){
            val raw=script.data().take(65_536)
            runCatching{
                fun inspect(value:Any?,depth:Int){
                    if(depth>3)return
                    when(value){
                        is JSONObject->{
                            val kind=value.optString("@type").lowercase(Locale.ROOT)
                            if(kind.contains("person")||kind.contains("profile")){
                                when(val same=value.opt("sameAs")){
                                    is String->add(same,"Creator-declared sameAs on "+original.first)
                                    is JSONArray->for(i in 0 until minOf(same.length(),40))
                                        add(same.optString(i),"Creator-declared sameAs on "+original.first)
                                }
                            }
                            val graph=value.opt("@graph")
                            if(graph is JSONArray)inspect(graph,depth+1)
                        }
                        is JSONArray->for(i in 0 until minOf(value.length(),25))
                            inspect(value.opt(i),depth+1)
                    }
                }
                inspect(org.json.JSONTokener(raw).nextValue(),0)
            }
        }
        return out.values.take(50)
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
    /** Indexed access to creator-declared link hubs. This is a one-hop
     * discovery queue, not a global crawler or an assertion of identity.
     */
    fun hubs(limit:Int=2000):List<CrossPlatformProfile>{
        val output=ArrayList<CrossPlatformProfile>()
        readableDatabase.rawQuery(
            """SELECT source,profile,platform,evidence FROM profile_links
               WHERE platform='LINK_HUB' ORDER BY seen DESC LIMIT ?""",
            arrayOf(limit.coerceIn(1,5000).toString())).use{c->
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
