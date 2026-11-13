package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SavedSearchHit(val query:String,val title:String,val url:String,
    val source:String,val score:Int,val queued:Boolean,val checkedAt:Long,val thumbnail:String?=null)

/** Persistent search evidence. Discovery results are not misrepresented as saved files. */
class SearchResultsDb(context:Context):
    SQLiteOpenHelper(context,"asterion_search_results.sqlite",null,2) {
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("""CREATE TABLE results(
            query TEXT NOT NULL, url_key TEXT NOT NULL,
            url TEXT NOT NULL, title TEXT NOT NULL,
            source TEXT NOT NULL, score INTEGER NOT NULL DEFAULT 0,
            queued INTEGER NOT NULL DEFAULT 0, checked_at INTEGER NOT NULL,
            thumbnail TEXT,
            PRIMARY KEY(query,url_key))""")
        db.execSQL("CREATE INDEX search_recent ON results(checked_at DESC)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){
        if(oldVersion<2)db.execSQL("ALTER TABLE results ADD COLUMN thumbnail TEXT")
    }
    fun save(query:String,hits:List<RankedHit>):Int {
        val db=writableDatabase
        var stored=0
        db.beginTransaction()
        try {
            // Persist every discovered match; truncation previously erased
            // later providers and made valid performers appear unrecognized.
            // SQLite transactions keep large result sets off the UI thread.
            for(item in hits){
                val q=query.trim()
                val key=SearchRanking.canonicalKey(item.url)
                val row=ContentValues().apply {
                    put("query",q);put("url_key",key);put("url",item.url)
                    put("title",item.title);put("source",item.source)
                    put("score",item.score);put("checked_at",System.currentTimeMillis())
                    if(item.thumbnail!=null)put("thumbnail",item.thumbnail)
                }
                val inserted=db.insertWithOnConflict("results",null,row,
                    SQLiteDatabase.CONFLICT_IGNORE)
                if(inserted!=-1L)stored++
                else db.update("results",row,"query=? AND url_key=?",arrayOf(q,key))
            }
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        return stored
    }
    fun wasQueued(query:String,url:String):Boolean =
        readableDatabase.rawQuery(
            "SELECT queued FROM results WHERE query=? AND url_key=?",
            arrayOf(query,SearchRanking.canonicalKey(url))).use { c ->
            c.moveToFirst() && c.getInt(0)==1
        }
    fun markQueued(query:String,url:String) {
        writableDatabase.update("results",ContentValues().apply{put("queued",1)},
            "query=? AND url_key=?",arrayOf(query,SearchRanking.canonicalKey(url)))
    }
    fun listForQuery(query:String,limit:Int=100):List<SavedSearchHit> {
        val records=ArrayList<SavedSearchHit>()
        readableDatabase.rawQuery("""SELECT query,title,url,source,score,queued,checked_at,thumbnail
            FROM results WHERE query=? ORDER BY score DESC, checked_at DESC LIMIT ?""",
            arrayOf(query.trim(),limit.coerceIn(1,5000).toString())).use{c->
            while(c.moveToNext())records.add(SavedSearchHit(
                c.getString(0),c.getString(1),c.getString(2),c.getString(3),
                c.getInt(4),c.getInt(5)==1,c.getLong(6),c.getString(7)))
        }
        return records
    }
    fun list(limit:Int=100):List<SavedSearchHit> {
        val records=ArrayList<SavedSearchHit>()
        readableDatabase.rawQuery("""SELECT query,title,url,source,score,queued,checked_at,thumbnail
            FROM results ORDER BY checked_at DESC,score DESC LIMIT ?""",
            arrayOf(limit.coerceIn(1,5000).toString())).use { c ->
            while(c.moveToNext())records.add(SavedSearchHit(
                c.getString(0),c.getString(1),c.getString(2),c.getString(3),
                c.getInt(4),c.getInt(5)==1,c.getLong(6),c.getString(7)))
        }
        return records
    }
}
