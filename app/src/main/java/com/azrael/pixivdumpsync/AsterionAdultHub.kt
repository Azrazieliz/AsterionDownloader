package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import com.azrael.galleryflow.SiteLoginActivity
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import kotlin.concurrent.thread

/** Compact site-row module. A provider row owns its controls and its media.
 * No duplicated provider picker, browser, global backfill toggle or false
 * completion indicators.
 */
object AsterionAdultHub {
    private val ui=NocturneDesign
    private data class Site(val name:String,val url:String,val source:IndexedSource?){
        val host:String get()=SiteControls.key(url)
    }
    private fun sites(a:Activity,area:String):List<Site>{
        val results=LinkedHashMap<String,Site>()
        val approved=AlohaSources.by(if(area=="ADULT")AlohaArea.ADULT else AlohaArea.HENTAI)
        val existing=SourceIndex.status(a,area)
        for(site in approved){
            val host=SiteControls.key(site.url)
            if(host.isNotBlank())results[host]=Site(site.name,site.url,
                SiteRunControl.matchingSource(site.url,area,existing))
        }
        for(site in existing){
            val host=SiteControls.key(site.url)
            if(host.isNotBlank() && host !in results)
                results[host]=Site(site.name,site.url,site)
        }
        return results.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER){it.name})
    }
    private fun sourceState(a:Activity,site:Site):String{
        val running=SiteRunControl.status(a,site.url)
        if(running.state=="RUNNING")return "Backfilling"
        if(running.state=="STOPPING")return "Stopping"
        if(running.state=="STOPPED")return "Stopped"
        if(running.state=="QUEUED")return "History queued; cursor retained"
        if(running.state=="INTERRUPTED")return "Interrupted; retry available"
        if(running.state=="ERROR")return "Check failed"
        val s=site.source
        if(s!=null && !s.error.isNullOrBlank())return "Source error"
        if(s==null||s.lastCheck<=0L)return "Not checked"
        if(!s.historyComplete && !s.cursor.isNullOrBlank())
            return "Older pages pending · "+s.discovered+" indexed"
        return if(s.discovered>0)"Indexed "+s.discovered else "Checked · no items"
    }
    private fun error(site:Site,a:Activity):String?{
        val r=SiteRunControl.status(a,site.url)
        if(r.state=="ERROR")return r.message
        return site.source?.error
    }
    /** Do not recommend site menus, language directories, tags or category pages
     * as if they were individual media. Never invent missing thumbnails.
     */
    internal fun realMedia(item:IndexedItem):Boolean =
        IndexedMediaQuality.isMedia(item.url,item.title,item.preview,item.area)
    /** Each preview belongs to its website; performer previews belong to
     * their followed model. Never mix an unrequested global "Latest" feed.
     */
    fun render(a:Activity,parent:LinearLayout,area:String,refresh:()->Unit){
        require(area in setOf("ADULT","HENTAI"))
        val isAdult=area=="ADULT"
        val title=if(isAdult)"Porn" else "Hentai"
        val preferences=a.getSharedPreferences("asterion_gallery_layout",0)
        val showModels=isAdult && preferences.getBoolean("adult_models",false)
        val expanded=preferences.getBoolean(area+"_manage_sites_v2",false)
        val sources=sites(a,area)
        val header=ui.row(a).apply{gravity=Gravity.CENTER_VERTICAL}
        header.addView(ui.text(a,title,22f,ui.white,true),
            LinearLayout.LayoutParams(0,-2,1f))
        if(isAdult)header.addView(ui.chip(a,if(showModels)"WEBSITES" else "MODELS",
            showModels){
            preferences.edit().putBoolean("adult_models",!showModels).apply()
            refresh()
        },LinearLayout.LayoutParams(-2,-2))
        parent.addView(header,ui.lp(a,9))
        if(showModels){
            // The original followed-name database owns these model cards,
            // and searches are never relabelled as a site-wide recommendation.
            AsterionUnifiedFollowing.adult(a,parent,refresh)
            return
        }
        val indexed=SourceIndex.items(a,area,1000)
            .asSequence()
            .filter{realMedia(it) && !it.preview.isNullOrBlank() &&
                SearchRanking.validUrl(it.preview)}
            .distinctBy{SearchRanking.canonicalKey(it.url)}
            .sortedByDescending{it.discoveredAt}
            .take(600).toList()
        val bySite=indexed.groupBy{SiteControls.key(it.source)}
        // Reuse actual thumbnail evidence from previous Search results when
        // SourceIndex has not yet re-indexed an individual video. This restores
        // visual browsing without generating imaginary/random previews.
        val approvedHosts=sources.map{it.host}.toSet()
        val searchBySite=SearchResultsDb(a).use{it.list(300)}
            .asSequence()
            .filter{it.source.isNotBlank() &&
                !it.thumbnail.isNullOrBlank() &&
                SearchRanking.validUrl(it.thumbnail) &&
                IndexedMediaQuality.isMedia(it.url,it.title,it.thumbnail,area) &&
                SiteControls.key(it.url) in approvedHosts}
            .distinctBy{SearchRanking.canonicalKey(it.url)}
            .groupBy{SiteControls.key(it.url)}
        val hasVisual=bySite.values.any{it.isNotEmpty()} ||
            searchBySite.values.any{it.isNotEmpty()}
        // A fresh installation may have sources but no indexed previews.
        // Start a bounded thumbnail-only discovery; never queue originals just
        // to populate this gallery. Throttle automatic retries after failures.
        if(!hasVisual && AppPower.enabled(a)){
            val attempts=a.getSharedPreferences("asterion_gallery_preview_bootstrap",0)
            val key="last:"+area
            val now=System.currentTimeMillis()
            if(now-attempts.getLong(key,0L)>15L*60L*1000L){
                attempts.edit().putLong(key,now).apply()
                SourceIndex.refreshPreviews(a.applicationContext,area){
                    a.runOnUiThread{
                        if(!a.isFinishing && !a.isDestroyed)refresh()
                    }
                }
            }
        }
        val previewHeader=ui.row(a).apply{gravity=Gravity.CENTER_VERTICAL}
        previewHeader.addView(ui.text(a,"Media previews",18f,ui.white,true),
            LinearLayout.LayoutParams(0,-2,1f))
        previewHeader.addView(ui.chip(a,"REFRESH",false){
            if(!AppPower.enabled(a)){
                Toast.makeText(a,"Resume downloads first",Toast.LENGTH_SHORT).show()
            }else{
                Toast.makeText(a,"Checking source previews",Toast.LENGTH_SHORT).show()
                SourceIndex.refreshPreviews(a.applicationContext,area){checked->
                    a.runOnUiThread{
                        if(!a.isFinishing && !a.isDestroyed){
                            Toast.makeText(a,
                                if(checked>0)"Preview index updated" else
                                    "No new source previews available",
                                Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                    }
                }
            }
        },LinearLayout.LayoutParams(-2,-2))
        parent.addView(previewHeader,ui.lp(a,12))
        if(!hasVisual)parent.addView(ui.text(a,
            "No real media thumbnails indexed yet. An enabled website's " +
                "preview check can populate this gallery without starting " +
                "a full backfill; unavailable sources remain unverified.",
            12f,ui.secondary),ui.lp(a,7))
        val active=sources.filter{
            bySite[it.host].orEmpty().isNotEmpty() ||
                searchBySite[it.host].orEmpty().isNotEmpty()
        }
        val inactive=sources.filterNot{it in active}
        parent.addView(ui.text(a,
            sources.size.toString()+" websites · "+active.size+
                " with real thumbnails · "+
                inactive.count{!error(it,a).isNullOrBlank()}+" needing attention",
            12f,ui.secondary),ui.lp(a,3))
        // A selected model's content remains inside that model's card, rather
        // than appearing under an unrelated "latest" website.
        fun siteCard(site:Site){
            val media=bySite[site.host].orEmpty()
            val known=media.map{SearchRanking.canonicalKey(it.url)}.toSet()
            val searches=searchBySite[site.host].orEmpty()
                .filterNot{SearchRanking.canonicalKey(it.url) in known}
            val issue=error(site,a)
            val operation=SiteRunControl.status(a,site.url)
            val running=operation.state in setOf("RUNNING","QUEUED","STOPPING")
            val card=ui.card(a,10)
            val identity=ui.row(a).apply{gravity=Gravity.CENTER_VERTICAL}
            val nameBlock=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
            nameBlock.addView(ui.text(a,site.name,17f,ui.white,true)
                .apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END})
            nameBlock.addView(ui.text(a,
                ((media.size+searches.size).takeIf{it>0}
                    ?.let{count->count.toString()+" source-owned previews · "}
                    ?: "")+sourceState(a,site),
                11f,if(issue.isNullOrBlank())ui.secondary else ui.danger),
                ui.lp(a,4))
            identity.addView(nameBlock,LinearLayout.LayoutParams(0,-2,1f))
            card.addView(identity)
            if(media.isNotEmpty()||searches.isNotEmpty()){
                val mediaTiles=media.map{item->KurohaMediaPreview.Tile(
                    url=item.url,title=item.title,
                    thumbnail=item.preview.orEmpty(),
                    attribution=site.name,
                    referer=item.source,
                    video=item.kind=="video"
                )}
                val searchTiles=searches.map{item->KurohaMediaPreview.Tile(
                    url=item.url,title=item.title,
                    thumbnail=item.thumbnail.orEmpty(),
                    attribution=site.name+" · search match",
                    referer=item.url,video=true
                )}
                KurohaMediaPreview.render(a,card,
                    "website:"+area+":"+site.host,
                    mediaTiles+searchTiles){tile->
                    val indexItem=media.firstOrNull{it.url==tile.url}
                    if(indexItem!=null){
                        SourceIndexPanel.details(a,indexItem)
                    }else{
                        val hit=searches.firstOrNull{it.url==tile.url}
                        if(hit!=null){
                            val created=VideoDb(a).use{
                                it.addVideo(hit.url,hit.title)
                            }
                            if(created)VideoEngine.launch(a,false)
                            Toast.makeText(a,
                                if(created)"Original queued · "+hit.title.take(45)
                                else "Already in Downloads · "+hit.title.take(45),
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            val commandRow=ui.row(a).apply{gravity=Gravity.CENTER_VERTICAL}
            val sync=Switch(a).apply{
                text="Sync new";textSize=12f;setTextColor(ui.secondary)
                isChecked=SiteControls.sync(a,site.url,
                    site.source?.enabled?:false)
                contentDescription="Sync new videos from "+site.name
                setOnCheckedChangeListener{_,enabled->
                    SiteControls.setSync(a,site.url,enabled)
                    thread(name="asterion-site-sync"){
                        val app=a.applicationContext
                        val address=site.source?.url?:site.url
                        if(enabled){
                            SourceIndexDb(app).use{
                                it.add(address,site.name,area,"native")
                                it.enable(address,true)
                            }
                            VideoDb(app).use{it.addWatch(address,site.name)}
                        }
                        SiteControls.applyToEngines(app,site.url)
                        if(enabled)VideoEngine.launch(app,true)
                    }
                }
            }
            commandRow.addView(sync,LinearLayout.LayoutParams(0,-2,1f))
            commandRow.addView(ui.action(a,
                if(running)"STOP" else "BACKFILL",!running){
                if(running)SiteRunControl.stop(a,site.url)
                else if(!AppPower.enabled(a)){
                    Toast.makeText(a,"Resume downloads first",Toast.LENGTH_SHORT).show()
                    return@action
                }else{
                    val requested=SiteRunControl.start(a,site.url,area)
                    Toast.makeText(a,
                        if(requested)"History queued · "+site.name
                        else "This provider is already being checked",
                        Toast.LENGTH_SHORT).show()
                }
                refresh()
            },LinearLayout.LayoutParams(0,-2,1f).apply{
                leftMargin=ui.px(a,9)
            })
            card.addView(commandRow,ui.lp(a,9))
            if(!issue.isNullOrBlank()){
                val readable=when{
                    issue.contains("403")->"HTTP 403 · authorization or provider adapter required"
                    issue.contains("429")->"HTTP 429 · rate limited"
                    issue.contains("redirect",true)->"Provider redirected unexpectedly · adapter needs repair"
                    else->issue.take(116)
                }
                card.addView(ui.text(a,readable,11f,ui.danger),ui.lp(a,6))
                if(issue.contains("403")||issue.contains("login",true)){
                    card.addView(ui.action(a,"CONNECT ACCOUNT",false){
                        a.startActivity(Intent(a,SiteLoginActivity::class.java)
                            .putExtra(SiteLoginActivity.EXTRA_URL,site.url))
                    },ui.lp(a,5))
                }
            }
            parent.addView(card,ui.lp(a,7))
        }
        // Index-backed sites first. Provider rows never display media from
        // another website. Other sites remain accessible, not duplicated.
        for(site in active)siteCard(site)
        if(inactive.isNotEmpty()){
            parent.addView(ui.action(a,
                if(expanded)"HIDE WEBSITE CONTROLS" else
                    "MANAGE WEBSITES · "+inactive.size,false){
                preferences.edit().putBoolean(area+"_manage_sites_v2",!expanded).apply()
                refresh()
            },ui.lp(a,10))
            if(expanded)for(site in inactive)siteCard(site)
        }
    }
}
