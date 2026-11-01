package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class CatalogDiscoveryTest {
    @Test fun titleSearchEmitsOnlyEpubDownloads() {
        val json="""{"results":[
          {"title":"Pride and Prejudice","authors":[{"name":"Austen, Jane"}],
           "formats":{"application/epub+zip":"https://www.gutenberg.org/ebooks/1342.epub3.images"}},
          {"title":"Unavailable edition","formats":{"text/html":"https://example.com/file.html"}}
        ]}"""
        val items=CatalogDiscovery.gutendex(json)
        assertEquals(1,items.size)
        assertEquals(CatalogKind.EPUB,items.first().kind)
        assertEquals("Pride and Prejudice.epub",items.first().filename)
        assertTrue(items.first().url.contains("1342"))
    }
    @Test fun restrictedArchiveBookIsNotDownloadable() {
        val response="""{"metadata":{"access-restricted-item":"true"},"files":[
          {"name":"restricted.epub","size":"1024"}]}"""
        assertTrue(CatalogDiscovery.archiveFiles(response,"example","Example",false).isEmpty())
        val loan="""{"metadata":{},"files":[
          {"name":"locked_lcp.epub","size":"1024"},
          {"name":"public.epub","size":"2048","private":"true"},
          {"name":"available.epub","size":"4096"}]}"""
        val hits=CatalogDiscovery.archiveFiles(loan,"example","Example",false)
        assertEquals(1,hits.size)
        assertTrue(hits[0].url.endsWith("available.epub"))
    }
    @Test fun gameFilesUseRealExternalFormats() {
        val meta="""{"metadata":{},"files":[
          {"name":"game-win.zip","size":"15000"},
          {"name":"game-android.apk","size":"20000"},
          {"name":"game-pdf.pdf","size":"30000"}]}"""
        val hits=CatalogDiscovery.archiveFiles(meta,"demo123","Demo Game",true)
        assertEquals(2,hits.size)
        assertTrue(hits.any{it.kind==CatalogKind.GAME_ARCHIVE})
        assertTrue(hits.any{it.kind==CatalogKind.APK})
    }
    @Test fun matchTitleAndArchiveRecord() {
        val response="""{"response":{"docs":[{"identifier":"test-123","title":"Example Novel"},
            {"identifier":"../../bad","title":"Bad"}]}}"""
        assertEquals(listOf("test-123" to "Example Novel"),CatalogDiscovery.archiveSearch(response))
        assertTrue(CatalogDiscovery.relevance("Example Novel","Example Novel Free Edition")>0)
        assertEquals(0,CatalogDiscovery.relevance("Not Here","Different Name"))
    }
}
