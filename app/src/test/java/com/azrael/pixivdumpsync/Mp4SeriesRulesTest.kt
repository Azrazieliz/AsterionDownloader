package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class Mp4SeriesRulesTest {
    @Test fun mainNameMatchesKnownAlternateAndAvoidsUnrelatedTitles(){
        assertTrue(Mp4SeriesRules.matches(
            "Tensei Shitara Slime Datta Ken Light Novel Epub","Tensura"))
        assertTrue(Mp4SeriesRules.matches(
            "That Time I Got Reincarnated as a Slime Novel EPUB","Tensura"))
        assertFalse(Mp4SeriesRules.matches(
            "Demon Princess Magical Chaos (Fans of Tensura)","Arifureta"))
        assertFalse(Mp4SeriesRules.matches(
            "Trinity Seven: all volumes EPUB","Trinity in Tempest"))
    }
    @Test fun volumeFloorIsPerWorkAndUnknownIsNotSilentlySkipped(){
        assertEquals(23,Mp4SeriesRules.volume("Volume 23 EPUB"))
        assertEquals(3,Mp4SeriesRules.volume("Vol. 03 epub"))
        assertNull(Mp4SeriesRules.volume("All volumes EPUB"))
        assertFalse(Mp4SeriesRules.eligible(22,23))
        assertTrue(Mp4SeriesRules.eligible(23,23))
        assertFalse(Mp4SeriesRules.eligible(null,23))
        assertTrue(Mp4SeriesRules.eligible(1,1))
    }
    @Test fun forumParsesOnlyNamedWorkWithoutPdfFallback(){
        val url="https://mp4directs.com/forums/ln.178/"
        val doc=Jsoup.parse("""<main>
          <a href="/forums/tensei-shitara-slime-datta-ken.300/"
             title="Tensei Shitara Slime Datta Ken">Series</a>
          <a href="/threads/tensura-volume-23.18011/">Tensura Volume 23 EPUB</a>
          <a href="/threads/trinity-seven.19000/">Trinity Seven</a>
          <a href="/threads/tensura-pdf.19001/">Tensura PDF</a>
        </main>""",url)
        val links=Mp4SeriesRules.forumLinks(doc,url,"Tensura")
        assertTrue(links.any{it.url.contains("volume-23")&&it.volume==23})
        assertTrue(links.any{it.url.contains("/forums/")})
        assertFalse(links.any{it.url.contains("trinity-seven")})
    }
    @Test fun linkedEpubAndMangaZipKeepTheirOwnFormats(){
        val url="https://mp4directs.com/threads/tensura-volumes.99/"
        val doc=Jsoup.parse("""<article class="message-body">
           <p>Volume 23 <a href="/files/tensura23.epub">Download</a></p>
           <p>Volume 22 <a href="/files/tensura22.epub">Download</a></p>
           <p>Chapter 1 <a href="/files/trinity-1.zip">Get images</a></p>
           <a href="/files/tensura23.pdf">PDF</a>
        </article>""",url)
        val epub=Mp4SeriesRules.threadOriginals(doc,url,"EPUB")
        assertEquals(2,epub.size)
        assertTrue(epub.all{it.isEpub && !it.isImageZip})
        assertEquals(setOf(22,23),epub.map{it.volume}.toSet())
        val images=Mp4SeriesRules.threadOriginals(doc,url,"ZIP")
        assertEquals(1,images.size)
        assertTrue(images.single().isImageZip)
    }
    @Test fun relatedWorkDiscoveredOnlyFromMatchingFamilyPrefix(){
        val doc=Jsoup.parse("""<main>
          <a href="/threads/t.1">That Time I Got Reincarnated as a Slime: Trinity in Tempest</a>
          <a href="/threads/u.2">That Time I Got Reincarnated as a Slime: The Slime Diaries</a>
          <a href="/threads/v.3">Trinity Seven</a>
        </main>""","https://mp4directs.com/")
        val related=Mp4SeriesRules.relatedNames(doc,"Tensura")
        assertEquals(2,related.size)
        assertTrue(related.any{it.contains("Trinity in Tempest")})
    }
}
