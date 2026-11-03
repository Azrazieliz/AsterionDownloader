package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.view.Gravity
import android.widget.*
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine

/** One compact timeline for existing Kuroha, Kyora/X, VideoFlow and direct-file queues.
 * Uses the existing data stores; no migration or re-enrollment is performed.
 */
object UnifiedDownloadsUi {
    private val ui=NocturneDesign
    private enum class State { ACTIVE,FAILED,DONE }
    private enum class Filter(val title:String){ALL("All"),ACTIVE("Active"),FAILED("Failed"),DONE("Saved")}
    private data class Row(val category:String,val title:String,val detail:String,
        val state:State,val percent:Int?,val color:Int,val order:Long,
        val action:()->Unit)
    private fun display(a:Activity,row:Row){
        AlertDialog.Builder(a).setTitle(row.title)
            .setMessage(row.category+" · "+row.detail)
            .setPositiveButton(when(row.state){
                State.FAILED->"RETRY";State.DONE->"OPEN";State.ACTIVE->"CLOSE"
            }){_,_->row.action()}
            .setNegativeButton("Close",null).show()
    }
    private fun open(a:Activity,url:String,mime:String){
        runCatching{
            a.startActivity(Intent(Intent.ACTION_VIEW).apply{
                setDataAndType(Uri.parse(url),mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure{
            Toast.makeText(a,"No compatible file viewer found",Toast.LENGTH_LONG).show()
        }
    }
    private fun get(a:Activity):List<Row>{
        val collected=ArrayList<Row>()
        val videos=VideoDb(a).use{it.listVideos(180)}
        for((i,item) in videos.withIndex()){
            val state=when(item.status) {
                "done"->State.DONE;"error"->State.FAILED;else->State.ACTIVE
            }
            collected+=Row("Video",item.title,
                item.status.replace('_',' ')+" · "+item.progress+"%"+
                    (item.error?.let{" · "+it.take(90)}?:""),
                state,item.progress,ui.primary,(videos.size-i).toLong(),{
                    when(state){
                        State.DONE->item.savedUri?.let{open(a,it,"video/*")}
                        State.FAILED->{
                            VideoDb(a).use{it.retry(item.id)}
                            VideoEngine.launch(a,false)
                        }
                        else->{}
                    }
                })
        }
        val comics=ChapterQueue(a).use{it.list().take(180)}
        for((i,item) in comics.withIndex()){
            val state=when(item.state){
                "done"->State.DONE;"error"->State.FAILED;else->State.ACTIVE
            }
            collected+=Row("Comic ZIP",item.title,
                item.state+" · "+item.progress+"%"+
                    (item.error?.let{" · "+it.take(90)}?:""),
                state,item.progress,ui.booksGold,(comics.size-i).toLong(),{
                    when(state){
                        State.DONE->item.savedUri?.let{open(a,it,"application/zip")}
                        State.FAILED->{
                            ChapterQueue(a).use{it.retry(item.id)}
                            ChapterArchives.start(a)
                        }
                        else->{}
                    }
                })
        }
        val photos=XPhotoQueue(a).use{it.recent(180)}
        for((i,item) in photos.withIndex()){
            val state=when(item.state){
                "done"->State.DONE;"error"->State.FAILED;else->State.ACTIVE
            }
            val title=item.photo.substringBefore('?').substringAfterLast('/').take(70)
                .ifBlank{"X image"}
            collected+=Row("X image",title,item.state+
                (item.error?.let{" · "+it.take(90)}?:""),
                state,if(state==State.DONE)100 else null,ui.xCyan,
                (photos.size-i).toLong(),{
                    when(state){
                        State.DONE->item.saved?.let{open(a,it,"image/*")}
                        State.FAILED->{
                            XPhotoQueue(a).use{it.retry(item.id)}
                            XPhotoEngine.start(a)
                        }
                        else->{}
                    }
                })
        }
        val direct=DirectDownloadTracker.states(a,180)
        for(item in direct){
            val state=when(item.status){
                DownloadManager.STATUS_SUCCESSFUL->State.DONE
                DownloadManager.STATUS_FAILED->State.FAILED
                else->State.ACTIVE
            }
            val p=if(item.total>0)(100L*item.bytes/item.total).toInt().coerceIn(0,100)
                else if(state==State.DONE)100 else null
            val detail=item.job.sourceHost+" · "+when(item.status){
                DownloadManager.STATUS_SUCCESSFUL->"Saved"
                DownloadManager.STATUS_FAILED->"Android download error "+item.reason
                DownloadManager.STATUS_PAUSED->"Paused"
                DownloadManager.STATUS_RUNNING->"Downloading"
                else->"Queued"
            }
            collected+=Row(item.job.folder,item.job.filename,detail,state,p,
                if(item.job.folder=="Books")ui.booksGold else ui.assetsLilac,
                item.job.created,{
                    when(state){
                        State.DONE->{
                            val manager=a.getSystemService(DownloadManager::class.java)
                            manager.getUriForDownloadedFile(item.job.id)?.let{
                                open(a,it.toString(),a.contentResolver.getType(it)?:"*/*")
                            }
                        }
                        State.FAILED->Toast.makeText(a,
                            "Select the source card to retry; signed links are not retained.",
                            Toast.LENGTH_LONG).show()
                        else->{}
                    }
                })
        }
        return collected.sortedWith(compareBy<Row>{
            when(it.state){State.ACTIVE->0;State.FAILED->1;State.DONE->2}
        }.thenByDescending{it.order})
    }
    fun render(a:Activity,parent:LinearLayout,refresh:()->Unit){
        val rows=get(a)
        val active=rows.count{it.state==State.ACTIVE}
        val failed=rows.count{it.state==State.FAILED}
        val completed=rows.count{it.state==State.DONE}
        parent.addView(ui.text(a,"Downloads",23f,ui.white,true),ui.lp(a,15))
        val summary=ui.card(a,12)
        summary.addView(ui.text(a,
            "$active active   ·   $failed failed   ·   $completed saved",
            13f,if(failed>0)ui.danger else ui.secondary),ui.lp(a,0))
        val actions=ui.weighted(a,ui.action(a,"SYNC & RESUME",false){
            VideoEngine.launch(a,false)
            ChapterArchives.start(a)
            XPhotoEngine.start(a)
            refresh()
        },ui.action(a,"TORRENTS",false){
            TorrentTransfers.showQueue(a)
        },8)
        summary.addView(actions,ui.lp(a,8))
        parent.addView(summary,ui.lp(a,9))
        RounderDispatch.render(a,parent)
        val pref=a.getSharedPreferences("asterion_downloads_ui",0)
        val filter=runCatching{
            Filter.valueOf(pref.getString("filter","ALL").orEmpty())
        }.getOrDefault(Filter.ALL)
        val filtered=rows.filter{filter==Filter.ALL || it.state.name==filter.name}
        val choices=ui.row(a)
        for(option in Filter.entries){
            choices.addView(ui.chip(a,option.title,option==filter){
                pref.edit().putString("filter",option.name).putInt("page",20).apply()
                refresh()
            },LinearLayout.LayoutParams(0,-2,1f).apply{
                rightMargin=ui.px(a,5)
            })
        }
        parent.addView(choices,ui.lp(a,12))
        val size=pref.getInt("page",20).coerceIn(10,140)
        for(item in filtered.take(size)){
            val row=ui.row(a).apply{
                background=ui.touch(a,ui.surface,14)
                setPadding(ui.px(a,11),ui.px(a,10),ui.px(a,11),ui.px(a,10))
                setOnClickListener{display(a,item)}
                isClickable=true;isFocusable=true
                contentDescription=item.category+" "+item.title+" "+item.detail
            }
            val stripe=android.view.View(a).apply{
                background=ui.shape(a,item.color,4,item.color)
            }
            row.addView(stripe,LinearLayout.LayoutParams(ui.px(a,4),ui.px(a,42))
                .apply{rightMargin=ui.px(a,10)})
            val main=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
            main.addView(ui.text(a,item.title.take(95),14f,ui.white,true).apply{
                maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
            })
            main.addView(ui.text(a,item.category+" · "+item.detail,11f,
                if(item.state==State.FAILED)ui.danger else ui.secondary).apply{
                maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
            },ui.lp(a,3))
            if(item.percent!=null && item.state==State.ACTIVE){
                val progress=ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal).apply{
                    max=100
                    this.progress=item.percent
                    progressTintList=ColorStateList.valueOf(item.color)
                }
                main.addView(progress,LinearLayout.LayoutParams(-1,ui.px(a,3))
                    .apply{topMargin=ui.px(a,6)})
            }
            row.addView(main,LinearLayout.LayoutParams(0,-2,1f))
            val indicator=when(item.state){
                State.ACTIVE->"…"
                State.FAILED->"!"
                State.DONE->"✓"
            }
            row.addView(ui.text(a,indicator,17f,item.color,true).apply{
                gravity=Gravity.CENTER
            },LinearLayout.LayoutParams(ui.px(a,32),-2))
            parent.addView(row,ui.lp(a,7))
        }
        if(filtered.size>size)parent.addView(ui.action(a,
            "SHOW MORE · "+(filtered.size-size),false){
            pref.edit().putInt("page",(size+20).coerceAtMost(140)).apply()
            refresh()
        },ui.lp(a,10))
        if(filtered.isEmpty())parent.addView(ui.text(a,
            if(rows.isEmpty())"No downloads yet. Downloads will appear here across all modules."
            else "No downloads in this filter.",12f,ui.secondary),ui.lp(a,12))
    }
}
