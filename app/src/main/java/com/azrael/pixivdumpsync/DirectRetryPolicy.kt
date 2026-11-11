package com.azrael.pixivdumpsync

/** Pure retry decision logic shared by scheduled and explicitly requested retries.
 * Authentication/entitlement errors do not loop automatically; after the user
 * reconnects their account, a manual retry may attempt the original again.
 */
internal object DirectRetryPolicy {
    private val nonAutomaticReasons=setOf(401,402,403,404,410)

    fun shouldAttempt(failed:Boolean,reason:Int,attempts:Int,after:Long,
                      now:Long,manual:Boolean):Boolean =
        failed && (manual || (attempts<5 && now>=after &&
            reason !in nonAutomaticReasons))

    fun backoffMs(previousAttempts:Int):Long =
        30_000L shl previousAttempts.coerceIn(0,6)
}
