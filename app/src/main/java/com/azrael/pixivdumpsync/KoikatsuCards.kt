package com.azrael.pixivdumpsync

import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.widget.*
import com.azrael.galleryflow.SiteSession
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.*
import java.io.*
import kotlin.concurrent.thread

data class KoikatsuCard(val name:String,val author:String,val download:String,
                         val source:String,val page:String,val preview:String?=null)
object KoikatsuCards {
    private fun encode(s:String)=URLEncoder.encode(s,"UTF-8")
    internal fun parseBepis(json:String):List<KoikatsuCard>{
        val array=JSONObject(json).optJSONObject("data")?.optJSONArray("cards")?:return emptyList()
        return (0 until minOf(array.length(),100)).mapNotNull{i->
            val card=array.optJSONObject(i)?:return@mapNotNull null
            if(card.optString("cardType")!="KK")return@mapNotNull null
            val id=card.optInt("id")
            if(id<=0)return@mapNotNull null
            val meta=card.optJSONObject("cardData")
            val name=listOf(meta?.optString("firstName").orEmpty(),
                meta?.optString("lastName").orEmpty()).filter{it.isNotBlank()}
                .joinToString(" ").ifBlank{"Card "+id}
            val author=card.optJSONObject("uploader")?.optString("username").orEmpty()
            val key="KK_"+id.toString().padStart(6,'0')
            val png="https://db.bepis.moe/card/full/"+key+".png"
            // Verified BepisDB frontend thumbnail endpoint. Loading a full
            // 22+ MiB card for each search tile previously stalled Android.
            val thumbnail="https://db.bepis.moe/card/thumb/"+key+"_thumb.webp"
            KoikatsuCard(name,author,png,"BepisDB",
                "https://db.bepis.moe/koikatsu/view/"+id,thumbnail)
        }
    }
    private fun safe(url:String):Boolean=runCatching{
        val u=URL(url)
        u.protocol=="https" && u.host.lowercase() in setOf("db.bepis.moe","koikatsucards.com")
    }.getOrDefault(false)
    private fun response(url:String):String{
        require(safe(url)){"Untrusted card source"}
        val c=URL(url).openConnection() as HttpURLConnection
        c.connectTimeout=12000;c.readTimeout=16000;c.instanceFollowRedirects=false
        c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 17) Chrome/131")
        SiteSession.cookie(url)?.let{c.setRequestProperty("Cookie",it)}
        try{
            require(c.responseCode==200){"HTTP "+c.responseCode}
            return c.inputStream.bufferedReader().use{it.readText().take(2_000_000)}
        }finally{c.disconnect()}
    }
    internal fun parseArchive(html:String,base:String):List<KoikatsuCard> {
        val doc=Jsoup.parse(html,base)
        return doc.select("a.card[href*=/contents/]").take(75).mapNotNull{link->
            val page=link.absUrl("href")
            if(!safe(page))return@mapNotNull null
            val thumbnail=link.selectFirst("img")?.let{img->
                listOf("data-src","data-original","src").map{img.absUrl(it)}
                    .firstOrNull{safe(it)}
            }
            KoikatsuCard(link.text().trim().take(160).ifBlank{"Character card"},"",
                page,"KoikatsuCards",page,thumbnail)
        }.distinctBy{it.page}
    }
    fun search(name:String):Pair<List<KoikatsuCard>,List<String>>{
        require(name.trim().length in 2..120){"Enter a character name"}
        val normalized=name.trim()
        val errors=ArrayList<String>()
        // Separate site agents: slow/blocked KoikatsuCards must not delay a
        // working BepisDB character search (verified official JSON endpoint).
        val workers=java.util.concurrent.Executors.newFixedThreadPool(2){task->
            Thread(task,"asterion-koikatsu-provider").apply{isDaemon=true}
        }
        try{
            val bepis=workers.submit<List<KoikatsuCard>>{
                val found=ArrayList<KoikatsuCard>()
                for(page in 1..3){
                    val cards=parseBepis(response(
                        "https://db.bepis.moe/api/frontend/search?cardType=KK&name="+
                            encode(normalized)+"&page="+page))
                    if(cards.isEmpty())break
                    found+=cards
                }
                found
            }
            val archive=workers.submit<List<KoikatsuCard>>{
                val base="https://koikatsucards.com/search?types=type-character-card"
                parseArchive(response(base),base).filter{
                    it.name.contains(normalized,true)
                }
            }
            val found=ArrayList<KoikatsuCard>()
            runCatching{bepis.get(25,java.util.concurrent.TimeUnit.SECONDS)}
                .onSuccess{found+=it}
                .onFailure{errors+="BepisDB: "+(it.message?:it.javaClass.simpleName)}
            runCatching{archive.get(3,java.util.concurrent.TimeUnit.SECONDS)}
                .onSuccess{found+=it}
                .onFailure{errors+="KoikatsuCards: unavailable or timed out"}
            return found.distinctBy{it.page} to errors
        }finally{workers.shutdownNow()}
    }
    internal fun parseArchiveDownload(html:String,base:String):String?{
        val doc=Jsoup.parse(html,base)
        return doc.select("div.download-menu-panel a.download-menu-item,"+
            "div.tag-group.download-link-group a.link-pill").map{it.absUrl("href")}
            .firstOrNull{safe(it) && URL(it).path.startsWith("/api/downloads/")}
    }
    fun resolve(card:KoikatsuCard):String {
        return if(card.source=="BepisDB")card.download
        else parseArchiveDownload(response(card.page),card.page)
            ?:error("No accessible card file; sign in on KoikatsuCards if required")
    }
    fun download(a:Activity,url:String,title:String):String{
        require(safe(url)){"Untrusted host"}
        val conn=URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout=18000
        conn.readTimeout=40000
        conn.instanceFollowRedirects=false
        conn.setRequestProperty("User-Agent","Mozilla/5.0 (Android 17)")
        SiteSession.cookie(url)?.let{conn.setRequestProperty("Cookie",it)}
        try{
            require(conn.responseCode==200){"HTTP "+conn.responseCode}
            val name=title.replace(Regex("[^A-Za-z0-9_. -]"),"_").take(65)
                .ifBlank{"Koikatsu"}+".png"
            val values=ContentValues().apply{
                put(MediaStore.Downloads.DISPLAY_NAME,name)
                put(MediaStore.Downloads.MIME_TYPE,"image/png")
                put(MediaStore.Downloads.RELATIVE_PATH,
                    "Download/AsterionDownloader/Koikatsu/Cards/")
                put(MediaStore.Downloads.IS_PENDING,1)
            }
            // Stream original PNG directly to MediaStore. Large modded cards
            // must not allocate their entire content in Android's Java heap.
            java.io.DataInputStream(
                BufferedInputStream(conn.inputStream,65536)).use{source->
                val magic=ByteArray(8)
                source.readFully(magic)
                require(magic.contentEquals(byteArrayOf(
                    -119,80,78,71,13,10,26,10))){
                    "Downloaded response is not an original PNG card"
                }
                val uri=a.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)
                    ?:error("Cannot create Koikatsu PNG in Downloads")
                try{
                    val bytes=a.contentResolver.openOutputStream(uri,"w")!!.use{output->
                        output.write(magic)
                        8L+source.copyTo(output,65536)
                    }
                    require(bytes>256){"Downloaded PNG is incomplete"}
                    a.contentResolver.update(uri,ContentValues().apply{
                        put(MediaStore.Downloads.IS_PENDING,0)
                    },null,null)
                    val saved=CrossSourceDedup(a).use{it.registerFile(a,uri)}
                    RounderDispatch.saved(a,saved,name,"Koikatsu")
                    return saved.toString()
                }catch(t:Throwable){
                    a.contentResolver.delete(uri,null,null)
                    throw t
                }
            }
        }finally{conn.disconnect()}
    }
}

