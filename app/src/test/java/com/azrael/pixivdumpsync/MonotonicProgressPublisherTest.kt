package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MonotonicProgressPublisherTest {
    @Test fun outOfOrderWorkerCompletionCannotDecreaseVisibleCount(){
        val publisher=MonotonicProgressPublisher()
        val actual=mutableListOf<Int>()
        assertTrue(publisher.publish(148){actual+=it})
        assertFalse(publisher.publish(147){actual+=it})
        assertTrue(publisher.publish(149){actual+=it})
        assertEquals(listOf(148,149),actual)
    }
    @Test fun competingWorkersSerializeTheirStatusAndNotificationCallbacks(){
        val publisher=MonotonicProgressPublisher()
        val pool=Executors.newFixedThreadPool(6)
        val started=CountDownLatch(1)
        val output=mutableListOf<Int>()
        try{
            val futures=(1..300).map{value->
                pool.submit{
                    started.await()
                    publisher.publish(value){visible->
                        // Stress the interval in which a later worker could
                        // otherwise publish its status before this callback.
                        if(visible%17==0)Thread.yield()
                        output.add(visible)
                    }
                }
            }
            started.countDown()
            futures.forEach{it.get(10,TimeUnit.SECONDS)}
            assertTrue(output.isNotEmpty())
            assertEquals(output.sorted(),output)
            assertEquals(output.distinct(),output)
            assertEquals(300,output.last())
        }finally{pool.shutdownNow()}
    }
}
