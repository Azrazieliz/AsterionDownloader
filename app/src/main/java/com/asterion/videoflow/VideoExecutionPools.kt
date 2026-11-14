package com.asterion.videoflow

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Keep native and extractor lanes independent while allowing the next
 * *individually selected* file to wait behind a timed-out provider task.
 *
 * The old SynchronousQueue had zero waiting capacity: two Python calls which
 * ignored interruption could cause a third, entirely separate user selection
 * to fail immediately with "FutureTask ... rejected".
 *
 * The queue is bounded so repeated blocked providers cannot exhaust memory;
 * the caller's existing metadata/transfer watchdog still owns timeouts.
 */
internal object VideoExecutionPools {
    fun create(label:String,parallelism:Int):ThreadPoolExecutor {
        require(parallelism in 1..8)
        return ThreadPoolExecutor(
            parallelism,parallelism,45L,TimeUnit.SECONDS,
            ArrayBlockingQueue<Runnable>(12),
            ThreadFactory{work->Thread(work,label).apply{isDaemon=true}},
            ThreadPoolExecutor.AbortPolicy())
    }
    fun discardCancelled(pool:ThreadPoolExecutor) {
        // Cancelling a queued FutureTask doesn't automatically remove it
        // from the executor's queue. Purge it after each timed-out attempt.
        pool.purge()
    }
}
