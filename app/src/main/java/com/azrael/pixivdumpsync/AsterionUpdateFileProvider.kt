package com.azrael.pixivdumpsync

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Least-privilege one-file bridge to Android's system package installer.
 * The cache stays private; the installer receives only a temporary read grant.
 */
class AsterionUpdateFileProvider : ContentProvider() {
    private fun fileFor(uri: Uri): File {
        val app = context ?: throw FileNotFoundException("App unavailable")
        if (uri.path != "/update.apk" ||
            uri.authority != app.packageName + ".update-apk") {
            throw FileNotFoundException("Unknown update file")
        }
        val file = File(app.cacheDir, "asterion_update/verified_update.apk")
        if (!file.isFile || file.length() <= 0L) throw FileNotFoundException("Update not staged")
        return file
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String {
        fileFor(uri)
        return "application/vnd.android.package-archive"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only APK provider")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val apk = fileFor(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> "Asterion-Downloader-Update.apk"
                OpenableColumns.SIZE -> apk.length()
                else -> null
            }
        })
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Read-only provider")

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("Read-only provider")

    override fun delete(
        uri: Uri, selection: String?, selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("Read-only provider")
}
