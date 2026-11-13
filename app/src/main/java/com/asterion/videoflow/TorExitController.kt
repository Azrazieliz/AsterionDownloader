package com.asterion.videoflow

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.torproject.jni.TorService
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** User-initiated local Tor daemon. It is a genuine SOCKS exit transport,
 * not a fake geolocation, a purchased proxy, or a system-wide VPN.
 * A route is activated only after a verified remote public exit address.
 */
internal object TorExitController {
    private var connection:ServiceConnection?=null
    private var receiver:BroadcastReceiver?=null
    private var service:TorService?=null
    private var selectedSite:String?=null
    private var update:((String)->Unit)?=null
    private val verifying=AtomicBoolean(false)
    private val main=Handler(Looper.getMainLooper())

    private fun report(message:String) {
        main.post { update?.invoke(message) }
    }
    @Synchronized fun start(context:Context,url:String,country:String,
                            onUpdate:(String)->Unit){
        val app=context.applicationContext
        val site=NetworkRoute.siteKey(url)
        require(site.isNotBlank()){"Select a publisher website"}
        val torrc=TorExitCountries.torrc(country)
        stop(app,resetRoute=false)
        selectedSite=site
        update=onUpdate
        NetworkRoute.waitForTor(app,site)
        // TorService reads this file before it runs the tor main loop.
        // No Android VPN permission is needed: only this app's opted-in
        // provider requests are passed through the local SOCKS listener.
        TorService.getTorrc(app).writeText(torrc)
        report("Starting embedded Tor; waiting for a real exit circuit")
        receiver=object:BroadcastReceiver(){
            override fun onReceive(ctx:Context,intent:Intent){
                if(intent.action==TorService.ACTION_ERROR){
                    report("Tor startup failed: "+intent.getStringExtra(Intent.EXTRA_TEXT)
                        .orEmpty().take(110))
                }else if(intent.action==TorService.ACTION_STATUS){
                    val state=intent.getStringExtra(TorService.EXTRA_STATUS).orEmpty()
                    when(state){
                        TorService.STATUS_ON->verify(app)
                        TorService.STATUS_STARTING->report("Connecting to Tor network")
                        TorService.STATUS_STOPPING,TorService.STATUS_OFF->
                            report("Tor stopped; selected website cannot fall back to direct")
                    }
                }
            }
        }
        LocalBroadcastManager.getInstance(app).registerReceiver(
            receiver!!,IntentFilter().apply{
                addAction(TorService.ACTION_STATUS)
                addAction(TorService.ACTION_ERROR)
            })
        connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,binder:IBinder) {
                val bound=binder as? TorService.LocalBinder
                service=bound?.service
                if(bound==null)report("Tor service binder unavailable")
                else if((service?.socksPort?:-1)>0)verify(app)
            }
            override fun onServiceDisconnected(name:ComponentName) {
                service=null
                report("Tor service disconnected; selected website blocked")
            }
        }
        if(!app.bindService(Intent(app,TorService::class.java),
                connection!!,Context.BIND_AUTO_CREATE)){
            report("Cannot bind the embedded Tor daemon")
        }
    }
    private fun verify(app:Context){
        if(!verifying.compareAndSet(false,true))return
        val site=selectedSite
        val port=service?.socksPort?:-1
        if(site==null || port !in 1..65535){
            verifying.set(false)
            report("Tor circuit has no SOCKS listener yet")
            return
        }
        Thread({
            try {
                val confirmation=NetworkRoute.verifyTorExitAndActivate(app,site,port)
                report(confirmation+" • "+site)
            }catch(t:Exception){
                report("No verified Tor exit: "+
                    (t.message?:t.javaClass.simpleName).take(140))
            }finally{verifying.set(false)}
        },"asterion-tor-exit-verification").start()
    }
    @Synchronized fun stop(context:Context,resetRoute:Boolean=true){
        val app=context.applicationContext
        val site=selectedSite
        receiver?.let{
            runCatching{LocalBroadcastManager.getInstance(app).unregisterReceiver(it)}
        }
        receiver=null
        connection?.let{runCatching{app.unbindService(it)}}
        connection=null
        service=null
        verifying.set(false)
        if(resetRoute && site!=null)
            runCatching{NetworkRoute.save(app,site,"direct","",0)}
        selectedSite=null
        if(resetRoute)report("Embedded Tor stopped; site route reset to direct")
    }
}
