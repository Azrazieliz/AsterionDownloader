package com.azrael.pixivdumpsync

/** Serializes parallel worker progress publication, not their transfers.
 * Workers can finish in order 147,148 but execute callbacks as 148,147.
 * A monotonic AtomicInteger alone cannot prevent that UI regression.
 */
internal class MonotonicProgressPublisher {
    private val lock=Any()
    private var last=0
    fun publish(completed:Int,emit:(Int)->Unit):Boolean = synchronized(lock){
        if(completed<=last)return@synchronized false
        last=completed
        emit(completed)
        true
    }
}
