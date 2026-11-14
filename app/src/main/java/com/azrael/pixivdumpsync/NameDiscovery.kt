package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.WebDiscovery
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

data class NameWatch(val id:Long,val name:String,val source:String,val enabled:Boolean,
    val lastCheck:Long,val lastError:String?)
class NameWatchDb(context:Context):SQLiteOpenHelper(context,"asterion_name_watches.sqlite",null,1){
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE name_watches(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL, source TEXT NOT NULL DEFAULT '',
            enabled INTEGER NOT NULL DEFAULT 1, last_check INTEGER NOT NULL DEFAULT 0,
            last_error TEXT, UNIQUE(name,source))""")
    }
    override fun onUpgrade(db:SQLiteDatabase,a:Int,b:Int){}
    fun add(name:String,source:String=""):Boolean {
        if(name.trim().length<2)return false
        val v=ContentValues().apply{put("name",name.trim());put("source",source.trim())}
        return writableDatabase.insertWithOnConflict(
            "name_watches",null,v,SQLiteDatabase.CONFLICT_IGNORE)>0L
    }
    /** Retain the existing watch identity when it was previously saved
     * without evidence. Verified follow never creates a fake person record.
     */
    fun addVerified(name:String,profileUrl:String):Long{
        require(PerformerIdentity.isProfileUrl(name,profileUrl)){
            "A verified first-party performer profile is required"
        }
        val db=writableDatabase
        db.beginTransaction()
        try{
            val prior=db.rawQuery(
                "SELECT id FROM name_watches WHERE lower(name)=lower(?) ORDER BY id LIMIT 1",
                arrayOf(name.trim())).use{q->if(q.moveToFirst())q.getLong(0)else null}
            val id=if(prior!=null){
                val values=ContentValues().apply{
                    put("source",profileUrl);put("enabled",1)
                    putNull("last_error")
                }
                db.update("name_watches",values,"id=?",arrayOf(prior.toString()))
                prior
            }else{
                val values=ContentValues().apply{
                    put("name",name.trim());put("source",profileUrl);put("enabled",1)
                }
                db.insertOrThrow("name_watches",null,values)
            }
            db.setTransactionSuccessful()
            return id
        }finally{db.endTransaction()}
    }
    fun list():List<NameWatch>{
        val out=ArrayList<NameWatch>()
        readableDatabase.rawQuery("""SELECT id,name,source,enabled,last_check,last_error
            FROM name_watches ORDER BY name COLLATE NOCASE""",null).use{c->
            while(c.moveToNext())out.add(NameWatch(c.getLong(0),c.getString(1),
                c.getString(2),c.getInt(3)==1,c.getLong(4),c.getString(5)))}
        return out
    }
    fun setEnabled(id:Long,value:Boolean){
        writableDatabase.execSQL("UPDATE name_watches SET enabled=? WHERE id=?",
            arrayOf(if(value)1 else 0,id))
    }
    fun setResult(id:Long,error:String?){
        val v=ContentValues().apply{put("last_check",System.currentTimeMillis())
            if(error==null)putNull("last_error") else put("last_error",error.take(300))}
        writableDatabase.update("name_watches",v,"id=?",arrayOf("$id"))
    }
    fun delete(id:Long){writableDatabase.delete("name_watches","id=?",arrayOf("$id"))}
}
/** Searches explicit source pages and public metadata; dedupe is enforced by VideoDb. */
object NameCrawler {
    /** Each performer owns an independent background discovery agent.
     * Slow providers never block downloads or unrelated performers.
     */
    fun scan(context:Context,onStatus:(String)->Unit={}){
        val ids=NameWatchDb(context).use{it.list().filter{watch->watch.enabled}.map{watch->watch.id}}
        for(id in ids)scanCreator(context,id,onStatus)
    }
    /** Live, federated search of the currently enabled provider sites.
     * The local SourceIndex is only a cache; absence from it must not hide
     * results published online. Results are candidates, not identity proof.
     */
    private fun liveCandidates(context:Context,name:String):List<RankedHit> {
        val result=KeywordSources.searchDetailed(context,name,VideoSearchArea.PORN)
        return result.hits.asSequence()
            .filter{SearchExactness.matches(name,it.title) &&
                com.asterion.videoflow.VideoSiteProfiles.isVideoPage(it.url) &&
                !ProviderRetirement.isRetired(it.url)}
            .distinctBy{SearchRanking.canonicalKey(it.url)}
            .map{it.copy(source="Name match · "+it.source)}
            .toList()
    }
    fun scanCreator(context:Context,id:Long,
                    onStatus:(String)->Unit={}):Boolean{
        val watch=NameWatchDb(context).use{it.list().firstOrNull{
            candidate->candidate.id==id && candidate.enabled
        }}?:return false
        if(!PerformerFollowPolicy.permittedName(watch.name)){
            NameWatchDb(context).use{it.setResult(id,
                "Ambiguous or invalid performer name; enter a distinctive creator identity.")}
            onStatus("Ambiguous creator name: "+watch.name)
            return false
        }
        if(!PerformerIdentity.isProfileUrl(watch.name,watch.source)){
            // Explicitly user-selected names are valid watch queries, not proof
            // that any particular provider page belongs to that performer.
            // Search only real indexed first-party video pages. No automated
            // download is permitted without a verified performer profile.
            BackfillAgents.submit(context,"performer:"+id){
                NameWatchDb(context).use{watches->
                    try{
                        val indexed=SourceIndex.items(context,"ADULT",5000)
                            .asSequence()
                            .filter { it.kind=="video" &&
                                SearchExactness.matches(watch.name,it.title) &&
                                com.asterion.videoflow.VideoSiteProfiles
                                    .isVideoPage(it.url) &&
                                !ProviderRetirement.isRetired(it.url) &&
                                SourceIndexParser.sameSite(it.source,it.url) }
                            .map{RankedHit(it.title,it.url,
                                "Name match · "+it.source,score=110,
                                thumbnail=it.preview)}.toList()
                        // Live discovery queries the actual provider websites;
                        // old indexed results remain visible even if offline.
                        val live=runCatching{liveCandidates(context,watch.name)}
                        val matches=(live.getOrDefault(emptyList())+indexed)
                            .distinctBy{SearchRanking.canonicalKey(it.url)}
                        SearchResultsDb(context).use{it.save(watch.name,matches)}
                        val diagnostic=when{
                            live.isFailure->"Live provider search failed: "+
                                (live.exceptionOrNull()?.message?:"unavailable")
                            matches.isEmpty()->"No matching originals found across live providers or cache"
                            else->"Name-only matches require manual review; no automatic downloads"
                        }
                        watches.setResult(id,diagnostic)
                        onStatus(watch.name+": "+matches.size+
                            " live and indexed matches for manual review")
                    }catch(t:Exception){
                        watches.setResult(id,t.message?:"Name search unavailable")
                    }
                }
            }
            return true
        }
        BackfillAgents.submit(context,"performer:"+id){
            NameWatchDb(context).use{watches->
                try{
                    val profile=com.asterion.videoflow.WebDiscovery.page(
                        watch.source,watch.source,context)
                    val hits=profile.hits.asSequence()
                        .filter{com.asterion.videoflow.VideoSiteProfiles
                            .isVideoPage(it.url)}
                        // A profile can display recommendations. We only admit
                        // titles that also explicitly name the followed actor.
                        .filter{SearchExactness.matches(watch.name,it.title)}
                        .distinctBy{SearchRanking.canonicalKey(it.url)}
                        .map{RankedHit(it.title,it.url,
                            "Verified profile: "+watch.source,score=350,
                            thumbnail=it.preview)}.toList()
                    // Do not limit a verified performer search to the
                    // visible first page of a profile. Search all supported
                    // live provider catalogs for additional candidates, but
                    // queue only profile-attributable originals automatically.
                    val live=runCatching{liveCandidates(context,watch.name)}
                        .getOrDefault(emptyList())
                    SearchResultsDb(context).use{results->
                        results.save(watch.name,
                            (hits+live).distinctBy{
                                SearchRanking.canonicalKey(it.url)
                            })
                        VideoDb(context).use{videos->
                            for(hit in hits){
                                if(watch.enabled &&
                                    !results.wasQueued(watch.name,hit.url) &&
                                    videos.addVideo(hit.url,hit.title,0L,"performer")){
                                    results.markQueued(watch.name,hit.url)
                                }
                            }
                        }
                    }
                    watches.setResult(id,if(hits.isEmpty())
                        "Profile verified; no directly attributable originals yet; live catalog candidates require review"
                        else null)
                    onStatus("Verified profile "+watch.name+": "+hits.size+" attributable videos")
                }catch(t:Exception){
                    watches.setResult(id,t.message?:"Profile could not be checked")
                    onStatus("Performer lookup failed: "+watch.name)
                }
            }
            if(AppPower.enabled(context))
                com.asterion.videoflow.VideoEngine.launch(context,false)
        }
        return true
    }
    fun publicArchive(name:String):List<Pair<String,String>> {
        val q=URLEncoder.encode("mediatype:movies AND title:(\"$name\")","UTF-8")
        val api="https://archive.org/advancedsearch.php?q=$q&fl%5B%5D=identifier&fl%5B%5D=title&rows=100&page=1&output=json"
        val doc=Jsoup.connect(api).ignoreContentType(true).timeout(30000)
            .maxBodySize(3500000).execute().body()
        val hits=JSONObject(doc).getJSONObject("response").getJSONArray("docs")
        val out=ArrayList<Pair<String,String>>()
        for(i in 0 until hits.length()){
            val item=hits.getJSONObject(i)
            val id=item.optString("identifier")
            val title=item.optString("title",id)
            if(id.isNotBlank()&&title.contains(name,true))
                out.add(title to "https://archive.org/details/$id")
        }
        return out
    }
    fun namedSource(name:String,template:String):List<Pair<String,String>> {
        val link=if(template.contains("{name}"))
            template.replace("{name}",URLEncoder.encode(name,"UTF-8")) else template
        require(WebDiscovery.isUrl(link)){"Invalid source search address"}
        // Source-page monitoring preserves source locality; it never pretends to search the entire web.
        return WebDiscovery.findOnPage(link,50)
            .filter{it.title.contains(name,true)||it.url.contains(name,true)}
            .map{it.title to it.url}
    }
}
