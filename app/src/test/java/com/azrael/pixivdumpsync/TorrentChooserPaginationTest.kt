package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class TorrentChooserPaginationTest {
    @Test fun hugeManifestNeverMaterializesThousandsOfCheckboxes(){
        assertEquals(250,TorrentChooserPagination.pageCount(20_000))
        val first=TorrentChooserPagination.bounds(20_000,0)
        assertEquals(0,first.first)
        assertEquals(80,first.last)
        val middle=TorrentChooserPagination.bounds(20_000,123)
        assertEquals(80,middle.last-middle.first)
        val final=TorrentChooserPagination.bounds(20_000,249)
        assertEquals(19_920,final.first)
        assertEquals(20_000,final.last)
        assertFalse(TorrentChooserPagination.hasNext(20_000,249))
    }

    @Test fun partialFinalPageAndSingleFileRemainAccessible(){
        assertEquals(2,TorrentChooserPagination.pageCount(81))
        assertEquals(81,TorrentChooserPagination.bounds(81,1).last)
        assertEquals(80,TorrentChooserPagination.bounds(81,1).first)
        assertEquals(1,TorrentChooserPagination.pageCount(1))
        assertEquals(1,TorrentChooserPagination.bounds(1,0).last)
        assertThrows(IllegalArgumentException::class.java){
            TorrentChooserPagination.bounds(81,2)
        }
        assertThrows(IllegalArgumentException::class.java){
            TorrentChooserPagination.pageCount(20_001)
        }
    }

    @Test fun selectionIndicesAreStableAcrossPageNavigation(){
        val selected=BooleanArray(161)
        val a=TorrentChooserPagination.bounds(161,0)
        val b=TorrentChooserPagination.bounds(161,1)
        val c=TorrentChooserPagination.bounds(161,2)
        selected[a.first+7]=true
        selected[b.first+4]=true
        selected[c.first]=true
        assertEquals(listOf(7,84,160),selected.indices.filter{selected[it]})
    }
}
