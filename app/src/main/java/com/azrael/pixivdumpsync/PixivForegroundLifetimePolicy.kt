package com.azrael.pixivdumpsync

/** An Android foreground-service refusal to a duplicate command must not
 * terminate a worker that the already-foreground service owns.
 */
internal object PixivForegroundLifetimePolicy {
    fun keepExistingWorker(active:Boolean):Boolean=active
    fun ownsCompletion(finishedGeneration:Long,currentGeneration:Long):Boolean =
        finishedGeneration==currentGeneration
}
