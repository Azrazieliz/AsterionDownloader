package com.azrael.pixivdumpsync

import android.app.Activity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Two actual independent original PNG download workers for Koikatsu cards.
 * A duplicate tap shares the active request rather than saving two files.
 */
internal object KoikatsuCardAgents {
    private val active=ConcurrentHashMap.newKeySet<String>()
    private val pool=Executors.newFixedThreadPool(2){r->
        Thread(r,"asterion-koikatsu-png-original-agent").apply{isDaemon=true}
    }
    fun submit(activity:Activity,card:KoikatsuCard,
               done:(Result<String>)->Unit){
        val key=card.download.ifBlank{card.page}
        if(!active.add(key)){
            activity.runOnUiThread{
                if(!activity.isDestroyed && !activity.isFinishing)
                    done(Result.failure(IllegalStateException(
                        "This original is already downloading")))
            }
            return
        }
        pool.execute {
            val result=runCatching {
                val original=KoikatsuCards.resolve(card)
                KoikatsuCards.download(activity,original,card.name)
            }
            active.remove(key)
            activity.runOnUiThread {
                if(!activity.isDestroyed && !activity.isFinishing)done(result)
            }
        }
    }
}
