package com.azrael.pixivdumpsync

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import kotlin.math.abs

/**
 * Safety admission before any native BitTorrent JNI initialization.
 * An owner-reported FULL DEVICE crash makes unbounded repeated native
 * attempts unacceptable. Existing descriptor/partial files are untouched.
 *
 * Torrent processing is deferred while battery, thermal or RAM conditions
 * cannot support a meaningful isolated native transfer. A suspected reboot
 * DURING a native request puts future JNI attempts on explicit safety hold.
 * This cannot diagnose the reboot cause; it records an attribution lead.
 */
internal object TorrentSafetyGate {
    private const val PREFS="torrent_native_safety_v1"
    private fun prefs(c:Context)=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    private fun bootEpoch()=System.currentTimeMillis()-SystemClock.elapsedRealtime()
    data class Admission(val allowed:Boolean,val reason:String="")

    fun assess(c:Context):Admission {
        val p=prefs(c)
        val boot=bootEpoch()
        val previous=p.getLong("last_boot_epoch_ms",0L)
        if(previous!=0L && abs(boot-previous)>120_000L &&
            p.getBoolean("native_request_running",false)){
            p.edit().putBoolean("suspected_device_restart",true).apply()
        }
        if(p.getBoolean("suspected_device_restart",false))
            return Admission(false,
                "A device reboot occurred during a prior native torrent "+
                "request. Native processing is suspended pending a reviewed "+
                "incident; saved metadata and partial files are retained.")
        val battery=c.registerReceiver(null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val percent=battery?.getIntExtra(
            BatteryManager.EXTRA_LEVEL,-1)?.takeIf{it>=0}
        val scale=battery?.getIntExtra(
            BatteryManager.EXTRA_SCALE,100)?.coerceAtLeast(1)?:100
        val plugged=(battery?.getIntExtra(
            BatteryManager.EXTRA_PLUGGED,0)?:0)!=0
        if(percent!=null && percent*100/scale<15)
            return Admission(false,
                "Native torrent processing is suspended below 15% battery "+
                    "even while charging after a reported full-device crash.")
        if(!plugged && percent!=null && percent*100/scale<25)
            return Admission(false,
                "Native torrent processing is deferred below 25% battery "+
                "when unplugged; the last test caused a device-level crash.")
        if(Build.VERSION.SDK_INT>=29){
            val power=c.getSystemService(PowerManager::class.java)
            if(power!=null &&
                power.currentThermalStatus>=
                    PowerManager.THERMAL_STATUS_SEVERE)
                return Admission(false,
                    "Device thermal status is severe; torrent JNI deferred")
        }
        val am=c.getSystemService(ActivityManager::class.java)
        val memory=ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memory)
        if(am!=null && memory.lowMemory)
            return Admission(false,
                "Android reports low memory; native torrent processing deferred")
        if(am!=null && memory.availMem<768L*1024*1024)
            return Admission(false,
                "Less than 768 MiB available RAM; native torrent deferred")
        return Admission(true)
    }

    @Synchronized fun begin(c:Context):Admission{
        val admission=assess(c)
        if(admission.allowed)prefs(c).edit()
            .putLong("last_boot_epoch_ms",bootEpoch())
            .putBoolean("native_request_running",true).commit()
        return admission
    }
    @Synchronized fun finished(c:Context){
        prefs(c).edit()
            .putBoolean("native_request_running",false).apply()
    }
}
