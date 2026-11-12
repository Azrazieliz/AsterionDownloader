package com.azrael.pixivdumpsync

import android.app.Activity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Name-based first-party model index: titles and keyword matches are NOT identities. */
object WatchPornPanel {
    private data class Performer(val name:String,val url:String)
    private fun slug(value:String):String =
        Normalizer.normalize(value,Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"),"-").trim('-')

    /** Pure fixture-checkable evidence gate; no search results may bypass it. */
    internal fun matchesProfile(name:String,address:String,document:Document):Boolean {
        val uri=runCatching { URI(address) }.getOrNull() ?: return false
        val host=uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
        if(uri.scheme!="https" || uri.userInfo!=null || host!="watchporn.to")return false
        val route=uri.path.orEmpty().trimEnd('/')
        if(!route.matches(Regex("^/models/[a-z0-9_-]+$")))return false
        if(!PerformerFollowPolicy.permittedName(name))return false
        val targetSlug=slug(name)
        if(targetSlug.length<2 || route.substringAfterLast('/')!=targetSlug)return false
        val canonical=document.selectFirst("link[rel=canonical]")?.absUrl("href").orEmpty()
        if(canonical.isNotBlank()){
            val listed=runCatching { URI(canonical) }.getOrNull() ?: return false
            if(listed.scheme!="https" ||
                listed.host?.lowercase(Locale.ROOT)?.removePrefix("www.")!="watchporn.to" ||
                listed.path.orEmpty().trimEnd('/')!=route)return false
        }
        // An actual page title or official first-party profile heading must
        // exactly identify the performer. A video result titled with the
        // person's name or a menu link is not identity evidence.
        return document.select("h1,.model-name,.performer-name,[itemprop=name]")
            .eachText().any { slug(it)==targetSlug }
    }

    private fun verify(name:String,url:String):Boolean=runCatching {
        val response=Jsoup.connect(url).timeout(12_000).maxBodySize(1_500_000)
            .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile)")
            .followRedirects(false).ignoreHttpErrors(true).execute()
        response.statusCode() in 200..299 &&
            matchesProfile(name,url,response.parse())
    }.getOrDefault(false)

    private fun models(name:String):List<Performer>{
        val needle=name.trim()
        if(!PerformerFollowPolicy.permittedName(needle))return emptyList()
        val handle=slug(needle)
        if(handle.length<2)return emptyList()
        // Try the exact profile even if the website's directory is inaccessible.
        val exactUrl="https://watchporn.to/models/"+handle+"/"
        val direct=if(verify(needle,exactUrl))Performer(needle,exactUrl)else null
        val page=runCatching {
            Jsoup.connect("https://watchporn.to/models/").timeout(12_000)
                .followRedirects(false).maxBodySize(2_000_000)
                .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile)").get()
        }.getOrNull() ?: return listOfNotNull(direct)
        val words=needle.lowercase(Locale.ROOT)
            .split(Regex("\\s+")).filter{it.isNotBlank()}
        val candidates=page.select("a[href*=/models/]").mapNotNull{node->
            val link=node.absUrl("href")
            val address=runCatching{URI(link)}.getOrNull()
            val host=address?.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
            if(address?.scheme!="https" || host!="watchporn.to" ||
                !address.path.orEmpty().matches(Regex("^/models/[a-zA-Z0-9_-]+/?$")))
                return@mapNotNull null
            val label=node.text().trim().ifBlank{
                address.path.orEmpty().trim('/').substringAfterLast('/').replace('-',' ')
            }
            if(words.any{!label.contains(it,true)})return@mapNotNull null
            Performer(label,link)
        }.distinctBy{it.url}.filter{it.url!=direct?.url}.take(12)
        // Four bounded concurrent checks instead of saving all keyword hits.
        val workers=Executors.newFixedThreadPool(4)
        return try{
            val jobs=workers.invokeAll(candidates.map{candidate->
                Callable<Performer?> {
                    if(verify(candidate.name,candidate.url))candidate else null
                }
            },22,TimeUnit.SECONDS)
            (listOfNotNull(direct)+jobs.mapNotNull{job->
                if(job.isCancelled)null else runCatching{job.get()}.getOrNull()
            }).distinctBy{it.url}
        }finally{workers.shutdownNow()}
    }

    fun render(a:Activity,body:LinearLayout){
        val ui=NocturneDesign
        val block=ui.card(a)
        block.addView(ui.text(a,"WATCHPORN · ARTIST LIBRARY",18f,ui.white,true))
        block.addView(ui.text(a,"Search named performer profiles. Follow a selected profile for a first-page backfill and regular new-video checks. Downloads use the existing VideoFlow queue; inaccessible streams remain errors.",12f,ui.secondary),ui.lp(a,7))
        val input=EditText(a).apply{
            hint="Performer name"
            setSingleLine(true)
            setTextColor(ui.white);setHintTextColor(ui.secondary)
        }
        block.addView(input,ui.lp(a,8))
        val status=ui.text(a,"",12f,ui.secondary)
        val result=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        block.addView(ui.action(a,"SEARCH WATCHPORN ARTISTS",true){
            val q=input.text.toString().trim()
            if(!PerformerFollowPolicy.permittedName(q)){
                status.text="Enter a distinctive creator name, not initials or a generic term"
                return@action
            }
            status.text="Verifying real WatchPorn performer profiles…";result.removeAllViews()
            thread(name="asterion-watchporn-models"){
                val found=runCatching{models(q)}
                a.runOnUiThread{
                    if(a.isFinishing||a.isDestroyed)return@runOnUiThread
                    found.onSuccess{items->
                        status.text=items.size.toString()+" verified performer profiles"
                        for(item in items){
                            val entry=ui.card(a)
                            entry.addView(ui.text(a,item.name,15f,ui.white,true))
                            entry.addView(ui.text(a,item.url,11f,ui.secondary),ui.lp(a,5))
                            entry.addView(ui.action(a,"FOLLOW · BACKFILL / SYNC VIDEOS",true){
                                status.text="Rechecking "+item.name+" before following…"
                                thread(name="asterion-watchporn-follow-verify"){
                                    val valid=verify(item.name,item.url)
                                    a.runOnUiThread{
                                        if(a.isFinishing||a.isDestroyed)return@runOnUiThread
                                        if(!valid){
                                            status.text="Profile could not be verified; nothing followed"
                                        }else{
                                            val saved=VideoDb(a).use{
                                                it.addWatch(item.url,item.name+" · WatchPorn")
                                            }
                                            VideoEngine.launch(a,true)
                                            Toast.makeText(a,if(saved)
                                                "Verified performer enrolled; backfill requested"
                                                else "Verified performer already monitored; checking new videos",
                                                Toast.LENGTH_LONG).show()
                                            status.text="Verified "+item.name
                                        }
                                    }
                                }
                            },ui.lp(a,8))
                            result.addView(entry,ui.lp(a,7))
                        }
                    }.onFailure{status.text="Artist search unavailable: "+it.message}
                }
            }
        },ui.lp(a,9))
        block.addView(status,ui.lp(a,7))
        block.addView(ui.action(a,"OPEN VIDEOFLOW · WATCHES / DOWNLOADS",false){
            a.startActivity(android.content.Intent(a,com.asterion.videoflow.MainActivity::class.java))
        },ui.lp(a,6))
        body.addView(block,ui.lp(a,13))
        body.addView(result,ui.lp(a,7))
    }
}
