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
}
