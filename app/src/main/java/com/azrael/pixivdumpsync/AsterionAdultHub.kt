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
    // Lazy: pure provider-route regression tests must not initialize Android
    // Color resources before a real Activity exists.
    private val ui by lazy { NocturneDesign }
    private data class Site(val name:String,val url:String,val source:IndexedSource?){
        val host:String get()=SiteControls.key(url)
    }
    private fun sites(a:Activity,area:String):List<Site>{
        val results=LinkedHashMap<String,Site>()
        val approved=AlohaSources.by(if(area=="ADULT")AlohaArea.ADULT else AlohaArea.HENTAI)
        val existing=SourceIndex.status(a,area)
        for(site in approved){
            val host=SiteControls.key(site.url)
            if(host.isNotBlank() && !ProviderRetirement.isRetired(site.url))results[host]=Site(site.name,site.url,
                SiteRunControl.matchingSource(site.url,area,existing))
        }
        for(site in existing){
            val host=SiteControls.key(site.url)
            if(host.isNotBlank() && host !in results &&
                host !in setOf("xxxclub.to","onejav.com","sukebei.nyaa.si") &&
                !ProviderRetirement.isRetired(site.url))
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
        return site.source?.error ?:
            KyoraCreatorPreviews.cached(a,site.url)?.error
    }
    // A real source-owned media cover may precede native original indexing.
    // Exclude category/menu links and never claim a cover proves a download.
    internal fun publicMedia(url:String,title:String,cover:String):Boolean{
        if(SiteControls.key(url) in setOf("onscreens.me","pimpbunny.com") &&
            !com.asterion.videoflow.VideoSiteProfiles.isVideoPage(url))
            return false
        if(!SourceIndexParser.safe(url) ||
            !SearchRanking.validUrl(cover) ||
            IndexedMediaQuality.navigationTitle(title))return false
        // The native provider contract recognizes first-party video slugs
        // without /video/ (HentaiBros) and localized catalog URLs (JAVIDEO).
        // The generic media heuristic rejects these legitimate single-segment
        // pages, leaving dozens of indexed covers invisible in the UI.
        if(com.asterion.videoflow.VideoSiteProfiles.isVideoPage(url) ||
            SearchRanking.isVideoUrl(url) ||
            IndexedMediaQuality.isMedia(url,title,cover,"ADULT"))return true
        val path=runCatching{Uri.parse(url).path.orEmpty()}.getOrDefault("")
        return Regex("^/[0-9]{4,}[-/][a-zA-Z0-9_-]{4,}").containsMatchIn(path)
    }
    /** Do not recommend site menus, language directories, tags or category pages
     * as if they were individual media. Never invent missing thumbnails.
     */
    internal fun realMedia(item:IndexedItem):Boolean {
        if(item.area in setOf("ADULT","HENTAI","VIDEOS") &&
            SiteControls.key(item.source) in setOf("onscreens.me","pimpbunny.com") &&
            !com.asterion.videoflow.VideoSiteProfiles.isVideoPage(item.url))
            return false
        if(item.area in setOf("ADULT","HENTAI","VIDEOS")){
            if(!SourceIndexParser.safe(item.url) ||
                !SourceIndexParser.sameSite(item.source,item.url))
                return false
            if(!IndexedMediaQuality.navigationTitle(item.title) &&
                com.asterion.videoflow.VideoSiteProfiles.isVideoPage(item.url))
                return true
        }
        return IndexedMediaQuality.isMedia(
            item.url,item.title,item.preview,item.area)
    }
    /** Each preview belongs to its website; performer previews belong to
     * their followed model. Never mix an unrequested global "Latest" feed.
     */
    /** Site refresh must not rebuild the Activity, tabs, scroll view or
     * unrelated modules. Coalesce independent provider callbacks and swap
     * only this Adult/Hentai section after the next section is prepared.
     */
    @Suppress("UNUSED_PARAMETER")
    fun render(a:Activity,parent:LinearLayout,area:String,refresh:()->Unit){
        require(area in setOf("ADULT","HENTAI"))
        val surface=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        parent.addView(surface)
        val pending=java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var repaint:()->Unit
        repaint=repaint@{
            if(a.isFinishing || a.isDestroyed || surface.parent!==parent ||
                !pending.compareAndSet(false,true))return@repaint
            surface.postDelayed({
                pending.set(false)
                if(!a.isFinishing && !a.isDestroyed && surface.parent===parent){
                    val next=LinearLayout(a).apply{
                        orientation=LinearLayout.VERTICAL
                    }
                    renderContents(a,next,area,repaint)
                    surface.removeAllViews()
                    surface.addView(next)
                }
            },1_200L)
        }
        val first=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        renderContents(a,first,area,repaint)
        surface.addView(first)

        // SourceIndexDb and KyoraCreatorPreviews now notify a shared
        // cross-process URI after durable source/poster cache commits.
        // Listen for those events continuously; only the affected adult
        // section is repainted. Periodic reconciliation is a safety net,
        // not the primary content update mechanism.
        val generation=java.util.concurrent.atomic.AtomicLong()
        val listening=java.util.concurrent.atomic.AtomicBoolean()
        val scheduled=java.util.concurrent.atomic.AtomicBoolean()
        fun fingerprint():Int{
            val sources=SourceIndexDb(a).use{db->
                db.sources().filter{it.area==area}.map{
                    listOf(it.url,it.lastCheck.toString(),
                        it.discovered.toString(),it.error.orEmpty())
                }
            }
            val covers=sites(a,area).map{site->
                val cached=KyoraCreatorPreviews.cached(a,site.url)
                listOf(site.host,cached?.updated.toString(),
                    cached?.entries?.size.toString(),cached?.error.orEmpty())
            }
            return (sources+covers).hashCode()
        }
        var observed=runCatching{fingerprint()}.getOrNull()
        val pump=Runnable{
            scheduled.set(false)
            if(a.isFinishing || a.isDestroyed || surface.parent!==parent ||
                !surface.isAttachedToWindow)return@Runnable
            val latest=runCatching{fingerprint()}.getOrNull()
            if(latest!=null && latest!=observed){
                observed=latest
                repaint()
            }else if(latest!=null)observed=latest
        }
        val observer=object:android.database.ContentObserver(
            android.os.Handler(android.os.Looper.getMainLooper())){
            override fun onChange(selfChange:Boolean){
                if(surface.parent===parent && surface.isAttachedToWindow &&
                    scheduled.compareAndSet(false,true))
                    surface.postDelayed(pump,300L)
            }
        }
        fun reconcile(token:Long){
            if(token!=generation.get() || a.isFinishing || a.isDestroyed ||
                surface.parent!==parent || !surface.isAttachedToWindow)return
            pump.run()
            surface.postDelayed({reconcile(token)},20_000L)
        }
        fun arm(){
            if(!listening.compareAndSet(false,true))return
            runCatching {
                a.contentResolver.registerContentObserver(
                    DownloadStateSignals.uri(a),false,observer)
            }.onFailure{listening.set(false)}
            val token=generation.incrementAndGet()
            surface.postDelayed({reconcile(token)},20_000L)
        }
        fun disarm(){
            generation.incrementAndGet()
            scheduled.set(false)
            surface.removeCallbacks(pump)
            if(listening.getAndSet(false))runCatching {
                a.contentResolver.unregisterContentObserver(observer)
            }
        }
        surface.addOnAttachStateChangeListener(
            object:View.OnAttachStateChangeListener{
                override fun onViewAttachedToWindow(v:View){arm()}
                override fun onViewDetachedFromWindow(v:View){disarm()}
            })
        surface.post{
            if(surface.isAttachedToWindow && surface.parent===parent)arm()
        }
    }

    private fun renderContents(a:Activity,parent:LinearLayout,area:String,
                               refresh:()->Unit){
        require(area in setOf("ADULT","HENTAI"))
        val isAdult=area=="ADULT"
        val title=if(isAdult)"Porn" else "Hentai"
        val preferences=a.getSharedPreferences("asterion_gallery_layout",0)
        val showModels=isAdult && preferences.getBoolean("adult_models",false)
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
        // Query by provider rather than relying on the first 1,000 entries
        // across all sources. A busy provider must not crowd newer catalogs
        // (3DHentai and EPORNER) completely off the visible shelf.
        val indexed=SourceIndexDb(a).use{db->
            sources.flatMap{db.forSource(it.url,120)}
        }.asSequence()
            // A provider redirect must never relabel another website's
            // videos as its own. In particular, OMG and FreePornVideos
            // must not share thumbnails merely because a redirect succeeded.
            .filter{SourceIndexParser.sameSite(it.source,it.url) &&
                realMedia(it) &&
                (it.preview.isNullOrBlank() ||
                    SearchRanking.validUrl(it.preview))}
            .distinctBy{SearchRanking.canonicalKey(it.url)}
            .sortedByDescending{it.discoveredAt}
            .take(1800).toList()
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
                (SiteControls.key(it.url) !in setOf("onscreens.me","pimpbunny.com") ||
                    com.asterion.videoflow.VideoSiteProfiles.isVideoPage(it.url)) &&
                SiteControls.key(it.url) in approvedHosts}
            .distinctBy{SearchRanking.canonicalKey(it.url)}
            .groupBy{SiteControls.key(it.url)}
        // Provider HTML often advertises genuine video covers before an
        // authenticated native index has discovered the originals.
        val publicBySite=sources.associate{site->
            site.host to KyoraCreatorPreviews.cached(a,site.url)?.entries.orEmpty()
                    .filter{SourceIndexParser.sameSite(site.url,it.url) &&
                        publicMedia(it.url,it.title,it.cover)}
        }
        val hasVisual=bySite.values.any{it.isNotEmpty()} ||
            searchBySite.values.any{it.isNotEmpty()} ||
            publicBySite.values.any{it.isNotEmpty()}
        // Every approved provider is probed independently. The old four-site
        // batch plus 15-minute delay left 17 of 19 sources unseen.
        if(AppPower.enabled(a)){
            val attempts=a.getSharedPreferences("asterion_gallery_preview_bootstrap",0)
            val key="full-scope:"+area
            val now=System.currentTimeMillis()
            if(now-attempts.getLong(key,0L)>20L*60L*1000L){
                attempts.edit().putLong(key,now).apply()
                SourceIndex.refreshPreviews(a.applicationContext,area){
                    a.runOnUiThread{if(!a.isFinishing&&!a.isDestroyed)refresh()}
                }
            }
            for(site in sources){
                val saved=KyoraCreatorPreviews.cached(a,site.url)
                val stale=saved==null || now-saved.updated>
                    (if(saved.error.isNullOrBlank())4L*60*60*1000 else 10L*60*1000)
                if(stale)KyoraCreatorPreviews.load(a,site.url,force=true){snapshot->
                    if(!a.isFinishing && !a.isDestroyed)refresh()
                }
            }
        }
        val previewHeader=ui.row(a).apply{gravity=Gravity.CENTER_VERTICAL}
        previewHeader.addView(ui.text(a,"Media library",18f,ui.white,true),
            LinearLayout.LayoutParams(0,-2,1f))
        previewHeader.addView(ui.chip(a,"REFRESH PREVIEWS",false){
            if(!AppPower.enabled(a)){
                Toast.makeText(a,"Resume downloads first",Toast.LENGTH_SHORT).show()
            }else{
                Toast.makeText(a,"Checking source previews",Toast.LENGTH_SHORT).show()
                // Explicit refresh checks every provider; nothing is hidden
                // behind a management dialog or an arbitrary four-site cycle.
                for(site in sources){
                    // Avoid one full section rebuild for each independent
                    // callback. The final index callback redraws once.
                    KyoraCreatorPreviews.load(a,site.url,force=true){_ -> }
                }
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
            "No source-owned media previews are available yet. " +
                "Every approved site is now checked independently. " +
                "Unavailable providers show their actual failure; " +
                "refreshing never starts a full download.",
            12f,ui.secondary),ui.lp(a,7))
        val active=sources.filter{
            bySite[it.host].orEmpty().isNotEmpty() ||
                searchBySite[it.host].orEmpty().isNotEmpty() ||
                publicBySite[it.host].orEmpty().isNotEmpty()
        }
        val inactive=sources.filterNot{it in active}
        // A source with 100 indexed posters can STILL have 7 failed real
        // originals. Previously "0 source errors" counted only providers
        // lacking a thumbnail, hiding actual VideoFlow failures completely.
        val catalogErrors=sources.count{!error(it,a).isNullOrBlank()}
        val transferBySite=runCatching{
            VideoDb(a).use{db->
                val counts=HashMap<String,Int>()
                db.readableDatabase.rawQuery(
                    "SELECT url FROM videos WHERE state IN ('error','full_only_hold')",
                    null).use{cursor->
                    while(cursor.moveToNext()){
                        val site=SiteControls.key(cursor.getString(0).orEmpty())
                        if(site in approvedHosts)
                            counts[site]=(counts[site]?:0)+1
                    }
                }
                counts
            }
        }.getOrDefault(emptyMap())
        val transferFailures=transferBySite.values.sum()
        parent.addView(ui.text(a,
            sources.size.toString()+" websites · "+active.size+
                " with indexed media cards · "+
                catalogErrors+" catalog errors · "+
                transferFailures+" failed selected originals · "+
                inactive.size+" without cards",
            12f,ui.secondary),ui.lp(a,3))
        // A selected model's content remains inside that model's card, rather
        // than appearing under an unrelated "latest" website.
        fun siteCard(site:Site){
            val media=bySite[site.host].orEmpty()
            val known=media.map{SearchRanking.canonicalKey(it.url)}.toSet()
            val searches=searchBySite[site.host].orEmpty()
                .filterNot{SearchRanking.canonicalKey(it.url) in known}
            val public=publicBySite[site.host].orEmpty()
                .filterNot{entry->
                    media.any{it.url==entry.url} ||
                        searches.any{it.url==entry.url}
                }
            val visibleSources=media.isNotEmpty()||searches.isNotEmpty()||public.isNotEmpty()
            val issue=error(site,a)?.takeUnless{reason->
                visibleSources && (
                    reason.contains("No supported public media",true) ||
                    reason.contains("No gallery thumbnails",true))
            }
            val operation=SiteRunControl.status(a,site.url)
            val running=operation.state in setOf("RUNNING","QUEUED","STOPPING")
            val card=ui.card(a,15)
            val identity=ui.row(a).apply{gravity=Gravity.CENTER_VERTICAL}
            val avatar=ImageView(a).apply{
                setImageResource(R.drawable.ic_source_video)
                scaleType=ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(ui.raised)
            }
            identity.addView(avatar,LinearLayout.LayoutParams(ui.px(a,52),ui.px(a,52)))
            media.firstOrNull()?.let{item->
                if(item.kind=="video")VideoSearchPreviews.display(a,avatar,item.preview,item.url)
                else RemoteImageLoader.load(a,avatar,item.preview,item.source)
            }
            if(media.isEmpty())searches.firstOrNull()?.let{hit->
                VideoSearchPreviews.display(a,avatar,hit.thumbnail,hit.url)
            }
            if(media.isEmpty() && searches.isEmpty())
                public.firstOrNull()?.let{item->
                    VideoSearchPreviews.display(a,avatar,item.cover,item.url)
                }
            val nameBlock=LinearLayout(a).apply{
                orientation=LinearLayout.VERTICAL
                setPadding(ui.px(a,11),0,0,0)
            }
            nameBlock.addView(ui.text(a,site.name,17f,ui.white,true).apply{
                maxLines=1;ellipsize=TextUtils.TruncateAt.END
            })
            nameBlock.addView(ui.text(a,site.host,12f,ui.secondary),ui.lp(a,4))
            transferBySite[site.host]?.takeIf{it>0}?.let{count->
                nameBlock.addView(ui.text(a,
                    count.toString()+" selected original transfer failures",
                    11f,ui.danger,true),ui.lp(a,4))
            }
            identity.addView(nameBlock,LinearLayout.LayoutParams(0,-2,1f))
            card.addView(identity)
            if(media.isNotEmpty()||searches.isNotEmpty()||public.isNotEmpty()){
                val mediaTiles=media.map{item->KurohaMediaPreview.Tile(
                    url=item.url,title=item.title,
                    thumbnail=item.preview.orEmpty(),
                    attribution=site.name,
                    referer=item.url,
                    video=item.kind=="video"
                )}
                val searchTiles=searches.map{item->KurohaMediaPreview.Tile(
                    url=item.url,title=item.title,
                    thumbnail=item.thumbnail.orEmpty(),
                    attribution=site.name+" · search match",
                    referer=item.url,video=true
                )}
                val publicTiles=public.map{item->KurohaMediaPreview.Tile(
                    url=item.url,title=item.title,thumbnail=item.cover,
                    attribution=site.name,referer=item.url,video=true,
                    duration=item.duration)}
                KurohaMediaPreview.render(a,card,
                    "website:"+area+":"+site.host,
                    mediaTiles+searchTiles+publicTiles){tile->
                    val indexItem=media.firstOrNull{it.url==tile.url}
                    if(indexItem!=null){
                        // Centralized selected-media route. An indexed video
                        // page must never become a generic direct-file request
                        // just because its indexed kind was mislabeled.
                        SourceIndexPanel.details(a,indexItem)
                    }else{
                        val hit=searches.firstOrNull{it.url==tile.url}
                        if(hit!=null){
                            if(!SelectedVideoRouter.request(
                                    a,hit.url,hit.title,area)){
                                Toast.makeText(a,
                                    "This result is not a verified selected video page",
                                    Toast.LENGTH_LONG).show()
                            }
                        }else if(public.any{it.url==tile.url}){
                            // A cover is not a downloaded original. However,
                            // tapping a confirmed video page must enqueue THAT
                            // exact original, not start an unrelated site-wide
                            // backfill and misleadingly claim download progress.
                            if(SelectedVideoRouter.request(
                                    a,tile.url,tile.title,area)){
                                // Only an exact user-selected episode enters
                                // VideoFlow, not a series page or recommendation.
                            }else{
                                thread(name="asterion-adult-public-gallery"){
                                    val message=runCatching{
                                        val source=site.source
                                        if(source?.adapter=="native")
                                            ProviderBackfillActions.request(a,source)
                                        else if(SiteRunControl.start(a,site.url,area))
                                            "Video page indexing requested · "+site.name
                                        else "Provider already scanning"
                                    }.getOrElse{it.message?:"Provider unavailable"}
                                    a.runOnUiThread{
                                        if(!a.isFinishing&&!a.isDestroyed)
                                            Toast.makeText(a,message,Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val publicChecked=KyoraCreatorPreviews.cached(a,site.url)
            val rawState=sourceState(a,site)
            val state=when{
                visibleSources && rawState in setOf("Source error","Check failed")->
                    if(issue==null)"Cached covers available · latest check inconclusive"
                    else "Cached covers available · latest check failed"
                publicChecked?.entries?.isNotEmpty()==true && rawState=="Not checked"->
                    "Catalog covers found · original downloads not verified"
                else->rawState
            }
            card.addView(ui.text(a,
                (media.size+searches.size+public.size).toString()+
                    " indexed media cards · "+
                    (media.count{!it.preview.isNullOrBlank()}+
                        searches.count{!it.thumbnail.isNullOrBlank()}+
                        public.count{it.cover.isNotBlank()}).toString()+
                    " poster URLs · "+state,
                12f,ui.secondary),ui.lp(a,5))
            if(media.isEmpty() && searches.isEmpty() && public.isEmpty()){
                card.addView(ui.text(a,
                    if(site.host=="archivebate.com")
                        "Archivebate requires its real video listing. Use CONNECT SITE if Continue is shown, then CHECK NEW; a blank landing page is not a catalog."
                    else "No accessible previews for this site yet. CHECK NEW retries"+
                        " this source only; login may be required.",
                    12f,ui.secondary),ui.lp(a,5))
                if(site.host=="archivebate.com"){
                    card.addView(ui.action(a,"CONNECT SITE",false){
                        a.startActivity(Intent(a,SiteLoginActivity::class.java)
                            .putExtra(SiteLoginActivity.EXTRA_URL,site.url))
                    },ui.lp(a,5))
                }
            }
            val sync=Switch(a).apply{
                text="Sync new videos"
                textSize=14f;setTextColor(ui.white)
                isChecked=SiteControls.sync(a,site.url,site.source?.enabled?:false)
                contentDescription="Sync new videos from "+site.name
                setOnCheckedChangeListener{_,enabled->
                    SiteControls.setSync(a,site.url,enabled)
                    thread(name="asterion-site-sync"){
                        val app=a.applicationContext
                        val address=site.source?.url?:site.url
                        if(enabled){
                            SourceIndexDb(app).use{
                                it.add(address,site.name,area,site.source?.adapter?:"native")
                                it.enable(address,true)
                            }
                            VideoDb(app).use{it.addWatch(address,site.name)}
                        }
                        SiteControls.applyToEngines(app,site.url)
                        if(enabled)VideoEngine.launch(app,true)
                    }
                }
            }
            card.addView(sync,ui.lp(a,8))
            val controls=ui.row(a)
            controls.addView(ui.action(a,"CHECK NEW",false){
                if(!AppPower.enabled(a))
                    Toast.makeText(a,"Resume first",Toast.LENGTH_SHORT).show()
                else{
                    SourceIndex.refreshSitePreview(a.applicationContext,area,site.url){
                        a.runOnUiThread{if(!a.isFinishing&&!a.isDestroyed)refresh()}
                    }
                    KyoraCreatorPreviews.load(a,site.url,force=true){snapshot->
                        if(!a.isFinishing&&!a.isDestroyed)refresh()
                    }
                    Toast.makeText(a,"Checking "+site.name+" only",Toast.LENGTH_SHORT).show()
                }
            },LinearLayout.LayoutParams(0,-2,1f))
            controls.addView(ui.action(a,if(running)"STOP" else "BACKFILL",false){
                if(running)SiteRunControl.stop(a,site.url)
                else if(!AppPower.enabled(a))
                    Toast.makeText(a,"Resume first",Toast.LENGTH_SHORT).show()
                else thread(name="asterion-site-backfill"){
                    val result=runCatching{
                        val src=site.source ?: SourceIndexDb(a).use{db->
                            // A site shown in the approved catalog must have
                            // a real source record before Backfill is offered.
                            db.add(site.url,site.name,area,
                                "generic")
                            db.sources().firstOrNull{it.url==site.url}
                        }
                        if(src?.adapter=="native")
                            ProviderBackfillActions.request(a,src)
                        else if(SiteRunControl.start(a,src?.url?:site.url,area))
                            "History queued · "+site.name
                        else "Already checking · "+site.name
                    }.getOrElse{it.message?:"Source error"}
                    a.runOnUiThread{
                        if(!a.isFinishing&&!a.isDestroyed)
                            Toast.makeText(a,result,Toast.LENGTH_LONG).show()
                    }
                }
            },LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=ui.px(a,8)})
            card.addView(controls,ui.lp(a,8))
            if(!issue.isNullOrBlank()){
                val readable=when{
                    issue.contains("403")->"HTTP 403 · provider blocked request (IP, session or access); cause unconfirmed"
                    issue.contains("429")->"HTTP 429 · rate limited"
                    issue.contains("redirect",true)->"Provider redirected unexpectedly · adapter needs repair"
                    else->issue.take(116)
                }
                card.addView(ui.text(a,
                    (if(visibleSources)"Latest check: " else "")+readable,
                    11f,if(visibleSources)ui.secondary else ui.danger),ui.lp(a,6))
                val loginRequired=issue.contains("401") ||
                    issue.contains("login required",true) ||
                    issue.contains("sign in",true) ||
                    issue.contains("session expired",true)
                val verificationBlocked=issue.contains("403") && !loginRequired
                if(loginRequired || verificationBlocked){
                    // HTTP 403 from a public site may be a browser or network
                    // challenge; never present it as mandatory account login.
                    card.addView(ui.action(a,
                        if(loginRequired)"CONNECT ACCOUNT" else "OPEN SITE",false){
                        if(loginRequired)
                            a.startActivity(Intent(a,SiteLoginActivity::class.java)
                                .putExtra(SiteLoginActivity.EXTRA_URL,site.url))
                        else a.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(site.url)))
                    },ui.lp(a,5))
                }
            }
            parent.addView(card,ui.lp(a,7))
        }
        // All approved websites remain in the same alphabetical order,
        // regardless of whether a publisher returned posters. The previous
        // active+inactive split pushed DSYSAV (no indexed covers yet) below
        // every populated provider and made an enrolled source appear missing.
        // Do not fabricate a cover or replace the existing website cards.
        for(site in sources)siteCard(site)
    }
}
