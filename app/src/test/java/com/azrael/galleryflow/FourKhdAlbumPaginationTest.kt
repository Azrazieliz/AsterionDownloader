package com.azrael.galleryflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class FourKhdAlbumPaginationTest {
    private val album="https://www.4khd.com/content/14/yeha-yeha-pure2.html"

    @Test fun eachPhotoPageResolvesOnlyWithinSelectedAlbum(){
        val adapter=GenericSiteAdapter(HttpClient())
        for(number in 1..10){
            val current=if(number==1)album else album+"/"+number
            val next=album+"/"+(number+1)
            val html="<nav class='pagination'><a href='"+next+
                "'>"+(number+1)+"</a>"+
                "<a href='/content/18/other-gallery.html'>Related gallery</a></nav>"
            val doc=Jsoup.parse(html,current)
            assertEquals(next,
                adapter.nextListingPage(doc,current,setOf(current)))
        }
    }

    @Test fun unrelatedAlbumNeverCountsAsPageTwo(){
        val doc=Jsoup.parse("""
            <main><a href="/content/19/other-album.html/2">2</a></main>
        """,album+"/2")
        assertNull(GenericSiteAdapter(HttpClient()).nextListingPage(
            doc,album+"/2",setOf(album+"/2")))
    }
}
