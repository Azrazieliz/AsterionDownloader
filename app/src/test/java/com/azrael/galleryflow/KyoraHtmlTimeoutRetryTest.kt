package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

/** Timeout retry is a narrow Kyora HTML transport repair. All working
 * publisher original engines and media transfer paths remain unchanged.
 */
class KyoraHtmlTimeoutRetryTest {
    @Test fun onlyRecordedGalleryHtmlTimeoutProvidersAreEligible(){
        val client=HttpClient()
        for(host in listOf("buondua.net","buondua.com",
            "cosplaytele.com","kiutaku.com")){
            assertTrue(host,client.retryableGalleryHtml("https://$host/album"))
        }
    }
    @Test fun frozenVideoProvidersAndOtherGalleryHostsAreNotRetried(){
        val client=HttpClient()
        for(host in listOf("jav.guru","eporner.com","pornhub.com",
            "4khd.com","4kgirl.com","everia.club","unknown.example")){
            assertFalse(host,client.retryableGalleryHtml("https://$host/gallery"))
        }
        assertFalse(client.retryableGalleryHtml("https://cosplaytele.com.evil.example/"))
    }
}
