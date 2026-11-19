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

    // Source cards should not rebuild on every 240 ms video byte
    // notification. They receive their own catalog-only event under the
    // SAME private authority; no additional ContentProvider or engine.
    fun catalogUri(context:Context):Uri =
        Uri.parse("content://"+context.packageName+".download-events/catalog")

    fun catalogChanged(context:Context){
        runCatching {
            context.contentResolver.notifyChange(catalogUri(context),null)
        }
    }

    fun changed(context:Context) {
        runCatching { context.contentResolver.notifyChange(uri(context),null) }
        // Transfer state remains durable in the existing DB/journal even
        // when the host OS drops an observer callback.
    }
}
