package com.asterion.videoflow

import android.content.Context
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.util.Locale

/** Per-provider network route. This does not fabricate exit addresses or
 * silently install, rent or activate VPN/proxy services. System VPNs are
 * already honored by ordinary Android HTTP connections.
 */
internal object NetworkRoute {
    private const val PREFS="asterion_network_routes_v1"
    data class Route(val mode:String,val host:String="",val port:Int=0)
    fun siteKey(site:String):String {
        val input=site.trim()
        val address=if("://" in input)input else "https://"+input
        return runCatching{
            URI(address).host.orEmpty().lowercase(Locale.ROOT)
                .removePrefix("www.").removePrefix("m.")
        }.getOrDefault("")
    }
    fun configured(context:Context?,url:String):Route? {
        if(context==null)return null
        val site=siteKey(url)
        if(site.isBlank())return null
        val pref=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        return Route(pref.getString(site+":mode","direct")?:"direct",
            pref.getString(site+":host","").orEmpty(),
            pref.getInt(site+":port",0))
    }
    fun save(context:Context,url:String,mode:String,host:String,port:Int) {
        val site=siteKey(url)
        require(site.isNotBlank()){"Enter a valid site domain"}
        require(mode in setOf("direct","http","socks5")){"Invalid route mode"}
        if(mode!="direct"){
            require(host.matches(Regex("""[A-Za-z0-9.-]{1,253}"""))){
                "Use a valid proxy DNS name or IPv4 address"
            }
            require(port in 1..65535){"Enter a valid proxy port"}
        }
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putString(site+":mode",mode)
            .putString(site+":host",if(mode=="direct")"" else host)
            .putInt(site+":port",if(mode=="direct")0 else port).apply()
    }
    fun javaProxy(context:Context?,url:String):Proxy? {
        val r=configured(context,url)?:return null
        if(r.mode !in setOf("http","socks5") || r.port !in 1..65535 ||
            r.host.isBlank())return null
        return Proxy(if(r.mode=="http")Proxy.Type.HTTP else Proxy.Type.SOCKS,
            InetSocketAddress.createUnresolved(r.host,r.port))
    }
    fun extractorProxy(context:Context?,url:String):String? {
        val r=configured(context,url)?:return null
        if(r.mode !in setOf("http","socks5") || r.port !in 1..65535 ||
            r.host.isBlank())return null
        val protocol=if(r.mode=="socks5")"socks5h" else "http"
        return protocol+"://"+r.host+":"+r.port
    }
    /** A route configuration is not a claim of a changed public IP. Compare
     * the actual direct and configured exits with no browser cookies.
     */
    fun compareExitIp(context:Context,url:String):String {
        val site=siteKey(url)
        require(site.isNotBlank()){"Enter the publisher site to check"}
        val configured=configured(context,url)
        if(configured==null || configured.mode=="direct"){
            return "Direct / Android system VPN: "+
                checkExitIp(context,url)+
                " · no per-site proxy is configured; exit region not changed by this app"
        }
        val routed=checkExitIp(context,url)
        val direct=probeExitIp(null)
        return if(direct==routed)
            "Exit IP unchanged: $routed · selected proxy is not changing the public exit"
        else "Exit IP changed: direct $direct · selected proxy $routed"
    }
    private fun probeExitIp(proxy:Proxy?):String {
        val endpoint=URL("https://api.ipify.org")
        val conn=(if(proxy==null)endpoint.openConnection()
            else endpoint.openConnection(proxy)) as HttpURLConnection
        conn.connectTimeout=8000
        conn.readTimeout=8000
        try{
            require(conn.responseCode==200){"Route test HTTP "+conn.responseCode}
            val value=conn.inputStream.bufferedReader().use{it.readText().trim()}
            require(value.length in 3..64 &&
                value.matches(Regex("""[0-9a-fA-F:.]+"""))){
                "IP check returned an invalid response"
            }
            return value
        }finally{conn.disconnect()}
    }
    /** Test performed only when the user presses the test control. */
    fun checkExitIp(context:Context,url:String):String {
        require(siteKey(url).isNotBlank()){"Enter the site to test"}
        return probeExitIp(javaProxy(context,url))
    }
}
