package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.*
import android.view.View
import android.net.Uri
import com.azrael.galleryflow.GalleryDb
import com.azrael.galleryflow.GalleryDetailActivity
import com.azrael.galleryflow.SiteLoginActivity
import com.azrael.galleryflow.SiteSession
import com.azrael.galleryflow.SyncMode
import com.azrael.galleryflow.SyncForegroundService as GalleryService
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import kotlin.concurrent.thread

/** Thumbnail-first modules share existing source databases, never replace or migrate them. */
object AsterionUnifiedFollowing {
    private val ui=NocturneDesign
    private fun put(a:Activity,p:LinearLayout,v:View,g:Int=7)=p.addView(v,ui.lp(a,g))
    private fun notice(a:Activity,t:String)=Toast.makeText(a,t,Toast.LENGTH_LONG).show()
    private fun action(a:Activity,p:LinearLayout,text:String,primary:Boolean=false,go:()->Unit){
        put(a,p,ui.action(a,text,primary,go),8)
    }
    private fun pair(a:Activity,p:LinearLayout,l:String,r:String,left:()->Unit,right:()->Unit){
        put(a,p,ui.weighted(a,ui.action(a,l,false,left),ui.action(a,r,false,right)),8)
    }
    private fun input(a:Activity,hint:String)=EditText(a).apply{
        this.hint=hint;setSingleLine(true);textSize=15f
        setTextColor(ui.white);setHintTextColor(ui.secondary)
        background=ui.shape(a,ui.raised,12)
        setPadding(ui.px(a,12),ui.px(a,10),ui.px(a,12),ui.px(a,10))
    }
    private fun toggle(a:Activity,p:LinearLayout,label:String,value:Boolean,changed:(Boolean)->Unit){
        put(a,p,Switch(a).apply{
            text=label;textSize=13f;setTextColor(ui.secondary);isChecked=value
            setOnCheckedChangeListener{_,v->changed(v)}
        },5)
    }
    private fun cardTitle(a:Activity,p:LinearLayout,title:String,subtitle:String,thumbnail:String?,
                          remote:Boolean=false,referer:String?=null):ImageView{
        val line=ui.row(a)
        val image=ImageView(a).apply{
            // A recognizable source icon is preferable to a fake globe
            // "preview" for creators without a fetched thumbnail.
            setImageResource(if(subtitle.startsWith("@"))R.drawable.ic_source_x
                else R.drawable.ic_source_kyora)
            scaleType=ImageView.ScaleType.CENTER_CROP
        }
        line.addView(image,LinearLayout.LayoutParams(ui.px(a,60),ui.px(a,60)))
        if(thumbnail!=null) {
            if(remote)RemoteImageLoader.load(a,image,thumbnail,referer?:thumbnail)
            else CreatorPreviews.galleryThumbnail(a,image,thumbnail,null)
        }
        val names=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL
            setPadding(ui.px(a,11),0,0,0)}
        put(a,names,ui.text(a,title,17f,ui.white,true),0)
        put(a,names,ui.text(a,subtitle,12f,ui.secondary),3)
        line.addView(names,LinearLayout.LayoutParams(0,-2,1f))
        put(a,p,line,0)
        return image
    }
    private fun images(a:Activity,p:LinearLayout,items:List<Pair<String,()->Unit>>,
                       local:Boolean=false,referer:String?=null){
        if(items.isEmpty())return
        val widthDp=a.resources.displayMetrics.widthPixels/
            a.resources.displayMetrics.density
        val columns=if(widthDp>=385f)4 else 3
        for(part in items.take(16).chunked(columns)){
            val row=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
            for((url,tap) in part){
                val img=ImageView(a).apply{
                    scaleType=ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(ui.raised)
                    contentDescription="Open this creator's actual media"
                    isClickable=true
                    isFocusable=true
                    setOnClickListener{tap()}
                }
                row.addView(img,LinearLayout.LayoutParams(
                    0,ui.px(a,if(columns==4)115 else 145),1f).apply{
                    rightMargin=ui.px(a,5)
                })
                if(local && url.startsWith("content:"))
                    CreatorPreviews.galleryThumbnail(a,img,url,null)
                else if(local)CreatorPreviews.galleryThumbnail(a,img,null,url)
                else RemoteImageLoader.load(a,img,url,referer?:url)
            }
            repeat(columns-part.size){
                row.addView(android.view.View(a),
                    LinearLayout.LayoutParams(0,1,1f))
            }
            put(a,p,row,7)
        }
    }
    private fun currentLimit(a:Activity,key:String)=
        a.getSharedPreferences("asterion_unified_ui",0).getInt(key,10).coerceIn(10,400)
    private fun more(a:Activity,p:LinearLayout,key:String,total:Int,refresh:()->Unit){
        val n=currentLimit(a,key)
        if(n<total)action(a,p,"SHOW MORE ("+(total-n)+" remaining)"){
            a.getSharedPreferences("asterion_unified_ui",0).edit()
                .putInt(key,(n+20).coerceAtMost(400)).apply()
            refresh()
        }
    }
    fun x(a:Activity,p:LinearLayout,refresh:()->Unit){
        val sources=XPhotoQueue(a).use{it.allSources()}
        val photos=XPhotoQueue(a).use{it.recent(700)}
        put(a,p,ui.text(a,"X · Following",22f,ui.white,true),8)
        put(a,p,ui.text(a,sources.size.toString()+" followed accounts",12f,ui.secondary),2)
        // Each creator owns her previews inside her card, like Kuroha.
        val controls=ui.card(a)
        pair(a,controls,"SIGN IN","IMPORT FOLLOWS",{
            a.startActivity(Intent(a,SiteLoginActivity::class.java)
                .putExtra(SiteLoginActivity.EXTRA_URL,"https://x.com/"))
        },{
            a.startActivity(Intent(a,XFollowingImportActivity::class.java))
        })
        action(a,controls,"SYNC NEW PHOTOS",true){XPhotoScheduler.ensure(a);XPhotoEngine.start(a)}
        put(a,p,controls,8)
        val follow=ui.card(a)
        val field=CreatorNames.input(a,"@handle or X profile link","X")
        put(a,follow,field,0)
        action(a,follow,"FOLLOW & BACKFILL",true){
            val v=field.text.toString().trim()
            val url=when{
                v.startsWith("@")->"https://x.com/"+v.drop(1)
                v.matches(Regex("[A-Za-z0-9_]{1,15}"))->"https://x.com/"+v
                else->v
            }
            if(XPhotoQueue(a).use{it.addSource(url)}){
                CreatorNames.remember(a,"@"+url.substringAfterLast("/"),"X",true)
                XPhotoScheduler.ensure(a);XPhotoEngine.start(a);refresh()
            }else notice(a,"Invalid or already followed X account")
        }
        put(a,p,follow,10)
        val recentByHandle=photos.groupBy{photo->
            runCatching{Uri.parse(photo.source).pathSegments.firstOrNull().orEmpty()
                .lowercase()}.getOrDefault("")
        }
        for(source in sources.take(currentLimit(a,"x"))){
            val found=recentByHandle[source.handle.lowercase()].orEmpty().take(5)
            val profile=XCreatorProfiles.cached(a,source.handle)
            val c=ui.card(a)
            val subtitle="@"+source.handle+" · "+(
                if(!source.error.isNullOrBlank())source.error.take(65)
                else if(source.cursor.isNullOrBlank())"Backfill ready"
                else "History cursor saved")
            val cover=profile?.avatar?:found.firstOrNull()?.photo
            val avatar=cardTitle(a,c,profile?.name?:("@"+source.handle),
                subtitle,cover,true,source.url)
            val previewHolder=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
            fun drawPreviews(latest:List<String>){
                previewHolder.removeAllViews()
                val photosOnDisk=found.map{it.photo}
                val pictures=(photosOnDisk+latest).distinct().take(5)
                if(pictures.isNotEmpty())images(a,previewHolder,pictures.map{url->
                    url to {
                        a.startActivity(Intent(a,XMediaCaptureActivity::class.java)
                            .putExtra(XMediaCaptureActivity.EXTRA_HANDLE,source.handle))
                    }
                }) else previewHolder.addView(ui.text(a,
                    "No media cached for this creator yet · use CHECK NEW to populate",
                    12f,ui.secondary),ui.lp(a,8))
            }
            drawPreviews(profile?.photos.orEmpty())
            c.addView(previewHolder)
            // Every currently displayed account gets a cached/TTL-limited
            // profile lookup. The former first-15 cap left later accounts blank.
            XCreatorProfiles.load(a,source.handle){info->
                if(info.avatar!=null)RemoteImageLoader.load(a,avatar,info.avatar,
                    "https://x.com/"+source.handle)
                drawPreviews(info.photos)
            }
            if(!source.error.isNullOrBlank())
                put(a,c,ui.text(a,source.error.take(160),12f,ui.danger),5)
            toggle(a,c,"Sync new",source.enabled && source.liveEnabled){enabled->
                XPhotoQueue(a).use{it.setMode(source.url,"live_enabled",enabled)}
                a.getSharedPreferences("asterion_x_live",0).edit()
                    .putBoolean(source.url,enabled).apply()
                XPhotoScheduler.ensure(a)
            }
            pair(a,c,"CHECK NEW",
                if(XPhotoEngine.isScanning(source.url))"STOP" else "BACKFILL",{
                XPhotoQueue(a).use{it.clearError(source.url)}
                XPhotoEngine.start(a,false,source.url)
                notice(a,"Checking X @"+source.handle)
            },{
                if(XPhotoEngine.isScanning(source.url)){
                    XPhotoEngine.stopSource(a,source.url)
                    notice(a,"Stopping X historical scan @"+source.handle)
                }else{
                    XPhotoEngine.startSourceBackfill(a,source.url)
                    notice(a,"Requested X history @"+source.handle)
                }
                refresh()
            })
            put(a,p,c,10)
        }
        more(a,p,"x",sources.size,refresh)
    }
    fun kyora(a:Activity,p:LinearLayout,refresh:()->Unit,
              creatorsOnly:Boolean=false){
        val followed=GalleryDb(a).use{it.listEntities()}
            .filter{!creatorsOnly || it.kind!="site"}
        if(creatorsOnly && followed.isEmpty())return
        put(a,p,ui.text(a,"Cosplay · Following",22f,ui.white,true),8)
        put(a,p,ui.text(a,followed.size.toString()+" creators and collections",12f,ui.secondary),2)
        // Latest gallery thumbnails are embedded within each followed creator card.
        for(entity in followed.take(currentLimit(a,"kyora"))){
            val gallery=GalleryDb(a).use{it.galleryCards(entity.entityId,5)}
            val c=ui.card(a)
            val first=gallery.firstOrNull()
            val thumbnail=first?.firstUri?:first?.firstUrl
            val cached=KyoraCreatorPreviews.cached(a,entity.canonicalUrl)
            val cover=thumbnail?:cached?.entries?.firstOrNull()?.cover
            val isProvider=entity.kind=="site"
            val siteHost=SiteControls.key(entity.canonicalUrl)
            val shortName=if(isProvider){
                AlohaSources.all.firstOrNull{
                    SiteControls.key(it.url)==siteHost
                }?.name?:siteHost.substringBefore('.')
                    .replaceFirstChar{it.uppercase()}
            }else entity.displayName
            val avatar=cardTitle(a,c,shortName.take(if(isProvider)28 else 90),
                if(isProvider)siteHost else entity.source.wireName,cover,
                cover!=null && !cover.startsWith("content:"),entity.canonicalUrl)
            val holder=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
            val nativePreviews=gallery.mapNotNull{item->
                (item.firstUri?:item.firstUrl)?.let{url->
                    url to {
                        a.startActivity(Intent(a,GalleryDetailActivity::class.java).apply{
                            putExtra(GalleryDetailActivity.EXTRA_GALLERY_ID,item.galleryId)
                            putExtra(GalleryDetailActivity.EXTRA_SOURCE,item.source.wireName)
                            putExtra(GalleryDetailActivity.EXTRA_TITLE,item.title)
                        })
                    }
                }
            }
            fun drawSite(site:KyoraCreatorPreviews.Snapshot?){
                if(nativePreviews.isNotEmpty())return
                holder.removeAllViews()
                val previews=site?.entries.orEmpty().map{item->
                    item.cover to {
                        // A listing cover is not a validated original file.
                        // Ask the existing native parser to resolve this gallery,
                        // instead of downloading an HTML page as an image.
                        if(!AppPower.enabled(a))notice(a,"Resume first")
                        else runCatching{
                            a.startForegroundService(Intent(a,GalleryService::class.java)
                                .setAction(GalleryService.ACTION_START)
                                .putExtra(GalleryService.EXTRA_MODE,SyncMode.BACKFILL.name)
                                .putExtra(GalleryService.EXTRA_SITE_ID,entity.entityId))
                            notice(a,"Indexing original gallery · "+item.title.take(50))
                        }.onFailure{notice(a,it.message?:"Gallery parser unavailable")}
                        Unit
                    }
                }
                if(previews.isNotEmpty())images(a,holder,previews,
                    referer=entity.canonicalUrl)
                else put(a,holder,ui.text(a,
                    site?.error?.let{"Gallery preview unavailable · "+it.take(95)}
                        ?: "Latest galleries not indexed yet · BACKFILL to populate",
                    12f,ui.secondary),8)
            }
            if(nativePreviews.isNotEmpty())images(a,holder,nativePreviews,true)
            else{
                drawSite(cached)
                // Fetch only cards rendered by the current "Show more" page;
                // existing per-site TTL and shared two-thread pool limit requests.
                KyoraCreatorPreviews.load(a,entity.canonicalUrl){snapshot->
                    // Do not recreate stale cards after tab changes.
                    if(holder.isAttachedToWindow && !a.isFinishing){
                        snapshot.entries.firstOrNull()?.cover?.let{src->
                            RemoteImageLoader.load(a,avatar,src,entity.canonicalUrl)
                        }
                        drawSite(snapshot)
                    }
                }
            }
            c.addView(holder)
            if(!entity.lastError.isNullOrBlank())
                put(a,c,ui.text(a,entity.lastError.take(130),12f,ui.danger),5)
            val currentSync=SiteControls.sync(a,entity.canonicalUrl,
                entity.selected && entity.liveEnabled)
            toggle(a,c,"Sync",currentSync){enabled->
                SiteControls.setSync(a,entity.canonicalUrl,enabled)
                GalleryDb(a).use{db->
                    db.setLiveEnabled(entity.source,entity.entityId,enabled)
                    db.setSelected(entity.source,entity.entityId,enabled)
                }
            }
            pair(a,c,"CHECK NEW","BACKFILL",{
                if(!AppPower.enabled(a))notice(a,"Background work is paused")
                else runCatching{
                    a.startForegroundService(Intent(a,GalleryService::class.java)
                        .setAction(GalleryService.ACTION_START)
                        .putExtra(GalleryService.EXTRA_MODE,SyncMode.LIVE.name)
                        .putExtra(GalleryService.EXTRA_SITE_ID,entity.entityId))
                }.onFailure{notice(a,it.message?:"Sync did not start")}
            },{
                if(!AppPower.enabled(a))notice(a,"Background work is paused")
                else runCatching{
                    a.startForegroundService(Intent(a,GalleryService::class.java)
                        .setAction(GalleryService.ACTION_START)
                        .putExtra(GalleryService.EXTRA_MODE,SyncMode.BACKFILL.name)
                        .putExtra(GalleryService.EXTRA_SITE_ID,entity.entityId))
                }.onFailure{notice(a,it.message?:"Backfill did not start")}
            })
            action(a,c,if(SiteSession.bound(a,entity.canonicalUrl))
                "ACCOUNT" else "SIGN IN",false){
                a.startActivity(Intent(a,SiteLoginActivity::class.java)
                    .putExtra(SiteLoginActivity.EXTRA_URL,entity.canonicalUrl))
            }
            put(a,p,c,10)
        }
        more(a,p,"kyora",followed.size,refresh)
    }
    fun adult(a:Activity,p:LinearLayout,refresh:()->Unit){
        val creators=NameWatchDb(a).use{it.list()}
        val providers=VideoDb(a).use{it.listWatches()}
        put(a,p,ui.text(a,"Followed performers",19f,ui.white,true),13)
        put(a,p,ui.text(a,creators.size.toString()+" followed performers · "+
            providers.count{it.enabled}+" subscribed websites",12f,ui.secondary),3)
        // Creator previews live inside the corresponding followed-name cards.
        // A separate top-of-module media strip misattributes thumbnail ownership.
        val follow=ui.card(a)
        val field=CreatorNames.input(a,"Artist or model name","PERFORMERS")
        put(a,follow,field,0)
        action(a,follow,"VERIFY & FOLLOW PERFORMER",true){
            val name=field.text.toString().trim()
            if(name.length<2){notice(a,"Enter a performer name");return@action}
            notice(a,"Checking actual first-party performer profiles…")
            thread(name="asterion-performer-verify"){
                val proof=PerformerIdentity.lookup(name)
                a.runOnUiThread{
                    if(a.isFinishing || a.isDestroyed)return@runOnUiThread
                    if(proof==null){
                        notice(a,"No verified performer profile found. Nothing was followed.")
                    }else{
                        val id=NameWatchDb(a).use{
                            it.addVerified(proof.displayName,proof.profileUrl)
                        }
                        CreatorNames.remember(a,proof.displayName,"PERFORMERS",true)
                        NameCrawler.scanCreator(a.applicationContext,id)
                        notice(a,"Verified "+proof.displayName+" on "+proof.provider)
                        refresh()
                    }
                }
            }
        }
        put(a,p,follow,10)
        for(creator in creators.take(currentLimit(a,"creators"))){
            val c=ui.card(a)
            val prefs=a.getSharedPreferences("asterion_performer_gallery",0)
            val full=prefs.getBoolean("expanded:"+creator.id,false)
            val verified=PerformerIdentity.isProfileUrl(creator.name,creator.source)
            // Old name-only follows are retained for recovery, but unrelated
            // historic keyword results never pass as verified performer media.
            val hits=if(verified)SearchResultsDb(a).use{
                it.listForQuery(creator.name,if(full)100 else 24)
            }.filter{SearchExactness.matches(creator.name,it.title) &&
                !IndexedMediaQuality.navigationTitle(it.title)}
            else emptyList()
            val gallery=hits.filter{!it.thumbnail.isNullOrBlank() &&
                SearchRanking.validUrl(it.thumbnail)}
            val head=ui.row(a)
            val avatar=ImageView(a).apply{
                scaleType=ImageView.ScaleType.CENTER_CROP
                setImageResource(R.drawable.ic_source_video)
                setBackgroundColor(ui.raised)
            }
            head.addView(avatar,LinearLayout.LayoutParams(ui.px(a,52),ui.px(a,52)))
            gallery.firstOrNull()?.let{cover->
                VideoSearchPreviews.display(a,avatar,cover.thumbnail,cover.url)
            }
            val labels=LinearLayout(a).apply{
                orientation=LinearLayout.VERTICAL
                setPadding(ui.px(a,11),0,0,0)
            }
            val name=ui.text(a,creator.name,17f,ui.white,true).apply{
                isClickable=true
                isFocusable=true
                contentDescription="Expand indexed videos for "+creator.name
                setOnClickListener{
                    prefs.edit().putBoolean("expanded:"+creator.id,!full).apply()
                    refresh()
                }
            }
            labels.addView(name)
            labels.addView(ui.text(a,
                hits.size.toString()+" indexed originals · "+
                    if(full)"full collection" else "tap name to expand",
                12f,ui.secondary),ui.lp(a,4))
            head.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            put(a,c,head,3)
            if(gallery.isNotEmpty()){
                KurohaMediaPreview.render(a,c,
                    "model:"+creator.id,
                    gallery.map{hit->KurohaMediaPreview.Tile(
                        url=hit.url,title=hit.title,
                        thumbnail=hit.thumbnail.orEmpty(),
                        attribution=hit.source,
                        referer=hit.url,video=true
                    )}){tile->
                    val matching=gallery.firstOrNull{it.url==tile.url}
                    if(matching!=null){
                        if(VideoDb(a).use{it.addVideo(matching.url,matching.title)})
                            VideoEngine.launch(a,false)
                        notice(a,"Queued in Downloads · "+matching.title.take(50))
                    }
                }
            }
            if(full){
                for(hit in hits.filter{it.thumbnail.isNullOrBlank()}.take(8)){
                    action(a,c,"DOWNLOAD · "+hit.title.take(45),false){
                        if(VideoDb(a).use{it.addVideo(hit.url,hit.title)})
                            VideoEngine.launch(a,false)
                        notice(a,"Queued · "+hit.title.take(65))
                    }
                }
            }
            if(hits.isEmpty()){
                put(a,c,ui.text(a,if(verified)
                    "Verified profile · no attributable original videos indexed yet"
                    else "Unverified legacy name · CHECK verifies real performer identity before syncing",
                    12f,ui.secondary),6)
            }
            if(!creator.lastError.isNullOrBlank())
                put(a,c,ui.text(a,"Source errors: "+creator.lastError.take(160),
                    11f,ui.danger),5)
            toggle(a,c,"Sync new videos",creator.enabled && verified){enabled->
                if(enabled && !verified){
                    notice(a,"Verify this model's actual profile first")
                }else NameWatchDb(a).use{it.setEnabled(creator.id,enabled)}
            }
            pair(a,c,if(verified)"CHECK THIS MODEL" else "VERIFY THIS MODEL","REMOVE",{
                if(verified){
                    NameWatchDb(a).use{it.setEnabled(creator.id,true)}
                    NameCrawler.scanCreator(a.applicationContext,creator.id)
                    notice(a,"Checking verified performer profile: "+creator.name)
                }else{
                    notice(a,"Verifying performer identity…")
                    thread(name="asterion-existing-performer-verify"){
                        val proof=PerformerIdentity.lookup(creator.name)
                        a.runOnUiThread{
                            if(!a.isFinishing && !a.isDestroyed){
                                if(proof==null)
                                    notice(a,"Not verified; automatic Sync remains disabled")
                                else{
                                    val id=NameWatchDb(a).use{
                                        it.addVerified(creator.name,proof.profileUrl)
                                    }
                                    NameCrawler.scanCreator(a.applicationContext,id)
                                    refresh()
                                }
                            }
                        }
                    }
                }
            },{
                AlertDialog.Builder(a).setTitle("Unfollow "+creator.name+"?")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Remove"){_,_->
                        NameWatchDb(a).use{it.delete(creator.id)};refresh()
                    }.show()
            })
            put(a,p,c,10)
        }
        more(a,p,"creators",creators.size,refresh)
    }
}
