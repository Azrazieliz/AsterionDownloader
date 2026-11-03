package com.azrael.pixivdumpsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerArtworkRecoveryTest {
    @Test fun extractsEveryLinkedArtworkOriginalWithoutThumbnails() {
        val page="https://art.example.com/posts/42"
        val html="""
            <a href="/media/first.png" download="full_first.png">Original 1</a>
            <a href="https://cdn.example.com/files/second.jpg?sig=123">Original 2</a>
            <img data-original="/originals/third.webp" src="/thumb/small.jpg">
            <a href="/thumb/preview.jpg">Preview</a>
            <a href="/media/first.png">Repeated</a>
            <a href="http://insecure.example.com/fourth.png">Insecure</a>
        """.trimIndent()
        val result=OwnerArtworkRecovery.extract(html,page)
        assertEquals(3,result.size)
        assertEquals("full_first.png",result[0].filename)
        assertTrue(result.all{it.kind=="image"})
        assertTrue(result.all{it.referer==page})
        assertFalse(result.any{it.url.contains("thumb")})
    }

    @Test fun acceptsExplicitDownloadUrlWithoutExtensionIfFilenameIdentifiesImage() {
        val html="""
            <a data-download-url="https://cdn.example.com/download/asset?sig=xyz"
               data-filename="original-art.webp">Download</a>
            <a href="https://127.0.0.1/local.jpg">Untrusted local address</a>
        """.trimIndent()
        val result=OwnerArtworkRecovery.extract(html,"https://art.example.com/work/7")
        assertEquals(1,result.size)
        assertEquals("original-art.webp",result.first().filename)
    }

    @Test fun deduplicatesInputAndUsesStableNoncollidingFileNames() {
        val seeds=OwnerArtworkRecovery.seeds(
            "https://art.example.com/a\nhttps://art.example.com/a\n\nhttps://art.example.com/b"
        )
        assertEquals(2,seeds.size)
        val a=AccessibleOriginal("https://cdn.example.com/one/art.jpg","art.jpg",
            "image","https://art.example.com/a")
        val b=AccessibleOriginal("https://cdn.example.com/two/art.jpg","art.jpg",
            "image","https://art.example.com/b")
        assertTrue(OwnerArtworkRecovery.uniqueFileName(a).endsWith("_art.jpg"))
        assertFalse(OwnerArtworkRecovery.uniqueFileName(a)==
            OwnerArtworkRecovery.uniqueFileName(b))
    }
}
