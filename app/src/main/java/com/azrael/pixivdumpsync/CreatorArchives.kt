package com.azrael.pixivdumpsync

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Contributor-uploaded public archives are searchable, but subscription authentication,
// unavailable original files and DRM are neither bypassed nor represented as downloaded.
enum class CreatorArchiveHost(val title:String, val origin:String, val fileOrigin:String) {
    PAWCHIVE("Pawchive","https://pawchive.pw","https://file.pawchive.pw"),
    KEMONO("Kemono","https://kemono.cr","https://n4.kemono.cr"),
    COOMER("Coomer","https://coomer.st","https://coomer.st")
}
data class ArchiveCreator(val host:CreatorArchiveHost, val service:String,
    val id:String, val name:String) {
    val url:String get() = "${host.origin}/$service/user/$id"
}
data class ArchiveFile(val title:String,val url:String)
data class ArchivePost(val creator:ArchiveCreator,val id:String,val title:String,
    val published:String,val files:List<ArchiveFile>) {
    val url:String get() = "${creator.url}/post/$id"
}
data class ArchiveCreatorSearch(val creators:List<ArchiveCreator>,
    val errors:List<String>,val checked:Int)

object CreatorArchives {
    private val segment=Regex("[A-Za-z0-9_-]{1,150}")
    private fun validSegment(s:String)=segment.matches(s)
    private fun connect(url:String):HttpURLConnection {
        val uri=URI(url)
        require(uri.scheme=="https" && CreatorArchiveHost.values().any {
            URI(it.origin).host==uri.host
        }) {"Untrusted archive API host"}
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout=14000;readTimeout=28000;instanceFollowRedirects=false
            setRequestProperty("Accept","application/json")
            setRequestProperty("User-Agent","AsterionDownloader/1.3 (Android)")
        }
    }
    private fun response(connection:HttpURLConnection):String {
        try {
            check(connection.responseCode in 200..299) {
                "HTTP ${connection.responseCode}; archive may require browser access"
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                val out=StringBuilder();val buf=CharArray(8192)
                while(true) {
                    val len=it.read(buf);if(len<0)break
                    out.append(buf,0,len)
                    check(out.length<=4_000_000) {"Archive response exceeds 4 MB"}
                }
                out.toString()
            }
        }finally{connection.disconnect()}
    }
    fun parseCreatorUrl(raw:String):ArchiveCreator? = runCatching {
        val uri=URI(raw.trim())
        if(uri.scheme!="https")return@runCatching null
        val host=CreatorArchiveHost.values().firstOrNull {
            URI(it.origin).host.equals(uri.host,true)
        }?:return@runCatching null
        val parts=uri.path.trim('/').split('/')
        if(parts.size<3 || parts[1]!="user" ||
            !validSegment(parts[0]) || !validSegment(parts[2]))return@runCatching null
        ArchiveCreator(host,parts[0].lowercase(Locale.ROOT),parts[2],parts[2])
    }.getOrNull()
    private fun readField(r:JsonReader):String =
        if(r.peek()==JsonToken.STRING || r.peek()==JsonToken.NUMBER) r.nextString()
        else {r.skipValue();""}
    // Streaming JSON makes large creator indices usable on a memory-limited Android phone.
    private fun findCreators(host:CreatorArchiveHost, query:String):List<ArchiveCreator>{
        val c=connect("${host.origin}/api/v1/creators")
        try{
            check(c.responseCode in 200..299){"HTTP ${c.responseCode}"}
            val hits=ArrayList<ArchiveCreator>()
            InputStreamReader(c.inputStream,Charsets.UTF_8).use{stream->
                JsonReader(stream).use{r->
                    check(r.peek()==JsonToken.BEGIN_ARRAY){"Unexpected creator index format"}
                    r.beginArray()
                    var visited=0
                    while(r.hasNext() && visited<600_000) {
                        visited++
                        if(r.peek()!=JsonToken.BEGIN_OBJECT){r.skipValue();continue}
                        r.beginObject()
                        var id="";var name="";var service=""
                        while(r.hasNext()){
                            when(r.nextName()){
                                "id"->id=readField(r)
                                "name"->name=readField(r)
                                "service"->service=readField(r)
                                else->r.skipValue()
                            }
                        }
                        r.endObject()
                        if(validSegment(id)&&validSegment(service)&&name.isNotBlank()&&
                            (name.contains(query,true)||id.contains(query,true))) {
                            hits+=ArchiveCreator(host,service.lowercase(Locale.ROOT),
                                id,name.take(180))
                            if(hits.size>=40)break
                        }
                    }
                }
            }
            return hits
        }finally{c.disconnect()}
    }
    fun search(input:String):ArchiveCreatorSearch {
        val query=input.trim()
        require(query.length in 2..150){"Enter a creator name or archive URL"}
        parseCreatorUrl(query)?.let{return ArchiveCreatorSearch(listOf(it),emptyList(),1)}
        if(query.startsWith("https://")||query.startsWith("http://"))
            return ArchiveCreatorSearch(emptyList(),
                listOf("Paste an archive creator URL or search by creator name"),0)
        val hosts=CreatorArchiveHost.values().toList()
        val pool=Executors.newFixedThreadPool(hosts.size)
        val work=ExecutorCompletionService<Pair<List<ArchiveCreator>,String?>>(pool)
        try{
            hosts.forEach{host->work.submit(Callable{
                try{findCreators(host,query) to null}
                catch(e:Exception){emptyList<ArchiveCreator>() to
                    "${host.title}: ${e.message?:e.javaClass.simpleName}"}
            })}
            val results=ArrayList<ArchiveCreator>();val errors=ArrayList<String>()
            var finished=0
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(55)
            while(finished<hosts.size){
                val remaining=deadline-System.nanoTime()
                if(remaining<=0)break
                val future=work.poll(remaining,TimeUnit.NANOSECONDS)?:break
                val (found,error)=future.get()
                results+=found;if(error!=null)errors+=error
                finished++
            }
            if(finished<hosts.size)errors+="${hosts.size-finished} archive(s) timed out"
            return ArchiveCreatorSearch(results.distinctBy{it.url}
                .sortedWith(compareByDescending<ArchiveCreator>{
                    it.name.equals(query,true)
                }.thenBy{it.name.lowercase(Locale.ROOT)}).take(60),errors,hosts.size)
        }finally{pool.shutdownNow()}
    }
    // No third-party arbitrary URL or path traversal is accepted from archive metadata.
    fun fileUrl(host:CreatorArchiveHost,path:String):String? {
        val p=path.trim()
        if(p.isBlank()||p.contains("..")||p.contains('\\')||p.contains('#'))return null
        if(p.startsWith("https://"))return runCatching{
            val uri=URI(p)
            val allowed=setOf(URI(host.origin).host,URI(host.fileOrigin).host,
                "n1.kemono.cr","n2.kemono.cr","n3.kemono.cr","n4.kemono.cr",
                "file.pawchive.st","file.pawchive.pw")
            if(uri.host in allowed && uri.path.startsWith("/data/"))p else null
        }.getOrNull()
        if(!p.startsWith("/")||p.startsWith("//")||p.contains('?'))return null
        return host.fileOrigin+(if(p.startsWith("/data/"))p else "/data$p")
    }
    internal fun parsePosts(json:String,creator:ArchiveCreator):List<ArchivePost> {
        val a=if(json.trimStart().startsWith("[")) JSONArray(json)
            else JSONObject(json).optJSONArray("posts")?:return emptyList()
        val posts=ArrayList<ArchivePost>()
        for(i in 0 until minOf(a.length(),50)){
            val obj=a.optJSONObject(i)?:continue
            val id=obj.optString("id")
            if(!validSegment(id))continue
            if(obj.optString("service").ifBlank{creator.service}!=creator.service ||
                obj.optString("user").ifBlank{creator.id}!=creator.id)continue
            val files=ArrayList<ArchiveFile>();val seen=HashSet<String>()
            fun add(file:JSONObject?){
                if(file==null)return
                val url=fileUrl(creator.host,file.optString("path"))?:return
                if(!seen.add(url))return
                val name=file.optString("name").ifBlank{
                    URI(url).path.substringAfterLast('/').ifBlank{"attachment"}
                }.take(180)
                files+=ArchiveFile(name,url)
            }
            add(obj.optJSONObject("file"))
            val attachments=obj.optJSONArray("attachments")
            if(attachments!=null)for(j in 0 until minOf(attachments.length(),150))
                add(attachments.optJSONObject(j))
            posts+=ArchivePost(creator,id,
                obj.optString("title").ifBlank{"Post $id"}.take(180),
                obj.optString("published").take(40),files)
        }
        return posts
    }
    fun posts(creator:ArchiveCreator,offset:Int):List<ArchivePost> {
        require(validSegment(creator.id)&&validSegment(creator.service))
        require(offset in 0..50000 && offset%50==0){"Invalid page offset"}
        val url="${creator.host.origin}/api/v1/${creator.service}/user/${creator.id}/posts?o=$offset"
        return parsePosts(response(connect(url)),creator)
    }
}

