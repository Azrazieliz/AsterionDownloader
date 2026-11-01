package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import com.azrael.galleryflow.AdapterRegistry
import com.azrael.galleryflow.GalleryDb
import com.azrael.galleryflow.Prefs
import com.azrael.galleryflow.SyncMode as GallerySyncMode

/**
 * The original account/creator archival engines stay as the source of truth.
 * These are first-class panels in ONE launcher, not independent applications.
 */
object AsterionAccountPanels {
    private val ui=NocturneDesign
    private fun say(a:Activity,s:String)=Toast.makeText(a,s,Toast.LENGTH_LONG).show()
    private fun add(a:Activity,p:LinearLayout,v:View,margin:Int=10){
        p.addView(v,ui.lp(a,margin))
    }
    private fun title(a:Activity,p:LinearLayout,h:String,desc:String){
        add(a,p,ui.text(a,h,23f,ui.white,true),12)
        add(a,p,ui.text(a,desc,13f,ui.secondary),5)
    }
    private fun entry(a:Activity,hint:String)=EditText(a).apply {
        this.hint=hint
        textSize=16f
        setSingleLine(true)
        setTextColor(ui.white)
        setHintTextColor(ui.subdued)
        background=ui.shape(a,ui.raised,12)
        setPadding(ui.px(a,14),ui.px(a,12),ui.px(a,14),ui.px(a,12))
        minimumHeight=ui.px(a,52)
    }
    private fun card(a:Activity,p:LinearLayout):LinearLayout=ui.card(a,15).also{add(a,p,it,12)}
    private fun action(a:Activity,p:LinearLayout,label:String,primary:Boolean=false,onTap:()->Unit){
        add(a,p,ui.action(a,label,primary,onTap),9)
    }
    private fun switch(a:Activity,p:LinearLayout,label:String,value:Boolean,onChange:(Boolean)->Unit){
        add(a,p,Switch(a).apply{
            text=label
            textSize=14f
            setTextColor(ui.white)
            isChecked=value
            setOnCheckedChangeListener{_,v->onChange(v)}
        },8)
    }
    private fun pair(a:Activity,p:LinearLayout,left:String,right:String,
                     runLeft:()->Unit,runRight:()->Unit){
        val row=ui.row(a)
        row.addView(ui.action(a,left,false,runLeft),
            LinearLayout.LayoutParams(0,-2,1f))
        row.addView(ui.action(a,right,false,runRight),
            LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=ui.px(a,8)})
        add(a,p,row)
    }
    private fun pixivSync(a:Activity,mode:SyncMode,id:String?=null){
        val current=SyncControl.snapshot()
        if(current.running){
            say(a,"Pixiv is already syncing: "+current.message)
            return
        }
        val intent=Intent(a,SyncForegroundService::class.java).apply{
            action=SyncForegroundService.ACTION_START
            putExtra(SyncForegroundService.EXTRA_MODE,mode.name)
            putExtra(SyncForegroundService.EXTRA_SELECTED_ONLY,id==null)
            if(id!=null)putExtra(SyncForegroundService.EXTRA_TARGET_ARTIST_ID,id)
        }
        runCatching{a.startForegroundService(intent)}
            .onSuccess{say(a,if(id==null)"Pixiv "+mode.name.lowercase()+" started"
                          else "Started "+mode.name.lowercase()+" for artist "+id)}
            .onFailure{say(a,"Pixiv sync error: "+it.message)}
    }
    fun pixiv(a:Activity,p:LinearLayout,refresh:()->Unit){
        val artists=AppDb(a).use{it.listArtists()}
        val connected=SessionStore.isLoggedIn(a)
        title(a,p,"Pixiv · Artists & Sync",
            "Keep artist collections, following feed, archived works and automatic updates.")
        val account=card(a,p)
        add(a,account,ui.text(a,
            if(connected)"Pixiv account connected" else "Pixiv account not connected",
            17f,if(connected)ui.primary else ui.danger,true),0)
        add(a,account,ui.text(a,"${artists.size} artists · "+
            "${artists.count{it.selected}} selected · "+
            "Last sync: "+SessionStore.lastSyncSummary(a),12f,ui.secondary))
        pair(a,account,"PIXIV LOGIN","ACCOUNT & FOLLOWING",
            {a.startActivity(Intent(a,LoginActivity::class.java))},
            {a.startActivity(Intent(a,MainActivity::class.java))})
        switch(a,account,"Automatic Pixiv updates",SessionStore.autoSync(a)){
            SessionStore.setAutoSync(a,it)
            if(it)Scheduler.ensure(a) else Scheduler.cancel(a)
        }
        pair(a,account,"ENABLE ALL ARTISTS","DISABLE ALL ARTISTS",
            {AppDb(a).use{it.setAllSelected(true);it.setAllLiveEnabled(true)};refresh()},
            {AppDb(a).use{it.setAllSelected(false);it.setAllLiveEnabled(false)};refresh()})
        pair(a,account,"ENABLE ALL PIXIV","DISABLE ALL PIXIV",
            {AppDb(a).use{it.setAllSelected(true);it.setAllLiveEnabled(true)};refresh()},
            {AppDb(a).use{it.setAllSelected(false);it.setAllLiveEnabled(false)};refresh()})
        pair(a,account,"SYNC NEW ART","BACKFILL ARTISTS",
            {pixivSync(a,SyncMode.LIVE)},{pixivSync(a,SyncMode.BACKFILL)})
        action(a,account,"NEW ARTWORK FROM FOLLOWING",false){
            a.startActivity(Intent(a,FollowingActivity::class.java))
        }
        val create=card(a,p)
        add(a,create,ui.text(a,"Add artists",17f,ui.white,true),0)
        add(a,create,ui.text(a,
            "Enter Pixiv artist IDs or profile links (separate with spaces or new lines).",
            12f,ui.secondary))
        val input=entry(a,"Artist IDs / pixiv.net/users/…")
        add(a,create,input)
        action(a,create,"ADD TO SYNC",true){
            val ids=InputParser.parseUserIds(input.text.toString())
            if(ids.isEmpty())say(a,"Enter a valid Pixiv artist ID or profile")
            else{
                AppDb(a).use{db->ids.forEach{db.addArtist(it)}}
                say(a,"${ids.size} artists added")
                refresh()
            }
        }
        add(a,p,ui.text(a,"Tracked artists",19f,ui.white,true),20)
        if(artists.isEmpty())add(a,p,ui.text(a,
            "No artists in this installation yet. Connect Pixiv or import existing artists.",
            13f,ui.secondary))
        for(artist in artists.take(20)){
            val c=card(a,p)
            add(a,c,ui.text(a,artist.label?.takeIf{it.isNotBlank()}
                ?: "Artist ${artist.userId}",16f,ui.white,true),0)
            add(a,c,ui.text(a,
                "ID ${artist.userId} · ${artist.archiveStatus}"+
                (if(artist.knownTotal>0)" · ${artist.knownTotal} artworks" else ""),
                12f,ui.secondary),3)
            if(!artist.lastError.isNullOrBlank())
                add(a,c,ui.text(a,artist.lastError.take(130),12f,ui.danger),5)
            switch(a,c,"Include in sync",artist.selected){
                AppDb(a).use{db->db.setArtistSelected(artist.userId,it)}
            }
            switch(a,c,"Sync new works automatically",artist.liveEnabled){
                AppDb(a).use{db->db.setArtistLiveEnabled(artist.userId,it)}
            }
            pair(a,c,"CHECK NEW","BACKFILL",
                {pixivSync(a,SyncMode.LIVE,artist.userId)},
                {pixivSync(a,SyncMode.BACKFILL,artist.userId)})
        }
        if(artists.size>20)action(a,p,"SHOW ALL ${artists.size} ARTISTS",false){
            a.startActivity(Intent(a,MainActivity::class.java))
        }
    }
    private fun cosplaySync(a:Activity,mode:GallerySyncMode,id:String?=null){
        val service=Intent(a,com.azrael.galleryflow.SyncForegroundService::class.java).apply{
            action=com.azrael.galleryflow.SyncForegroundService.ACTION_START
            putExtra(com.azrael.galleryflow.SyncForegroundService.EXTRA_MODE,mode.name)
            if(id!=null)putExtra(
                com.azrael.galleryflow.SyncForegroundService.EXTRA_SITE_ID,id)
        }
        runCatching{a.startForegroundService(service)}
            .onSuccess{say(a,"Cosplay "+mode.name.lowercase()+" started")}
            .onFailure{say(a,"Cosplay sync error: "+it.message)}
    }
    fun cosplay(a:Activity,p:LinearLayout,refresh:()->Unit){
        val entities=GalleryDb(a).use{it.listEntities()}
        title(a,p,"Cosplay · Creators & Galleries",
            "Saved creators, websites and galleries with new-post sync and full backfill.")
        CreatorPreviews.kyora(a,p)
        val controls=card(a,p)
        add(a,controls,ui.text(a,"${entities.size} connected sources",
            17f,ui.white,true),0)
        switch(a,controls,"Check new cosplay posts automatically",Prefs.autoSync(a)){
            Prefs.setAutoSync(a,it)
            if(it)com.azrael.galleryflow.Scheduler.ensure(a)
            else com.azrael.galleryflow.Scheduler.cancel(a)
        }
        pair(a,controls,"ENABLE ALL SITES","DISABLE ALL SITES",
            {GalleryDb(a).use{it.setAllSelected(true);it.setAllLiveEnabled(true)};refresh()},
            {GalleryDb(a).use{it.setAllSelected(false);it.setAllLiveEnabled(false)};refresh()})
        pair(a,controls,"ENABLE ALL KYORA","DISABLE ALL KYORA",
            {GalleryDb(a).use{it.setAllSelected(true);it.setAllLiveEnabled(true)};refresh()},
            {GalleryDb(a).use{it.setAllSelected(false);it.setAllLiveEnabled(false)};refresh()})
        pair(a,controls,"SYNC NEW","BACKFILL ALL",
            {cosplaySync(a,GallerySyncMode.LIVE)},
            {cosplaySync(a,GallerySyncMode.BACKFILL)})
        action(a,controls,"DISCOVER & DOWNLOAD COSPLAY VIDEOS",true){
            val watched=CreatorPreviews.syncKyoraVideos(a)
            say(a,"Monitoring "+watched+" new creator video pages; check Videos > Downloads for transfer progress")
        }
        action(a,controls,"FULL GALLERY MANAGER & TAG FILTERS",false){
            a.startActivity(Intent(a,com.azrael.galleryflow.MainActivity::class.java))
        }
        val creator=card(a,p)
        add(a,creator,ui.text(a,"Follow a cosplay creator or website",
            17f,ui.white,true),0)
        val input=entry(a,"Creator / gallery URL")
        add(a,creator,input)
        val connect=ui.action(a,"SIGN IN TO SITE"){
            val url=input.text.toString().trim()
            if(!com.azrael.galleryflow.SiteSession.validLoginUrl(url)){
                say(a,"Enter a secure HTTPS website URL above")
            }else if(!com.azrael.galleryflow.SiteSession.bound(a,url)){
                a.startActivity(Intent(a,com.azrael.galleryflow.SiteLoginActivity::class.java)
                    .putExtra(com.azrael.galleryflow.SiteLoginActivity.EXTRA_URL,url))
            }
        }
        add(a,creator,connect)
        input.addTextChangedListener(object:android.text.TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){
                connect.visibility=if(com.azrael.galleryflow.SiteSession.bound(a,s.toString().trim()))
                    View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s:android.text.Editable?){}
        })
        action(a,creator,"FOLLOW & SYNC",true){
            val url=input.text.toString().trim()
            if(!url.startsWith("https://")&&!url.startsWith("http://")){
                say(a,"Enter a creator or gallery website URL")
                return@action
            }
            Thread{
                    val result=runCatching{
                        val entity=AdapterRegistry.forUrl(url).resolveEntity(url)
                        GalleryDb(a).use{it.addEntity(entity)}
                        entity.stableId
                    }
                    a.runOnUiThread{
                        result.onSuccess{
                            say(a,"Source followed")
                            cosplaySync(a,GallerySyncMode.BACKFILL,it)
                            refresh()
                        }.onFailure{say(a,"Cannot follow: "+it.message)}
                    }
                }.start()
        }
        val specific=card(a,p)
        add(a,specific,ui.text(a,"DOWNLOAD A SPECIFIC KYORA LIST / GALLERY",17f,ui.white,true),0)
        add(a,specific,ui.text(a,"Provide a public or signed-in collection URL. Only that collection is enrolled for backfill; original images, tag exclusions and progress remain handled by Kyora.",12f,ui.secondary))
        val listUrl=entry(a,"Collection / creator album URL")
        add(a,specific,listUrl)
        action(a,specific,"SAVE LIST AND BACKFILL",true){
            val link=listUrl.text.toString().trim()
            if(!com.azrael.galleryflow.SiteSession.validLoginUrl(link)){
                say(a,"Enter an HTTPS collection URL")
            }else{
                Thread{
                    val result=runCatching{
                        val entity=AdapterRegistry.forUrl(link).resolveEntity(link)
                        GalleryDb(a).use{it.addEntity(entity)}
                        entity.stableId
                    }
                    a.runOnUiThread{
                        result.onSuccess{
                            cosplaySync(a,GallerySyncMode.BACKFILL,it)
                            say(a,"Kyora collection enrolled for backfill")
                            refresh()
                        }.onFailure{say(a,"List cannot be indexed: "+it.message)}
                    }
                }.start()
            }
        }
        add(a,p,ui.text(a,"Followed creators & websites",19f,ui.white,true),20)
        if(entities.isEmpty())add(a,p,
            ui.text(a,"No cosplay sources linked in this installation.",13f,ui.secondary))
        for(entity in entities.take(25)){
            val c=card(a,p)
            add(a,c,ui.text(a,entity.displayName,16f,ui.white,true),0)
            add(a,c,ui.text(a,entity.canonicalUrl.take(110),12f,ui.secondary),4)
            if(com.azrael.galleryflow.SiteSession.cookie(entity.canonicalUrl)!=null)
                add(a,c,ui.text(a,"Website session cookies available",12f,ui.primary),4)
            val bound=com.azrael.galleryflow.SiteSession.bound(a,entity.canonicalUrl)
            if(bound){
                add(a,c,ui.text(a,"Account bound",13f,ui.primary,true),4)
                action(a,c,"MANAGE CONNECTION",false){
                    android.app.AlertDialog.Builder(a).setTitle(entity.displayName)
                        .setItems(arrayOf("Refresh session","Disconnect account")){_,choice->
                            if(choice==0){
                                a.startActivity(Intent(a,com.azrael.galleryflow.SiteLoginActivity::class.java)
                                    .putExtra(com.azrael.galleryflow.SiteLoginActivity.EXTRA_URL,entity.canonicalUrl))
                            }else{
                                com.azrael.galleryflow.SiteSession.disconnect(a,entity.canonicalUrl)
                                refresh()
                            }
                        }.show()
                }
            }else action(a,c,"SIGN IN TO ACCOUNT",false){
                val address=entity.canonicalUrl
                if(com.azrael.galleryflow.SiteSession.validLoginUrl(address))
                    a.startActivity(Intent(a,com.azrael.galleryflow.SiteLoginActivity::class.java)
                        .putExtra(com.azrael.galleryflow.SiteLoginActivity.EXTRA_URL,address))
                else say(a,"Account connection requires HTTPS")
            }
            if(!entity.lastError.isNullOrBlank())
                add(a,c,ui.text(a,entity.lastError.take(150),12f,ui.danger),5)
            switch(a,c,"Include in sync",entity.selected){
                GalleryDb(a).use{db->db.setSelected(entity.source,entity.entityId,it)}
            }
            switch(a,c,"Auto-sync creator",entity.liveEnabled){
                GalleryDb(a).use{db->db.setLiveEnabled(entity.source,entity.entityId,it)}
            }
            pair(a,c,"CHECK NEW","BACKFILL",
                {cosplaySync(a,GallerySyncMode.LIVE,entity.entityId)},
                {cosplaySync(a,GallerySyncMode.BACKFILL,entity.entityId)})
        }
        if(entities.size>25) action(a,p,"SHOW ALL ${entities.size} SOURCES",false){
            a.startActivity(Intent(a,com.azrael.galleryflow.MainActivity::class.java))
        }
    }
    fun x(a:Activity,p:LinearLayout,refresh:()->Unit){
        val accounts=XPhotoQueue(a).use{it.allSources()}
        val count=XPhotoQueue(a).use{it.counts()}
        title(a,p,"X · Artists & Photo Archive",
            "Follow X accounts, save all posted images and keep checking for new photos.")
        CreatorPreviews.x(a,p)
        val summary=card(a,p)
        add(a,summary,ui.text(a,"${accounts.size} X accounts · "+
            "${count["done"]?:0} images saved",17f,ui.white,true),0)
        add(a,summary,ui.text(a,
            "Only images are downloaded from X. Videos are not mixed into this archive.",
            12f,ui.secondary))
        val accountUrl="https://x.com/"
        val xBound=com.azrael.galleryflow.SiteSession.bound(a,accountUrl)
        add(a,summary,ui.text(a,if(xBound)"X account bound" else "X account not connected",
            14f,if(xBound)ui.primary else ui.secondary,true))
        if(!xBound){
            action(a,summary,"SIGN IN TO X",true){
                a.startActivity(Intent(a,com.azrael.galleryflow.SiteLoginActivity::class.java)
                    .putExtra(com.azrael.galleryflow.SiteLoginActivity.EXTRA_URL,accountUrl))
            }
        }else{
            action(a,summary,"MANAGE X ACCOUNT",false){
                android.app.AlertDialog.Builder(a).setTitle("X account connection")
                    .setItems(arrayOf("Refresh connection","Disconnect X account")){_,choice->
                        if(choice==0){
                            a.startActivity(Intent(a,com.azrael.galleryflow.SiteLoginActivity::class.java)
                                .putExtra(com.azrael.galleryflow.SiteLoginActivity.EXTRA_URL,accountUrl))
                        }else{
                            com.azrael.galleryflow.SiteSession.disconnect(a,accountUrl)
                            refresh()
                        }
                    }.show()
            }
        }
        add(a,summary,ui.text(a,
            "If X metadata requests fail, open an artist's media tab and capture visible photos.",
            12f,ui.secondary))
        pair(a,summary,"ENABLE ALL X","DISABLE ALL X",
            {XPhotoQueue(a).use{it.setAllEnabled(true)};refresh()},
            {XPhotoQueue(a).use{it.setAllEnabled(false)};refresh()})
        action(a,summary,"SYNC ALL X ACCOUNTS",true){
            XPhotoEngine.start(a);say(a,"Checking X photo accounts")
        }
        action(a,summary,"VIEW X FOLLOWING FEED & CAPTURE PHOTOS",true){
            a.startActivity(Intent(a,XMediaCaptureActivity::class.java)
                .putExtra(XMediaCaptureActivity.EXTRA_FEED,true))
        }
        action(a,summary,"IMPORT ACCOUNTS I FOLLOW",false){
            a.startActivity(Intent(a,XFollowingImportActivity::class.java))
        }
        add(a,summary,ui.text(a,
            "Automatic checks every 15 minutes when Android allows background jobs.",
            12f,ui.secondary))
        val new=card(a,p)
        add(a,new,ui.text(a,"Follow an X artist",17f,ui.white,true),0)
        val input=entry(a,"@username or x.com/username")
        add(a,new,input)
        action(a,new,"FOLLOW ACCOUNT & BACKFILL",true){
            val raw=input.text.toString().trim()
            val address=if(raw.startsWith("@"))"https://x.com/"+raw.removePrefix("@")
                else if(raw.matches(Regex("[A-Za-z0-9_]{1,15}")))
                    "https://x.com/$raw" else raw
            val ok=XPhotoQueue(a).use{it.addSource(address)}
            if(ok){
                XPhotoScheduler.ensure(a);XPhotoEngine.start(a)
                say(a,"X account linked and backfill queued");refresh()
            }else say(a,"Invalid or already followed X account")
        }
        val xLists=card(a,p)
        add(a,xLists,ui.text(a,"DOWNLOAD A SPECIFIC X LIST",17f,ui.white,true),0)
        add(a,xLists,ui.text(a,"Enter an X list URL. The signed-in browser captures its photo posts and queues originals; downloaded images remain outside the app.",12f,ui.secondary))
        val listInput=entry(a,"https://x.com/i/lists/123456789...")
        add(a,xLists,listInput)
        action(a,xLists,"SAVE LIST & OPEN IMAGE DOWNLOAD",true){
            val url=XMediaCaptureActivity.normalizeList(listInput.text.toString())
            if(url==null)say(a,"Enter a valid X list URL")
            else{
                val prefs=a.getSharedPreferences("asterion_x_lists",Activity.MODE_PRIVATE)
                val all=prefs.getStringSet("urls",emptySet()).orEmpty().toMutableSet()
                all.add(url);prefs.edit().putStringSet("urls",all).apply()
                a.startActivity(Intent(a,XMediaCaptureActivity::class.java)
                    .putExtra(XMediaCaptureActivity.EXTRA_LIST_URL,url))
            }
        }
        val savedLists=a.getSharedPreferences("asterion_x_lists",Activity.MODE_PRIVATE)
            .getStringSet("urls",emptySet()).orEmpty().sorted()
        for(listUrl in savedLists){
            action(a,xLists,"OPEN LIST "+listUrl.substringAfterLast('/'),false){
                a.startActivity(Intent(a,XMediaCaptureActivity::class.java)
                    .putExtra(XMediaCaptureActivity.EXTRA_LIST_URL,listUrl))
            }
        }
        add(a,p,ui.text(a,"Followed X accounts",19f,ui.white,true),20)
        if(accounts.isEmpty())add(a,p,ui.text(a,
            "Follow an account above to build a persistent artist archive.",
            13f,ui.secondary))
        for(source in accounts.take(25)){
            val c=card(a,p)
            add(a,c,ui.text(a,"@"+source.handle,16f,ui.white,true),0)
            add(a,c,ui.text(a,if(source.cursor.isNullOrBlank())
                "No saved pagination cursor" else "Backfill cursor saved",12f,ui.secondary))
            if(!source.error.isNullOrBlank())add(a,c,
                ui.text(a,source.error.take(150),12f,ui.danger))
            switch(a,c,"Automatic photo sync",source.enabled){
                XPhotoQueue(a).use{db->db.setEnabled(source.url,it)}
                XPhotoScheduler.ensure(a)
                refresh()
            }
            pair(a,c,"SYNC PHOTOS","BACKFILL ALL",
                {
                    XPhotoQueue(a).use{db->db.setEnabled(source.url,true)}
                    XPhotoEngine.start(a)
                    say(a,"Checking @"+source.handle)
                },
                {
                    XPhotoQueue(a).use{db->db.backfill(source.url)}
                    XPhotoEngine.start(a)
                    say(a,"Restarted image backfill for @"+source.handle)
                    refresh()
                })
            action(a,c,"OPEN X MEDIA & CAPTURE PHOTOS",false) {
                a.startActivity(Intent(a,XMediaCaptureActivity::class.java)
                    .putExtra(XMediaCaptureActivity.EXTRA_HANDLE,source.handle))
            }
            pair(a,c,"RETRY ERRORS","REMOVE ARTIST",
                {
                    XPhotoQueue(a).use{ db->
                        db.clearError(source.url)
                        db.setEnabled(source.url,true)
                        db.retryFailed(source.url)
                    }
                    XPhotoEngine.start(a)
                    refresh()
                },
                {
                    android.app.AlertDialog.Builder(a)
                        .setTitle("Unfollow @"+source.handle+" in the downloader?")
                        .setMessage("Stop monitoring this artist. Already saved images stay intact.")
                        .setNegativeButton("Cancel",null)
                        .setPositiveButton("Remove"){_,_->
                            XPhotoQueue(a).use{it.removeSource(source.url)}
                            refresh()
                        }.show()
                })
        }
    }
}
