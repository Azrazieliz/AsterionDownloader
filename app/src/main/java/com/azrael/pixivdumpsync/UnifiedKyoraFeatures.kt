package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Switch
import android.widget.Toast
import com.azrael.galleryflow.AdapterRegistry
import com.azrael.galleryflow.GalleryDb
import com.azrael.galleryflow.GalleryRequestLedger
import com.azrael.galleryflow.Prefs
import com.azrael.galleryflow.SourceId
import com.azrael.galleryflow.SyncControl as GalleryControl
import com.azrael.galleryflow.SyncForegroundService as GalleryService
import com.azrael.galleryflow.SyncMode as GalleryMode
import com.azrael.galleryflow.Scheduler as GalleryScheduler
import com.azrael.galleryflow.ExportManager
import kotlin.concurrent.thread

/** Legacy Kyora's real source, exclusion, import/export and sync commands.
 * All use the original GalleryDb + GalleryFlow service; never create an
 * alternate downloader or require switching to the legacy MainActivity.
 */
internal object UnifiedKyoraFeatures {
    private val ui=NocturneDesign
    private fun show(a:Activity,s:String) {
        if(!a.isFinishing&&!a.isDestroyed)
            Toast.makeText(a,s,Toast.LENGTH_LONG).show()
    }
    private fun add(a:Activity,p:LinearLayout,t:String,run:()->Unit) {
        p.addView(ui.action(a,t,false,run),ui.lp(a,8))
    }
    private fun row(a:Activity,p:LinearLayout,l:String,r:String,
                    left:()->Unit,right:()->Unit) {
        val v=ui.row(a)
        v.addView(ui.action(a,l,false,left),LinearLayout.LayoutParams(0,-2,1f))
        v.addView(ui.action(a,r,false,right),
            LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=ui.px(a,7)})
        p.addView(v,ui.lp(a,7))
    }
    private fun editor(a:Activity,hint:String,value:String="")=EditText(a).apply {
        this.hint=hint
        textSize=14f
        setText(value)
        setTextColor(ui.white)
        setHintTextColor(ui.secondary)
        setPadding(ui.px(a,13),ui.px(a,12),ui.px(a,13),ui.px(a,12))
        background=ui.shape(a,ui.raised,12)
    }
    private fun runSync(a:Activity,mode:GalleryMode,id:String?=null) {
        if(!AppPower.enabled(a)){show(a,"Resume Asterion first");return}
        if(GalleryControl.snapshot().running){
            show(a,"Gallery sync is already running");return
        }
        val eligible=GalleryDb(a).use{it.listEntities()}
        if(eligible.isEmpty()){show(a,"Add a gallery source first");return}
        if(id!=null)GalleryRequestLedger.remember(a,
            GalleryRequestLedger.Request(mode,id))
        val intent=Intent(a,GalleryService::class.java)
            .setAction(GalleryService.ACTION_START)
            .putExtra(GalleryService.EXTRA_MODE,mode.name)
        if(id!=null)intent.putExtra(GalleryService.EXTRA_SITE_ID,id)
        runCatching{a.startForegroundService(intent)}
            .onSuccess{show(a,"Kyora "+mode.name.lowercase()+" request sent")}
            .onFailure{show(a,"Kyora could not start: "+it.message)}
    }
    private fun exclusion(a:Activity,id:String?=null) {
        val input=editor(a,"One exact excluded gallery tag per line",
            if(id!=null)Prefs.siteExcludedTags(a,id)else Prefs.excludedTags(a))
            .apply{minLines=5;setSingleLine(false)}
        AlertDialog.Builder(a).setTitle(if(id==null)
            "Kyora · blocked tags" else "Blocked tags · selected gallery site")
            .setMessage("No existing downloaded files will be deleted.")
            .setView(input).setNegativeButton("Cancel",null)
            .setPositiveButton("Save"){_,_->
                if(id==null)Prefs.setExcludedTags(a,input.text.toString())
                else Prefs.setSiteExcludedTags(a,id,input.text.toString())
                show(a,"Kyora filters saved")
            }.show()
    }
    private fun addSource(a:Activity,refresh:()->Unit) {
        val box=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;
            setPadding(ui.px(a,14),ui.px(a,8),ui.px(a,14),ui.px(a,8))}
        val url=editor(a,"https://website.com/album/")
        box.addView(url,ui.lp(a,5))
        val choices=listOf("Auto detect","Gallery feed","Albums / site")
        val spinner=Spinner(a).apply{
            adapter=ArrayAdapter(a,android.R.layout.simple_spinner_dropdown_item,choices)
        }
        box.addView(spinner,ui.lp(a,10))
        AlertDialog.Builder(a).setTitle("Add a Kyora website")
            .setMessage("Use an exact supported website, artist or album URL.")
            .setView(box).setNegativeButton("Cancel",null)
            .setPositiveButton("Add"){_,_->
                val address=url.text.toString().trim()
                if(address.isBlank()){show(a,"Enter a valid site URL");return@setPositiveButton}
                val mode=when(spinner.selectedItemPosition){
                    1->"gallery_only";2->"site";else->"auto"
                }
                thread(name="asterion-unified-kyora-add"){
                    val result=runCatching{
                        val adapter=AdapterRegistry.forUrl(address,a.applicationContext)
                        check(adapter.enabled){adapter.statusLabel}
                        val resolved=adapter.resolveEntity(address)
                        val configured=if(adapter.source==SourceId.GENERIC &&
                            mode!="auto")resolved.copy(kind=mode) else resolved
                        GalleryDb(a.applicationContext).use{it.addEntity(configured)}
                        configured.displayName
                    }
                    a.runOnUiThread{
                        if(a.isFinishing||a.isDestroyed)return@runOnUiThread
                        result.onSuccess{show(a,"Added Kyora source: "+it);refresh()}
                            .onFailure{show(a,"Kyora source rejected: "+it.message)}
                    }
                }
            }.show()
    }
    private fun exports(a:Activity,json:Boolean) {
        thread(name="asterion-kyora-export"){
            val outcome=runCatching{
                if(json)ExportManager(a.applicationContext).exportJson()
                else ExportManager(a.applicationContext).exportCsv()
            }
            a.runOnUiThread{
                if(a.isFinishing||a.isDestroyed)return@runOnUiThread
                outcome.onSuccess{show(a,"Gallery catalog exported to Downloads/"+
                    a.getString(R.string.gallery_download_folder)+"/exports")}
                    .onFailure{show(a,"Gallery export failed: "+it.message)}
            }
        }
    }
    fun render(a:Activity,p:LinearLayout,refresh:()->Unit) {
        val container=ui.card(a,14)
        container.addView(ui.text(a,"Kyora · Full library controls",19f,ui.white,true))
        val status=ui.text(a,"Gallery engine idle",12f,ui.secondary)
        container.addView(status,ui.lp(a,7))
        fun poll(){
            if(a.isFinishing||a.isDestroyed||!status.isAttachedToWindow)return
            val s=GalleryControl.snapshot()
            status.text=if(s.running) "Gallery: "+s.message else "Gallery: idle"
            status.postDelayed({poll()},1_200L)
        }
        status.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v:View){poll()}
            override fun onViewDetachedFromWindow(v:View){}
        })
        row(a,container,"SYNC NEW","BACKFILL SELECTED",
            {runSync(a,GalleryMode.LIVE)},{runSync(a,GalleryMode.BACKFILL)})
        row(a,container,"PAUSE / RESUME","STOP GALLERY",
            {
                val snap=GalleryControl.snapshot()
                if(snap.running)a.startService(Intent(a,GalleryService::class.java)
                    .setAction(if(snap.paused)GalleryService.ACTION_RESUME
                        else GalleryService.ACTION_PAUSE))
            },
            {
                if(GalleryControl.snapshot().running)
                    a.startService(Intent(a,GalleryService::class.java)
                        .setAction(GalleryService.ACTION_STOP))
            })
        val auto=Switch(a).apply{
            text="Automatic gallery checks"
            textSize=14f;setTextColor(ui.white)
            isChecked=Prefs.autoSync(a)
            setOnCheckedChangeListener{_,checked->
                Prefs.setAutoSync(a,checked)
                if(checked)GalleryScheduler.ensure(a)else GalleryScheduler.cancel(a)
            }
        }
        container.addView(auto,ui.lp(a,9))
        row(a,container,"ENABLE ALL","DISABLE ALL",
            {GalleryDb(a).use{it.setAllSelected(true);it.setAllLiveEnabled(true)};refresh()},
            {GalleryDb(a).use{it.setAllSelected(false);it.setAllLiveEnabled(false)};refresh()})
        row(a,container,"ADD WEBSITE","BLOCKED TAGS",
            {addSource(a,refresh)},{exclusion(a)})
        row(a,container,"IMPORT OLD KYORA JSON","EXPORT JSON",
            {
                @Suppress("DEPRECATION")
                a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
                    addCategory(Intent.CATEGORY_OPENABLE);type="application/json"
                },708)
            },
            {exports(a,true)})
        row(a,container,"EXPORT CSV","TERABOX / NDUS",
            {exports(a,false)},{NduSettingsDialog.show(a)})
        row(a,container,"UPLOAD PENDING","INCLUDE SAVED IMAGES",
            {NduSettingsDialog.start(a)},{NduSettingsDialog.start(a,true)})
        p.addView(container,ui.lp(a,14))

        val watched=GalleryDb(a).use{it.listEntities()}
        p.addView(ui.text(a,"Watched Kyora websites · "+watched.size,
            17f,ui.white,true),ui.lp(a,12))
        val prefs=a.getSharedPreferences("asterion_kyora_unified",0)
        val limit=prefs.getInt("visible",20).coerceIn(20,2000)
        for(entity in watched.take(limit)){
            val card=ui.card(a,11)
            card.addView(ui.text(a,entity.displayName,15f,ui.white,true))
            card.addView(ui.text(a,entity.canonicalUrl+" · "+entity.kind,
                11f,ui.secondary),ui.lp(a,3))
            if(!entity.lastError.isNullOrBlank())
                card.addView(ui.text(a,entity.lastError.take(180),
                    12f,ui.danger),ui.lp(a,7))
            for((label,checked,setter) in listOf(
                Triple("Include in backfill",entity.selected,{v:Boolean->
                    GalleryDb(a).use{it.setSelected(entity.source,entity.entityId,v)}
                }),
                Triple("Automatic new posts",entity.liveEnabled,{v:Boolean->
                    GalleryDb(a).use{it.setLiveEnabled(entity.source,entity.entityId,v)}
                })
            )){
                card.addView(Switch(a).apply{
                    text=label;textSize=13f;setTextColor(ui.white)
                    isChecked=checked
                    setOnCheckedChangeListener{_,v->setter(v)}
                },ui.lp(a,6))
            }
            row(a,card,"CHECK NEW","BACKFILL",
                {runSync(a,GalleryMode.LIVE,entity.entityId)},
                {runSync(a,GalleryMode.BACKFILL,entity.entityId)})
            row(a,card,"SITE TAG FILTER","REMOVE WEBSITE",
                {exclusion(a,entity.entityId)},
                {
                    AlertDialog.Builder(a).setTitle("Remove Kyora website?")
                        .setMessage("Only the subscription is archived. Existing gallery files are retained.")
                        .setNegativeButton("Cancel",null)
                        .setPositiveButton("Remove"){_,_->
                            GalleryDb(a).use{it.archiveEntity(entity.source,entity.entityId)}
                            refresh()
                        }.show()
                })
            // The legacy library's individual gallery-open action remains
            // available for every saved custom source, not just the main
            // screen's approved-site previews.
            val galleryKey="albums:"+entity.entityId
            val shownAlbums=prefs.getInt(galleryKey,3).coerceIn(3,100)
            val albums=GalleryDb(a).use{
                it.galleryCards(entity.entityId,shownAlbums+1)}
            for(album in albums.take(shownAlbums)){
                add(a,card,"OPEN · "+album.title.take(65)+
                    " ("+album.count+" images)"){
                    a.startActivity(Intent(a,
                        com.azrael.galleryflow.GalleryDetailActivity::class.java)
                        .putExtra(
                            com.azrael.galleryflow.GalleryDetailActivity.EXTRA_GALLERY_ID,
                            album.galleryId)
                        .putExtra(
                            com.azrael.galleryflow.GalleryDetailActivity.EXTRA_SOURCE,
                            album.source.wireName)
                        .putExtra(
                            com.azrael.galleryflow.GalleryDetailActivity.EXTRA_TITLE,
                            album.title))
                }
            }
            if(albums.size>shownAlbums)add(a,card,"SHOW MORE SAVED GALLERIES"){
                prefs.edit().putInt(galleryKey,
                    (shownAlbums+10).coerceAtMost(100)).apply()
                refresh()
            }
            p.addView(card,ui.lp(a,9))
        }
        if(watched.size>limit)add(a,p,
            "SHOW NEXT "+minOf(20,watched.size-limit)+" WEBSITES"){
            prefs.edit().putInt("visible",(limit+20).coerceAtMost(2000)).apply()
            refresh()
        }
    }
}
