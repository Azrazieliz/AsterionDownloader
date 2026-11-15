package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

class PreviewLoadExecutorTest {
    @Test fun entireFirstVideoPageSurvivesWhenNetworkWorkersAreBusy(){
        val gate=CountDownLatch(1)
        val started=CountDownLatch(3)
        val done=CountDownLatch(PreviewLoadExecutor.MAX_VISIBLE_VIDEO_CARDS)
        val observed=ConcurrentHashMap.newKeySet<Int>()
        val executor=PreviewLoadExecutor.create(3)
        try{
            repeat(3){executor.execute{started.countDown();gate.await(4,TimeUnit.SECONDS)}}
            assertTrue("All network workers must be occupied",started.await(2,TimeUnit.SECONDS))
            repeat(PreviewLoadExecutor.MAX_VISIBLE_VIDEO_CARDS){index->
                executor.execute { observed.add(index);done.countDown() }
            }
            gate.countDown()
            assertTrue("First thumbnails were silently evicted",done.await(6,TimeUnit.SECONDS))
            assertEquals(PreviewLoadExecutor.MAX_VISIBLE_VIDEO_CARDS,observed.size)
            assertTrue(observed.contains(0))
            assertTrue(observed.contains(1))
        }finally{
            gate.countDown()
            executor.shutdownNow()
        }
    }
    @Test fun boundedCapacityExceedsFullFirstPage(){
        assertTrue(PreviewLoadExecutor.CAPACITY>
            PreviewLoadExecutor.MAX_VISIBLE_VIDEO_CARDS)
    }
}
