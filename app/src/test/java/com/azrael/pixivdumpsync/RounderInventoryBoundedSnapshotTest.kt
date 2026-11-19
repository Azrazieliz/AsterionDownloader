package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class RounderInventoryBoundedSnapshotTest {
    @Test fun smallSnapshotCanBeParsedWithoutReadingUnboundedInput(){
        val contents="{\"source\":\"asterion-rounder\",\"version\":1,\"items\":[]}"
            .toByteArray(Charsets.UTF_8)
        val loaded=RounderInventoryIndex.boundedSnapshot(
            ByteArrayInputStream(contents))
        assertArrayEquals(contents,loaded)
    }

    @Test fun rejectsStreamAsSoonAsItExceedsSixteenMebibyteBudget(){
        val oversized=ByteArray(16_000_001)
        assertThrows(IllegalArgumentException::class.java){
            RounderInventoryIndex.boundedSnapshot(
                ByteArrayInputStream(oversized))
        }
        assertEquals(16_000_000,
            RounderInventoryIndex.boundedSnapshot(
                ByteArrayInputStream(ByteArray(16_000_000))).size)
    }
}