// Isolated storage; no migrations or modifications to Pixiv, Kyora or X databases.
object CreatorArchiveStore {
    private fun prefs(c:Context)=c.getSharedPreferences("asterion_creator_archives",Context.MODE_PRIVATE)
    fun list(c:Context):List<ArchiveCreator> = runCatching {
        val a=JSONArray(prefs(c).getString("creators","[]"))
        (0 until a.length()).mapNotNull{i->
            val o=a.optJSONObject(i)?:return@mapNotNull null
            val host=runCatching{CreatorArchiveHost.valueOf(o.optString("host"))}.getOrNull()
                ?:return@mapNotNull null
            CreatorArchives.parseCreatorUrl("${host.origin}/${o.optString("service")}/user/${o.optString("id")}")
                ?.copy(name=o.optString("name").take(180))
        }.distinctBy{it.url}
    }.getOrDefault(emptyList())
    fun set(c:Context,creator:ArchiveCreator,enabled:Boolean){
        val all=list(c).filterNot{it.url==creator.url}.toMutableList()
        if(enabled)all.add(creator)
        val a=JSONArray()
        all.forEach{a.put(JSONObject().put("host",it.host.name).put("service",it.service)
            .put("id",it.id).put("name",it.name))}
        prefs(c).edit().putString("creators",a.toString()).apply()
        CreatorArchiveScheduler.ensure(c)
    }
    fun isSaved(c:Context,creator:ArchiveCreator)=list(c).any{it.url==creator.url}
}

