package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class CrossPlatformCreatorProfilesTest {
    @Test fun linkedCreatorProfilesUseExplicitProfileEvidence(){
        val source="https://www.patreon.com/artist_one"
        val page=Jsoup.parse("""<html><head>
            <link rel="me" href="https://x.com/artist_one">
            </head><body>
            <header><a href="https://www.pixiv.net/users/123456">Pixiv</a></header>
            <section class="creator-links">
                <a href="https://fantia.jp/fanclubs/77777">Fantia full work</a>
            </section>
            <main><a href="https://x.com/random_other_user">Unrelated tweet</a></main>
            <div class="suggestions">
                <a href="https://www.patreon.com/other_creator">Other artist</a>
            </div>
        </body></html>""",source)
        val profiles=CrossPlatformProfileParser.linkedProfiles(page,source)
        assertEquals(setOf("X","PIXIV","FANTIA"),
            profiles.map{it.platform}.toSet())
        assertTrue(profiles.all{it.source=="https://patreon.com/artist_one"})
        assertFalse(profiles.any{it.url.contains("random_other_user")||
            it.url.contains("other_creator")})
    }
    @Test fun genericSiteHomepagesAndPostLinksAreNotCreatorProfiles(){
        assertNull(CrossPlatformProfileParser.normalized("https://www.patreon.com/"))
        assertNull(CrossPlatformProfileParser.normalized("https://x.com/artist/status/123"))
        assertNull(CrossPlatformProfileParser.normalized("https://x.com/search?q=creator"))
        assertNull(CrossPlatformProfileParser.normalized("http://127.0.0.1/admin"))
        assertNull(CrossPlatformProfileParser.normalized("https://bad.org/creator"))
    }
    @Test fun fanboxCreatorSubdomainsAndInternationalProfilesAreRealLinks(){
        val fanbox=CrossPlatformProfileParser.normalized(
            "https://studio-name.fanbox.cc/")
        assertEquals("FANBOX",fanbox?.second)
        assertEquals("https://studio-name.fanbox.cc/",fanbox?.first)
        assertNull(CrossPlatformProfileParser.normalized("https://www.fanbox.cc/"))
        assertEquals("CREATORS",CrossPlatformProfileParser.normalized(
            "https://ko-fi.com/exampleartist")?.second)
        assertEquals("CREATORS",CrossPlatformProfileParser.normalized(
            "https://boosty.to/exampleartist")?.second)
        assertNull(CrossPlatformProfileParser.normalized("https://x.com/intent"))
    }
    @Test fun previewsOnOtherSitesDoNotBecomeSameFile(){
        val x=CrossPlatformProfileParser.normalized("https://twitter.com/artist_one")
        assertEquals("https://x.com/artist_one",x?.first)
        val patreon=CrossPlatformProfileParser.normalized(
            "https://www.patreon.com/artist_one")
        assertNotEquals(x?.first,patreon?.first)
    }
}
