package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ArtistRecord(
    val userId: String,
    val label: String?,
    val avatarUrl: String?,
    val previewJson: String?,
    val selected: Boolean,
    val liveEnabled: Boolean,
    val liveCursor: String?,
    val archiveStatus: String,
    val knownTotal: Int,
    val lastSyncAt: Long?,
    val lastError: String?
)

data class ArtistProgress(
    val done: Int,
    val partial: Int,
    val skipped: Int,
    val knownTotal: Int
) {
    val pending: Int
        get() = (knownTotal - done - skipped).coerceAtLeast(0)
}

class AppDb(context: Context) : SQLiteOpenHelper(context, "pixivdump.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE artists(
                user_id TEXT PRIMARY KEY,
                label TEXT,
                enabled INTEGER NOT NULL DEFAULT 1,
                selected INTEGER NOT NULL DEFAULT 1,
                live_enabled INTEGER NOT NULL DEFAULT 1,
                live_cursor TEXT,
                archive_status TEXT NOT NULL DEFAULT 'NOT_STARTED',
                known_total INTEGER NOT NULL DEFAULT 0,
                last_sync_at INTEGER,
                last_error TEXT,
                avatar_url TEXT,
                preview_json TEXT
            )""".trimIndent())
        db.execSQL("""CREATE TABLE works(
                illust_id TEXT PRIMARY KEY,
                artist_id TEXT NOT NULL,
                title TEXT,
                state TEXT NOT NULL,
                page_count INTEGER NOT NULL DEFAULT 0,
                liked_marked INTEGER NOT NULL DEFAULT 0,
                bookmark_marked INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )""".trimIndent())
        db.execSQL("""CREATE TABLE pages(
                illust_id TEXT NOT NULL,
                page_index INTEGER NOT NULL,
                filename TEXT NOT NULL,
                saved INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(illust_id, page_index)
            )""".trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "ALTER TABLE works ADD COLUMN bookmark_marked INTEGER NOT NULL DEFAULT 0"
            )
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE artists ADD COLUMN selected INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE artists ADD COLUMN live_enabled INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE artists ADD COLUMN live_cursor TEXT")
            db.execSQL(
                "ALTER TABLE artists ADD COLUMN archive_status TEXT NOT NULL DEFAULT 'NOT_STARTED'"
            )
            db.execSQL("ALTER TABLE artists ADD COLUMN known_total INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE artists ADD COLUMN last_sync_at INTEGER")
            db.execSQL("ALTER TABLE artists ADD COLUMN last_error TEXT")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE artists ADD COLUMN avatar_url TEXT")
            db.execSQL("ALTER TABLE artists ADD COLUMN preview_json TEXT")
        }
    }

    fun addArtist(
        userId: String,
        label: String? = null,
        liveCursor: String? = null,
        avatarUrl: String? = null,
        previewJson: String? = null
    ) {
        val values = ContentValues().apply {
            put("user_id", userId)
            put("label", label)
            put("enabled", 1)
            put("selected", 1)
            put("live_enabled", 1)
            put("live_cursor", liveCursor)
            put("archive_status", "NOT_STARTED")
            put("known_total", 0)
            put("avatar_url", avatarUrl)
            put("preview_json", previewJson)
        }
        writableDatabase.insertWithOnConflict(
            "artists",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
        updateArtistIdentity(
            userId = userId,
            label = label,
            liveCursor = liveCursor,
            avatarUrl = avatarUrl,
            previewJson = previewJson
        )
    }

    fun updateArtistIdentity(
        userId: String,
        label: String?,
        liveCursor: String? = null,
        avatarUrl: String? = null,
        previewJson: String? = null
    ) {
        val values = ContentValues()
        if (!label.isNullOrBlank()) values.put("label", label)
        if (!liveCursor.isNullOrBlank()) values.put("live_cursor", liveCursor)
        if (!avatarUrl.isNullOrBlank()) values.put("avatar_url", avatarUrl)
        if (!previewJson.isNullOrBlank()) values.put("preview_json", previewJson)
        if (values.size() > 0) {
            writableDatabase.update("artists", values, "user_id=?", arrayOf(userId))
        }
    }

    fun updateArtistPresentation(
        userId: String,
        avatarUrl: String? = null,
        previewJson: String? = null
    ) {
        val values = ContentValues()
        if (!avatarUrl.isNullOrBlank()) values.put("avatar_url", avatarUrl)
        if (!previewJson.isNullOrBlank()) values.put("preview_json", previewJson)
        if (values.size() > 0) {
            writableDatabase.update("artists", values, "user_id=?", arrayOf(userId))
        }
    }

    fun removeArtist(userId: String) {
        writableDatabase.delete("artists", "user_id=?", arrayOf(userId))
    }

    fun setArtistSelected(userId: String, selected: Boolean) {
        writableDatabase.update(
            "artists",
            ContentValues().apply { put("selected", if (selected) 1 else 0) },
            "user_id=?",
            arrayOf(userId)
        )
    }

    fun setAllSelected(selected: Boolean) {
        writableDatabase.update(
            "artists",
            ContentValues().apply { put("selected", if (selected) 1 else 0) },
            "enabled=1",
            null
        )
    }

    fun setAllLiveEnabled(enabled:Boolean) {
        writableDatabase.update("artists",
            ContentValues().apply{put("live_enabled",if(enabled)1 else 0)},
            "enabled=1",null)
    }
    fun setArtistLiveEnabled(userId: String, enabled: Boolean) {
        writableDatabase.update(
            "artists",
            ContentValues().apply { put("live_enabled", if (enabled) 1 else 0) },
            "user_id=?",
            arrayOf(userId)
        )
    }

    fun setLiveCursor(userId: String, illustId: String?) {
        writableDatabase.update(
            "artists",
            ContentValues().apply {
                if (illustId == null) putNull("live_cursor") else put("live_cursor", illustId)
                put("last_sync_at", System.currentTimeMillis())
            },
            "user_id=?",
            arrayOf(userId)
        )
    }

    fun setArchiveMeta(
        userId: String,
        status: String,
        knownTotal: Int? = null,
        error: String? = null
    ) {
        val values = ContentValues().apply {
            put("archive_status", status)
            put("last_sync_at", System.currentTimeMillis())
            if (knownTotal != null) put("known_total", knownTotal)
            if (error == null) putNull("last_error") else put("last_error", error.take(400))
        }
        writableDatabase.update("artists", values, "user_id=?", arrayOf(userId))
    }

    fun setArtistError(userId: String, message: String?) {
        val values = ContentValues().apply {
            if (message == null) putNull("last_error") else put("last_error", message.take(400))
            put("last_sync_at", System.currentTimeMillis())
        }
        writableDatabase.update("artists", values, "user_id=?", arrayOf(userId))
    }

    fun markArtistChecked(userId: String) {
        writableDatabase.update(
            "artists",
            ContentValues().apply {
                put("last_sync_at", System.currentTimeMillis())
                putNull("last_error")
            },
            "user_id=?",
            arrayOf(userId)
        )
    }

    fun listArtists(): List<ArtistRecord> = listArtistsWhere("enabled=1")

    fun listSelectedArtists(): List<ArtistRecord> =
        listArtistsWhere("enabled=1 AND selected=1")

    fun listLiveArtists(): List<ArtistRecord> =
        listArtistsWhere("enabled=1 AND live_enabled=1")

    fun listSelectedLiveArtists(): List<ArtistRecord> =
        listArtistsWhere("enabled=1 AND selected=1 AND live_enabled=1")

    fun artist(userId: String): ArtistRecord? =
        listArtists().firstOrNull { it.userId == userId }

    fun clearArtistError(userId: String) {
        writableDatabase.update(
            "artists",
            ContentValues().apply { putNull("last_error") },
            "user_id=?",
            arrayOf(userId)
        )
    }

    fun clearTransientNetworkErrors() {
        writableDatabase.execSQL(
            """UPDATE artists SET last_error=NULL
               WHERE last_error LIKE '%Unable to resolve host%'
                  OR last_error LIKE '%Network is unreachable%'
                  OR last_error LIKE '%failed to connect%'"""
        )
    }

    private fun listArtistsWhere(selection: String): List<ArtistRecord> {
        val out = mutableListOf<ArtistRecord>()
        readableDatabase.query(
            "artists",
            arrayOf(
                "user_id", "label", "avatar_url", "preview_json",
                "selected", "live_enabled", "live_cursor",
                "archive_status", "known_total", "last_sync_at", "last_error"
            ),
            selection,
            null,
            null,
            null,
            "COALESCE(label, user_id) COLLATE NOCASE ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out += ArtistRecord(
                    userId = c.getString(0),
                    label = c.getString(1),
                    avatarUrl = c.getString(2),
                    previewJson = c.getString(3),
                    selected = c.getInt(4) == 1,
                    liveEnabled = c.getInt(5) == 1,
                    liveCursor = c.getString(6),
                    archiveStatus = c.getString(7) ?: "NOT_STARTED",
                    knownTotal = c.getInt(8),
                    lastSyncAt = if (c.isNull(9)) null else c.getLong(9),
                    lastError = c.getString(10)
                )
            }
        }
        return out
    }

    fun artistProgress(userId: String, knownTotal: Int): ArtistProgress {
        var done = 0
        var partial = 0
        var skipped = 0
        readableDatabase.rawQuery(
            """SELECT state, COUNT(*) FROM works
               WHERE artist_id=?
               GROUP BY state""".trimIndent(),
            arrayOf(userId)
        ).use { c ->
            while (c.moveToNext()) {
                when (c.getString(0)) {
                    "DONE" -> done += c.getInt(1)
                    "SKIPPED_UGOIRA", "SKIPPED_TAG" -> skipped += c.getInt(1)
                    else -> partial += c.getInt(1)
                }
            }
        }
        return ArtistProgress(done, partial, skipped, knownTotal)
    }

    fun hasWorkRecord(illustId: String): Boolean {
        readableDatabase.query(
            "works",
            arrayOf("illust_id"),
            "illust_id=?",
            arrayOf(illustId),
            null,
            null,
            null,
            "1"
        ).use { c ->
            return c.moveToFirst()
        }
    }

    fun isWorkDoneAndBookmarked(illustId: String): Boolean {
        readableDatabase.query(
            "works",
            arrayOf("state", "bookmark_marked"),
            "illust_id=?",
            arrayOf(illustId),
            null,
            null,
            null
        ).use { c ->
            return c.moveToFirst() &&
                c.getString(0) == "DONE" &&
                c.getInt(1) == 1
        }
    }

    fun workPageCount(illustId: String): Int? {
        readableDatabase.query(
            "works",
            arrayOf("page_count"),
            "illust_id=?",
            arrayOf(illustId),
            null,
            null,
            null
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else null
        }
    }

    fun savedPageFilenames(illustId: String): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.query(
            "pages",
            arrayOf("filename"),
            "illust_id=? AND saved=1",
            arrayOf(illustId),
            null,
            null,
            "page_index ASC"
        ).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    fun upsertWork(
        illustId: String,
        artistId: String,
        title: String?,
        state: String,
        pageCount: Int
    ) {
        val values = ContentValues().apply {
            put("illust_id", illustId)
            put("artist_id", artistId)
            put("title", title)
            put("state", state)
            put("page_count", pageCount)
            put("updated_at", System.currentTimeMillis())
        }

        val updated = writableDatabase.update(
            "works",
            values,
            "illust_id=?",
            arrayOf(illustId)
        )
        if (updated == 0) {
            writableDatabase.insertOrThrow("works", null, values)
        }
    }

    fun setWorkState(
        illustId: String,
        state: String,
        bookmarkedMarked: Boolean? = null
    ) {
        val values = ContentValues().apply {
            put("state", state)
            put("updated_at", System.currentTimeMillis())
            if (bookmarkedMarked != null) {
                put("bookmark_marked", if (bookmarkedMarked) 1 else 0)
            }
        }
        writableDatabase.update(
            "works",
            values,
            "illust_id=?",
            arrayOf(illustId)
        )
    }

    fun isPageSaved(illustId: String, pageIndex: Int): Boolean {
        readableDatabase.query(
            "pages",
            arrayOf("saved"),
            "illust_id=? AND page_index=?",
            arrayOf(illustId, pageIndex.toString()),
            null,
            null,
            null
        ).use { c ->
            return c.moveToFirst() && c.getInt(0) == 1
        }
    }

    fun markPageSaved(illustId: String, pageIndex: Int, filename: String) {
        val values = ContentValues().apply {
            put("illust_id", illustId)
            put("page_index", pageIndex)
            put("filename", filename)
            put("saved", 1)
        }
        writableDatabase.insertWithOnConflict(
            "pages",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }
}
