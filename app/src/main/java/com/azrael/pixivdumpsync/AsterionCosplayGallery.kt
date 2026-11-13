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
        val following=prefs.getBoolean("following",true)
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

        if(following)AsterionUnifiedFollowing.kyora(
            a,parent,refresh,creatorsOnly=true)
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
                attribution=site.name,referer=item.url)}
            val publicTiles=public.map{item->KurohaMediaPreview.Tile(
                url=item.url,title=item.title,thumbnail=item.cover,
                attribution=site.name,referer=item.url)}
            val visual=KurohaMediaPreview.usable(nativeTiles+indexedTiles+publicTiles)
            if(visual.isNotEmpty())shown++
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
                    gallery!=null -> {
                        // Saved albums open locally. An incomplete indexed
                        // album must retry THIS gallery only, not the parent
                        // artist/tag or every gallery on its source website.
                        val original=GalleryDb(a).use{db->
                            db.galleryCanonicalUrl(gallery.source,gallery.galleryId)
                        }
                        if(gallery.state!="complete" &&
                            !original.isNullOrBlank() &&
                            ApprovedCosplayActions.isGalleryPage(original)){
                            thread(name="asterion-cosplay-retry-album"){
                                val message=runCatching{
                                    ApprovedCosplayActions.selectedGallery(
                                        a,site.url,tile.copy(url=original))
                                }.getOrElse{it.message?:"Album retry unavailable"}
                                toast(a,message)
                            }
                        }else a.startActivity(
                            Intent(a,GalleryDetailActivity::class.java).apply{
                                putExtra(GalleryDetailActivity.EXTRA_GALLERY_ID,gallery.galleryId)
                                putExtra(GalleryDetailActivity.EXTRA_SOURCE,gallery.source.wireName)
                                putExtra(GalleryDetailActivity.EXTRA_TITLE,gallery.title)
                            })
                    }
                    item!=null->SourceIndexPanel.details(a,item)
                    else->{
                        // A gallery listing thumbnail does not prove that its
                        // original is directly downloadable. Run its actual
                        // native provider parser instead of saving HTML as art.
                        thread(name="asterion-cosplay-open-gallery"){
                            val message=runCatching{
                                // A deliberately tapped public tile names a
                                // precise gallery, not an authorization to
                                // download the entire site or show a dead
                                // "source not registered" message.
                                ApprovedCosplayActions.selectedGallery(
                                    a,site.url,tile)
                            }.getOrElse{it.message?:"Gallery indexing failed"}
                            toast(a,message)
                        }
                    }
                }
            }
            val previewStatus=snapshots[host]
            val checked=when{
                !previewStatus?.error.isNullOrBlank() && visual.isEmpty()->
                    "Preview check failed"
                !source?.error.isNullOrBlank() && visual.isEmpty()->
                    "Source check failed"
                (source?.lastCheck?:0L)>0L->
                    "Catalog checked · original download not verified"
                visual.isNotEmpty()->"Public media covers · originals not verified"
                else->"Not checked"
            }
            card.addView(ui.text(a,
                visual.size.toString()+" gallery cover links · "+checked,
                12f,ui.secondary),ui.lp(a,5))
            val issue=(source?.error?:previewStatus?.error)?.takeUnless{reason->
                visual.isNotEmpty() &&
                    (reason.contains("No supported public media",true) ||
                     reason.contains("No gallery thumbnails",true))
            }
            if(!issue.isNullOrBlank())
                card.addView(ui.text(a,issue.take(130),
                    11f,ui.danger),ui.lp(a,5))
            if(visual.isEmpty())
                card.addView(ui.text(a,
                    "No source-owned media covers yet. CHECK NEW retries this "+
                        "provider; login may be required.",
                    12f,ui.secondary),ui.lp(a,5))

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
                    SourceIndex.refreshSitePreview(a.applicationContext,area,site.url){
                        a.runOnUiThread{if(!a.isFinishing&&!a.isDestroyed)refresh()}
                    }
                    KyoraCreatorPreviews.load(a,site.url,force=true){
                        if(!a.isFinishing&&!a.isDestroyed)refresh()
                    }
                    toast(a,"Checking "+site.name+" only")
                }
            },LinearLayout.LayoutParams(0,-2,1f))
            commands.addView(ui.action(a,"BACKFILL",false){
                if(!AppPower.enabled(a)){
                    toast(a,"Resume the application first")
                }else thread(name="asterion-cosplay-backfill"){
                    val result=runCatching{
                        ApprovedCosplayActions.backfill(a,site.url)
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
                " with indexed gallery cards · "+(approved.size-shown)+
                " without verified gallery metadata",
            12f,ui.secondary),ui.lp(a,8))
        if(shown==0)parent.addView(ui.text(a,
            "No genuine source covers are available yet. Refresh previews or "+
                "inspect provider and sign-in status in Manage websites. "+
                "Unverified website logos are not media thumbnails.",
            12f,ui.secondary),ui.lp(a,9))

    }
}