object CreatorArchiveDownloads {
    data class Result(val queued:Int,val skipped:Int,val failed:Int,val deferred:Int=0)
    private fun key(s:String):String=MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun filename(s:String):String=s.substringAfterLast('/').replace(
        Regex("[^\\p{L}\\p{N}._ -]"),"_").trim().take(85).ifBlank{"file"}
    fun enqueue(context:Context,posts:List<ArchivePost>,maxFiles:Int=40):Result {
        if(!AppPower.enabled(context))return Result(0,0,posts.sumOf{it.files.size})
        val pref=context.getSharedPreferences("asterion_creator_downloads",Context.MODE_PRIVATE)
        val manager=context.getSystemService(DownloadManager::class.java)
        var queued=0;var skipped=0;var failed=0;var deferred=0
        for(post in posts)for((index,file) in post.files.withIndex()){
            if(CreatorArchives.fileUrl(post.creator.host,file.url)!=file.url){failed++;continue}
            val kind=ArchiveContent.category(file,post)
            // VideoFlow owns and deduplicates its own persistent queue. A cached
            // video (including full-only holds) must not consume the per-page 40
            // new-download budget, or dense creator pages never finish backfill.
            if(kind==ArchiveSection.VIDEOS){
                val already=com.asterion.videoflow.VideoDb(context).use{
                    it.hasVideo(file.url)
                }
                if(already){skipped++;continue}
                if(queued>=maxFiles){deferred++;continue}
                val accepted=runCatching{
                    com.asterion.videoflow.VideoDb(context).use{
                        it.addVideo(file.url,post.title+" · "+file.title,0L)
                    }
                }.getOrDefault(false)
                if(accepted){
                    queued++
                    com.asterion.videoflow.VideoEngine.launch(context,false)
                }else failed++
                continue
            }
            if(queued>=maxFiles){deferred++;continue}
            val token=key(file.url)
            val saved=pref.getLong(token,-1L)
            if(saved>0) {
                val cursor=runCatching{manager.query(DownloadManager.Query().setFilterById(saved))}.getOrNull()
                val active=cursor?.use {
                    if(it.moveToFirst()){
                        val col=it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status=if(col>=0)it.getInt(col) else -1
                        status==DownloadManager.STATUS_PENDING || status==DownloadManager.STATUS_RUNNING ||
                            status==DownloadManager.STATUS_PAUSED ||
                            status==DownloadManager.STATUS_SUCCESSFUL
                    }else false
                }?:false
                if(active){skipped++;continue}
            }
            val folder="AsterionDownloader/"+kind.folder+"/"+post.creator.host.name.lowercase(Locale.ROOT)+"/"+post.creator.service+"/"+post.creator.id
            val name="${post.id}_${index+1}_${filename(file.title)}"
            try{
                val request=DownloadManager.Request(Uri.parse(file.url)).apply{
                    setTitle(name)
                    setDescription("${post.creator.host.title} · ${post.creator.name}")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,"$folder/$name")
                }
                val id=manager.enqueue(request)
                pref.edit().putLong(token,id).apply()
                queued++
            }catch(e:Exception){failed++}
        }
        return Result(queued,skipped,failed,deferred)
    }
}
