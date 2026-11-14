package com.azrael.galleryflow

/** Approved original flat image storage. Albums and dates are tracked in the
 * gallery database, never represented as physical directory branches.
 * FlatStoragePolicy filenames keep gallery/page identity collision-free.
 */
internal object CosplayStoragePolicy {
    fun relativePath(galleryFolder:String):String =
        "Download/"+galleryFolder.trim('/').ifBlank{"AsterionDownloader"}+"/"
}
