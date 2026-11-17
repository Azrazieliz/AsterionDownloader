package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/**
 * The owner confirmed Rule34Video thumbnails are restored in v1.3.72.
 * Freeze the provider's actual selected-card poster and prohibit placeholder
 * and recommendation-image substitution. This is an offline visual-URL
 * contract, not an assertion about remote CDN uptime.
 */
class Rule34PosterFreezeTest {
    @Test fun selectedVideoUsesRealPosterNotLazyLoadingPlaceholder(){
        val source="https://rule34video.com/"
        val html="""<main><article class="video-card">
            <a title="Selected original scene"
                href="/video/4727779/selected-scene/">
                <img src="/static/loading.gif"
                    data-src="/thumbs/4727779-selected.webp"
                    alt="Selected original scene">
            </a>
            </article></main>"""
        val found=SourceIndexParser.entries(
            Jsoup.parse(html,source),source,"VIDEOS",100L)
        assertEquals("Selected video card disappeared",1,found.size)
        assertEquals(
            "https://rule34video.com/video/4727779/selected-scene/",
            found.single().url)
        assertEquals(
            "https://rule34video.com/thumbs/4727779-selected.webp",
            found.single().preview)
        assertEquals("video",found.single().kind)
    }
    @Test fun selectedVideoNeverDisplaysGenericPlaceholderAsItsPoster(){
        val source="https://rule34video.com/"
        val html="""<main><article>
            <a href="/video/4727779/selected-scene/"
               title="Selected original scene">
              <img src="/images/default-cover.png"
                   alt="Selected original scene">
            </a></article></main>"""
        val found=SourceIndexParser.entries(
            Jsoup.parse(html,source),source,"VIDEOS",100L)
        assertEquals(1,found.size)
        assertTrue("Placeholder must not be presented as real poster",
            found.single().preview.isNullOrBlank())
    }
}
