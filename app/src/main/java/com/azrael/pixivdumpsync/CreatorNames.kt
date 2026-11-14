package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView

/** Durable cross-module name vocabulary. Only observed or user-added names are suggested. */
class CreatorNameDb(context:Context):
    SQLiteOpenHelper(context,"asterion_creator_names.sqlite",null,1){
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE names(
            key TEXT PRIMARY KEY,label TEXT NOT NULL,category TEXT NOT NULL,
            chosen INTEGER NOT NULL DEFAULT 0,seen_at INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX names_category ON names(category,label)")
    }
    override fun onUpgrade(db:SQLiteDatabase,a:Int,b:Int){}
    fun remember(label:String,category:String="ALL",userChosen:Boolean=false){
        val name=label.trim().replace(Regex("\\s+")," ").take(120)
        if(name.length<2)return
        val normalized=name.lowercase()
        val key=category.uppercase()+"|"+normalized
        val v=ContentValues().apply{
            put("key",key);put("label",name);put("category",category.uppercase())
            put("seen_at",System.currentTimeMillis())
            if(userChosen)put("chosen",1)
        }
        writableDatabase.insertWithOnConflict("names",null,v,
            SQLiteDatabase.CONFLICT_IGNORE)
        if(userChosen)writableDatabase.execSQL(
            "UPDATE names SET chosen=1 WHERE key=?",arrayOf(key))
    }
    fun suggest(prefix:String,category:String?=null,limit:Int=50):List<String>{
        val needle=prefix.trim().lowercase()
        if(needle.isEmpty())return emptyList()
        val out=ArrayList<String>()
        val restrict=if(category.isNullOrBlank()||category=="ALL")"" else
            "AND (category=? OR category='ALL')"
        // Name recognition must not depend on where a model's name starts.
        // Keep prefix matches first, then substring matches across the
        // creator vocabulary, with a UI-sized page of results.
        val args=if(restrict.isEmpty())
            arrayOf("%"+needle+"%",needle+"%",limit.coerceIn(1,500).toString())
        else arrayOf("%"+needle+"%",category.orEmpty().uppercase(),
            needle+"%",limit.coerceIn(1,500).toString())
        readableDatabase.rawQuery(
            "SELECT label FROM names WHERE lower(label) LIKE ? $restrict "+
                "ORDER BY CASE WHEN lower(label) LIKE ? THEN 0 ELSE 1 END, "+
                "chosen DESC,seen_at DESC LIMIT ?",args).use{c->
            while(c.moveToNext())out+=c.getString(0)
        }
        return out.distinctBy{it.lowercase()}
    }
    fun chosen(category:String?=null,limit:Int=300):List<String>{
        val restrict=if(category==null)"" else "AND (category=? OR category='ALL')"
        val args=if(category==null)arrayOf(limit.toString())
            else arrayOf(category.uppercase(),limit.toString())
        val out=ArrayList<String>()
        readableDatabase.rawQuery("SELECT label FROM names WHERE chosen=1 $restrict "+
            "ORDER BY seen_at DESC LIMIT ?",args).use{c->
            while(c.moveToNext())out+=c.getString(0)
        }
        return out.distinctBy{it.lowercase()}
    }
}
object CreatorNames {
    fun remember(c:Context,label:String,category:String,userChosen:Boolean=false)=
        CreatorNameDb(c).use{it.remember(label,category,userChosen)}
    fun input(a:Activity,hint:String,category:String):AutoCompleteTextView{
        val ui=NocturneDesign
        val view=AutoCompleteTextView(a).apply{
            this.hint=hint
            threshold=1
            setSingleLine(true)
            textSize=15f
            setTextColor(ui.white);setHintTextColor(ui.secondary)
            background=ui.shape(a,ui.raised,12)
            setPadding(ui.px(a,12),ui.px(a,10),ui.px(a,12),ui.px(a,10))
        }
        val adapter=ArrayAdapter(a,android.R.layout.simple_dropdown_item_1line,
            emptyList<String>())
        view.setAdapter(adapter)
        view.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){}
            override fun afterTextChanged(s:Editable?){
                if(s.isNullOrBlank())return
                val results=CreatorNameDb(a).use{it.suggest(s.toString(),category)}
                adapter.clear()
                adapter.addAll(results)
                adapter.notifyDataSetChanged()
                if(results.isNotEmpty() && view.hasFocus())view.showDropDown()
            }
        })
        return view
    }
    /** Reuse known users from the native databases; no generated fake performer names. */
    fun seed(c:Context){
        CreatorNameDb(c).use{db->
            AppDb(c).use{it.listArtists().forEach{x->
                x.label?.takeIf{v->v.length>=2}?.let{v->db.remember(v,"PIXIV")}
            }}
            NameWatchDb(c).use{it.list().forEach{x->db.remember(x.name,"PERFORMERS",true)}}
            XPhotoQueue(c).use{it.allSources().forEach{x->
                db.remember("@"+x.handle,"X")
            }}
            GalleryDbBridge.readNames(c).forEach{db.remember(it,"COSPLAY")}
            KoikatsuFollowDb(c).use{it.follows().forEach{x->
                db.remember(x.name,"KOIKATSU",true)
            }}
        }
    }
}
/** Bridge keeps GalleryDb imports localized; never rewrites their database. */
private object GalleryDbBridge {
    fun readNames(c:Context):List<String> =
        com.azrael.galleryflow.GalleryDb(c).use{it.listEntities().map{e->e.displayName}}
}
