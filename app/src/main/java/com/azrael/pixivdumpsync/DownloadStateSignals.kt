package com.azrael.pixivdumpsync

import android.content.Context
import android.net.Uri

/**
 * Lightweight cross-process invalidation for the ONE existing download
 * manager. ContentService delivers an immediate ContentObserver callback
 * from VideoFlow, torrent and the main process; no duplicate transfer engine,
 * app rebuild or provider network scan is involved.
 *
 * Only an invalidation signal is transmitted: no URLs, cookies, media titles,
 * private files, or actual transfer state travel through this URI.
 */
internal object DownloadStateSignals {
    fun uri(context:Context):Uri =
        Uri.parse("content://"+context.packageName+".download-events/changed")

    fun changed(context:Context) {
        runCatching { context.contentResolver.notifyChange(uri(context),null) }
        // Transfer state remains durable in the existing DB/journal even
        // when the host OS drops an observer callback.
    }
}
