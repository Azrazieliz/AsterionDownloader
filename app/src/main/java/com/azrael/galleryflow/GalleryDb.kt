package com.azrael.galleryflow

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

class GalleryDb(context: Context) : SQLiteOpenHelper(context, "galleryflow.db", null, 5) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE entities(
            source TEXT NOT NULL,
            entity_id TEXT NOT NULL,
            display_name TEXT NOT NULL,
            canonical_url TEXT NOT NULL,
            kind TEXT NOT NULL,
            selected INTEGER NOT NULL DEFAULT 1,
            live_enabled INTEGER NOT NULL DEFAULT 1,
            archived INTEGER NOT NULL DEFAULT 0,
            live_cursor TEXT,
            backfill_page INTEGER NOT NULL DEFAULT 0,
            last_sync_at INTEGER,
            last_error TEXT,
            PRIMARY KEY(source, entity_id)
        )""")
        db.execSQL("""CREATE TABLE galleries(
            source TEXT NOT NULL,
            gallery_id TEXT NOT NULL,
            entity_id TEXT NOT NULL,
            canonical_url TEXT NOT NULL,
            title TEXT NOT NULL,
            tags_json TEXT,
            published_at TEXT,
            retrieved_at INTEGER,
            media_count INTEGER NOT NULL DEFAULT 0,
            state TEXT NOT NULL DEFAULT 'unseen',
            last_error TEXT,
            PRIMARY KEY(source, gallery_id)
        )""")
        db.execSQL("""CREATE TABLE media(
            source TEXT NOT NULL,
            media_id TEXT NOT NULL,
            gallery_id TEXT NOT NULL,
            entity_id TEXT NOT NULL,
            media_index INTEGER NOT NULL,
            url TEXT NOT NULL,
            normalized_url TEXT,
            referer TEXT,
            content_uri TEXT,
            filename TEXT,
            mime_type TEXT,
            bytes INTEGER,
            sha256 TEXT,
            ahash64 TEXT,
            duplicate_of TEXT,
            state TEXT NOT NULL DEFAULT 'unseen',
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(source, media_id)
        )""")
        db.execSQL("""CREATE TABLE identity_aliases(
            canonical_identity TEXT NOT NULL,
            source TEXT NOT NULL,
            source_entity_id TEXT NOT NULL,
            confidence TEXT NOT NULL DEFAULT 'explicit',
            PRIMARY KEY(source, source_entity_id)
        )""")
        db.execSQL("""CREATE TABLE events(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            event_type TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX idx_media_hash ON media(sha256)")
        db.execSQL("CREATE INDEX idx_media_gallery ON media(source, gallery_id)")
        createRetryTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE entities ADD COLUMN backfill_page INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) createRetryTable(db)
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE entities ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            // Retired bundled providers are no longer visible or scheduled.
            // Their historical catalogs, images, and metadata are preserved.
            db.execSQL("""
                UPDATE entities SET archived=1, selected=0, live_enabled=0
                WHERE source NOT IN ('kiutaku','generic')
            """.trimIndent())
        }
        if (oldVersion < 5) {
            // Unhide preserved records from the original Kyora providers.
            // Do not automatically re-enable background downloads.
            db.execSQL("""
                UPDATE entities SET archived=0
                WHERE source IN ('4khd','buondua','cosplaytele','4kgirl','everia')
            """.trimIndent())
        }
    }

    private fun createRetryTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS backfill_retries (
                source TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                gallery_id TEXT NOT NULL,
                canonical_url TEXT NOT NULL,
                title TEXT NOT NULL,
                retry_after INTEGER NOT NULL DEFAULT 0,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                PRIMARY KEY(source, gallery_id)
            )
        """.trimIndent())
    }

    fun queueRetry(ref: GalleryRef, error: String) {
        val row = ContentValues().apply {
            put("source", ref.source.wireName)
            put("entity_id", ref.entityStableId)
            put("gallery_id", ref.stableId)
            put("canonical_url", ref.canonicalUrl)
            put("title", ref.title)
            put("last_error", error.take(400))
            put("retry_after", System.currentTimeMillis() + 60_000L)
        }
        writableDatabase.insertWithOnConflict(
            "backfill_retries", null, row, SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.update("backfill_retries", row,
            "source=? AND gallery_id=?",
            arrayOf(ref.source.wireName, ref.stableId))
    }

    fun clearRetry(ref: GalleryRef) {
        writableDatabase.delete("backfill_retries", "source=? AND gallery_id=?",
            arrayOf(ref.source.wireName, ref.stableId))
    }

    fun pendingRetries(entity: EntityRecord, limit: Int = 12): List<GalleryRef> {
        val result = mutableListOf<GalleryRef>()
        readableDatabase.query(
            "backfill_retries",
            arrayOf("gallery_id", "canonical_url", "title"),
            "source=? AND entity_id=? AND retry_after<=?",
            arrayOf(entity.source.wireName, entity.entityId,
                System.currentTimeMillis().toString()),
            null, null, "retry_after ASC", limit.coerceIn(1, 40).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(
                GalleryRef(entity.source, cursor.getString(0), entity.entityId,
                    cursor.getString(1), cursor.getString(2)))
        }
        return result
    }

    fun retryCount(source: SourceId, entityId: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM backfill_retries WHERE source=? AND entity_id=?",
            arrayOf(source.wireName, entityId)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun addEntity(entity: SourceEntity) {
        val values = ContentValues().apply {
            put("source", entity.source.wireName)
            put("entity_id", entity.stableId)
            put("display_name", entity.displayName)
            put("canonical_url", entity.canonicalUrl)
            put("kind", entity.kind)
            put("selected", 1)
            put("live_enabled", 1)
            put("archived", 0)
        }
        writableDatabase.insertWithOnConflict("entities", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        // Re-adding an archived site restores it; normal existing settings stay unchanged.
        writableDatabase.execSQL(
            "UPDATE entities SET archived=0, selected=1, live_enabled=1 " +
                "WHERE source=? AND entity_id=? AND archived=1",
            arrayOf(entity.source.wireName, entity.stableId)
        )
        writableDatabase.update(
            "entities",
            ContentValues().apply {
                put("display_name", entity.displayName)
                put("canonical_url", entity.canonicalUrl)
                put("kind", entity.kind)
            },
            "source=? AND entity_id=?",
            arrayOf(entity.source.wireName, entity.stableId)
        )
    }

    data class GalleryCard(
        val source: SourceId, val galleryId: String, val title: String,
        val count: Int, val state: String, val firstUri: String?, val firstUrl:String?=null
    )

    fun latestSelectedGalleries(limit:Int=24):List<Pair<String,GalleryCard>>{
        val cards=ArrayList<Pair<String,GalleryCard>>()
        val sql="""SELECT e.display_name,g.source,g.gallery_id,g.title,g.media_count,g.state,
          (SELECT m.content_uri FROM media m WHERE m.source=g.source
           AND m.gallery_id=g.gallery_id AND m.content_uri IS NOT NULL
           AND m.state='complete' ORDER BY m.media_index LIMIT 1),
          (SELECT m.url FROM media m WHERE m.source=g.source
           AND m.gallery_id=g.gallery_id ORDER BY m.media_index LIMIT 1)
          FROM galleries g JOIN entities e ON e.source=g.source AND e.entity_id=g.entity_id
          WHERE e.selected=1 AND e.archived=0
          ORDER BY COALESCE(g.retrieved_at,0) DESC, g.gallery_id DESC LIMIT ?"""
        readableDatabase.rawQuery(sql,arrayOf(limit.coerceIn(1,100).toString())).use{c->
            while(c.moveToNext()){
                cards+=c.getString(0) to GalleryCard(
                    SourceId.fromWire(c.getString(1)),c.getString(2),
                    c.getString(3),c.getInt(4),c.getString(5),c.getString(6),c.getString(7))
            }
        }
        return cards
    }

    fun galleryCards(entityId: String, limit: Int = 80): List<GalleryCard> {
        val result = ArrayList<GalleryCard>()
        val sql = """
            SELECT g.source, g.gallery_id, g.title, g.media_count, g.state,
              (SELECT m.content_uri FROM media m WHERE m.source=g.source
               AND m.gallery_id=g.gallery_id AND m.content_uri IS NOT NULL
               AND m.state='complete' ORDER BY m.media_index LIMIT 1),
          (SELECT m.url FROM media m WHERE m.source=g.source
           AND m.gallery_id=g.gallery_id ORDER BY m.media_index LIMIT 1)
            FROM galleries g WHERE g.entity_id=?
            ORDER BY COALESCE(g.retrieved_at, 0) DESC, g.gallery_id DESC
            LIMIT ?
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(entityId, limit.coerceIn(1, 100).toString())).use { cursor ->
            while (cursor.moveToNext()) result.add(GalleryCard(
                SourceId.fromWire(cursor.getString(0)), cursor.getString(1),
                cursor.getString(2), cursor.getInt(3), cursor.getString(4),
                cursor.getString(5)
            ))
        }
        return result
    }

    fun listEntities(): List<EntityRecord> {
        val out = mutableListOf<EntityRecord>()
        readableDatabase.query(
            "entities",
            arrayOf("source","entity_id","display_name","canonical_url","kind","selected","live_enabled","live_cursor","last_sync_at","last_error"),
            "archived=0 AND source IN ('kiutaku','4khd','buondua','cosplaytele','4kgirl','everia','generic')",
            null,null,null,"display_name COLLATE NOCASE ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out += EntityRecord(
                    source = SourceId.fromWire(c.getString(0)),
                    entityId = c.getString(1),
                    displayName = c.getString(2),
                    canonicalUrl = c.getString(3),
                    kind = c.getString(4),
                    selected = c.getInt(5) == 1,
                    liveEnabled = c.getInt(6) == 1,
                    liveCursor = c.getString(7),
                    lastSyncAt = if (c.isNull(8)) null else c.getLong(8),
                    lastError = c.getString(9)
                )
            }
        }
        return out
    }

    fun archiveEntity(source: SourceId, entityId: String) {
        writableDatabase.update("entities", ContentValues().apply {
            put("archived", 1)
            put("selected", 0)
            put("live_enabled", 0)
        }, "source=? AND entity_id=?", arrayOf(source.wireName, entityId))
    }

    /** Seed the original Kyora supported-site catalog once; no background sync starts.
     * Existing rows, enabled flags and user removals are respected. */
    fun seedCatalogOnce(context: Context) {
        val preferences=context.getSharedPreferences("kyora_catalog",Context.MODE_PRIVATE)
        if(preferences.getBoolean("canonical_six_seeded_20261031",false)) return
        writableDatabase.beginTransaction()
        try {
            for (entity in AdapterRegistry.defaultEntities()) {
                val row=ContentValues().apply {
                    put("source",entity.source.wireName)
                    put("entity_id",entity.stableId)
                    put("display_name",entity.displayName)
                    put("canonical_url",entity.canonicalUrl)
                    put("kind",entity.kind)
                    put("selected",0)
                    put("live_enabled",0)
                    put("archived",0)
                }
                writableDatabase.insertWithOnConflict("entities",null,row,SQLiteDatabase.CONFLICT_IGNORE)
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        preferences.edit().putBoolean("canonical_six_seeded_20261031",true).apply()
    }

    fun setAllSelected(value:Boolean) {
        writableDatabase.execSQL("UPDATE entities SET selected=? WHERE archived=0 AND source IN ('kiutaku','4khd','buondua','cosplaytele','4kgirl','everia','generic')",
            arrayOf(if(value)1 else 0))
    }
    fun setAllLiveEnabled(value:Boolean) {
        writableDatabase.execSQL("UPDATE entities SET live_enabled=? WHERE archived=0 AND source IN ('kiutaku','4khd','buondua','cosplaytele','4kgirl','everia','generic')",
            arrayOf(if(value)1 else 0))
    }
    fun setSelected(source: SourceId, entityId: String, value: Boolean) =
        setEntityBoolean(source, entityId, "selected", value)

    fun setLiveEnabled(source: SourceId, entityId: String, value: Boolean) =
        setEntityBoolean(source, entityId, "live_enabled", value)

    private fun setEntityBoolean(source: SourceId, entityId: String, column: String, value: Boolean) {
        require(column == "selected" || column == "live_enabled")
        writableDatabase.update(
            "entities",
            ContentValues().apply { put(column, if (value) 1 else 0) },
            "source=? AND entity_id=?",
            arrayOf(source.wireName, entityId)
        )
    }

    fun setLiveCursor(source: SourceId, entityId: String, cursor: String?) {
        writableDatabase.update(
            "entities",
            ContentValues().apply {
                if (cursor == null) putNull("live_cursor") else put("live_cursor", cursor)
                put("last_sync_at", System.currentTimeMillis())
                putNull("last_error")
            },
            "source=? AND entity_id=?",
            arrayOf(source.wireName, entityId)
        )
    }

    fun setEntityError(source: SourceId, entityId: String, error: String?) {
        writableDatabase.update(
            "entities",
            ContentValues().apply {
                if (error == null) putNull("last_error") else put("last_error", error.take(500))
                put("last_sync_at", System.currentTimeMillis())
            },
            "source=? AND entity_id=?",
            arrayOf(source.wireName, entityId)
        )
    }

    fun upsertGallery(ref: GalleryRef, state: TransferState = TransferState.PENDING) {
        val values = ContentValues().apply {
            put("source", ref.source.wireName)
            put("gallery_id", ref.stableId)
            put("entity_id", ref.entityStableId)
            put("canonical_url", ref.canonicalUrl)
            put("title", ref.title)
            if (ref.publishedAt == null) putNull("published_at") else put("published_at", ref.publishedAt)
            put("state", state.dbValue)
        }
        val updated = writableDatabase.update(
            "galleries", values, "source=? AND gallery_id=?", arrayOf(ref.source.wireName, ref.stableId)
        )
        if (updated == 0) writableDatabase.insertOrThrow("galleries", null, values)
    }

    fun updateGalleryMeta(meta: GalleryMeta, mediaCount: Int) {
        writableDatabase.update(
            "galleries",
            ContentValues().apply {
                put("title", meta.title)
                put("canonical_url", meta.canonicalUrl)
                put("tags_json", JSONArray(meta.tags).toString())
                if (meta.publishedAt == null) putNull("published_at") else put("published_at", meta.publishedAt)
                put("retrieved_at", System.currentTimeMillis())
                put("media_count", mediaCount)
            },
            "source=? AND gallery_id=?",
            arrayOf(meta.source.wireName, meta.stableId)
        )
    }

    fun setGalleryState(source: SourceId, galleryId: String, state: TransferState, error: String? = null) {
        writableDatabase.update(
            "galleries",
            ContentValues().apply {
                put("state", state.dbValue)
                if (error == null) putNull("last_error") else put("last_error", error.take(500))
            },
            "source=? AND gallery_id=?",
            arrayOf(source.wireName, galleryId)
        )
    }

    fun upsertMedia(entityId: String, media: MediaRef) {
        val values = ContentValues().apply {
            put("source", media.source.wireName)
            put("media_id", media.stableId)
            put("gallery_id", media.galleryStableId)
            put("entity_id", entityId)
            put("media_index", media.index)
            put("url", media.url)
            put("normalized_url", media.normalizedUrl)
            put("referer", media.referer)
            put("updated_at", System.currentTimeMillis())
        }
        val updated = writableDatabase.update(
            "media", values, "source=? AND media_id=?", arrayOf(media.source.wireName, media.stableId)
        )
        if (updated == 0) {
            values.put("state", TransferState.PENDING.dbValue)
            writableDatabase.insertOrThrow("media", null, values)
        }
    }

    fun media(source: SourceId, mediaId: String): MediaRecord? = queryMedia(
        "source=? AND media_id=?", arrayOf(source.wireName, mediaId), "1"
    ).firstOrNull()

    fun galleryMedia(source: SourceId, galleryId: String): List<MediaRecord> = queryMedia(
        "source=? AND gallery_id=?", arrayOf(source.wireName, galleryId), null, "media_index ASC"
    )

    private fun queryMedia(selection: String, args: Array<String>, limit: String?, orderBy: String? = null): List<MediaRecord> {
        val out = mutableListOf<MediaRecord>()
        readableDatabase.query(
            "media",
            arrayOf("source","media_id","gallery_id","entity_id","media_index","url","content_uri","filename","state","sha256","ahash64","duplicate_of"),
            selection,args,null,null,orderBy,limit
        ).use { c ->
            while (c.moveToNext()) {
                out += MediaRecord(
                    SourceId.fromWire(c.getString(0)), c.getString(1), c.getString(2), c.getString(3),
                    c.getInt(4), c.getString(5), c.getString(6), c.getString(7),
                    stateFromDb(c.getString(8)), c.getString(9), c.getString(10), c.getString(11)
                )
            }
        }
        return out
    }

    fun markMediaDownloading(media: MediaRef) =
        setMediaState(media.source, media.stableId, TransferState.DOWNLOADING)

    fun setMediaState(source: SourceId, mediaId: String, state: TransferState) {
        writableDatabase.update(
            "media",
            ContentValues().apply {
                put("state", state.dbValue)
                put("updated_at", System.currentTimeMillis())
            },
            "source=? AND media_id=?",
            arrayOf(source.wireName, mediaId)
        )
    }

    fun markMediaComplete(media: MediaRef, result: DownloadResult, duplicateOf: String? = null) {
        writableDatabase.update(
            "media",
            ContentValues().apply {
                put("content_uri", result.contentUri)
                put("filename", result.filename)
                put("mime_type", result.mimeType)
                put("bytes", result.bytes)
                put("sha256", result.sha256)
                if (result.aHash64 == null) putNull("ahash64") else put("ahash64", result.aHash64)
                if (duplicateOf == null) putNull("duplicate_of") else put("duplicate_of", duplicateOf)
                put("state", TransferState.COMPLETE.dbValue)
                put("updated_at", System.currentTimeMillis())
            },
            "source=? AND media_id=?",
            arrayOf(media.source.wireName, media.stableId)
        )
    }

    fun findCompleteByHash(hash: String): MediaRecord? =
        queryMedia("sha256=? AND state=? AND content_uri IS NOT NULL", arrayOf(hash, TransferState.COMPLETE.dbValue), "1", "updated_at DESC").firstOrNull()

    fun recordEvent(type: String, payloadJson: String) {
        writableDatabase.insertOrThrow(
            "events", null,
            ContentValues().apply {
                put("event_type", type)
                put("payload_json", payloadJson)
                put("created_at", System.currentTimeMillis())
            }
        )
    }

    fun exportTables(): Map<String, List<Map<String, Any?>>> = linkedMapOf(
        "entities" to tableRows("entities"),
        "galleries" to tableRows("galleries"),
        "media" to tableRows("media"),
        "identity_aliases" to tableRows("identity_aliases"),
        "events" to tableRows("events")
    )

    private fun tableRows(table: String): List<Map<String, Any?>> {
        require(table in setOf("entities","galleries","media","identity_aliases","events"))
        val out = mutableListOf<Map<String, Any?>>()
        readableDatabase.rawQuery("SELECT * FROM " + table, null).use { c ->
            while (c.moveToNext()) {
                val row = linkedMapOf<String, Any?>()
                c.columnNames.forEachIndexed { i, name ->
                    row[name] = when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).joinToString("") { byte -> "%02x".format(byte) }
                        else -> c.getString(i)
                    }
                }
                out += row
            }
        }
        return out
    }

    private fun stateFromDb(value: String): TransferState =
        TransferState.entries.firstOrNull { it.dbValue == value } ?: TransferState.UNSEEN
}
