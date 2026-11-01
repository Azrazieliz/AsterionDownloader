package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class ArchiveContentRouteTest {
    private val creator=ArchiveCreator(CreatorArchiveHost.KEMONO,"gumroad","123","Model artist")
    private fun post(title:String,name:String)=ArchivePost(
        creator,"4",title,"2026-10-10",listOf(ArchiveFile(name,"https://n4.kemono.cr/data/file")))
    @Test fun preservesOriginalModelCategory(){
        assertEquals(ArchiveSection.ASSETS,ArchiveContent.category(post("Rigged character","body.blend").files[0],
            post("Rigged character","body.blend")))
        assertEquals(ArchiveSection.ASSETS,ArchiveContent.category(post("Textures","metal.dds").files[0],
            post("Textures","metal.dds")))
    }
    @Test fun videosAndBooksDoNotBecomeArtwork(){
        val video=post("Trailer","scene.mp4")
        assertEquals(ArchiveSection.VIDEOS,ArchiveContent.category(video.files[0],video))
        val book=post("Manual","guide.pdf")
        assertEquals(ArchiveSection.BOOKS,ArchiveContent.category(book.files[0],book))
        val comic=post("Manga","comic.cbz")
        assertEquals(ArchiveSection.BOOKS,ArchiveContent.category(comic.files[0],comic))
    }
    @Test fun filtersOnlyRequestedFiles(){
        val source=ArchivePost(creator,"5","mixed","",listOf(
            ArchiveFile("model.glb","https://n4.kemono.cr/data/model.glb"),
            ArchiveFile("video.mp4","https://n4.kemono.cr/data/video.mp4")))
        assertEquals(listOf("model.glb"),ArchiveContent.filtered(ArchiveSection.ASSETS,source)
            .files.map{it.title})
    }
}
