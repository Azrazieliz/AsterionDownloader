package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/** Real provider HTML shapes observed during the November 11 acceptance audit. */
class NativeProviderListingTest {
    @Test fun hentaibrosHomepageCardKeepsItsRealCover() {
        val base="https://hentaibros.net/"
        val doc=Jsoup.parse("""<main><article class="loop-video thumb-block video-preview-item">
          <a href="/muramata-san-no-aijou-episode-2/" title="Muramata-san no Aijou episode 2">
            <div class="post-thumbnail"><img class="video-main-thumb"
              src="/wp-content/uploads/2026/10/Muramata-san-no-Aijou-02.jpg"
              alt="Muramata-san no Aijou episode 2"></div>
          </a>
        </article></main>""",base)
        val entries=SourceIndexParser.entries(doc,base,"HENTAI",123L)
        assertEquals(1,entries.size)
        assertEquals("video",entries.single().kind)
        assertTrue(entries.single().preview.orEmpty().endsWith("Muramata-san-no-Aijou-02.jpg"))
        assertEquals("https://hentaibros.net/muramata-san-no-aijou-episode-2/",
            entries.single().url)
    }

    @Test fun javideoCardIndexesVideoDespitePlaceholderSiblingCover() {
        val base="https://javideo.net/en/"
        val doc=Jsoup.parse("""<main><div class="card card--big">
          <div class="card__cover">
            <img src="/images/default-cover.webp" data-src="/images/default-cover.jpg"
                 alt="Example Movie">
            <a class="card__play" href="https://javideo.net/en/fc2ppv-4994295"></a>
          </div><div class="card__content"><h3 class="card__title">
            <a href="https://javideo.net/en/fc2ppv-4994295"
              title="Example Movie">Example Movie</a>
          </h3></div>
        </div></main>""",base)
        val entries=SourceIndexParser.entries(doc,base,"ADULT",123L)
        val item=entries.single{it.url=="https://javideo.net/en/fc2ppv-4994295"}
        assertEquals("video",item.kind)
        assertNull("Website placeholder must not count as a real thumbnail",item.preview)
    }

    @Test fun nativeRoutingRejectsCrossSiteAndNavigationCards() {
        val source="https://hentaibros.net/"
        val doc=Jsoup.parse("""<main>
          <a href="/latest-videos/" title="Latest videos">
            <img src="/default-cover.jpg"></a>
          <a href="https://attacker.example/muramata-san-no-aijou-episode-2/"
             title="Cross-site example">
            <img src="/wp-content/uploads/2026/10/real.jpg"></a>
        </main>""",source)
        assertTrue(SourceIndexParser.entries(doc,source,"HENTAI",123L).isEmpty())
    }
}
