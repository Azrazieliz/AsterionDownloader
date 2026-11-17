package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.BaseColumns
import android.provider.MediaStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object FileStore {
    private fun relativeDir(context: Context): String =
        Environment.DIRECTORY_DOWNLOADS + "/" + context.getString(R.string.pixiv_download_folder) + "/"

    private fun storageDirectories(context: Context): List<String> =
        if (context.resources.getBoolean(R.bool.nocturne_preview))
            listOf(relativeDir(context))
        else listOf(relativeDir(context), LEGACY_RELATIVE_DIR)

    private val LEGACY_RELATIVE_DIR =
        "${Environment.DIRECTORY_DOWNLOADS}/PixiFlow/"

    private val COLLECTION_URI =
        MediaStore.Downloads.EXTERNAL_CONTENT_URI

    fun exists(context: Context, filename: String): Boolean {
        val projection = arrayOf(BaseColumns._ID)
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?"

        for (relativeDir in storageDirectories(context)) {
            context.contentResolver.query(
                COLLECTION_URI,
                projection,
                selection,
                arrayOf(filename, relativeDir),
                null
            ).use { c ->
                if (c != null && c.moveToFirst()) return true
            }
        }
        return false
    }

    fun findUri(context: Context, filename: String): Uri? {
        val projection = arrayOf(BaseColumns._ID)
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        for (directory in storageDirectories(context)) {
            context.contentResolver.query(
                COLLECTION_URI, projection, selection, arrayOf(filename, directory), null
            ).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    return android.content.ContentUris.withAppendedId(
                        COLLECTION_URI, cursor.getLong(0)
                    )
                }
            }
        }
        return null
    }

    fun listArtworkFiles(context: Context): List<String> {
        val files = linkedSetOf<String>()
        val pattern = Regex("[0-9]+_p[0-9]+\\.(jpg|jpeg|png|gif|webp)")
        for (directory in storageDirectories(context)) {
            context.contentResolver.query(
                COLLECTION_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(directory), null
            ).use { cursor ->
                if (cursor != null) while (cursor.moveToNext()) {
                    val filename = cursor.getString(0)
                    if (filename.matches(pattern)) files += filename
                }
            }
        }
        return files.toList()
    }

    fun saveImage(
        context: Context,
        imageUrl: String,
        filename: String,
        referer: String,
        knownMissing: Boolean = false
    ) {
        FileSaveLockPolicy.forFilename(filename) {
            saveImageForFilename(context, imageUrl, filename, referer, knownMissing)
        }
    }

    private fun saveImageForFilename(
        context: Context,
        imageUrl: String,
        filename: String,
        referer: String,
        knownMissing: Boolean
    ) {
        if (!knownMissing && exists(context, filename)) return

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(filename))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir(context))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(COLLECTION_URI, values)
            ?: throw IOException("MediaStore insert failed for $filename")

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                val conn = URL(imageUrl).openConnection() as HttpURLConnection
                NetworkRequestRegistry.register(conn)
                try {
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 12_000
                    conn.readTimeout = 45_000
                    conn.setRequestProperty("User-Agent", PixivApi.USER_AGENT)
                    conn.setRequestProperty("Referer", referer)

                    val code = conn.responseCode
                    if (code !in 200..299) {
                        throw IOException("Image HTTP $code")
                    }

                    conn.inputStream.use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            if (SyncControl.isStopping()) throw SyncCancelledException()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                } catch (t: Throwable) {
                    if (SyncControl.isStopping()) throw SyncCancelledException()
                    throw t
                } finally {
                    NetworkRequestRegistry.unregister(conn)
                    runCatching { conn.disconnect() }
                }
            } ?: throw IOException("Could not open output for $filename")

            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null
            )
            // Upload from the already saved MediaStore URI; no redundant copy.
            if (NduSettings.configured(context)) {
                runCatching {
                    NduCloudQueue(context).use { it.enqueue("kuroha",filename,uri) }
                }
            }
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    fun extensionFromUrl(url: String): String {
        val clean = Uri.parse(url).lastPathSegment ?: return "jpg"
        val ext = clean.substringAfterLast('.', "jpg").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp" -> ext
            else -> "jpg"
        }
    }

    private fun mimeFor(filename: String): String =
        when (filename.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
}
