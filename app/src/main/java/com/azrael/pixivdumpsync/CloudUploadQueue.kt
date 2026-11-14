package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Independent durable outbox. A cloud failure must not affect Pixiv bookmarks
 * or the physical local archive; retry is safe and nondestructive. */
class CloudUploadQueue(context: Context) :
    SQLiteOpenHelper(context, "kuroha_cloud_uploads.db", null, 2) {

    data class Pending(val filename: String, val attempts: Int,
                       val lastAttempt: Long, val state:String,
                       val remotePath:String?)
    data class Summary(val pending: Int, val uploaded: Int, val lastError: String?)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS uploads(
                filename TEXT PRIMARY KEY NOT NULL,
                state TEXT NOT NULL DEFAULT 'PENDING',
                remote_path TEXT,
                remote_size INTEGER,remote_fs_id INTEGER,
                remote_md5 TEXT,verified_at INTEGER,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_attempt INTEGER NOT NULL DEFAULT 0,
                last_error TEXT
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<2){
            db.execSQL("ALTER TABLE uploads ADD COLUMN remote_size INTEGER")
            db.execSQL("ALTER TABLE uploads ADD COLUMN remote_fs_id INTEGER")
            db.execSQL("ALTER TABLE uploads ADD COLUMN remote_md5 TEXT")
            db.execSQL("ALTER TABLE uploads ADD COLUMN verified_at INTEGER")
            db.execSQL("UPDATE uploads SET state='VERIFY_PENDING' WHERE state='UPLOADED'")
        }
    }

    @Synchronized fun enqueue(filename: String) {
        require(filename.matches(Regex("[0-9]+_p[0-9]+\\.(jpg|jpeg|png|gif|webp)"))) {
            "Not a Kuroha artwork page"
        }
        val values = ContentValues().apply {
            put("filename", filename)
            put("state", "PENDING")
        }
        writableDatabase.insertWithOnConflict(
            "uploads", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    @Synchronized fun nextBatch(limit: Int, nowMillis: Long): List<Pending> {
        // Retry failures only after 30 minutes. Fresh entries upload immediately.
        val entries = mutableListOf<Pending>()
        readableDatabase.query(
            "uploads", arrayOf("filename", "attempts", "last_attempt",
                "state", "remote_path"),
            "state IN ('PENDING','VERIFY_PENDING') AND (last_attempt = 0 OR last_attempt <= ?)",
            arrayOf((nowMillis - 30 * 60_000L).toString()),
            null, null, "last_attempt ASC, filename ASC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                entries += Pending(c.getString(0), c.getInt(1), c.getLong(2),
                    c.getString(3),c.getString(4))
            }
        }
        return entries
    }

    @Synchronized fun uploaded(filename: String, proof:NduRemoteReceipt) {
        require(proof.path.startsWith("/") && proof.fsId>0L && proof.size>0L)
        val values = ContentValues().apply {
            put("state", "VERIFIED")
            put("remote_path", proof.path)
            put("remote_size",proof.size)
            put("remote_fs_id",proof.fsId)
            put("remote_md5",proof.remoteMd5)
            put("verified_at",proof.verifiedAt)
            putNull("last_error")
            put("last_attempt", System.currentTimeMillis())
        }
        writableDatabase.update("uploads", values, "filename=?", arrayOf(filename))
    }

    @Synchronized fun failed(filename: String, message: String) {
        val values = ContentValues().apply {
            put("last_error", message.take(180))
            put("last_attempt", System.currentTimeMillis())
        }
        writableDatabase.execSQL(
            "UPDATE uploads SET attempts=attempts+1 WHERE filename=?", arrayOf(filename)
        )
        writableDatabase.update("uploads", values, "filename=?", arrayOf(filename))
    }

    fun summary(): Summary {
        var pending = 0
        var uploaded = 0
        readableDatabase.rawQuery(
            "SELECT state, COUNT(*) FROM uploads GROUP BY state", null
        ).use { c ->
            while (c.moveToNext()) {
                if (c.getString(0) == "VERIFIED") uploaded += c.getInt(1)
                else pending += c.getInt(1)
            }
        }
        val lastError = readableDatabase.query(
            "uploads", arrayOf("last_error"),
            "last_error IS NOT NULL", null, null, null,
            "last_attempt DESC", "1"
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        return Summary(pending, uploaded, lastError)
    }
}
