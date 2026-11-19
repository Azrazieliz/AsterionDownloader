package com.azrael.pixivdumpsync

/** Connection-only auto retry for Android DownloadManager; auth, missing
 * originals, storage errors and invalid media never loop automatically.
 * Manual user retry still requires a terminated/paused request.
 */
internal object DirectRetryPolicy {
    private val transientHttp=setOf(408,425,429,500,502,503,504)
    // Android DownloadManager.ERROR_HTTP_DATA_ERROR: transient read, response,
    // or transport failure. The other 1000-series codes represent storage,
    // unsupported HTTP, routing or non-resumable file corruption.
    private const val HTTP_DATA_ERROR=1004

    fun transientFailure(reason:Int):Boolean=
        reason in transientHttp || reason==HTTP_DATA_ERROR

    fun shouldAttempt(failed:Boolean,reason:Int,attempts:Int,after:Long,
                      now:Long,manual:Boolean):Boolean=
        failed && (manual || (attempts<5 && now>=after &&
            transientFailure(reason)))

    fun manualRetryable(failed:Boolean,paused:Boolean,
                        invalidSavedFile:Boolean):Boolean=
        failed || paused || invalidSavedFile

    fun backoffMs(previousAttempts:Int,reason:Int=HTTP_DATA_ERROR):Long {
        if(reason==429)return 10L*60_000L
        return (15_000L shl previousAttempts.coerceIn(0,4))
            .coerceAtMost(240_000L)
    }
}
