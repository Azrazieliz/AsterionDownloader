package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.InputStream

/** Read-only confirmed inventory bridge. Does not interpret filenames.
 * Rounder may export this JSON structure without exposing cloud credentials:
 * { "source":"asterion-rounder", "version":1, "items":[
 *   {"work":"Series title","volume":23,"format":"EPUB",
 *    "remote_id":"opaque provider ID","remote_path":"/Books/...",
 *    "confirmed":true} ] }
 * No unverified or ambiguous entry can suppress an actual download.
 */
object RounderInventoryIndex {
    private class Db(ctx:Context):
        SQLiteOpenHelper(ctx,"asterion_rounder_inventory.sqlite",null,1){
        override fun onCreate(db:SQLiteDatabase){
            db.execSQL("""CREATE TABLE inventory(
                work_key TEXT NOT NULL,volume INTEGER NOT NULL,
                format TEXT NOT NULL,remote_id TEXT NOT NULL,
                remote_path TEXT NOT NULL,last_seen INTEGER NOT NULL,
                PRIMARY KEY(work_key,volume,format,remote_id))""")
            db.execSQL("CREATE INDEX inv_work ON inventory(work_key,volume,format)")
        }
        override fun onUpgrade(db:SQLiteDatabase,old:Int,next:Int){}
    }
    fun isConfirmedPresent(ctx:Context,work:String,volume:Int,type:String):Boolean{
        if(volume<1 || type !in setOf("EPUB","ZIP"))return false
        return Db(ctx).use{db->
            db.readableDatabase.rawQuery(
                "SELECT 1 FROM inventory WHERE work_key=? AND volume=? AND format=? LIMIT 1",
                arrayOf(Mp4SeriesRules.normalize(work),volume.toString(),type)
            ).use{it.moveToFirst()}
        }
    }
    fun confirmedCount(ctx:Context):Int=Db(ctx).use{db->
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM inventory",null).use{
            if(it.moveToFirst())it.getInt(0) else 0
        }
    }
    fun importSnapshot(ctx:Context,stream:InputStream):Int{
        val bytes=stream.use{it.readBytes()}
        require(bytes.size in 2..16_000_000){"Invalid or oversized inventory"}
        val root=JSONObject(String(bytes,Charsets.UTF_8))
        require(root.optString("source")=="asterion-rounder" &&
            root.optInt("version")==1){"Expected Rounder inventory v1"}
        val entries=root.getJSONArray("items")
        require(entries.length()<=200_000)
        val clean=ArrayList<ContentValues>()
        for(i in 0 until entries.length()){
            val row=entries.getJSONObject(i)
            if(!row.optBoolean("confirmed"))continue
            val work=Mp4SeriesRules.normalize(row.optString("work"))
            val volume=row.optInt("volume",0)
            val kind=row.optString("format").uppercase()
            val remoteId=row.optString("remote_id").take(240)
            val path=row.optString("remote_path").take(1200)
            if(work.isBlank() || volume !in 1..999 ||
                kind !in setOf("EPUB","ZIP") ||
                remoteId.isBlank() || path.isBlank())continue
            clean+=ContentValues().apply{
                put("work_key",work);put("volume",volume)
                put("format",kind);put("remote_id",remoteId)
                put("remote_path",path);put("last_seen",System.currentTimeMillis())
            }
        }
        Db(ctx).use{store->
            val db=store.writableDatabase
            db.beginTransaction()
            try {
                db.delete("inventory",null,null)
                for(row in clean)db.insertOrThrow("inventory",null,row)
                db.setTransactionSuccessful()
            }finally{db.endTransaction()}
        }
        return clean.size
    }
}
