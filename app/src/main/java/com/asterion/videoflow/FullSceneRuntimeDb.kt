package com.asterion.videoflow

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Duration evidence, not media organization. Provider observations MUST NOT
 * become verified original runtimes just because a source is popular or selected.
 * No media or account sessions are stored here.
 */
class FullSceneRuntimeDb(context:Context):
    SQLiteOpenHelper(context,"videoflow_full_runtime.sqlite",null,2) {
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE reference_runtime(
            scene_key TEXT PRIMARY KEY,
            seconds INTEGER NOT NULL,
            source TEXT NOT NULL,
            checked INTEGER NOT NULL,
            verified INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE observed_runtime(
            scene_key TEXT NOT NULL,
            source TEXT NOT NULL,
            seconds INTEGER NOT NULL,
            checked INTEGER NOT NULL,
            PRIMARY KEY(scene_key,source))""")
    }
    override fun onUpgrade(db:SQLiteDatabase,old:Int,next:Int){
        if(old<2){
            // Older releases used provider reputation as their only evidence.
            // Preserve historical rows but invalidate their authority.
            db.execSQL("ALTER TABLE reference_runtime ADD COLUMN verified INTEGER NOT NULL DEFAULT 0")
            db.execSQL("""CREATE TABLE observed_runtime(
                scene_key TEXT NOT NULL,
                source TEXT NOT NULL,
                seconds INTEGER NOT NULL,
                checked INTEGER NOT NULL,
                PRIMARY KEY(scene_key,source))""")
        }
    }
    fun expected(title:String):Long? {
        val key=FullLengthVideoPolicy.sceneKey(title)
        if(key.length<12 || key.split(' ').size<3)return null
        return readableDatabase.rawQuery(
            "SELECT seconds FROM reference_runtime WHERE scene_key=? AND verified=1",
            arrayOf(key)).use{c->if(c.moveToFirst())c.getLong(0) else null}
    }
    fun remember(title:String,url:String,duration:Long){
        // Source metadata remains an unverified observation, even from a site
        // usually carrying full scenes. Never promote it to original reference.
        if(duration<=0L)return
        val key=FullLengthVideoPolicy.sceneKey(title)
        if(key.length<12 || key.split(' ').size<3)return
        val v=ContentValues().apply{
            put("scene_key",key);put("seconds",duration)
            put("source",url.take(500));put("checked",System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("observed_runtime",null,v,
            SQLiteDatabase.CONFLICT_REPLACE)
    }
}
