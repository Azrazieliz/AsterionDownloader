package com.azrael.pixivdumpsync

/** Bounded UI window for the existing file-select torrent manifest.
 * No libtorrent JNI or file changes occur during pagination.
 */
internal object TorrentChooserPagination {
    private const val PAGE_SIZE=80
    data class Window(val first:Int,val last:Int) // last is exclusive

    fun pageCount(fileCount:Int):Int {
        require(fileCount in 1..20_000)
        return (fileCount-1)/PAGE_SIZE+1
    }
    fun bounds(fileCount:Int,page:Int):Window {
        val pages=pageCount(fileCount)
        require(page in 0 until pages)
        val begin=page*PAGE_SIZE
        return Window(begin,minOf(begin+PAGE_SIZE,fileCount))
    }
    fun hasNext(fileCount:Int,page:Int):Boolean =
        page+1<pageCount(fileCount)
}
