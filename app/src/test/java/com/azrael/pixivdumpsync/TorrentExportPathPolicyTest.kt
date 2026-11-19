package com.azrael.pixivdumpsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentExportPathPolicyTest {
    @Test fun ownerSelectedMediaPathsRemainExportable() {
        assertTrue(TorrentExportPathPolicy.valid("Album/Episode 1.mp4"))
        assertTrue(TorrentExportPathPolicy.valid("Game/install/data.bin"))
    }
    @Test fun unsafeRelativeAndDeceptivePathsCannotBePublished() {
        for(path in listOf("../outside.exe","folder/../outside","/root",
            "x\\\\payload","x//y","x/./y","x/a\u202eexe.mp4",
            "file:name.mp4","x/\u0000bad")) {
            assertFalse(path,TorrentExportPathPolicy.valid(path))
        }
    }
}
