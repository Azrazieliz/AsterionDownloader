package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class AuthorizedOriginalResolverTest {
    @Test fun signedOriginalHeadersRestoreOriginalFilename(){
        val url="https://files.example.net/api/download?token=hidden"
        val name=AuthorizedEndpointInspector.filename(
            "attachment; filename*=UTF-8''My%20Book.epub",url)
        assertEquals("My_Book.epub",name)
        assertEquals("epub",AuthorizedEndpointInspector.mediaType(
            "application/epub+zip",name))
        assertEquals("model",AuthorizedEndpointInspector.mediaType(
            "model/gltf-binary","file"))
        assertEquals("video",AuthorizedEndpointInspector.mediaType(
            "video/mp4","file"))
    }

    @Test fun authenticatedAdultGalleryImageIsSelectable(){
        val page="<main><a download href='/file/full.jpg'>Photo original</a></main>"
        val result=AuthorizedOriginalResolver.parse(page,
            "https://creator.example.net/profile/test","ADULT")
        assertEquals("image",result?.kind)
        assertTrue(result?.url?.endsWith("/file/full.jpg")==true)
    }
    @Test fun resolvesOnlyActuallyLinkedOriginals(){
        val html="""<main>
          <a href="/creator/posts/21">Post</a>
          <a download href="https://example.com/files/full-original.zip">Full archive</a>
          <a href="/files/preview.mp4">Preview</a>
        </main>"""
        val result=AuthorizedOriginalResolver.parse(html,"https://example.com/creator/posts/21","ASSETS")
        assertEquals("https://example.com/files/full-original.zip",result?.url)
        assertEquals("archive",result?.kind)
    }
    @Test fun epubDoesNotAcceptPdfOrZip(){
        val html="""<main>
          <a download href="/work.pdf">PDF</a>
          <a download href="/work.zip">ZIP</a>
          <a download href="/book.epub">EPUB</a>
        </main>"""
        val original=AuthorizedOriginalResolver.parse(html,"https://example.org/books","BOOKS")
        assertTrue(original?.url?.endsWith("book.epub")==true)
    }
    @Test fun signedDownloadUrlsCanUseExposedFilename(){
        val html="""<article>
          <a download="FullNovel.epub" href="https://example.org/download?id=246&token=abcd">Download original</a>
        </article>"""
        val original=AuthorizedOriginalResolver.parse(html,
            "https://example.org/post/246","BOOKS")
        assertEquals("FullNovel.epub",original?.filename)
        assertEquals("https://example.org/download?id=246&token=abcd",original?.url)
    }
    @Test fun inaccessibleContentIsNotInvented(){
        val html="<main><p>Subscribe to view originals</p></main>"
        assertNull(AuthorizedOriginalResolver.parse(html,"https://example.org/locked","VIDEOS"))
    }
}
