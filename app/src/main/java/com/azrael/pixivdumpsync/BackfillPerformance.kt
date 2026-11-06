package com.azrael.pixivdumpsync

/** Keep dozens of simultaneous source scanners from starving the Android UI.
 * No provider, download type, historical cursor or file is removed or capped.
 */
object BackfillPerformance {
    fun backgroundWorker(){
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())return
        runCatching {
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_BACKGROUND)
        }
    }
}
