package com.azrael.galleryflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class CosplayTeleNativeAlbumScopeTest {
    private val selected="https://cosplaytele.com/selected-cosplay-set/"

    @Test fun nativeDirectPageExtractorNeverIncludesRecommendedGalleryOrPageAssets(){
        val photos=(1..37).joinToString("") { n ->
            "<figure><a href='https://cdn.cosplaytele.com/set/$n.jpg'>"+
            "<img src='https://cdn.cosplaytele.com/set/thumb$n.jpg' /></a></figure>"
        }
        val other=(1..113).joinToString("") { n ->
            "<a href='https://cdn.cosplaytele.com/other/$n.jpg'>"+
            "<img src='https://cdn.cosplaytele.com/other/thumb$n.jpg' /></a>"
        }
        val html="<main><div class='recommendations'>$other</div>"+
            "<article id='post-123' class='post-123'>"+
            "<div class='entry-content'><h1>37 photos</h1>$photos"+
            "<div class='related'>$other</div></div></article></main>"
        val doc=Jsoup.parse(html,selected)
        val unscoped=directPageAssets(doc,selected).filter {
            it.kind==MediaKind.IMAGE
        }
        assertTrue(unscoped.size>37)
        val selectedContent=SelectedAlbumScope.selectedContent(doc,selected)
        val scoped=directPageAssets(selectedContent,selected).filter {
            it.kind==MediaKind.IMAGE
        }
        assertEquals(37,scoped.size)
        assertTrue(scoped.all{"/set/" in it.url})
        SelectedAlbumScope.verifyAdvertisedCount(37,scoped.size)
    }

    @Test fun selectedVideoDownloadLinksAreNotDiscardedAsNonImageAnchors(){
        val doc=Jsoup.parse("""
          <main><article><div class="entry-content">
            <h2>Selected cosplay 2 videos</h2>
            <a href="https://cdn.cosplaytele.com/set/full-scene.mp4">Full video</a>
            <a href="https://cosplaytele.com/unrelated-set/">Related set</a>
            <a href="https://cdn.cosplaytele.com/set/highres.jpg"><img src="/thumb.jpg"/></a>
          </div></article></main>
        """,selected)
        val scoped=SelectedAlbumScope.selectedContent(doc,selected)
        val assets=directPageAssets(scoped,selected)
        assertEquals(2,assets.size)
        assertTrue(assets.any{it.kind==MediaKind.VIDEO &&
            it.url.endsWith("/set/full-scene.mp4")})
        assertFalse(scoped.select("a[href*=unrelated-set]").isNotEmpty())
    }

    @Test fun selectedArticleDirectVideoIsRetainedButUnrelatedVideosAreExcluded(){
        val doc=Jsoup.parse("""
          <main><div class="recommended">
          <video src="https://cdn.cosplaytele.com/other.mp4"></video></div>
          <article><div class="post-content">
          <img src="https://cdn.cosplaytele.com/selected/1.jpg"/>
          <video controls src="https://cdn.cosplaytele.com/selected/full.mp4"></video>
          <aside class="related">
            <img src="https://cdn.cosplaytele.com/foreign.jpg"/>
          </aside>
          </div></article></main>
        """,selected)
        val content=SelectedAlbumScope.selectedContent(doc,selected)
        val direct=directPageAssets(content,selected)
        assertEquals(2,direct.size)
        assertTrue(direct.any{it.kind==MediaKind.IMAGE &&
            it.url.endsWith("/selected/1.jpg")})
        assertTrue(direct.any{it.kind==MediaKind.VIDEO &&
            it.url.endsWith("/selected/full.mp4")})
        assertTrue(direct.none{"/other.mp4" in it.url ||
            "/foreign.jpg" in it.url})
    }
}
