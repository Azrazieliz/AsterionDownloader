package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class TorrentServiceLifetimePolicyTest {
    @Test fun idleServiceMayStopAfterAllNativeWorkReturns(){
        assertTrue(TorrentServiceLifetimePolicy.mayStop(0,0,0))
    }

    @Test fun concurrentMetadataCannotBeStoppedByPauseCancelOrInvalidAction(){
        assertFalse(TorrentServiceLifetimePolicy.mayStop(0,1,0))
        assertFalse(TorrentServiceLifetimePolicy.mayStop(0,2,0))
    }

    @Test fun activeTransfersAndExportsKeepIsolatedServiceAlive(){
        assertFalse(TorrentServiceLifetimePolicy.mayStop(1,0,0))
        assertFalse(TorrentServiceLifetimePolicy.mayStop(0,0,1))
        assertFalse(TorrentServiceLifetimePolicy.mayStop(1,1,1))
    }
}
