package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class JavGuruOriginalSourceTest {
    private val selected="https://jav.guru/1077002/doks-695-little-devil-jk-gives-thick-tongue-kisses-while-repeatedly-teasing-and-toying-with-you-in-a-mean-slow-handjob-until-you-cum/"
    private val wrapper="https://jav.guru/searcho/?ed=2613a7577637338603566346f256&bg=https%3A%2F%2Fjav.guru%2Fwp-content%2Fuploads%2F2026%2F11%2Fh_139doks695pl.jpg"
    private val key="uqntxohyiq"
    private val encoded=Base64.getEncoder().encodeToString(wrapper.toByteArray())
    private val post="""<html>
      <article id="post-1077002">
        <ul><li><a id="wp-btn-iframe" class="wp-btn-iframe__shortcode"
          data-localize="$key" href="javascript:void(0)">STREAM LU</a></li>
        </ul>
      </article>
      <iframe src="https://go.mayzaent.com/smartpop/fake"></iframe>
      <aside><a data-localize="badtenkeys" class="wp-btn-iframe__shortcode">STREAM VO</a></aside>
      <script id="wp-btn-iframe-js-extra">
        var uqntxohyiq = {"btn_class":"wp-btn-iframe__shortcode",
            "iframe_url":"$encoded","iframe_width":"505px","iframe_height":"315px"};
      </script>
    </html>"""

    @Test fun partialSelectedWordpressButtonsStillRecoverAdvertisedJk(){
        val html=org.jsoup.Jsoup.parse(post.replace(
            "</ul>",
            "<li><a class='wp-btn-iframe__shortcode' "+
                "data-localize='jjjjjjjjjj'>STREAM JK</a></li></ul>"),selected)
        val initial=JavGuruOriginalSource.published(selected,html.outerHtml())
        assertEquals(listOf("LU"),initial.map{it.server})
        assertTrue(SiteSpecificOriginalSources.shouldInspectSelectedJk(
            html,selected,initial))
        val recovered=listOf(JavGuruOriginalSource.Player("JK",wrapper))+initial
        assertFalse(SiteSpecificOriginalSources.shouldInspectSelectedJk(
            html,selected,recovered))
        val unrelated=org.jsoup.Jsoup.parse(post.replace(
            "</html>","<aside><a class='wp-btn-iframe__shortcode'>"+
                "STREAM JK</a></aside></html>"),selected)
        // A selected post whose JK control is absent from static HTML
        // still qualifies for one same-article hydration. Sidebar controls
        // are not themselves used as source media.
        assertTrue(SiteSpecificOriginalSources.shouldInspectSelectedJk(
            unrelated,selected,initial))
    }
    @Test fun onlyExactSelectedWordpressStreamNotAdvertisementOrRelatedPost(){
        assertEquals(
            listOf(JavGuruOriginalSource.Player("LU",wrapper)),
            JavGuruOriginalSource.published(selected,post))
        assertTrue(JavGuruOriginalSource.published(
            selected.replace("1077002","9999999"),post).isEmpty())
        assertTrue(JavGuruOriginalSource.published(
            "https://jav.guru/category/jav/",post).isEmpty())
        assertTrue(JavGuruOriginalSource.published(
            "https://go.mayzaent.com/1077002/other/",post).isEmpty())
    }


    @Test fun exactLuluStreamIsAttemptedBeforeUnsupportedAlternativeHosters(){
        val voKey="aaaaaaaaaa"
        val voUrl="https://jav.guru/searcho/?ed=2613a7577637338603566346f256"
        val voEncoded=Base64.getEncoder().encodeToString(voUrl.toByteArray())
        val fixture=post.replace(
            "<ul><li><a",
            "<ul><li><a class=\"wp-btn-iframe__shortcode\" data-localize=\"$voKey\">STREAM VO</a></li><li><a")
            .replace("var uqntxohyiq =",
                "var aaaaaaaaaa = {\"iframe_url\":\"$voEncoded\"}; var uqntxohyiq =")
        val buttons=JavGuruOriginalSource.published(selected,fixture)
        assertEquals(listOf("LU","VO"),buttons.map{it.server})
        assertEquals(wrapper,buttons.first().url)
    }

    @Test fun publisherReversedTokenCreatesExactSelectedSecondIframe(){
        val html="""<html>
          <script>window.cfg = {
            cid: 'c19a2b5', base: 'https://jav.guru/searcho/',
            rtype: 'e', cls: 'v866e2598',
            keys: ['data-c5b86', 'data-3297b', 'data-9f73a']
          };
          function activate_real_stream() {
            var fullToken = p1 + p2 + p3;
            var realSrc = window.cfg.base+'?'+window.cfg.rtype+'r='+fullToken.rev();
          }</script>
          <div id="c19a2b5" class="stream-box" data-c5b86="2613a7577"
            data-3297b="637338603" data-9f73a="566346f256">
            <iframe src="about:blank" class="v866e2598"></iframe>
          </div>
          <iframe src="https://go.mayzaent.com/smartpop/unrelated"></iframe>
        </html>"""
        val expected="https://jav.guru/searcho/?er=652f6436653068337367757a3162"
        assertEquals(expected,JavGuruOriginalSource.nextIframe(wrapper,html))
        assertNull(JavGuruOriginalSource.nextIframe(wrapper,
            html.replace("data-9f73a=\"566346f256\"","data-9f73a=\"\"")))
        assertNull(JavGuruOriginalSource.nextIframe(
            "https://go.mayzaent.com/stream",html))
    }

    @Test fun selectedLuluPlayerUnpacksExactlyOneRealHlsAndRejectsAd(){
        val embed="https://streamhihi.com/e/d6e0h3sguz1b"
        val url="https://3idpeoIOgbFV.tnmr.org/hls2/02/04704/d3i157qffqx9_h/master.m3u8?t=selected&s=1"
        val dictionary=MutableList(36){""}
        dictionary[35]="jwplayer"
        dictionary[34]="setup"
        dictionary[33]="sources"
        dictionary[32]="file"
        val packed="z(\"37\").y({x:[{w:\"$url\"}]})"
        val script="""eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('$packed',36,36,'${dictionary.joinToString("|")}'.split('|')))"""
        assertEquals(url,JavGuruOriginalSource.selectedHls(embed,
            "<html><script>$script</script></html>"))
        val ad=packed.replace(url,
            "https://go.mayzaent.com/hls2/02/04704/test/master.m3u8?t=ad")
        assertNull(JavGuruOriginalSource.selectedHls(embed,
            "<html><script>${script.replace(packed,ad)}</script></html>"))
        assertNull(JavGuruOriginalSource.selectedHls(
            "https://go.mayzaent.com/e/fake", "<script>$script</script>"))
        assertNull(JavGuruOriginalSource.selectedHls(embed,
            "<script>var sources=[{file:'$url'}];</script>"))
    }

    @Test fun onlyPublisherSelectedEmbeddedVideoHostIsEligible(){
        assertTrue(JavGuruOriginalSource.selectedEmbed(
            "LU","https://streamhihi.com/e/d6e0h3sguz1b"))
        assertFalse(JavGuruOriginalSource.selectedEmbed(
            "LU","https://go.mayzaent.com/smartpop/example"))
        assertFalse(JavGuruOriginalSource.selectedEmbed(
            "VO","https://jav.guru/searcho/?er=65123"))
        assertFalse(JavGuruOriginalSource.selectedEmbed(
            "SB","http://streamhihi.com/e/d6e0h3sguz1b"))
    }
    @Test fun currentOwnerPublishedRelayModesFollowSelectedWordpressTokenOnly(){
        val selectedWrapper="https://jav.guru/searcho/?xd=2613a7577637338603566346f256"
        val body="""<html><script>
            window.cfg = {
                cid: 'c19a2b5', base: 'https://jav.guru/searcho/',
                rtype: 'MODE',
                keys: ['data-c5b86','data-3297b','data-9f73a']
            };
            function activate_real_stream() {
                var fullToken = part1 + part2 + part3;
                var src = window.cfg.base+'?'+window.cfg.rtype+'r='+fullToken.rev();
            }
            </script>
            <div id="c19a2b5" data-c5b86="2613a7577"
                data-3297b="637338603" data-9f73a="566346f256"></div>
            </html>"""
        for(mode in listOf("x","t","h","o")){
            val actual=JavGuruOriginalSource.nextIframe(
                selectedWrapper,body.replace("MODE",mode))
            assertEquals("https://jav.guru/searcho/?"+mode+
                "r=652f6436653068337367757a3162",actual)
        }
        assertNull(JavGuruOriginalSource.nextIframe(
            selectedWrapper,body.replace("MODE","z")))
    }

    @Test fun historicalNonJkTransferOrderExcludesJkEvenWhenAdvertisedFirst() {
        fun player(server:String)=JavGuruOriginalSource.Player(
            server,"https://jav.guru/searcho/?ed=2613a7577637338603566346f256")
        val mixed=listOf(player("JK"),player("VO"),player("SB"),player("LU"),
            player("DD"),player("VI"),player("SB"))
        assertEquals(listOf("LU","SB","VO","VI","DD"),
            JavGuruOriginalSource.nonJkTransferOrder(mixed).map{it.server})
        assertTrue(JavGuruOriginalSource.nonJkTransferOrder(
            listOf(player("JK"))).isEmpty())
    }

    @Test fun nonJkHydrationTargetsOnlySelectedPostWhenLuSbMissing() {
        val current=org.jsoup.Jsoup.parse(post,selected)
        val existing=JavGuruOriginalSource.published(selected,current.outerHtml())
        assertEquals(listOf("LU"),existing.map{it.server})
        assertFalse(SiteSpecificOriginalSources.shouldInspectSelectedNonJk(
            current,selected,existing))
        val jkOnly=listOf(JavGuruOriginalSource.Player(
            "JK","https://jav.guru/searcho/?ed=2613a7577637338603566346f256"))
        assertTrue(SiteSpecificOriginalSources.shouldInspectSelectedNonJk(
            current,selected,jkOnly))
        assertTrue(SiteSpecificOriginalSources.shouldInspectSelectedNonJk(
            org.jsoup.Jsoup.parse("<html><main>Loading</main></html>",selected),
            selected,jkOnly))
        val mismatched=org.jsoup.Jsoup.parse(
            "<article id='post-1000000'>other scene</article>",selected)
        assertFalse(SiteSpecificOriginalSources.shouldInspectSelectedNonJk(
            mismatched,selected,jkOnly))
        assertFalse(SiteSpecificOriginalSources.shouldInspectSelectedNonJk(
            current,"https://jav.guru/category/videos/",jkOnly))
    }

    @Test fun onlyCanonicalSelectedJavGuruArticlesPrimePython() {
        assertTrue(JavGuruOriginalSource.selectedArticle(selected))
        for(url in listOf(
            "https://jav.guru/",
            "https://jav.guru/?s=search",
            selected+"?related=1",
            selected+"#player",
            "https://jav.guru.evil.example/1077002/example-post/",
            "http://jav.guru/1077002/example-post/"
        )){
            assertFalse(url,JavGuruOriginalSource.selectedArticle(url))
        }
    }

}
