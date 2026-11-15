package com.asterion.videoflow

import android.app.ActivityManager
import android.content.Context

/**
 * Two already-existing isolated Android video processes. The first can
 * resolve pages; the second can also resolve pages when memory permits.
 * Python and native working directory are independent PER PROCESS, unlike
 * two threads in one process. On low-memory devices keep agent two native
 * HTTP only rather than duplicating the Chaquopy runtime and crashing.
 */
internal object VideoAgentAdmission {
    internal const val MIN_FREE_FOR_TWO_EXTRACTORS=1_500_000_000L

    internal fun secondaryNativeOnly(freeBytes:Long,lowMemory:Boolean):Boolean =
        lowMemory || freeBytes<MIN_FREE_FOR_TWO_EXTRACTORS

    fun nativeOnly(context:Context):Boolean {
        val secondary=android.app.Application.getProcessName().orEmpty()
            .endsWith(":videoflow_secondary")
        if(!secondary)return false
        val am=context.getSystemService(ActivityManager::class.java)
            ?:return true
        val info=ActivityManager.MemoryInfo()
        return try {
            am.getMemoryInfo(info)
            secondaryNativeOnly(info.availMem,info.lowMemory)
        }catch(_:RuntimeException){
            // No unknown memory state may cause a second large Python engine
            // to start without evidence of sufficient available RAM.
            true
        }
    }
}
