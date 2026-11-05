package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class CreatorSiteBackfillTest {
    private fun posts(html:String,source:String,area:String)=
        CreatorSiteBackfill.entries(Jsoup.parse(html,source),source,area,1L).orEmpty()

    @Test fun patreonPostLinksNotCreatorProfilesOrAccountPages(){
        val source="https://www.patreon.com/cw/example"
        val items=posts("""<main>
          <a href="https://www.patreon.com/posts/work-171603033">New illustration</a>
          <a href="https://www.patreon.com/cw/other">Another creator</a>
        </main>""",source,"CREATORS")
        assertEquals(1,items.size)
        assertTrue(items[0].url.contains("171603033"))
    }
    @Test fun fanboxCreatorSubdomainRetainedWithProviderBoundary(){
        val source="https://www.fanbox.cc/"
        val items=posts("""<main>
          <a href="https://somebody.fanbox.cc/posts/1020304">Post art</a>
          <a href="https://fakefanbox.cc/posts/1020304">Unrelated</a>
        </main>""",source,"CREATORS")
        assertEquals(1,items.size)
        assertTrue(items.single().url.contains("somebody.fanbox.cc"))
    }
    @Test fun fantiaPreservesPostAndProductAssets(){
        val source="https://fantia.jp/fanclubs/34875"
        val links=posts("""<div class="post-list">
          <a href="/posts/1318854">Illustration</a>
          <a href="/products/989799">Complete original files</a>
          <a href="/fanclubs/34875">Fanclub</a>
        </div>""",source,"CREATORS")
        assertEquals(2,links.size)
    }
    @Test fun gumroadProductsRemainAssetDetailsNotBookHtml(){
        val source="https://gumroad.com/"
        val products=posts("""<main>
          <a href="/l/some3dmodel">Rigged 3D model</a>
          <a href="/account">Account</a>
        </main>""",source,"ASSETS")
        assertEquals(1,products.size)
        assertEquals("assets",products.single().kind)
    }
    @Test fun onlyfansFanslyNoFabricatedEntitlements(){
        val onlyfans=posts("""<main>
          <a href="/171603033/chloewildd">Available post</a>
          <a href="/account/profile">Manage account</a>
        </main>""","https://onlyfans.com/chloewildd","CREATORS")
        assertEquals(1,onlyfans.size)
        val fansly=posts("""<article>
          <a href="/post/123456">Creator post</a>
        </article>""","https://fansly.com/creator","CREATORS")
        assertEquals(1,fansly.size)
    }
    @Test fun f95AndAnnaArchiveRequireLegitimateDetailPaths(){
        assertEquals(1,posts("""<main>
          <a href="/threads/my-game.1020304/">Original game thread</a>
          <a href="/forum/">Forum</a>
        </main>""","https://f95zone.to/","GAMES").size)
        assertEquals(1,posts("""<main>
          <a href="/md5/0123456789abcdef0123456789abcdef">Book file record</a>
          <a href="/search?q=somebook">Search page</a>
        </main>""","https://annas-archive.org/","BOOKS").size)
    }
    @Test fun coversAllNamedCreatorDiscoveryHostsWithoutReplacingNativeArchiveEngines(){
        assertEquals(10,CreatorSiteBackfill.supportedHosts.size)
    }
}
