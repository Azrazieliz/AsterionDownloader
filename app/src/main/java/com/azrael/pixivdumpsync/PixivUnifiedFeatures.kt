package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

/**
 * All formerly legacy-only Pixiv commands in the Asterion home Pixiv panel.
 * This is a UI adapter to the SAME native Pixiv API, database and foreground
 * sync service; it does not create a second sync/download engine.
 */
internal object PixivUnifiedFeatures {
    private val ui=NocturneDesign
    private val importing=AtomicBoolean(false)
    private val feedLoading=AtomicBoolean(false)
    @Volatile private var feedPreview:List<PixivApi.ArtworkPreview> = emptyList()

    private fun notice(a:Activity,message:String){
        if(!a.isFinishing && !a.isDestroyed)
            Toast.makeText(a,message,Toast.LENGTH_LONG).show()
    }
    private fun action(a:Activity,p:LinearLayout,title:String,primary:Boolean=false,
                       callback:()->Unit){
        p.addView(ui.action(a,title,primary,callback),ui.lp(a,8))
    }
    private fun pair(a:Activity,p:LinearLayout,left:String,right:String,
                     first:()->Unit,second:()->Unit){
        val row=ui.row(a)
        row.addView(ui.action(a,left,false,first),LinearLayout.LayoutParams(0,-2,1f))
        row.addView(ui.action(a,right,false,second),
            LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=ui.px(a,8)})
        p.addView(row,ui.lp(a,7))
    }

    private fun service(a:Activity,mode:SyncMode,
                        artist:String?=null,artwork:String?=null,feedOnly:Boolean=false){
        if(!AppPower.enabled(a)){
            notice(a,"Resume Asterion in Settings before starting Pixiv downloads")
            return
        }
        if(mode!=SyncMode.CLOUD && !SessionStore.isLoggedIn(a)){
            notice(a,"Connect your Pixiv account first")
            return
        }
        val state=SyncControl.snapshot()
        if(state.running){
            notice(a,if(mode==SyncMode.LIVE)
                "Pixiv is busy; new artwork can be checked after this run"
                else "Pixiv is already running; finish or stop the current job first")
            return
        }
        val req=Intent(a,SyncForegroundService::class.java)
            .setAction(SyncForegroundService.ACTION_START)
            .putExtra(SyncForegroundService.EXTRA_MODE,mode.name)
            .putExtra(SyncForegroundService.EXTRA_SELECTED_ONLY,artist==null)
            .putExtra(SyncForegroundService.EXTRA_FEED_ONLY,feedOnly)
        if(artist!=null)req.putExtra(SyncForegroundService.EXTRA_TARGET_ARTIST_ID,artist)
        if(artwork!=null)req.putExtra(SyncForegroundService.EXTRA_TARGET_ARTWORK_ID,artwork)
        runCatching{a.startForegroundService(req)}
            .onSuccess{notice(a,"Pixiv "+mode.name.lowercase()+" request sent")}
            .onFailure{notice(a,"Pixiv could not start: "+it.message)}
    }

    fun controls(a:Activity,p:LinearLayout,refresh:()->Unit){
        val state=ui.text(a,"Pixiv engine status",13f,ui.secondary)
        p.addView(state,ui.lp(a,7))
        val runActions=ui.row(a)
        val pause=ui.action(a,"PAUSE PIXIV",false){
            val current=SyncControl.snapshot()
            if(current.running && !current.stopping)
                a.startService(Intent(a,SyncForegroundService::class.java)
                    .setAction(if(current.paused)SyncForegroundService.ACTION_RESUME
                               else SyncForegroundService.ACTION_PAUSE))
        }
        val stop=ui.action(a,"STOP PIXIV",false){
            if(SyncControl.snapshot().running)
                a.startService(Intent(a,SyncForegroundService::class.java)
                    .setAction(SyncForegroundService.ACTION_STOP))
        }
        runActions.addView(pause,LinearLayout.LayoutParams(0,-2,1f))
        runActions.addView(stop,LinearLayout.LayoutParams(0,-2,1f).apply{
            leftMargin=ui.px(a,8)
        })
        p.addView(runActions,ui.lp(a,7))
        // The status view is inserted before this listener is installed;
        // onViewAttached can already have fired. Arm once after layout and
        // invalidate pending timers when the user leaves the page.
        val statusGeneration=java.util.concurrent.atomic.AtomicLong()
        fun status(token:Long){
            if(token!=statusGeneration.get() || a.isDestroyed ||
                a.isFinishing || !state.isAttachedToWindow)return
            val current=SyncControl.snapshot()
            state.text=when {
                current.stopping->"Pixiv · stopping safely"
                current.paused->"Pixiv · paused · "+current.message
                current.running->"Pixiv · "+current.message
                else->"Pixiv · idle · "+SessionStore.lastSyncSummary(a)
            }
            pause.text=if(current.paused)"RESUME PIXIV" else "PAUSE PIXIV"
            pause.isEnabled=current.running && !current.stopping
            stop.isEnabled=current.running && !current.stopping
            state.postDelayed({status(token)},900L)
        }
        fun armStatus(){
            val token=statusGeneration.incrementAndGet()
            state.post{status(token)}
        }
        state.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(view:View){armStatus()}
            override fun onViewDetachedFromWindow(view:View){
                statusGeneration.incrementAndGet()
            }
        })
        state.post{
            if(statusGeneration.get()==0L && state.isAttachedToWindow)
                armStatus()
        }

        val feedSwitch=android.widget.Switch(a).apply{
            text="Archive new artwork from Pixiv Following"
            textSize=14f
            setTextColor(ui.white)
            isChecked=SessionStore.followingFeedEnabled(a)
            setOnCheckedChangeListener{_,on->
                SessionStore.setFollowingFeedEnabled(a,on)
                if(on && SessionStore.autoSync(a) && AppPower.enabled(a))Scheduler.ensure(a)
            }
        }
        p.addView(feedSwitch,ui.lp(a,7))
        action(a,p,if(importing.get())"IMPORTING FOLLOWED ARTISTS..." else
            "IMPORT ARTISTS I FOLLOW",false){
            importFollows(a,refresh)
        }
        pair(a,p,"EXCLUDED PIXIV TAGS","SYNC FOLLOWING NOW",
            {editExcludedTags(a)},
            {if(SessionStore.followingFeedEnabled(a))
                service(a,SyncMode.LIVE,feedOnly=true)
             else notice(a,"Enable the Pixiv Following feed first")})
        pair(a,p,"TERABOX SETTINGS","CLOUD UPLOAD",
            {TeraboxSettingsDialog.show(a)},
            {
                if(TeraboxSettings.enabled(a)&&TeraboxSettings.isConfigured(a)){
                    kotlin.concurrent.thread(name="asterion-pixiv-cloud-queue"){
                        runCatching{
                            CloudUploadQueue(a.applicationContext).use{queue->
                                FileStore.listArtworkFiles(a.applicationContext).forEach{
                                    queue.enqueue(it)
                                }
                            }
                        }.onSuccess{a.runOnUiThread{service(a,SyncMode.CLOUD)}}
                         .onFailure{a.runOnUiThread{notice(a,"Cloud queue: "+it.message)}}
                    }
                }else notice(a,"Configure and enable TeraBox first")
            })
        pair(a,p,"DIRECT TERABOX SETTINGS","UPLOAD NDUS QUEUE",
            {NduSettingsDialog.show(a)},
            {NduSettingsDialog.start(a)})
        action(a,p,"QUEUE EXISTING PIXIV / COSPLAY FOR NDUS",false){
            NduSettingsDialog.start(a,true)
        }
    }

    private fun editExcludedTags(a:Activity){
        val input=EditText(a).apply{
            setText(SessionStore.excludedTagsText(a))
            textSize=15f
            minLines=5
            setSingleLine(false)
            setTextColor(ui.white)
            setHintTextColor(ui.secondary)
            hint="One excluded tag per line"
        }
        AlertDialog.Builder(a).setTitle("Excluded Pixiv artwork tags")
            .setMessage("Original and translated tag matches are excluded. Existing files are not deleted.")
            .setView(input)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Save"){_,_->
                SessionStore.setExcludedTagsText(a,input.text.toString())
                notice(a,"Pixiv exclusions saved")
            }.show()
    }

    private fun importFollows(a:Activity,refresh:()->Unit){
        if(!SessionStore.isLoggedIn(a)){notice(a,"Connect Pixiv first");return}
        if(importing.get()){notice(a,"Follow import is already running");return}
        AlertDialog.Builder(a).setTitle("Import artists followed on Pixiv?")
            .setMessage("Add your actual followed accounts to the existing artist database. Existing artist records and downloaded artwork are retained.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Import"){_,_->
                if(!importing.compareAndSet(false,true))return@setPositiveButton
                kotlin.concurrent.thread(name="asterion-pixiv-follow-import"){
                    var imported=0
                    val result=runCatching{
                        val followed=PixivApi(a.applicationContext).followingUsers()
                        AppDb(a.applicationContext).use{db->
                            for(artist in followed){
                                db.addArtist(artist.userId,artist.name,
                                    avatarUrl=artist.imageUrl)
                                imported++
                            }
                        }
                    }
                    importing.set(false)
                    a.runOnUiThread{
                        if(a.isFinishing||a.isDestroyed)return@runOnUiThread
                        result.onSuccess{notice(a,"Imported $imported followed artists; existing data preserved")}
                            .onFailure{notice(a,"Pixiv follow import failed: "+it.message)}
                        refresh()
                    }
                }
            }.show()
    }

    fun artistExtras(a:Activity,p:LinearLayout,artist:ArtistRecord,refresh:()->Unit){
        val record=AppDb(a).use{it.artistProgress(artist.userId,artist.knownTotal)}
        val status=artist.archiveStatus.uppercase(java.util.Locale.ROOT)
        val hasPartial=record.partial>0 || status in setOf("PARTIAL","ERROR","FAILED")
        val neverStarted=status in setOf("NOT_STARTED","NEW","PENDING")
        // An unknown archive total must never render as "1 saved / 0
        // expected" and a first-time backfill is not a failed Retry.
        val expected=if(artist.knownTotal<=0)"total not discovered"
            else artist.knownTotal.toString()+" expected"
        val actionLabel=when{
            hasPartial->"RETRY FAILED"
            neverStarted->"BACKFILL"
            status=="RUNNING"->"SYNC RUNNING"
            else->"CHECK NEW"
        }
        p.addView(ui.text(a,
            "Archive: "+artist.archiveStatus+" · "+
            record.done+" saved · "+expected+" · "+
            record.pending+" pending · "+record.partial+" partial"+
            (if(artist.liveCursor.isNullOrBlank())" · Live baseline pending" else " · Live ready"),
            12f,ui.secondary),ui.lp(a,5))
        val row=ui.row(a)
        row.addView(ui.action(a,"OPEN ARTIST",false){
            a.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.pixiv.net/users/"+artist.userId)))
        },LinearLayout.LayoutParams(0,-2,1f))
        if(status=="RUNNING" && SyncControl.snapshot().running){
            row.addView(ui.text(a,"SYNC RUNNING",11f,ui.primary,true),
                LinearLayout.LayoutParams(0,-2,1f).apply{
                    leftMargin=ui.px(a,6)
                })
        }else{
            row.addView(ui.action(a,actionLabel,false){
                if(SyncControl.snapshot().running){
                    notice(a,"Pixiv sync is already active")
                }else{
                    if(hasPartial)AppDb(a).use{it.clearArtistError(artist.userId)}
                    val mode=if(hasPartial || neverStarted)SyncMode.BACKFILL
                        else SyncMode.LIVE
                    service(a,mode,artist=artist.userId)
                }
            },LinearLayout.LayoutParams(0,-2,1f).apply{
                leftMargin=ui.px(a,6)
            })
        }
        row.addView(ui.action(a,"REMOVE",false){
            AlertDialog.Builder(a).setTitle("Remove followed Pixiv artist?")
                .setMessage("Only the artist subscription is removed. Existing images, bookmarks and download history are retained.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Remove"){_,_->
                    AppDb(a).use{it.removeArtist(artist.userId)}
                    refresh()
                }.show()
        },LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=ui.px(a,6)})
        p.addView(row,ui.lp(a,6))
    }

    fun feed(a:Activity,p:LinearLayout,refresh:()->Unit){
        p.addView(ui.text(a,"Pixiv · Latest from Following",19f,ui.white,true),ui.lp(a,16))
        val opts=ui.row(a)
        opts.addView(ui.action(a,if(feedLoading.get())"LOADING..." else "REFRESH FOLLOWING",false){
            refreshFeed(a,refresh)
        },LinearLayout.LayoutParams(-1,-2))
        p.addView(opts,ui.lp(a,6))
        val previews=feedPreview.ifEmpty{
            ArtworkPreviewCodec.decode(SessionStore.followingPreviewJson(a))
        }
        if(previews.isEmpty()){
            p.addView(ui.text(a,if(feedLoading.get())"Loading feed..."
                else "No cached following feed. Refresh after connecting Pixiv.",
                12f,ui.secondary),ui.lp(a,5))
            return
        }
        val prefs=a.getSharedPreferences("asterion_pixiv_unified",0)
        val limit=prefs.getInt("feed_visible",10).coerceIn(10,40)
        for(preview in previews.take(limit)){
            val card=ui.card(a,10)
            val row=ui.row(a)
            val image=ImageView(a).apply{
                scaleType=ImageView.ScaleType.CENTER_CROP
                contentDescription="Pixiv artwork "+preview.id
            }
            row.addView(image,LinearLayout.LayoutParams(ui.px(a,68),ui.px(a,68)).apply{
                rightMargin=ui.px(a,9)
            })
            RemoteImageLoader.load(a,image,preview.thumbnailUrl,
                "https://www.pixiv.net/artworks/"+preview.id)
            val texts=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
            texts.addView(ui.text(a,preview.title.ifBlank{"Artwork "+preview.id},
                13f,ui.white,true))
            val saved=AppDb(a).use{it.isWorkDoneAndBookmarked(preview.id)}
            texts.addView(ui.text(a,
                "Artist "+preview.userId+" · #"+preview.id+" · "+
                (if(saved)"Downloaded" else "Not downloaded"),
                11f,ui.secondary),ui.lp(a,4))
            row.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
            card.addView(row)
            pair(a,card,"OPEN","DOWNLOAD",
                {a.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.pixiv.net/artworks/"+preview.id)))},
                {
                    if(saved)notice(a,"Already downloaded")
                    else service(a,SyncMode.DIRECT,
                        artist=preview.userId,artwork=preview.id)
                })
            p.addView(card,ui.lp(a,8))
        }
        if(previews.size>limit)action(a,p,"SHOW MORE FOLLOWING ARTWORK",false){
            prefs.edit().putInt("feed_visible",(limit+10).coerceAtMost(40)).apply()
            refresh()
        }
    }

    private fun refreshFeed(a:Activity,refresh:()->Unit){
        if(!SessionStore.isLoggedIn(a)){notice(a,"Connect Pixiv first");return}
        if(!feedLoading.compareAndSet(false,true))return
        kotlin.concurrent.thread(name="asterion-pixiv-feed-load"){
            val result=runCatching{
                val api=PixivApi(a.applicationContext)
                val seen=linkedMapOf<String,PixivApi.ArtworkPreview>()
                for(page in 1..3){
                    val feed=api.followingFeedPage(page)
                    for(preview in feed.items){
                        seen.putIfAbsent(preview.id,preview)
                        if(seen.size>=40)break
                    }
                    if(seen.size>=40||feed.isLastPage||feed.items.isEmpty())break
                }
                seen.values.take(40)
            }
            result.onSuccess{
                feedPreview=it
                SessionStore.setFollowingPreviewJson(a.applicationContext,
                    ArtworkPreviewCodec.encode(it.take(10)))
            }
            feedLoading.set(false)
            a.runOnUiThread{
                if(a.isFinishing||a.isDestroyed)return@runOnUiThread
                result.onFailure{notice(a,"Pixiv Following: "+it.message)}
                refresh()
            }
        }
    }
}
