package com.azrael.pixivdumpsync

/** Pure library routing rule; individual user-selected albums are not sites. */
internal object CosplayGalleryVisibility {
    fun hasGalleryCards(kind:String):Boolean =
        kind in setOf("site","gallery_only","selected_album")
}
