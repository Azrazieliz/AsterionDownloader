package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class FullPornerPublishedQualitiesTest {
    private val selected="https://fullporner.com/watch/6af84d762f7835591446cc91"
    private val player="https://fullporner.com/player/9499933/14"
    private val published="""<script>
      var id = "9499933".split('').reverse().join('');
      var quality = parseInt("14");
      let qualities = btq(quality);
      let base = `xiaoshenke.net`;
      let prefix = '/vid';
      v.media = prefix;
    </script>"""

    @Test fun selectedScriptYieldsOnlyRealHighestFirstQualityPaths(){
        val expected=listOf(1080,720,480).map{
            "https://xiaoshenke.net/vid/3399949/$it"
        }
        assertEquals(expected,FullPornerOriginalSource.publishedQualities(
            selected,player,published))
        assertEquals(expected,SiteSpecificOriginalSources.fullPornerSelectedPlayer(
            org.jsoup.Jsoup.parse(published,player),selected,player).map{it.url})
    }

    @Test fun noPublisherAttestationOrDifferentSelectedIdCannotCreateUrls(){
        assertTrue(FullPornerOriginalSource.publishedQualities(
            selected,player,"<script>var id='9499933';var quality=14;</script>").isEmpty())
        assertTrue(FullPornerOriginalSource.publishedQualities(
            selected,player.replace("9499933","1111111"),published).isEmpty())
        assertTrue(FullPornerOriginalSource.publishedQualities(
            "https://fullporner.com/",player,published).isEmpty())
        assertTrue(FullPornerOriginalSource.publishedQualities(
            selected,player,published.replace("14","0")).isEmpty())
    }
}
