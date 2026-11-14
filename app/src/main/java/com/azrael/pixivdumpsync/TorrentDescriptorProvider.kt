package com.azrael.pixivdumpsync

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Grant one read-only .torrent metadata file to an external torrent client. */
class TorrentDescriptorProvider:ContentProvider(){
    private fun target(uri:Uri):File {
        val name=uri.lastPathSegment.orEmpty()
        require(name.matches(Regex("[a-f0-9]{20}\\.torrent"))){"Invalid torrent file"}
        return File(requireNotNull(context).cacheDir,"torrent-descriptors/$name")
            .also{require(it.isFile){"Torrent metadata missing"}}
    }
    override fun onCreate():Boolean=true
    override fun getType(uri:Uri):String="application/x-bittorrent"
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,
        selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
        val file=target(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply{
            addRow(arrayOf(file.name,file.length()))
        }
    }
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor{
        require(mode=="r"){"Torrent files are read-only"}
        return ParcelFileDescriptor.open(target(uri),ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=null
    override fun update(uri:Uri,values:ContentValues?,where:String?,
        args:Array<out String>?):Int=0
    override fun delete(uri:Uri,where:String?,args:Array<out String>?):Int=0
}
