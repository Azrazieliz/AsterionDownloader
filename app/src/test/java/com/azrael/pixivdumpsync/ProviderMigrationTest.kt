package com.azrael.pixivdumpsync

import com.asterion.videoflow.VideoSiteProfiles
import org.junit.Assert.*
import org.junit.Test

class ProviderMigrationTest {
    @Test fun omgOfficial301TargetRetainsOneSiteIdentity(){
        val old="https://www.omg.xxx/fr/"
        val current="https://www.omg.porn/fr/"
        assertEquals(SiteControls.key(old),SiteControls.key(current))
        assertTrue(SourceIndexParser.sameSite(old,current))
        assertTrue(VideoSiteProfiles.sameProvider(old,current))
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://www.omg.porn/fr/videos/93823032/example/"))
    }
    @Test fun unrelatedPornhitsDestinationNeverMasqueradesAsOriginalProvider(){
        val old="https://www.pornhits.com/main/"
        val destination="https://www.pornhub.com/channels/other"
        assertFalse(SourceIndexParser.sameSite(old,destination))
        assertFalse(VideoSiteProfiles.sameProvider(old,destination))
    }
    @Test fun lookalikeExternalDomainsCannotPassAliases(){
        assertFalse(SourceIndexParser.sameSite(
            "https://www.omg.xxx/fr/","https://www.omg.porn.evil.example/fr/"))
        assertFalse(VideoSiteProfiles.sameProvider(
            "https://www.omg.xxx/fr/","https://www.omg.porn.evil.example/fr/"))
    }
}
