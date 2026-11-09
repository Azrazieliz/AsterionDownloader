package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import com.azrael.galleryflow.GalleryDb
import com.azrael.galleryflow.GalleryDetailActivity
import com.azrael.galleryflow.SyncForegroundService as GalleryService
import com.azrael.galleryflow.SyncMode
import kotlin.concurrent.thread

/**
 * Cosplay shares Pixiv's actual creator/gallery structure instead of showing
 * the list of unverified websites as its home page. No fake source thumbnails.
 * Native GalleryFlow files, indexed media and proven public listing covers are
 * separate sources of evidence, displayed only with their owning provider.
 */
object AsterionCosplayGallery {
    private val ui=NocturneDesign

    private fun toast(a:Activity,message:String)=a.runOnUiThread{
        if(!a.isFinishing && !a.isDestroyed)
            Toast.makeText(a,message,Toast.LENGTH_LONG).show()
    }

    fun render(a:Activity,parent:LinearLayout,refresh:()->Unit){
        val area="COSPLAY"
        val prefs=a.getSharedPreferences("asterion_cosplay_visual_v3",0)
        val following=prefs.getBoolean("following",false)
        val title=ui.row(a).apply{gravity=Gravity.CENTER_VERTICAL}
        title.addView(ui.text(a,"Cosplay",22f,ui.white,true),
            LinearLayout.LayoutParams(0,-2,1f))
        title.addView(ui.chip(a,
            if(following)"HIDE FOLLOWING" else "FOLLOWING",following){
            prefs.edit().putBoolean("following",!following).apply()
            refresh()
        },LinearLayout.LayoutParams(-2,-2))
        parent.addView(title,ui.lp(a,9))

        val approved=AlohaSources.by(AlohaArea.COSPLAY)
        val registered=SourceIndex.status(a,area)
        val indexed=SourceIndex.items(a,area,700).asSequence()
            .filter{AsterionAdultHub.realMedia(it) &&
                it.preview?.let{url->SearchRanking.validUrl(url)}==true}
            .distinctBy{SearchRanking.canonicalKey(it.url)}.toList()
        val bySite=indexed.groupBy{SiteControls.key(it.source)}
        val entities=GalleryDb(a).use{it.listEntities()}
        val snapshots=approved.associate{it.host to KyoraCreatorPreviews.cached(a,it.url)}
        val nativeBySite=entities.filter{
            it.canonicalUrl.isNotBlank() && it.kind=="site"
        }.groupBy{SiteControls.key(it.canonicalUrl)}

        // Real public media previews can be discovered with Sync switched off.
        // Loading cover metadata never starts a full media download.
        if(AppPower.enabled(a)){
            val now=System.currentTimeMillis()
            val key="last-preview-discovery"
            if(now-prefs.getLong(key,0L)>15L*60*1000L){
                prefs.edit().putLong(key,now).apply()
                SourceIndex.refreshPreviews(a.applicationContext,area){
                    a.runOnUiThread{if(!a.isFinishing&&!a.isDestroyed)refresh()}
                }
            }
            for(site in approved){
                if(snapshots[site.host]==null){
                    KyoraCreatorPreviews.load(a,site.url){got->
                        if(got.entries.isNotEmpty() && !a.isFinishing && !a.isDestroyed)
                            refresh()
                    }
                }
            }
        }

        val tools=ui.row(a).apply{gravity=Gravity.CENTER_VERTICAL}
        tools.addView(ui.text(a,"Gallery library",18f,ui.white,true),
            LinearLayout.LayoutParams(0,-2,1f))
        tools.addView(ui.chip(a,"REFRESH PREVIEWS",false){
            if(!AppPower.enabled(a)){
                toast(a,"Resume the application first")
            }else{
                SourceIndex.refreshPreviews(a.applicationContext,area){
                    a.runOnUiThread{if(!a.isFinishing && !a.isDestroyed)refresh()}
                }
                approved.forEach{site->
                    KyoraCreatorPreviews.load(a,site.url,force=true){got->
                        if(!a.isFinishing && !a.isDestroyed){
                            toast(a,if(got.error.isNullOrBlank())
                                "Updated gallery previews · "+site.name
                                else site.name+": "+got.error.take(80))
                            refresh()
                        }
                    }
                }
                toast(a,"Checking real gallery covers from approved sources")
            }
        },LinearLayout.LayoutParams(-2,-2))
        parent.addView(tools,ui.lp(a,8))

        var shown=0
        for(site in approved){
            val host=site.host
            val source=registered.firstOrNull{SiteControls.key(it.url)==host}
            val galleryEntities=nativeBySite[host].orEmpty()
            val galleries=GalleryDb(a).use{db->
                galleryEntities.flatMap{db.galleryCards(it.entityId,5)}
            }.distinctBy{it.galleryId}.take(12)
            val indexedItems=bySite[host].orEmpty()
            val public=snapshots[host]?.entries.orEmpty()

            val nativeTiles=galleries.mapNotNull{item->
                val cover=item.firstUri?:item.firstUrl
                if(cover.isNullOrBlank())null
                else KurohaMediaPreview.Tile(
                    url="gallery:"+item.galleryId,
                    title=item.title,
                    thumbnail=cover,
                    attribution=site.name,
                    referer=site.url)
            }
            val indexedTiles=indexedItems.map{item->KurohaMediaPreview.Tile(
                url=item.url,title=item.title,thumbnail=item.preview.orEmpty(),
                attribution=site.name,referer=site.url)}
            val publicTiles=public.map{item->KurohaMediaPreview.Tile(
                url=item.url,title=item.title,thumbnail=item.cover,
                attribution=site.name,referer=site.url)}
            val visual=KurohaMediaPreview.usable(nativeTiles+indexedTiles+publicTiles)
            if(visual.isEmpty())continue
            shown++
            val card=ui.card(a,15)
            val head=ui.row(a).apply{gravity=Gravity.CENTER_VERTICAL}
            val avatar=ImageView(a).apply{
                scaleType=ImageView.ScaleType.CENTER_CROP
                setImageResource(R.drawable.ic_source_kyora)
                setBackgroundColor(ui.raised)
            }
            head.addView(avatar,LinearLayout.LayoutParams(ui.px(a,52),ui.px(a,52)))
            visual.firstOrNull()?.let{cover->
                if(cover.thumbnail.startsWith("content://"))
                    CreatorPreviews.galleryThumbnail(a,avatar,cover.thumbnail,null)
                else RemoteImageLoader.load(a,avatar,cover.thumbnail,site.url)
            }
            val labels=LinearLayout(a).apply{
                orientation=LinearLayout.VERTICAL
                setPadding(ui.px(a,11),0,0,0)
            }
            labels.addView(ui.text(a,site.name,17f,ui.white,true))
            labels.addView(ui.text(a,host,12f,ui.secondary),ui.lp(a,3))
            head.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            card.addView(head)

            KurohaMediaPreview.render(a,card,"pixiv:cosplay:"+host,visual){tile->
                val gallery=galleries.firstOrNull{"gallery:"+it.galleryId==tile.url}
                val item=indexedItems.firstOrNull{it.url==tile.url}
                when {
                    gallery!=null -> a.startActivity(
                        Intent(a,GalleryDetailActivity::class.java).apply{
                            putExtra(GalleryDetailActivity.EXTRA_GALLERY_ID,gallery.galleryId)
                            putExtra(GalleryDetailActivity.EXTRA_SOURCE,gallery.source.wireName)
                            putExtra(GalleryDetailActivity.EXTRA_TITLE,gallery.title)
                        })
                    item!=null->SourceIndexPanel.details(a,item)
                    else->{
                        // A gallery listing thumbnail does not prove that its
                        // original is directly downloadable. Run its actual
                        // native provider parser instead of saving HTML as art.
                        thread(name="asterion-cosplay-open-gallery"){
                            val message=runCatching{
                                if(source==null)"Source not registered · "+site.name
                                else if(source.adapter=="native")
                                    ProviderBackfillActions.request(a,source)
                                else if(SiteRunControl.start(a,source.url,area))
                                    "Gallery indexing requested · "+site.name
                                else "Gallery already being indexed"
                            }.getOrElse{it.message?:"Gallery indexing failed"}
                            toast(a,message)
                        }
                    }
                }
            }
            val checked=when {
                !source?.error.isNullOrBlank()->"Check failed"
                source?.lastCheck?:0L>0L->"Checked · not a completed download"
                else->"Catalog preview · history not verified"
            }
            card.addView(ui.text(a,
                visual.size.toString()+" authentic gallery covers · "+checked,
                12f,ui.secondary),ui.lp(a,5))
            if(!source?.error.isNullOrBlank())
                card.addView(ui.text(a,source?.error.orEmpty().take(130),
                    11f,ui.danger),ui.lp(a,5))

            val sync=Switch(a).apply{
                text="Sync new galleries"
                textSize=14f
                setTextColor(ui.white)
                isChecked=SiteControls.sync(a,source?.url?:site.url,false)
                setOnCheckedChangeListener{_,enabled->
                    SiteControls.setSync(a,site.url,enabled)
                    thread(name="asterion-cosplay-sync"){
                        SiteControls.applyToEngines(a.applicationContext,site.url)
                    }
                }
            }
            card.addView(sync,ui.lp(a,8))
            val commands=ui.row(a)
            commands.addView(ui.action(a,"CHECK NEW",false){
                if(!AppPower.enabled(a)){
                    toast(a,"Resume the application first")
                }else{
                    SourceIndex.refreshPreviews(a.applicationContext,area){
                        a.runOnUiThread{if(!a.isFinishing&&!a.isDestroyed)refresh()}
                    }
                    toast(a,"Checking new previews · "+site.name)
                }
            },LinearLayout.LayoutParams(0,-2,1f))
            commands.addView(ui.action(a,"BACKFILL",false){
                if(!AppPower.enabled(a)){
                    toast(a,"Resume the application first")
                }else thread(name="asterion-cosplay-backfill"){
                    val result=runCatching{
                        when{
                            source?.adapter=="native" ->
                                ProviderBackfillActions.request(a,source)
                            source!=null && SiteRunControl.start(a,source.url,area)->
                                "History queued · "+site.name
                            source==null->"Site not registered"
                            else->"Already checking · "+site.name
                        }
                    }.getOrElse{it.message?:"Provider error"}
                    toast(a,result)
                }
            },LinearLayout.LayoutParams(0,-2,1f).apply{
                leftMargin=ui.px(a,8)
            })
            card.addView(commands,ui.lp(a,8))
            parent.addView(card,ui.lp(a,11))
        }
        parent.addView(ui.text(a,
            approved.size.toString()+" approved websites · "+shown+
                " displaying real media · "+(approved.size-shown)+
                " awaiting accessible thumbnails",
            12f,ui.secondary),ui.lp(a,8))
        if(shown==0)parent.addView(ui.text(a,
            "No genuine source covers are available yet. Refresh previews or "+
                "inspect provider and sign-in status in Manage websites. "+
                "Unverified website logos are not media thumbnails.",
            12f,ui.secondary),ui.lp(a,9))
        parent.addView(ui.action(a,"MANAGE WEBSITES · "+approved.size,false){
            SourceIndexPanel.showStatus(a,listOf(area),"Cosplay")
        },ui.lp(a,9))
        if(following)AsterionUnifiedFollowing.kyora(a,parent,refresh)
    }
}
