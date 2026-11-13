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
            var host=URI(address).host.orEmpty().lowercase(Locale.ROOT)
            // VideoSiteProfiles already treats www/m/fr/en variants as one
            // publisher. Network exits must follow the SAME identity or an
            // HTTP redirect to fr.pornhub.com silently loses the VPN route.
            while(listOf("www.","m.","fr.","en.").any{host.startsWith(it)}){
                val prefix=listOf("www.","m.","fr.","en.")
                    .first{host.startsWith(it)}
                host=host.removePrefix(prefix)
            }
            host
        }.getOrDefault("")
    }
    fun configured(context:Context?,url:String):Route? {
        if(context==null)return null
        val site=siteKey(url)
        if(site.isBlank())return null
        // Foreground, video-primary, video-secondary and GalleryFlow run in
        // separate Android processes. SharedPreferences is process-cached:
        // relying on it can silently leave a newly chosen exit route DIRECT.
        // Each configuration is also stored in one atomically replaced file.
        routeFile(context,site).takeIf{it.isFile}?.let{file->
            return runCatching{
                val obj=org.json.JSONObject(file.readText())
                val mode=obj.getString("mode")
                require(mode in setOf("direct","http","socks5","vpn"))
                Route(mode,obj.optString("host",""),obj.optInt("port",0))
            }.getOrElse{
                // Corrupt routing metadata must NEVER silently expose traffic
                // over the user's direct French public address.
                Route("vpn","",0)
            }
        }
        val pref=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        return Route(pref.getString(site+":mode","direct")?:"direct",
            pref.getString(site+":host","").orEmpty(),
            pref.getInt(site+":port",0))
    }
    fun save(context:Context,url:String,mode:String,host:String,port:Int) {
        val site=siteKey(url)
        require(site.isNotBlank()){"Enter a valid site domain"}
        require(mode in setOf("direct","http","socks5","vpn")){"Invalid route mode"}
        if(mode!="direct" && mode!="vpn"){
            require(host.matches(Regex("""[A-Za-z0-9.-]{1,253}"""))){
                "Use a valid proxy DNS name or IPv4 address"
            }
            require(port in 1..65535){"Enter a valid proxy port"}
        }
        val h=if(mode=="direct")"" else host
        val p=if(mode=="direct")0 else port
        val file=routeFile(context,site)
        val temporary=java.io.File(file.parentFile,file.name+".tmp")
        try{
            temporary.writeText(org.json.JSONObject().apply{
                put("mode",mode);put("host",h);put("port",p)
            }.toString())
            if(file.exists() && !file.delete())
                error("Cannot replace stored network route")
            check(temporary.renameTo(file)){"Could not commit network route"}
        }finally{temporary.delete()}
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putString(site+":mode",mode)
            .putString(site+":host",h)
            .putInt(site+":port",p).apply()
    }
    private fun routeFile(context:Context,site:String):java.io.File {
        val digest=java.security.MessageDigest.getInstance("SHA-256")
            .digest(site.toByteArray(Charsets.UTF_8)).take(12)
            .joinToString(""){"%02x".format(it)}
        val directory=java.io.File(context.filesDir,"asterion-network-routes")
            .apply{mkdirs()}
        return java.io.File(directory,digest+".json")
    }
    fun javaProxy(context:Context?,url:String):Proxy? {
        val r=configured(context,url)?:return null
        if(r.mode=="vpn"){
            requireVpn(context,url)
            return null
        }
        if(r.mode !in setOf("http","socks5") || r.port !in 1..65535 ||
            r.host.isBlank())return null
        return Proxy(if(r.mode=="http")Proxy.Type.HTTP else Proxy.Type.SOCKS,
            InetSocketAddress.createUnresolved(r.host,r.port))
    }
    fun extractorProxy(context:Context?,url:String):String? {
        val r=configured(context,url)?:return null
        if(r.mode=="vpn"){
            requireVpn(context,url)
            return null
        }
        if(r.mode !in setOf("http","socks5") || r.port !in 1..65535 ||
            r.host.isBlank())return null
        val protocol=if(r.mode=="socks5")"socks5h" else "http"
        return protocol+"://"+r.host+":"+r.port
    }
    /** A real remote VPN exit is supplied by the Android VPN connection.
     * The downloader does not spoof IP addresses or run Tor.
     */
    fun isVpnActive(context:Context?):Boolean {
        if(context==null)return false
        val cm=context.getSystemService(android.net.ConnectivityManager::class.java)
            ?:return false
        val active=cm.activeNetwork?:return false
        return cm.getNetworkCapabilities(active)
            ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)==true
    }
    fun requireVpn(context:Context?,url:String) {
        if(configured(context,url)?.mode=="vpn")
            check(isVpnActive(context)){
                "Active Android VPN required for this site; connect a remote VPN exit"
            }
    }
    fun verifyVpnExit(context:Context,url:String):String {
        check(isVpnActive(context)){"No Android VPN connected"}
        val conn=URL("https://ipwho.is/").openConnection() as HttpURLConnection
        conn.connectTimeout=8000
        conn.readTimeout=8000
        try {
            require(conn.responseCode==200){"VPN exit check HTTP "+conn.responseCode}
            val data=org.json.JSONObject(conn.inputStream.bufferedReader().use{
                it.readText().take(4000)})
            require(data.optBoolean("success",false)){
                "Cannot verify the VPN public exit country"
            }
            val code=data.optString("country_code").uppercase(Locale.ROOT)
            require(code.matches(Regex("[A-Z]{2}"))){"Invalid VPN exit country"}
            return "VPN public exit: "+data.optString("ip").take(64)+
                " · "+code+
                if(code=="FR" && siteKey(url)=="pornhub.com")
                    " · Pornhub may still enforce French regional rules"
                else " · verified"
        } finally {conn.disconnect()}
    }
    /** A route configuration is not a claim of a changed public IP. Compare
     * the actual direct and configured exits with no browser cookies.
     */
    fun compareExitIp(context:Context,url:String):String {
        val site=siteKey(url)
        require(site.isNotBlank()){"Enter the publisher site to check"}
        val configured=configured(context,url)
        if(configured?.mode=="vpn"){
            requireVpn(context,url)
            return verifyVpnExit(context,url)
        }
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
