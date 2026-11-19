package com.azrael.pixivdumpsync

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Private, data-free URI authority for Android ContentService invalidations.
 *
 * The torrent, Gallery, Pixiv and VideoFlow workers use notifyChange after
 * real state commits. The main screen observes that URI; callers cannot
 * query or mutate anything through this provider. Not another download engine.
 */
class DownloadStateSignalProvider:ContentProvider(){
    override fun onCreate():Boolean=true
    override fun getType(uri:Uri):String?=null
    override fun query(uri:Uri,projection:Array<out String>?,
        selection:String?,selectionArgs:Array<out String>?,
        sortOrder:String?):Cursor?=null
    override fun insert(uri:Uri,values:ContentValues?):Uri?=null
    override fun delete(uri:Uri,selection:String?,
        selectionArgs:Array<out String>?):Int=0
    override fun update(uri:Uri,values:ContentValues?,
        selection:String?,selectionArgs:Array<out String>?):Int=0
}
