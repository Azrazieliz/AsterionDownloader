package com.asterion.videoflow

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import java.io.IOException
import java.net.URI
import java.util.Locale

/**
 * JAV Guru's historical LU/SB exact article -> /searcho/ -> packed player.
 *
 * Real selected LU on 2026-11-22: Python urllib directly received the
 * streamhihi.com player (200, 79 KiB) while BOTH Jsoup and Java
 * HttpURLConnection received 403 on that same publisher redirect.
 * The pinned yt-dlp extractor reported Unsupported URL for this player,
 * so blindly handing the HTML page to it also fails. Read only the
 * article-attested player HTML through the existing Python interpreter;
 * the frozen JavGuruOriginalSource decoder still checks the signed HLS.
 *
 * No proxy-policy bypass, cookies, downloads, generic scraping, challenge
 * handling, raw URL logging or additional Python interpreter is introduced.
 */
internal object JavGuruPythonSelectedPlayer {
    private fun relay(url:String):Boolean=runCatching{
        val u=URI(url)
        u.scheme=="https" && u.userInfo==null && u.port==-1 &&
            u.fragment==null &&
            u.host.orEmpty().lowercase(Locale.ROOT)
                .removePrefix("www.")=="jav.guru" &&
            u.path=="/searcho/" &&
            // Match the already validated first-party shortcode wrapper.
            // Its optional encoded background can exceed 480 characters.
            u.rawQuery.orEmpty().length in 16..800 &&
            !u.rawQuery.isNullOrBlank()
    }.getOrDefault(false)

    internal fun eligible(
        selected:String,wrapper:String,playerRelay:String,server:String
    ):Boolean =
        server in setOf("LU","SB") &&
            JavGuruNonJkRouting.selectedArticle(selected) &&
            relay(wrapper) && relay(playerRelay) &&
            // Only the publisher-generated second iframe token, never an
            // arbitrary /searcho/ URL or an ad/related page.
            URI(playerRelay).rawQuery.orEmpty().matches(
                Regex("""^[evsxtho]r=[A-Za-z0-9]{15,180}$"""))

    /** Called on the existing VideoFlow extraction worker AFTER the embedded
     * Python runtime is primed and AFTER the frozen exact-post relay decoder
     * verified the article and publisher-created redirect. */
    fun read(
        context:Context,selected:String,wrapper:String,
        playerRelay:String,server:String
    ):Pair<String,String>? {
        if(!eligible(selected,wrapper,playerRelay,server))return null
        val route=NetworkRoute.configured(context,selected)
        // urllib's standard HTTP opener does not implement SOCKS5. Refusing
        // that configuration is essential: never fall back to direct and
        // silently reveal an owner's selected-video route.
        if(route?.mode=="socks5")
            throw IOException("Selected JAV Guru Python source requires an "+
                "HTTP or VPN route; SOCKS5-only route cannot be honored")
        if(route?.mode=="vpn")
            NetworkRoute.requireVpn(context,selected)
        val python=Python.getInstance()
        val urllib=python.getModule("urllib.request")
        val request=urllib.callAttr("Request",playerRelay)
        request.callAttr("add_header","User-Agent",
            NativeMediaDownloader.USER_AGENT)
        request.callAttr("add_header","Referer",wrapper)
        request.callAttr("add_header","Accept-Encoding","identity")
        val settings=python.getBuiltins().callAttr("dict")
        if(route?.mode=="http") {
            val configured=NetworkRoute.extractorProxy(context,selected)
                ?:throw IOException("Selected JAV Guru HTTP proxy unavailable")
            settings.asMap()[PyObject.fromJava("https")]=
                PyObject.fromJava(configured)
        }
        // An explicit ProxyHandler also prevents urllib from consulting an
        // unrelated process environment proxy when mode is direct/VPN.
        val opener=urllib.callAttr("build_opener",
            urllib.callAttr("ProxyHandler",settings))
        // urllib OpenerDirector.open(fullurl, data=None, timeout=...):
        // Passing 22 as argument #2 treats it as POST data and raises
        // TypeError before any LU/SB player HTML can be received.
        // Explicit None preserves a GET and makes 22 the timeout.
        val response=opener.callAttr("open",request,null,22)
        try{
            if(response.callAttr("getcode").toInt()!=200)
                throw IOException("Selected JAV Guru player did not return HTTP 200")
            val final=response.callAttr("geturl").toString()
            val uri=runCatching{URI(final)}.getOrNull()
                ?:throw IOException("Selected JAV Guru redirect invalid")
            val host=uri.host.orEmpty().lowercase(Locale.ROOT)
                .removePrefix("www.")
            if(uri.scheme!="https" || uri.userInfo!=null ||
                uri.port!=-1 || uri.fragment!=null ||
                !JavGuruOriginalSource.selectedEmbed(server,final) ||
                (server=="LU" && host !in setOf(
                    "streamhihi.com","lulustream.com")) ||
                (server=="SB" && host!="javclan.com"))
                throw IOException("Selected JAV Guru relay redirected "+
                    "outside its attested LU/SB player")
            val bytes=response.callAttr("read",950_001)
                .toJava(ByteArray::class.java)
            if(bytes.size !in 100..950_000)
                throw IOException("Selected JAV Guru player HTML size invalid")
            return final to String(bytes,Charsets.UTF_8)
        }finally{
            runCatching{response.callAttr("close")}
        }
    }
}
