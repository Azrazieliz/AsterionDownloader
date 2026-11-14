package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class VisibleMediaSupportTest {
    @Test fun acceptsAccessibleHttpsMediaWithCdnHost() {
        val media=VisibleMedia("https://cdn.example.net/album/original.jpg?token=abc","image")
        assertTrue(VisibleMediaSupport.accepted("https://onlyfans.com/somecreator",media))
        assertFalse(VisibleMediaSupport.accepted("https://onlyfans.com/somecreator",
            media.copy(url="http://cdn.example.net/album/original.jpg")))
        assertFalse(VisibleMediaSupport.accepted("https://onlyfans.com/somecreator",
            media.copy(url="https://127.0.0.1/private.jpg")))
        assertFalse(VisibleMediaSupport.accepted("https://onlyfans.com/somecreator",
            media.copy(url="https://me:password@cdn.example.net/file.jpg")))
    }
    @Test fun genericFilesRequireActualMediaFileExtension() {
        assertFalse(VisibleMediaSupport.accepted("https://fansly.com/example",
            VisibleMedia("https://example.net/profile","file")))
        assertTrue(VisibleMediaSupport.accepted("https://fansly.com/example",
            VisibleMedia("https://example.net/download/opaque?id=1","file","artwork.zip")))
    }
    @Test fun booksAcceptOnlyExplicitOriginalEpubFiles() {
        assertTrue(VisibleMediaSupport.isEpubOriginal(
            VisibleMedia("https://mp4directs.com/attachments/book.epub?token=abc","file")))
        assertTrue(VisibleMediaSupport.isEpubOriginal(
            VisibleMedia("https://cdn.example.net/get?id=4","file","Volume 1.epub")))
        assertFalse(VisibleMediaSupport.isEpubOriginal(
            VisibleMedia("https://mp4directs.com/threads/book.123","file")))
        assertFalse(VisibleMediaSupport.isEpubOriginal(
            VisibleMedia("https://cdn.example.net/vol1.pdf","file")))
        assertFalse(VisibleMediaSupport.isEpubOriginal(
            VisibleMedia("https://cdn.example.net/cover.jpg","image")))
    }
    @Test fun safeFileNamesForSignedMedia() {
        val filename=VisibleMediaSupport.fileName(
            VisibleMedia("https://cdn.example.net/files/opaque?sig=secret","image"))
        assertTrue(filename.startsWith("media_"))
        assertTrue(filename.endsWith(".jpg"))
        assertEquals("portrait.png",VisibleMediaSupport.fileName(
            VisibleMedia("https://cdn.example.net/files/opaque","image","portrait.png")))
    }
}
