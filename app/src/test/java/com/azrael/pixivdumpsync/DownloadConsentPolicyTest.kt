package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class DownloadConsentPolicyTest {
    @Test fun categoryDiscoveryCannotDownloadUnrequestedBooksComicsOrAssets(){
        for(area in listOf("BOOKS","COMICS","GAMES","ASSETS","MODS","VOICE",
            "STUDIO","MMD","LIVE2D","ANIMATION")){
            assertFalse(area,DownloadConsentPolicy.automaticAllowed(area,false))
            assertFalse(area,DownloadConsentPolicy.automaticAllowed(area,true))
        }
    }
    @Test fun videoSyncNeverAuthorizesBulkDownloading(){
        for(area in listOf("ADULT","HENTAI","VIDEOS","CREATORS")){
            assertFalse(area,DownloadConsentPolicy.automaticAllowed(area,false))
            assertFalse(area,DownloadConsentPolicy.automaticAllowed(area,true))
        }
        for(area in listOf("COSPLAY","ARTWORK")){
            assertFalse(area,DownloadConsentPolicy.automaticAllowed(area,false))
            assertTrue(area,DownloadConsentPolicy.automaticAllowed(area,true))
        }
    }
    @Test fun mainCosplaySourcesRemainInApprovedDirectory(){
        val sites=AlohaSources.by(AlohaArea.COSPLAY).map{it.name}.toSet()
        for(name in listOf("4KHD","Buondua","CosplayTele","Kiutaku"))
            assertTrue("Missing "+name,name in sites)
    }
}
