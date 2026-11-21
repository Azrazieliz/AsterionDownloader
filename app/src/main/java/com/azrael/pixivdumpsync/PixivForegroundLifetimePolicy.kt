package com.azrael.pixivdumpsync

/** An Android foreground-service refusal to a duplicate command must not
 * terminate a worker that the already-foreground service owns.
 */
internal object PixivForegroundLifetimePolicy {
    // Ownership lasts through the posted cleanup even after Future.isDone.
    fun keepExistingWorker(owned:Boolean):Boolean=owned
    fun ownsCompletion(finishedGeneration:Long,currentGeneration:Long):Boolean =
        finishedGeneration==currentGeneration
}
