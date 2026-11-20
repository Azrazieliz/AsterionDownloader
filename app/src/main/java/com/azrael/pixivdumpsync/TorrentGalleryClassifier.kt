package com.azrael.pixivdumpsync

/** Gallery search is a VIEW of existing torrent metadata, never fabricated
 * image previews or a new unapproved torrent index.
 */
internal object TorrentGalleryClassifier {
    private val gallery=Regex(
        """(?i)\b(?:photo(?:s|set|shoot|book)?|image(?:s|set)?|picture(?:s)?|gallery|galleries|cosplay|gravure|wallpaper(?:s)?)\b""")
    private val videoOnly=Regex("""(?i)\b(?:movie|full.?scene|720p|1080p|2160p|web.?dl|bluray|h264|h265|x264|x265)\b""")
    fun likelyGallery(title:String,detail:String=""):Boolean =
        gallery.containsMatchIn(title+" "+detail) &&
            !videoOnly.containsMatchIn(title)
}
