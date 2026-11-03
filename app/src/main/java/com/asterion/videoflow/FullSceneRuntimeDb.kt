package com.asterion.videoflow

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Small evidence index, not a media organizer. Only remembers the longest duration
 * observed on user-selected full-scene reference providers. No media or cookies stored.
 */
class FullSceneRuntimeDb(context:Context):
    SQLiteOpenHelper(context,"videoflow_full_runtime.sqlite",null,1) {
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE reference_runtime(
            scene_key TEXT PRIMARY KEY,
            seconds INTEGER NOT NULL,
            source TEXT NOT NULL,
            checked INTEGER NOT NULL)""")
    }
    override fun onUpgrade(db:SQLiteDatabase,old:Int,next:Int){}
    fun expected(title:String):Long? {
        val key=FullLengthVideoPolicy.sceneKey(title)
        if(key.length<12 || key.split(' ').size<3)return null
        return readableDatabase.rawQuery(
            "SELECT seconds FROM reference_runtime WHERE scene_key=?",
            arrayOf(key)).use{c->if(c.moveToFirst())c.getLong(0) else null}
    }
    fun remember(title:String,url:String,duration:Long){
        if(!FullLengthVideoPolicy.knownFullReferenceHost(url) ||
            duration<20L*60L)return
        val key=FullLengthVideoPolicy.sceneKey(title)
        if(key.length<12 || key.split(' ').size<3)return
        val existing=expected(title)?:0L
        if(duration<=existing)return
        val v=ContentValues().apply{
            put("scene_key",key);put("seconds",duration)
            put("source",url.take(500));put("checked",System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("reference_runtime",null,v,
            SQLiteDatabase.CONFLICT_REPLACE)
    }
}
