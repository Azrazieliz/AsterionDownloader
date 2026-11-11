package com.azrael.pixivdumpsync

/** Pure torrent content policies. A title alone can never prove a torrent contains a game. */
object GameTorrentPolicy {
    private val gameFormats = setOf(
        "exe", "apk", "rpa", "rpy", "rpyc", "xp3", "nsa", "ns2",
        "unity3d", "pck", "obb", "pak", "iso", "bin", "cue", "jar",
        "swf", "msi", "dmg", "pkg", "xci", "nsp", "cia", "3ds",
        "nds", "chd", "rvz", "wud", "gdi", "cso"
    )
    private val archiveFormats = setOf("zip", "7z", "rar")
    private val mediaFormats = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "webm", "flv", "m4v",
        "mp3", "m4a", "flac", "aac", "wav", "ogg", "wma"
    )
    private val extras = listOf("trailer", "preview", "sample", "screenshots", "wallpaper")
    private val mediaOnlyMarkers = Regex(
        "(?i)(?:\\b(?:bdrip|brrip|dvdrip|webrip|web[-.]?dl|bluray|x264|x265|h\\.?264|hevc|1080p|2160p|music[ ._-]?video|discography|audio[ ._-]?book|porn[ ._-]?video)\\b|\\b(?:mp3|flac)[ ._-]?(?:album|discography|collection)\\b)"
    )
    /** An obvious video or audio release is not a game search result. */
    fun clearlyNonGameTitle(title: String): Boolean {
        val normalized = title.trim().lowercase()
        val mediaExtension = normalized.substringBefore('?').substringAfterLast('.', "")
            .substringBefore(' ').substringBefore(']')
        return mediaExtension in mediaFormats || mediaOnlyMarkers.containsMatchIn(normalized)
    }

    /** Inspect the actual torrent paths before accepting a Games download.
     * An archive with no visible contents remains only a candidate until inspected.
     */
    fun hasGamePayload(paths: List<String>): Boolean {
        if (paths.isEmpty()) return false
        return paths.any { raw ->
            val path = raw.replace('\\', '/').lowercase()
            val name = path.substringAfterLast('/')
            val ext = name.substringAfterLast('.', "")
            when {
                extras.any { it in path } -> false
                ext in gameFormats -> !clearlyNonGameTitle(name)
                ext in archiveFormats -> !clearlyNonGameTitle(name) &&
                    !Regex("(?i)(?:^|[ ._\\-])(movies?|videos?|episodes?|music|albums?|photosets?|image[ ._-]?sets)(?:[ ._\\-]|$)")
                        .containsMatchIn(name)
                name == "index.html" -> true
                else -> false
            }
        }
    }
}
