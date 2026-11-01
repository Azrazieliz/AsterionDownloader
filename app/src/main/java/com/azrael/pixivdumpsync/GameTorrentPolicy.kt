package com.azrael.pixivdumpsync

/** Pure torrent file-content classifier; independent from the native libtorrent runtime. */
object GameTorrentPolicy {
    fun hasGamePayload(paths:List<String>):Boolean {
        val gameFormats=setOf("exe","apk","rpa","rpy","rpyc","xp3","nsa",
            "ns2","unity3d","pck","obb","pak","iso","bin","cue","jar",
            "swf","zip","7z","rar","msi","dmg","pkg","xci","nsp",
            "cia","3ds","nds","chd","rvz","wud","gdi","cso")
        return paths.any { raw ->
            val p=raw.lowercase()
            val name=p.substringAfterLast('/')
            val ext=name.substringAfterLast('.', "")
            val extras=listOf("trailer","preview","sample","screenshots","wallpaper")
            ext in gameFormats && extras.none{it in p}
        } || paths.any{it.lowercase().endsWith("index.html")}
    }

}
