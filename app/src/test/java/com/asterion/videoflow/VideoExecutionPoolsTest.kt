package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VideoExecutionPoolsTest {
    @Test fun separateSequentialSelectionsAreNotRejectedByStaleExtractors() {
        val pool=VideoExecutionPools.create("test-video",2)
        val entered=CountDownLatch(2)
        val release=CountDownLatch(1)
        try {
            val stuck=(0 until 2).map {
                pool.submit {
                    entered.countDown()
                    try { release.await(3,TimeUnit.SECONDS) }
                    catch (_:InterruptedException) { release.await(3,TimeUnit.SECONDS) }
                }
            }
            assertTrue("both old tasks started",entered.await(2,TimeUnit.SECONDS))
            val next=pool.submit(java.util.concurrent.Callable<Int> { 13 })
            assertFalse("new selection waits rather than fails",next.isDone)
            stuck.forEach { it.cancel(true) }
            VideoExecutionPools.discardCancelled(pool)
            release.countDown()
            assertEquals(13,next.get(3,TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun cancelledPendingRequestsDoNotRemainInTheQueue() {
        val pool=VideoExecutionPools.create("test-cancel",1)
        val began=CountDownLatch(1)
        val release=CountDownLatch(1)
        try{
            pool.submit { began.countDown();release.await(3,TimeUnit.SECONDS) }
            assertTrue(began.await(2,TimeUnit.SECONDS))
            val queued=pool.submit { 99 }
            assertTrue(queued.cancel(true))
            VideoExecutionPools.discardCancelled(pool)
            assertEquals(0,pool.queue.size)
        }finally{
            release.countDown()
            pool.shutdownNow()
        }
    }
}
