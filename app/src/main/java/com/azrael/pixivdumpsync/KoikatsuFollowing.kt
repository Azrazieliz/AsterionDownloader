package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.widget.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class KoikatsuFollow(val name:String,val enabled:Boolean,val live:Boolean,
    val lastCheck:Long,val error:String?,val count:Int)
class KoikatsuFollowDb(context:Context):
    SQLiteOpenHelper(context,"asterion_koikatsu_follows.sqlite",null,1){
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE follows(name TEXT PRIMARY KEY COLLATE NOCASE,
            enabled INTEGER NOT NULL DEFAULT 1,live INTEGER NOT NULL DEFAULT 1,
            last_check INTEGER NOT NULL DEFAULT 0,error TEXT,count INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE cards(page TEXT PRIMARY KEY,follow_name TEXT NOT NULL,
            name TEXT,author TEXT,source TEXT,download TEXT,preview TEXT,
            discovered INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX cards_follow ON cards(follow_name,discovered)")
    }
    override fun onUpgrade(db:SQLiteDatabase,a:Int,b:Int){}
    fun follow(name:String):Boolean{
        val value=name.trim().take(120)
        if(value.length<2)return false
        return writableDatabase.insertWithOnConflict("follows",null,
            ContentValues().apply{put("name",value)},
            SQLiteDatabase.CONFLICT_IGNORE)>0L
    }
    fun follows():List<KoikatsuFollow>{
        val out=ArrayList<KoikatsuFollow>()
        readableDatabase.rawQuery("SELECT name,enabled,live,last_check,error,count "+
            "FROM follows ORDER BY name COLLATE NOCASE",null).use{c->
            while(c.moveToNext())out+=KoikatsuFollow(c.getString(0),c.getInt(1)==1,
                c.getInt(2)==1,c.getLong(3),c.getString(4),c.getInt(5))
        }
        return out
    }
    fun toggle(name:String,column:String,enabled:Boolean){
        require(column in setOf("enabled","live"))
        writableDatabase.execSQL("UPDATE follows SET "+column+"=? WHERE name=?",
            arrayOf(if(enabled)1 else 0,name))
    }
    fun cards(name:String,limit:Int=7):List<KoikatsuCard>{
        val out=ArrayList<KoikatsuCard>()
        readableDatabase.rawQuery("SELECT name,author,download,source,page,preview "+
            "FROM cards WHERE follow_name=? ORDER BY discovered DESC LIMIT ?",
            arrayOf(name,limit.coerceIn(1,40).toString())).use{c->
            while(c.moveToNext())out+=KoikatsuCard(c.getString(0),c.getString(1),
                c.getString(2),c.getString(3),c.getString(4),c.getString(5))
        }
        return out
    }
    fun save(name:String,items:List<KoikatsuCard>,error:String?){
        val db=writableDatabase
        db.beginTransaction()
        try{
            for(item in items.take(150)){
                val cv=ContentValues().apply{
                    put("page",item.page);put("follow_name",name)
                    put("name",item.name);put("author",item.author)
                    put("source",item.source);put("download",item.download)
                    if(item.preview!=null)put("preview",item.preview)
                    put("discovered",System.currentTimeMillis())
                }
                db.insertWithOnConflict("cards",null,cv,SQLiteDatabase.CONFLICT_IGNORE)
            }
            val count=db.rawQuery("SELECT COUNT(*) FROM cards WHERE follow_name=?",
                arrayOf(name)).use{if(it.moveToFirst())it.getInt(0)else 0}
            val cv=ContentValues().apply{
                put("count",count);put("last_check",System.currentTimeMillis())
                if(error==null)putNull("error") else put("error",error.take(240))
            }
            db.update("follows",cv,"name=?",arrayOf(name))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
}
object KoikatsuFollowing {
    private val busy=AtomicBoolean(false)
    private val ui=NocturneDesign
    private const val JOB=49862
    fun ensure(context:Context){
        val jobs=context.getSystemService(JobScheduler::class.java)
        if(jobs.allPendingJobs.none{it.id==JOB})
            jobs.schedule(JobInfo.Builder(JOB,ComponentName(context,
                KoikatsuFollowJob::class.java))
                .setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6*60*60_000L).build())
    }
    fun scan(context:Context,only:String?=null,onComplete:(()->Unit)?=null){
        if(!AppPower.enabled(context)||!busy.compareAndSet(false,true))return
        thread(name="asterion-koikatsu-follow-sync"){
            try{
                KoikatsuFollowDb(context).use{db->
                    val names=if(only==null)db.follows().filter{it.enabled&&it.live}
                        .take(20).map{it.name} else listOf(only)
                    for(name in names){
                        if(!AppPower.enabled(context))break
                        runCatching{KoikatsuCards.search(name)}
                            .onSuccess{(cards,errors)->
                                db.save(name,cards,errors.take(3).joinToString(" | ")
                                    .takeIf{it.isNotBlank()})
                                for(card in cards)CreatorNames.remember(context,card.name,
                                    "KOIKATSU")
                            }.onFailure{db.save(name,emptyList(),it.message)}
                    }
                }
            }finally{busy.set(false);onComplete?.invoke()}
        }
    }
    fun render(a:Activity,parent:LinearLayout,refresh:()->Unit){
        ensure(a)
        val all=KoikatsuFollowDb(a).use{it.follows()}
        parent.addView(ui.text(a,"Koikatsu · Character cards",21f,ui.white,true),ui.lp(a,13))
        parent.addView(ui.text(a,all.size.toString()+
            " followed names · BepisDB / KoikatsuCards original PNGs",
            12f,ui.secondary),ui.lp(a,4))
        val editor=ui.card(a)
        val input=CreatorNames.input(a,"Character or creator name","KOIKATSU")
        editor.addView(input)
        editor.addView(ui.action(a,"FOLLOW & SEARCH CARDS",true){
            val value=input.text.toString().trim()
            if(value.length<2)return@action
            KoikatsuFollowDb(a).use{it.follow(value)}
            CreatorNames.remember(a,value,"KOIKATSU",true)
            scan(a,value){a.runOnUiThread{if(!a.isFinishing)refresh()}}
            refresh()
        },ui.lp(a,9))
        parent.addView(editor,ui.lp(a,10))
        for(follow in all.take(40)){
            val cards=KoikatsuFollowDb(a).use{it.cards(follow.name,6)}
            val c=ui.card(a)
            val header=ui.row(a)
            val portrait=ImageView(a).apply{
                scaleType=ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(ui.raised)
                setImageResource(R.drawable.ic_source_koikatsu)
            }
            header.addView(portrait,LinearLayout.LayoutParams(ui.px(a,62),ui.px(a,62)))
            cards.firstOrNull()?.let{item->
                val cover=item.preview?:item.download.takeIf{item.source=="BepisDB"}
                cover?.let{RemoteImageLoader.load(a,portrait,it,item.page)}
            }
            val details=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL
                setPadding(ui.px(a,10),0,0,0)}
            details.addView(ui.text(a,follow.name,17f,ui.white,true))
            val state=if(follow.lastCheck==0L)"Not scanned" else
                if(follow.error==null)"Latest search complete" else "Source error"
            details.addView(ui.text(a,follow.count.toString()+" cards · "+state,
                12f,ui.secondary),ui.lp(a,5))
            header.addView(details,LinearLayout.LayoutParams(0,-2,1f))
            c.addView(header)
            if(cards.isNotEmpty()){
                val scroll=HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false}
                val gallery=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
                for(item in cards.take(5)){
                    val image=ImageView(a).apply{
                        scaleType=ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(ui.raised)
                        setImageResource(R.drawable.ic_source_koikatsu)
                        contentDescription=item.name+" original PNG"
                        setOnClickListener{
                            thread(name="asterion-koi-download"){
                                val res=runCatching{
                                    KoikatsuCards.download(a,
                                        KoikatsuCards.resolve(item),item.name)
                                }
                                a.runOnUiThread{
                                    if(!a.isFinishing)Toast.makeText(a,
                                        res.fold({"Saved original PNG · "+item.name},
                                            {"PNG download failed: "+it.message}),
                                        Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    gallery.addView(image,LinearLayout.LayoutParams(ui.px(a,108),ui.px(a,108))
                        .apply{rightMargin=ui.px(a,7)})
                    (item.preview?:item.download.takeIf{item.source=="BepisDB"})?.let{
                        RemoteImageLoader.load(a,image,it,item.page)
                    }
                }
                scroll.addView(gallery);c.addView(scroll,ui.lp(a,11))
            }else c.addView(ui.text(a,"No indexed cards yet · CHECK NEW",
                12f,ui.secondary),ui.lp(a,10))
            if(!follow.error.isNullOrEmpty())
                c.addView(ui.text(a,follow.error.take(150),12f,ui.danger),ui.lp(a,6))
            c.addView(Switch(a).apply{
                text="Include in sync";textSize=13f
                setTextColor(ui.secondary);isChecked=follow.enabled
                setOnCheckedChangeListener{_,v->
                    KoikatsuFollowDb(a).use{it.toggle(follow.name,"enabled",v)}
                }
            },ui.lp(a,7))
            c.addView(Switch(a).apply{
                text="Sync new cards automatically";textSize=13f
                setTextColor(ui.secondary);isChecked=follow.live
                setOnCheckedChangeListener{_,v->
                    KoikatsuFollowDb(a).use{it.toggle(follow.name,"live",v)}
                }
            },ui.lp(a,6))
            c.addView(ui.weighted(a,ui.action(a,"CHECK NEW"){
                scan(a,follow.name){a.runOnUiThread{if(!a.isFinishing)refresh()}}
            },ui.action(a,"BACKFILL"){
                scan(a,follow.name){a.runOnUiThread{if(!a.isFinishing)refresh()}}
            }),ui.lp(a,9))
            parent.addView(c,ui.lp(a,10))
        }
    }
}
class KoikatsuFollowJob:JobService(){
    override fun onStartJob(params:JobParameters):Boolean{
        if(!AppPower.enabled(this))return false
        KoikatsuFollowing.scan(applicationContext){jobFinished(params,false)}
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean=true
}
