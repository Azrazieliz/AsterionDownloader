package com.azrael.galleryflow

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test

class KyoraOriginalCatalogRegressionTest {
    @Test fun pornPicsSiteRootUsesActualPublishedAlbumListing() {
        val root="https://www.pornpics.com/"
        val listing="https://www.pornpics.com/popular/"
        val http=object:HttpClient(){
            override fun document(url:String,referer:String?):Document {
                assertEquals("Must not scan a menu as an album",listing,url)
                return Jsoup.parse("""
                    <main><a href="/galleries/album-one/">
                    Album one original</a></main>
                """,url)
            }
        }
        val entity=SourceEntity(SourceId.GENERIC,
            "site:pornpics.com","PornPics",root,"site")
        val records=GenericSiteAdapter(http)
            .enumerateGalleries(entity).toList()
        assertEquals(1,records.size)
        assertTrue(records.single().canonicalUrl
            .contains("/galleries/album-one/"))
    }
    @Test fun celebCreatorImageCollectionsDoNotBecomeHomepagePreviews() {
        val source="https://celeb.st/feed/hot"
        val http=object:HttpClient(){
            override fun document(url:String,referer:String?):Document {
                assertEquals(source,url)
                return Jsoup.parse("""
                    <main><a href="/creator/artist-123/images">
                    Artist images</a>
                    <a href="/login">Sign in</a></main>
                """,url)
            }
        }
        val entity=SourceEntity(SourceId.GENERIC,
            "url:celeb-hot","Celeb.st",source,"site")
        val records=GenericSiteAdapter(http)
            .enumerateGalleries(entity).toList()
        assertEquals(1,records.size)
        assertTrue(records.single().canonicalUrl
            .contains("/creator/artist-123/images"))
    }
}
