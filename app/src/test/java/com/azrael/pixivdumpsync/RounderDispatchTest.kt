package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class RounderDispatchTest {
    @Test fun rounderCatchupDoesNotOverwriteDirectDownloadRetry(){
        assertNotEquals(48764,RounderDispatch.CATCHUP_JOB)
    }
    @Test fun newCreatorAndGalleryOriginalsReachTheRightCloudCategories(){
        assertEquals("Creators",RounderDispatch.folderCategory(
            "Download/AsterionDownloader/Creators/"))
        assertEquals("Kyora",RounderDispatch.folderCategory(
            "Download/AsterionDownloader/Cosplay/"))
        assertEquals("Artwork",RounderDispatch.folderCategory(
            "Download/AsterionDownloader/Artwork/"))
        assertEquals("Games",RounderDispatch.folderCategory(
            "Download/AsterionDownloader/Games/"))
    }
    @Test fun unrelatedDownloadsRemainExcluded(){
        assertNull(RounderDispatch.folderCategory("Download/Unrelated/"))
    }
}
