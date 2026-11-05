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
                          remote:Boolean=false):ImageView{
        val line=ui.row(a)
        val image=ImageView(a).apply{
            setImageResource(R.drawable.ic_source_site)
            scaleType=ImageView.ScaleType.CENTER_CROP
        }
        line.addView(image,LinearLayout.LayoutParams(ui.px(a,60),ui.px(a,60)))
        if(thumbnail!=null) {
            if(remote)RemoteImageLoader.load(a,image,thumbnail,thumbnail)
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
                       local:Boolean=false){
        if(items.isEmpty())return
        val line=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
        for((url,tap) in items.take(5)){
            val img=ImageView(a).apply{
                scaleType=ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(ui.raised)
                contentDescription="Open preview"
                setOnClickListener{tap()}
            }
            line.addView(img,LinearLayout.LayoutParams(ui.px(a,106),ui.px(a,106))
                .apply{rightMargin=ui.px(a,7)})
            if(local && url.startsWith("content:"))CreatorPreviews.galleryThumbnail(a,img,url,null)
            else if(local)CreatorPreviews.galleryThumbnail(a,img,null,url)
            else RemoteImageLoader.load(a,img,url,url)
        }
        put(a,p,HorizontalScrollView(a).apply{
            isHorizontalScrollBarEnabled=false;addView(line)
        },9)
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
                subtitle,cover,true)
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
            pair(a,c,"CHECK NEW","BACKFILL",{
                XPhotoQueue(a).use{it.clearError(source.url)}
                XPhotoEngine.start(a,false,source.url)
                notice(a,"Checking X @"+source.handle)
            },{
                XPhotoQueue(a).use{it.backfill(source.url)}
                XPhotoEngine.start(a,false,source.url)
                notice(a,"Resuming X history @"+source.handle)
            })
            put(a,p,c,10)
        }
        more(a,p,"x",sources.size,refresh)
    }
    fun kyora(a:Activity,p:LinearLayout,refresh:()->Unit){
        val followed=GalleryDb(a).use{it.listEntities()}
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
                cover!=null && !cover.startsWith("content:"))
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
                        a.startActivity(Intent(a,
                            com.azrael.galleryflow.ProviderWebActivity::class.java)
                            .putExtra(com.azrael.galleryflow.ProviderWebActivity.EXTRA_URL,item.url))
                    }
                }
                if(previews.isNotEmpty())images(a,holder,previews)
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
        put(a,p,ui.text(a,creators.size.toString()+" followed names · "+
            providers.count{it.enabled}+" active providers",12f,ui.secondary),3)
        // Creator previews live inside the corresponding followed-name cards.
        // A separate top-of-module media strip misattributes thumbnail ownership.
        val follow=ui.card(a)
        val field=CreatorNames.input(a,"Artist or model name","PERFORMERS")
        put(a,follow,field,0)
        action(a,follow,"FOLLOW & SEARCH ALL PROVIDERS",true){
            val name=field.text.toString().trim()
            if(name.length<2){notice(a,"Enter a name");return@action}
            NameWatchDb(a).use{it.add(name)}
            CreatorNames.remember(a,name,"PERFORMERS",true)
            thread(name="asterion-follow-scan"){NameCrawler.scan(a.applicationContext)}
            refresh()
        }
        put(a,p,follow,10)
        for(creator in creators.take(currentLimit(a,"creators"))){
            val c=ui.card(a)
            val hits=SearchResultsDb(a).use{it.listForQuery(creator.name,10)}
            val previews=hits.mapNotNull{hit->
                hit.thumbnail?.let{url->
                    url to {
                        if(VideoDb(a).use{it.addVideo(hit.url,hit.title)})
                            VideoEngine.launch(a,false)
                        notice(a,"Video queued or already saved")
                    }
                }
            }
            if(previews.isNotEmpty()){
                val line=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
                for((url,tap) in previews.take(5)){
                    val img=ImageView(a).apply{
                        setOnClickListener{tap()}
                        contentDescription="Queue video from preview"
                    }
                    line.addView(img,LinearLayout.LayoutParams(ui.px(a,106),ui.px(a,106))
                        .apply{rightMargin=ui.px(a,7)})
                    VideoSearchPreviews.display(a,img,url)
                }
                put(a,c,HorizontalScrollView(a).apply{addView(line)},8)
            }
            put(a,c,ui.text(a,creator.name,17f,ui.white,true),9)
            if(!creator.lastError.isNullOrBlank())
                put(a,c,ui.text(a,"Last scan: "+creator.lastError.take(90),
                    11f,ui.danger),5)
            toggle(a,c,"Live discovery",creator.enabled){enabled->
                NameWatchDb(a).use{db->db.setEnabled(creator.id,enabled)}
            }
            pair(a,c,"BACKFILL / CHECK","REMOVE",{
                NameWatchDb(a).use{it.setEnabled(creator.id,true)}
                thread(name="asterion-creator-scan"){NameCrawler.scan(a.applicationContext)}
                notice(a,"Creator scan started")
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
