package com.azrael.pixivdumpsync

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** First-page thumbnails must not silently disappear when 120 cards render.
 * The old DiscardOldestPolicy queues (72 and 96) discarded first cards.
 * A bounded queue sized for full first-page discovery preserves earliest
 * visible work under overload; rejected distant work cannot freeze the UI.
 */
internal object PreviewLoadExecutor {
    const val MAX_VISIBLE_VIDEO_CARDS=120
    // A following list can render far more than one 120-card source strip.
    // A bounded 2048 pending-image window avoids silent rejection of later
    // artist cards while older source images occupy the network workers.
    const val CAPACITY=2048

    fun create(workers:Int):ThreadPoolExecutor {
        require(workers in 1..8)
        return ThreadPoolExecutor(workers,workers,0L,
            TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(CAPACITY),
            ThreadPoolExecutor.DiscardPolicy())
    }
}