class KoikatsuCardActivity:Activity(){
    private val ui=NocturneDesign
    private lateinit var results:LinearLayout
    private lateinit var status:TextView
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val body=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(ui.px(this@KoikatsuCardActivity,15),ui.px(this@KoikatsuCardActivity,15),
                ui.px(this@KoikatsuCardActivity,15),ui.px(this@KoikatsuCardActivity,15))
            setBackgroundColor(ui.background)
        }
        body.addView(ui.text(this,"KOIKATSU CARDS",24f,ui.white,true))
        body.addView(ui.text(this,"Search by character name. Original PNG cards are saved to Downloads/AsterionDownloader/Koikatsu/Cards.",12f,ui.secondary),ui.lp(this,7))
        val query=EditText(this).apply{
            hint="Character name";setSingleLine(true)
            setTextColor(ui.white);setHintTextColor(ui.secondary)
        }
        body.addView(query,ui.lp(this,9))
        status=ui.text(this,"Sources: BepisDB + KoikatsuCards",12f,ui.secondary)
        body.addView(ui.action(this,"SEARCH CARDS BY NAME",true){
            val term=query.text.toString().trim()
            if(term.length<2){status.text="Enter at least 2 characters";return@action}
            status.text="Searching…";results.removeAllViews()
            thread(name="koikatsu-name-search"){
                val found=runCatching{KoikatsuCards.search(term)}
                runOnUiThread{found.onSuccess{(cards,errors)->
                    status.text=cards.size.toString()+" cards"+if(errors.isEmpty())"" else
                        " · "+errors.joinToString(" | ").take(180)
                    cards.forEach{addCard(it)}
                }.onFailure{status.text="Search failed: "+it.message}}
            }
        },ui.lp(this,9))
        body.addView(status,ui.lp(this,7))
        results=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        body.addView(ScrollView(this).apply{addView(results)},LinearLayout.LayoutParams(-1,0,1f))
        setContentView(body)
    }
    private fun addCard(card:KoikatsuCard){
        val v=ui.card(this)
        v.addView(ui.text(this,card.name,16f,ui.white,true))
        v.addView(ui.text(this,card.source+(if(card.author.isBlank())"" else " · "+card.author),
            12f,ui.secondary),ui.lp(this,4))
        val previewUrl=card.preview ?: if(card.source=="BepisDB")card.download else null
        val preview=ImageView(this).apply{
            scaleType=ImageView.ScaleType.FIT_CENTER
            adjustViewBounds=true
            maxHeight=ui.px(this@KoikatsuCardActivity,520)
            setBackgroundColor(ui.raised)
        }
        v.addView(preview,LinearLayout.LayoutParams(-1,-2))
        if(previewUrl!=null)RemoteImageLoader.load(this,preview,previewUrl,card.page,preserveOriginal=true)
        v.addView(ui.action(this,"DOWNLOAD CARD PNG",true){
            status.text="Retrieving "+card.name
            KoikatsuCardAgents.submit(this,card){done->
                done.onSuccess{status.text="Saved original PNG: "+card.name}
                    .onFailure{status.text="Download failed: "+it.message}
            }
        },ui.lp(this,8))
        results.addView(v,ui.lp(this,10))
    }
}
