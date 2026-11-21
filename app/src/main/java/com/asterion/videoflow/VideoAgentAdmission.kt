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
    // The original 4 GB free-memory requirement unnecessarily disabled
    // the second extractor on the owner's 12 GB Samsung: Android reported
    // ~3.4 GB available AND lowMemory=false. RAM used by reclaimable caches
    // is not evidence that another transfer cannot start. Respect the OS's
    // actual pressure threshold plus a reserve instead of one hardcoded
    // free-RAM requirement for every RAM configuration.
    internal const val MIN_FREE_FOR_TWO_EXTRACTORS=4_000_000_000L
    internal const val MIN_TOTAL_FOR_TWO_EXTRACTORS=10_000_000_000L
    internal const val LARGE_DEVICE_RESERVE_BYTES=2_600_000_000L
    // Native-only fallback remains mandatory when Android reports pressure.
    // On >=10 GB devices with healthy available RAM, reserving an extra
    // 1.3 GB *above* Android's own pressure threshold permanently disabled
    // the second publisher extractor on the owner's non-low-memory phone.
    // Keep 900 MB above the OS threshold and the 2.6 GB absolute floor.
    internal const val OS_PRESSURE_RESERVE_BYTES=900_000_000L

    /** Compatibility for older admission fixtures where total RAM and
     * Android's pressure threshold are unavailable: remain conservative.
     */
    internal fun secondaryNativeOnly(freeBytes:Long,lowMemory:Boolean):Boolean =
        secondaryNativeOnly(freeBytes,lowMemory,0L,0L)

    internal fun secondaryNativeOnly(
        freeBytes:Long,lowMemory:Boolean,totalBytes:Long,
        androidPressureThreshold:Long):Boolean {
        if(lowMemory || freeBytes<=0L)return true
        val needs=if(totalBytes>=MIN_TOTAL_FOR_TWO_EXTRACTORS &&
                androidPressureThreshold in 1L..totalBytes)
            maxOf(LARGE_DEVICE_RESERVE_BYTES,
                androidPressureThreshold+OS_PRESSURE_RESERVE_BYTES)
        else MIN_FREE_FOR_TWO_EXTRACTORS
        return freeBytes<needs
    }

    fun nativeOnly(context:Context):Boolean {
        val secondary=android.app.Application.getProcessName().orEmpty()
            .endsWith(":videoflow_secondary")
        if(!secondary)return false
        val am=context.getSystemService(ActivityManager::class.java)
            ?:return true
        val info=ActivityManager.MemoryInfo()
        return try {
            am.getMemoryInfo(info)
            secondaryNativeOnly(info.availMem,info.lowMemory,
                info.totalMem,info.threshold)
        }catch(_:RuntimeException){
            // No unknown memory state may cause a second large Python engine
            // to start without evidence of sufficient available RAM.
            true
        }
    }
}
